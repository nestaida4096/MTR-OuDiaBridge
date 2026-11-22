package nest.oudiabridge.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;
import nest.oudiabridge.mtr4.Mtr4RuntimeAccess;
import nest.oudiabridge.mtr4.OudiaBridgeSavedData;
import nest.oudiabridge.mtr4.OudiaTimetableRepository;
import nest.oudiabridge.mtr4.OudiaVehicleBindingService;
import nest.oudiabridge.mtr4.OudiaVehicleMatcher;
import nest.oudiabridge.parser.OudiaParser;

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
                .then(literal("timetables")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> listTimetables(context.getSource())))
                .then(literal("bind")
                        .requires(source -> source.hasPermission(2))
                        .then(argument("vehicleId", LongArgumentType.longArg(Long.MIN_VALUE))
                                .then(argument("timetableId", StringArgumentType.string())
                                        .then(argument("trainIndex", IntegerArgumentType.integer(0))
                                                .executes(context -> bind(
                                                        context.getSource(),
                                                        LongArgumentType.getLong(context, "vehicleId"),
                                                        StringArgumentType.getString(context, "timetableId"),
                                                        IntegerArgumentType.getInteger(context, "trainIndex")))))))
                .then(literal("unbind")
                        .requires(source -> source.hasPermission(2))
                        .then(argument("vehicleId", LongArgumentType.longArg(Long.MIN_VALUE))
                                .executes(context -> unbind(context.getSource(),
                                        LongArgumentType.getLong(context, "vehicleId")))))
                .then(literal("timing")
                        .requires(source -> source.hasPermission(2))
                        .then(argument("vehicleId", LongArgumentType.longArg(Long.MIN_VALUE))
                                .then(argument("currentTime", StringArgumentType.word())
                                        .executes(context -> analyzeTiming(
                                                context.getSource(),
                                                LongArgumentType.getLong(context, "vehicleId"),
                                                StringArgumentType.getString(context, "currentTime"))))))
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
                    OudiaTimetableRepository.reload(source.getServer().getServerDirectory().toPath());
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

    private static int listTimetables(CommandSourceStack source) {
        var timetables = OudiaTimetableRepository.getAll();
        if (timetables.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No OuDia timetables are loaded. Use /oudiabridge reload."), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Loaded OuDia timetables: " + timetables.size()), false);
        timetables.forEach((id, timetable) -> source.sendSuccess(() -> Component.literal(
                id + " | route=" + emptyAsDash(timetable.routeName)
                        + " | trains=" + timetable.trains.size()
                        + " | stations=" + timetable.stations.size()), false));
        return timetables.size();
    }

    private static int bind(CommandSourceStack source, long vehicleId, String timetableId, int trainIndex) {
        OudiaParser.ParseResult timetable = OudiaTimetableRepository.getAll().get(timetableId);
        if (timetable == null) {
            source.sendFailure(Component.literal("Unknown timetable ID: " + timetableId
                    + ". Use /oudiabridge timetables to list loaded IDs."));
            return 0;
        }
        if (timetable.trains.isEmpty()) {
            source.sendFailure(Component.literal("The selected timetable contains no trains."));
            return 0;
        }
        if (trainIndex >= timetable.trains.size()) {
            source.sendFailure(Component.literal("Train index is out of range. Valid range: 0-"
                    + (timetable.trains.size() - 1) + " (zero-based)."));
            return 0;
        }
        OudiaVehicleBindingService.bind(source.getLevel(), vehicleId, timetableId, trainIndex);
        source.sendSuccess(() -> Component.literal("Bound MTR4 vehicle " + vehicleId
                + " to " + timetableId + "#" + trainIndex + "."), true);
        return 1;
    }

    private static int unbind(CommandSourceStack source, long vehicleId) {
        OudiaVehicleBindingService.unbind(source.getLevel(), vehicleId);
        source.sendSuccess(() -> Component.literal("Removed OuDia binding for MTR4 vehicle " + vehicleId + "."), true);
        return 1;
    }

    /**
     * Compares a bound vehicle with its OuDia schedule using an explicitly supplied clock.
     * This is analysis-only: it never writes vehicle speed, rail progress, or MTR internals.
     */
    private static int analyzeTiming(CommandSourceStack source, long vehicleId, String currentTime) {
        try {
            OudiaScheduleAnalyzer.parseTime(currentTime);
        } catch (IllegalArgumentException exception) {
            source.sendFailure(Component.literal("Invalid time. Use HHmm, HHmmss, HH:mm, or HH:mm:ss."));
            return 0;
        }

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

        List<CompletableFuture<List<Mtr4RuntimeAccess.VehicleSnapshot>>> futures = new ArrayList<>();
        for (Object simulator : simulators) {
            futures.add(Mtr4RuntimeAccess.collectVehicles(simulator));
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).whenComplete((ignored, error) -> {
            source.getServer().execute(() -> {
                if (error != null) {
                    source.sendFailure(Component.literal("Failed to read MTR4 vehicles: " + rootMessage(error)));
                    return;
                }

                Mtr4RuntimeAccess.VehicleSnapshot selected = null;
                for (CompletableFuture<List<Mtr4RuntimeAccess.VehicleSnapshot>> future : futures) {
                    for (Mtr4RuntimeAccess.VehicleSnapshot vehicle : future.join()) {
                        if (vehicle.id() == vehicleId) {
                            selected = vehicle;
                            break;
                        }
                    }
                    if (selected != null) break;
                }
                if (selected == null) {
                    source.sendFailure(Component.literal("MTR4 vehicle not found: " + vehicleId));
                    return;
                }

                String bindingText = matchOuDia(source, selected);
                OudiaBridgeSavedData.VehicleBinding binding =
                        OudiaVehicleBindingService.getBinding(source.getLevel(), vehicleId);
                if (binding == null) {
                    source.sendFailure(Component.literal("Vehicle has no valid OuDia binding (match=" + bindingText + ")."));
                    return;
                }

                OudiaParser.ParseResult timetable =
                        OudiaTimetableRepository.getAll().get(binding.timetableId());
                if (timetable == null || binding.trainIndex() < 0
                        || binding.trainIndex() >= timetable.trains.size()) {
                    source.sendFailure(Component.literal("Stored OuDia binding is invalid: "
                            + binding.timetableId() + "#" + binding.trainIndex()));
                    return;
                }

                OudiaScheduleAnalyzer.Analysis analysis = OudiaScheduleAnalyzer.analyze(
                        timetable, binding.trainIndex(), selected, currentTime);
                if (!analysis.matched()) {
                    source.sendFailure(Component.literal("Schedule analysis unavailable: " + analysis.reason()));
                    return;
                }

                source.sendSuccess(() -> Component.literal("OuDia timing analysis (read-only) | vehicle="
                        + vehicleId + " | timetable=" + binding.timetableId()
                        + "#" + binding.trainIndex()), false);
                source.sendSuccess(() -> Component.literal("Station: " + analysis.currentStation()
                        + " -> " + analysis.nextStation()), false);
                source.sendSuccess(() -> Component.literal("Scheduled departure: "
                        + analysis.scheduledDepartureText() + " | scheduled arrival: "
                        + analysis.scheduledArrivalText()), false);
                source.sendSuccess(() -> Component.literal("Supplied clock: " + currentTime
                        + " | aligned clock: " + analysis.alignedClockText()
                        + " | deviation: " + analysis.deviationText()), false);
                source.sendSuccess(() -> Component.literal("Next arrival: "
                        + analysis.remainingText()), false);
                source.sendSuccess(() -> Component.literal(
                        "Distance/required speed: not available yet; no speed control is applied."), false);
            });
        });
        source.sendSuccess(() -> Component.literal(
                "Collecting MTR4 vehicle state for OuDia timing analysis..."), false);
        return 1;
    }

    private static int execute(CommandSourceStack source, double radius) {
        if (!Double.isFinite(radius) || radius < 0 || radius > 4096) {
            source.sendFailure(Component.literal("Radius must be a finite value between 0 and 4096."));
            return 0;
        }

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
                java.util.Map<String, Integer> positionStatusCounts = new java.util.LinkedHashMap<>();
                List<String> positionSamples = new ArrayList<>();
                List<String> lines = new ArrayList<>();

                for (CompletableFuture<List<Mtr4RuntimeAccess.VehicleSnapshot>> future : futures) {
                    for (Mtr4RuntimeAccess.VehicleSnapshot vehicle : future.join()) {
                        if (!vehicle.dimension().equalsIgnoreCase(currentDimension)) continue;
                        total++;
                        if (!vehicle.hasPosition()) {
                            positionUnavailable++;
                            positionStatusCounts.merge(vehicle.positionStatus(), 1, Integer::sum);
                            if (positionSamples.size() < 3) {
                                positionSamples.add("Vehicle " + vehicle.id() + ": " + vehicle.positionStatus());
                            }
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
                    positionStatusCounts.forEach((status, count) -> source.sendSuccess(() ->
                            Component.literal("Position status [" + count + "]: " + status), false));
                    positionSamples.forEach(sample -> source.sendSuccess(() ->
                            Component.literal(sample), false));
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
        var existing = OudiaVehicleBindingService.getBinding(source.getLevel(), vehicle.id());
        if (existing != null) {
            OudiaParser.ParseResult storedTimetable =
                    OudiaTimetableRepository.getAll().get(existing.timetableId());
            if (storedTimetable != null && existing.trainIndex() < storedTimetable.trains.size()) {
                return existing.timetableId() + "#" + existing.trainIndex() + "(stored)";
            }
            return "invalid-stored(" + existing.timetableId() + "#" + existing.trainIndex() + ")";
        }

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
        return "unmatched";
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
