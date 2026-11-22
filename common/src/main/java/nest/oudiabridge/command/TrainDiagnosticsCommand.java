package nest.oudiabridge.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;
import nest.oudiabridge.mtr4.Mtr4RuntimeAccess;
import nest.oudiabridge.mtr4.OudiaTimetableRepository;
import nest.oudiabridge.mtr4.OudiaVehicleBindingService;
import nest.oudiabridge.mtr4.OudiaVehicleMatcher;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public final class TrainDiagnosticsCommand {
    private TrainDiagnosticsCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(literal("oudiabridge")
                .then(literal("reload")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> reload(context.getSource())))
                .then(literal("trains")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> execute(context.getSource(), 64))
                        .then(argument("radius", DoubleArgumentType.doubleArg(0.0, 4096.0))
                                .executes(context -> execute(context.getSource(),
                                        DoubleArgumentType.getDouble(context, "radius"))))));
    }

    private static int reload(CommandSourceStack source) {
        try {
            OudiaTimetableRepository.ReloadResult result =
                    OudiaTimetableRepository.reload(source.getServer().getServerDirectory());
            source.sendSuccess(() -> Component.literal(
                    "Loaded " + result.loadedCount() + " OuDia timetable(s) from " + result.directory()), true);
            result.failures().forEach((file, message) ->
                    source.sendFailure(Component.literal(file + ": " + message)));
            return result.loadedCount();
        } catch (Exception exception) {
            source.sendFailure(Component.literal("OuDia timetable reload failed: " + exception.getMessage()));
            return 0;
        }
    }

    private static int execute(CommandSourceStack source, double radius) {
        final List<Object> simulators;
        try {
            simulators = Mtr4RuntimeAccess.getSimulators();
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("MTR4 runtime access failed: " + exception.getMessage()));
            return 0;
        }

        if (simulators.isEmpty()) {
            source.sendFailure(Component.literal("MTR4 simulator is not initialized yet."));
            return 0;
        }

        String currentDimension = source.getLevel().dimension().location().toString().replace(':', '/');
        Vec3 origin = source.getPosition();
        List<CompletableFuture<List<Mtr4RuntimeAccess.VehicleSnapshot>>> futures = new ArrayList<>();

        for (Object simulator : simulators) {
            futures.add(Mtr4RuntimeAccess.collectVehicles(simulator));
        }

        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).whenComplete((ignored, error) -> {
            MinecraftServer server = source.getServer();
            server.execute(() -> {
                if (error != null) {
                    source.sendFailure(Component.literal("Failed to read MTR4 vehicles: " + rootMessage(error)));
                    return;
                }

                int total = 0;
                int matched = 0;
                int positionUnavailable = 0;
                List<String> lines = new ArrayList<>();

                for (CompletableFuture<List<Mtr4RuntimeAccess.VehicleSnapshot>> future : futures) {
                    for (Mtr4RuntimeAccess.VehicleSnapshot vehicle : future.join()) {
                        if (!vehicle.dimension().equalsIgnoreCase(currentDimension)) continue;
                        total++;
                        if (!vehicle.hasPosition()) {
                            positionUnavailable++;
                            continue;
                        }
                        double dx = vehicle.x() - origin.x;
                        double dy = vehicle.y() - origin.y;
                        double dz = vehicle.z() - origin.z;
                        double distanceSquared = dx * dx + dy * dy + dz * dz;
                        if (distanceSquared > radius * radius) continue;

                        matched++;
                        String timetableMatch = matchOuDia(source, vehicle);
                        lines.add(String.format(
                                "Vehicle %d | %.1fm | %s | %s | %s -> %s | destination=%s | moving=%s | departureIndex=%d | oudia=%s",
                                vehicle.id(), Math.sqrt(distanceSquared),
                                vehicle.onRoute() ? "on-route" : "not-on-route",
                                emptyAsDash(vehicle.route()), emptyAsDash(vehicle.station()),
                                emptyAsDash(vehicle.nextStation()), emptyAsDash(vehicle.destination()),
                                vehicle.moving(), vehicle.departureIndex(), timetableMatch));
                    }
                }

                final int matchedCount = matched;
                final int totalCount = total;
                final int unavailableCount = positionUnavailable;
                source.sendSuccess(() -> Component.literal(
                        "MTR4 vehicles within " + radius + " blocks: " + matchedCount + " (active in dimension: " + totalCount + ")"), false);
                if (unavailableCount > 0) {
                    source.sendSuccess(() -> Component.literal(
                            "Position unavailable for " + unavailableCount + " vehicle(s); omitted from radius results."), false);
                }
                lines.forEach(line -> source.sendSuccess(() -> Component.literal(line), false));
                if (matched == 0) {
                    source.sendSuccess(() -> Component.literal("No vehicles matched the requested radius."), false);
                }
            });
        });
        source.sendSuccess(() -> Component.literal("Collecting MTR4 vehicle state on simulator thread..."), false);
        return 1;
    }

    private static String matchOuDia(CommandSourceStack source, Mtr4RuntimeAccess.VehicleSnapshot vehicle) {
        String matchedId = null;
        int matchedIndex = -1;
        int matches = 0;
        for (var entry : OudiaTimetableRepository.getAll().entrySet()) {
            OudiaVehicleMatcher.MatchResult result = OudiaVehicleMatcher.match(entry.getValue(), vehicle);
            if (result.status() == OudiaVehicleMatcher.Status.MATCHED) {
                matchedId = entry.getKey();
                matchedIndex = result.trainIndex();
                matches++;
            }
        }
        if (matches == 1) {
            OudiaVehicleBindingService.bind(source.getLevel(), vehicle.id(), matchedId, matchedIndex);
            return matchedId + "#" + matchedIndex;
        }
        if (matches > 1) {
            return "ambiguous(" + matches + ")";
        }
        var existing = OudiaVehicleBindingService.getBinding(source.getLevel(), vehicle.id());
        return existing == null ? "unmatched" : existing.timetableId() + "#" + existing.trainIndex() + "(stored)";
    }

    private static String emptyAsDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
