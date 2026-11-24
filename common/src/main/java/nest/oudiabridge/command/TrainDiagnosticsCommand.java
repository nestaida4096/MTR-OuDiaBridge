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
import org.mtr.core.simulation.Simulator;
import nest.oudiabridge.mtr4.OudiaBridgeSavedData;
import nest.oudiabridge.mtr4.OudiaScheduleAnalyzer;
import nest.oudiabridge.mtr4.OudiaTimetableRepository;
import nest.oudiabridge.mtr4.OudiaVehicleBindingService;
import nest.oudiabridge.mtr4.OudiaVehicleMatcher;
import nest.oudiabridge.mtr4.OudiaTimeMatcher;
import nest.oudiabridge.mtr4.OudiaDepotDepartureMatcher;
import java.time.ZoneId;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
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
                .then(literal("departures")
                        .requires(source -> source.hasPermission(2))
                        .then(argument("timetableId", StringArgumentType.string())
                                .executes(context -> listUpcomingDepartures(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "timetableId"), 20))
                                .then(argument("limit", IntegerArgumentType.integer(1, 100))
                                        .executes(context -> listUpcomingDepartures(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "timetableId"),
                                                IntegerArgumentType.getInteger(context, "limit"))))))
                .then(literal("match")
                        .requires(source -> source.hasPermission(2))
                        .then(argument("vehicleId", LongArgumentType.longArg(Long.MIN_VALUE))
                                .executes(context -> diagnoseMatch(
                                        context.getSource(),
                                        LongArgumentType.getLong(context, "vehicleId")))))
                .then(literal("time-match")
                        .requires(source -> source.hasPermission(2))
                        .then(argument("vehicleId", LongArgumentType.longArg(Long.MIN_VALUE))
                                .executes(context -> diagnoseTimingCandidates(
                                        context.getSource(),
                                        LongArgumentType.getLong(context, "vehicleId")))))
                .then(literal("time-bind")
                        .requires(source -> source.hasPermission(2))
                        .then(argument("vehicleId", LongArgumentType.longArg(Long.MIN_VALUE))
                                .executes(context -> bindByScheduledTime(
                                        context.getSource(),
                                        LongArgumentType.getLong(context, "vehicleId")))))
                .then(literal("depot-match")
                        .requires(source -> source.hasPermission(2))
                        .then(argument("vehicleId", LongArgumentType.longArg(Long.MIN_VALUE))
                                .executes(context -> diagnoseDepotDeparture(
                                        context.getSource(),
                                        LongArgumentType.getLong(context, "vehicleId")))))
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
                                .executes(context -> analyzeTiming(
                                        context.getSource(),
                                        LongArgumentType.getLong(context, "vehicleId"),
                                        null))
                                .then(argument("currentTime", StringArgumentType.greedyString())
                                        .executes(context -> analyzeTiming(
                                                context.getSource(),
                                                LongArgumentType.getLong(context, "vehicleId"),
                                                StringArgumentType.getString(context, "currentTime"))))))
                .then(literal("time-debug")
                        .requires(source -> source.hasPermission(2))
                        .then(argument("vehicleId", LongArgumentType.longArg(Long.MIN_VALUE))
                                .executes(context -> diagnoseTimeState(
                                        context.getSource(),
                                        LongArgumentType.getLong(context, "vehicleId")))))
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
        } catch (Throwable throwable) {
            CommandErrorReporter.report(source, "OuDia timetable reload failed", throwable);
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

    /**
     * Lists upcoming first-station departures from an imported timetable.
     * This command is read-only and does not inspect or modify MTR4 vehicles.
     */
    private static int listUpcomingDepartures(CommandSourceStack source, String timetableId, int limit) {
        OudiaParser.ParseResult timetable = OudiaTimetableRepository.getAll().get(timetableId);
        if (timetable == null) {
            source.sendFailure(Component.literal("Unknown timetable: " + timetableId
                    + ". Use /oudiabridge timetables to list loaded IDs."));
            return 0;
        }
        ZoneId zone = ZoneId.systemDefault();
        LocalDateTime now = LocalDateTime.now(zone);
        List<UpcomingDeparture> departures = new ArrayList<>();
        int skipped = 0;
        for (int trainIndex = 0; trainIndex < timetable.trains.size(); trainIndex++) {
            UpcomingDeparture departure = firstDeparture(timetable, timetable.trains.get(trainIndex), trainIndex, now);
            if (departure == null) skipped++;
            else if (!departure.departureTime().isBefore(now)) departures.add(departure);
        }
        departures.sort(Comparator.comparing(UpcomingDeparture::departureTime)
                .thenComparingInt(UpcomingDeparture::trainIndex));
        source.sendSuccess(() -> Component.literal("Upcoming OuDia departures (read-only) | timetable="
                + timetableId + " | route=" + emptyAsDash(timetable.routeName) + " | zone=" + zone
                + " | now=" + now.format(DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss"))), false);
        if (departures.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No upcoming first-station departures remain for this timetable today."), false);
        } else {
            int shown = Math.min(limit, departures.size());
            for (int i = 0; i < shown; i++) {
                UpcomingDeparture departure = departures.get(i);
                source.sendSuccess(() -> Component.literal(String.format(java.util.Locale.ROOT,
                        "#%d | %s | %s | %s", departure.trainIndex(), departure.direction(),
                        departure.departureTime().format(DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")),
                        departure.firstStation())), false);
            }
            if (departures.size() > shown) source.sendSuccess(() -> Component.literal(
                    "... and " + (departures.size() - shown) + " more upcoming departure(s)."), false);
        }
        final int skippedCount = skipped;
        source.sendSuccess(() -> Component.literal("Upcoming=" + departures.size()
                + " | trains without a usable first-station time=" + skippedCount + " | displayed limit=" + limit), false);
        return departures.size();
    }

    private static UpcomingDeparture firstDeparture(OudiaParser.ParseResult timetable,
                                                     OudiaParser.TrainData train, int trainIndex,
                                                     LocalDateTime now) {
        for (OudiaParser.StopTime stop : train.stops) {
            if (stop.stopType == OudiaParser.StopType.NONE || stop.stationIndex < 0
                    || stop.stationIndex >= timetable.stations.size()) continue;
            String timeText = stop.departureTime == null || stop.departureTime.isBlank()
                    ? stop.arrivalTime : stop.departureTime;
            if (timeText == null || timeText.isBlank()) return null;
            final int seconds;
            try { seconds = OudiaScheduleAnalyzer.parseTime(timeText); }
            catch (IllegalArgumentException exception) { return null; }
            LocalDate date = now.toLocalDate().plusDays(seconds / 86400);
            LocalTime time = LocalTime.ofSecondOfDay(Math.floorMod(seconds, 86400));
            return new UpcomingDeparture(trainIndex,
                    train.direction == null || train.direction.isBlank() ? "-" : train.direction,
                    timetable.stations.get(stop.stationIndex).name, LocalDateTime.of(date, time));
        }
        return null;
    }

    private record UpcomingDeparture(int trainIndex, String direction,
                                     String firstStation, LocalDateTime departureTime) {
    }

    /**
     * Prints why a vehicle does or does not match each loaded timetable.
     * This command is diagnostic-only and never creates or changes a binding.
     */
    private static int diagnoseMatch(CommandSourceStack source, long vehicleId) {
        final List<Simulator> simulators;
        try {
            simulators = Mtr4RuntimeAccess.getSimulators();
        } catch (Throwable throwable) {
            CommandErrorReporter.report(source, "MTR4 runtime access failed", throwable);
            return 0;
        }
        if (simulators.isEmpty()) {
            source.sendFailure(Component.literal("MTR4 simulator is not initialized yet."));
            return 0;
        }

        List<CompletableFuture<List<Mtr4RuntimeAccess.VehicleSnapshot>>> futures = new ArrayList<>();
        for (Simulator simulator : simulators) {
            futures.add(Mtr4RuntimeAccess.collectVehicles(simulator));
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).whenComplete((ignored, error) ->
                source.getServer().execute(() -> {
                    if (error != null) {
                        CommandErrorReporter.report(source, "Failed to read MTR4 vehicles", error);
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
                    final Mtr4RuntimeAccess.VehicleSnapshot vehicleSnapshot = selected;

                    source.sendSuccess(() -> Component.literal("OuDia match diagnostics (read-only) | vehicle="
                            + vehicleId), false);
                    source.sendSuccess(() -> Component.literal("MTR4 route=" + emptyAsDash(vehicleSnapshot.route())
                            + " | current=" + emptyAsDash(vehicleSnapshot.station())
                            + " | next=" + emptyAsDash(vehicleSnapshot.nextStation())), false);

                    var timetables = OudiaTimetableRepository.getAll();
                    if (timetables.isEmpty()) {
                        source.sendFailure(Component.literal("No OuDia timetables are loaded. Use /oudiabridge reload."));
                        return;
                    }
                    timetables.forEach((id, timetable) -> {
                        OudiaVehicleMatcher.Diagnostic diagnostic =
                                OudiaVehicleMatcher.diagnose(timetable, vehicleSnapshot);
                        source.sendSuccess(() -> Component.literal("Timetable " + id
                                + " | route=" + emptyAsDash(timetable.routeName)
                                + " | routeMatch=" + diagnostic.routeMatches()
                                + " | normalizedRoute=" + diagnostic.normalizedTimetableRoute()
                                + " vs " + diagnostic.normalizedVehicleRoute()), false);
                        source.sendSuccess(() -> Component.literal("  trains=" + diagnostic.trainCount()
                                + " | current-only=" + diagnostic.currentOnlyCount()
                                + " | next-only=" + diagnostic.nextOnlyCount()
                                + " | consecutive-pair=" + diagnostic.pairMatchCount()
                                + " | candidateIndices=" + diagnostic.pairTrainIndices()), false);
                        source.sendSuccess(() -> Component.literal("  directions="
                                + diagnostic.directionCounts() + " | " + diagnostic.reason()), false);
                    });
                }));
        source.sendSuccess(() -> Component.literal(
                "Collecting MTR4 vehicle state for read-only OuDia match diagnostics..."), false);
        return 1;
    }

    /**
     * Ranks station-pair candidates using the local wall-clock time represented by the
     * MTR4 simulation timestamp. This command is diagnostic-only and never creates a binding.
     */
    private static int diagnoseTimingCandidates(CommandSourceStack source, long vehicleId) {
        final List<Simulator> simulators;
        try {
            simulators = Mtr4RuntimeAccess.getSimulators();
        } catch (Throwable throwable) {
            CommandErrorReporter.report(source, "MTR4 runtime access failed", throwable);
            return 0;
        }
        if (simulators.isEmpty()) {
            source.sendFailure(Component.literal("MTR4 simulator is not initialized yet."));
            return 0;
        }

        List<CompletableFuture<List<Mtr4RuntimeAccess.VehicleSnapshot>>> futures = new ArrayList<>();
        for (Simulator simulator : simulators) {
            futures.add(Mtr4RuntimeAccess.collectVehicles(simulator));
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).whenComplete((ignored, error) ->
                source.getServer().execute(() -> {
                    if (error != null) {
                        CommandErrorReporter.report(source, "Failed to read MTR4 vehicles", error);
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

                    final Mtr4RuntimeAccess.VehicleSnapshot vehicle = selected;
                    ZoneId zone = ZoneId.systemDefault();
                    source.sendSuccess(() -> Component.literal(
                            "OuDia time candidate ranking (read-only) | vehicle=" + vehicleId), false);
                    source.sendSuccess(() -> Component.literal(
                            "Clock zone=" + zone + " | route=" + emptyAsDash(vehicle.route())
                                    + " | " + emptyAsDash(vehicle.station())
                                    + " -> " + emptyAsDash(vehicle.nextStation())), false);

                    var timetables = OudiaTimetableRepository.getAll();
                    if (timetables.isEmpty()) {
                        source.sendFailure(Component.literal("No OuDia timetables are loaded. Use /oudiabridge reload."));
                        return;
                    }

                    timetables.forEach((id, timetable) -> {
                        OudiaTimeMatcher.Result result = OudiaTimeMatcher.analyze(timetable, vehicle, zone);
                        source.sendSuccess(() -> Component.literal("Timetable " + id
                                + " | clock=" + (result.clockText().isBlank() ? "-" : result.clockText())
                                + " | candidates=" + result.candidateCount()
                                + " | pairMatchesWithoutTimes=" + result.pairMatchesWithoutTimes()
                                + " | " + result.reason()), false);
                        result.topCandidates().forEach(candidate -> source.sendSuccess(() ->
                                Component.literal(String.format(java.util.Locale.ROOT,
                                        "  #%d %s | %s -> %s | departureDelta=%+ds | intervalDistance=%ds | inside=%s",
                                        candidate.trainIndex(), candidate.direction(),
                                        candidate.scheduledDepartureText(), candidate.scheduledArrivalText(),
                                        candidate.departureDeltaSeconds(), candidate.intervalDistanceSeconds(),
                                        candidate.insideScheduledInterval())), false));
                    });
                }));
        source.sendSuccess(() -> Component.literal(
                "Collecting MTR4 vehicle state for read-only OuDia time candidate ranking..."), false);
        return 1;
    }

    /**
     * Binds a vehicle only when exactly one loaded timetable candidate is inside its
     * scheduled current-to-next-station time interval. Existing bindings are never replaced.
     */
    private static int bindByScheduledTime(CommandSourceStack source, long vehicleId) {
        final List<Simulator> simulators;
        try {
            simulators = Mtr4RuntimeAccess.getSimulators();
        } catch (Throwable throwable) {
            CommandErrorReporter.report(source, "MTR4 runtime access failed", throwable);
            return 0;
        }
        if (simulators.isEmpty()) {
            source.sendFailure(Component.literal("MTR4 simulator is not initialized yet."));
            return 0;
        }

        List<CompletableFuture<List<Mtr4RuntimeAccess.VehicleSnapshot>>> futures = new ArrayList<>();
        for (Simulator simulator : simulators) {
            futures.add(Mtr4RuntimeAccess.collectVehicles(simulator));
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).whenComplete((ignored, error) ->
                source.getServer().execute(() -> {
                    if (error != null) {
                        CommandErrorReporter.report(source, "Failed to read MTR4 vehicles", error);
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

                    OudiaBridgeSavedData.VehicleBinding existing =
                            OudiaVehicleBindingService.getBinding(source.getLevel(), vehicleId);
                    if (existing != null) {
                        source.sendFailure(Component.literal("Vehicle already has an OuDia binding: "
                                + existing.timetableId() + "#" + existing.trainIndex()
                                + ". Use /oudiabridge unbind before assigning a different train."));
                        return;
                    }

                    Mtr4RuntimeAccess.VehicleSnapshot vehicle = selected;
                    if (!vehicle.onRoute()) {
                        source.sendFailure(Component.literal("Vehicle is not on an MTR4 route; automatic time binding was not attempted."));
                        return;
                    }

                    var timetables = OudiaTimetableRepository.getAll();
                    if (timetables.isEmpty()) {
                        source.sendFailure(Component.literal("No OuDia timetables are loaded. Use /oudiabridge reload."));
                        return;
                    }

                    ZoneId zone = ZoneId.systemDefault();
                    List<TimeBindingCandidate> matches = new ArrayList<>();
                    List<String> diagnostics = new ArrayList<>();
                    for (var entry : timetables.entrySet()) {
                        OudiaTimeMatcher.Result result =
                                OudiaTimeMatcher.analyze(entry.getValue(), vehicle, zone);
                        diagnostics.add(entry.getKey() + ": inside=" + result.insideCandidates().size()
                                + ", candidates=" + result.candidateCount()
                                + ", reason=" + result.reason());
                        for (OudiaTimeMatcher.Candidate candidate : result.insideCandidates()) {
                            matches.add(new TimeBindingCandidate(entry.getKey(), candidate));
                        }
                    }

                    if (matches.isEmpty()) {
                        source.sendFailure(Component.literal("No OuDia train is currently inside the scheduled station-pair time interval. No binding was created."));
                        diagnostics.forEach(line -> source.sendSuccess(
                                () -> Component.literal("  " + line), false));
                        return;
                    }
                    if (matches.size() != 1) {
                        source.sendFailure(Component.literal("Automatic time binding is ambiguous: "
                                + matches.size() + " candidates are inside their scheduled intervals. No binding was created."));
                        matches.stream().limit(10).forEach(match -> source.sendSuccess(() ->
                                Component.literal("  " + match.timetableId() + "#"
                                        + match.candidate().trainIndex() + " "
                                        + match.candidate().direction() + " | "
                                        + match.candidate().scheduledDepartureText() + " -> "
                                        + match.candidate().scheduledArrivalText()), false));
                        return;
                    }

                    TimeBindingCandidate match = matches.get(0);
                    OudiaVehicleBindingService.bind(source.getLevel(), vehicleId,
                            match.timetableId(), match.candidate().trainIndex());
                    source.sendSuccess(() -> Component.literal("Automatically bound MTR4 vehicle "
                            + vehicleId + " to " + match.timetableId() + "#"
                            + match.candidate().trainIndex() + " | "
                            + match.candidate().direction() + " | "
                            + match.candidate().scheduledDepartureText() + " -> "
                            + match.candidate().scheduledArrivalText()
                            + " | clock=" + vehicle.simulationMillis()
                            + " (" + zone + ")."), true);
                }));
        source.sendSuccess(() -> Component.literal(
                "Collecting MTR4 vehicle state for unique scheduled-time binding..."), false);
        return 1;
    }

    private record TimeBindingCandidate(String timetableId, OudiaTimeMatcher.Candidate candidate) {
    }

    /**
     * Shows the MTR4 depot-exit estimate and OuDia first-station departure candidates.
     * This command is diagnostic-only and never creates or changes a binding.
     */
    private static int diagnoseDepotDeparture(CommandSourceStack source, long vehicleId) {
        final List<Simulator> simulators;
        try {
            simulators = Mtr4RuntimeAccess.getSimulators();
        } catch (Throwable throwable) {
            CommandErrorReporter.report(source, "MTR4 runtime access failed", throwable);
            return 0;
        }
        if (simulators.isEmpty()) {
            source.sendFailure(Component.literal("MTR4 simulator is not initialized yet."));
            return 0;
        }

        List<CompletableFuture<List<Mtr4RuntimeAccess.VehicleSnapshot>>> futures = new ArrayList<>();
        for (Simulator simulator : simulators) {
            futures.add(Mtr4RuntimeAccess.collectVehicles(simulator));
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).whenComplete((ignored, error) ->
                source.getServer().execute(() -> {
                    if (error != null) {
                        CommandErrorReporter.report(source, "Failed to read MTR4 vehicles", error);
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

                    final Mtr4RuntimeAccess.VehicleSnapshot vehicle = selected;
                    ZoneId zone = ZoneId.systemDefault();
                    source.sendSuccess(() -> Component.literal(
                            "OuDia depot departure matching (read-only) | vehicle=" + vehicleId), false);
                    source.sendSuccess(() -> Component.literal(
                            "Depot=" + emptyAsDash(vehicle.depotName())
                                    + " | route=" + emptyAsDash(vehicle.route())
                                    + " | onRoute=" + vehicle.onRoute()
                                    + " | zone=" + zone), false);
                    source.sendSuccess(() -> Component.literal(
                            "Departure reference=" + (vehicle.departureReferenceMillis() > 0
                                    ? java.time.Instant.ofEpochMilli(vehicle.departureReferenceMillis())
                                            .atZone(zone).format(java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss"))
                                    : "unavailable")
                                    + " | source=" + vehicle.departureReferenceSource()
                                    + " | recorded=" + vehicle.recordedSidingDepartureMillis()
                                    + " | routeEstimate=" + (vehicle.estimatedDepartureMillis() > 0
                                            ? java.time.Instant.ofEpochMilli(vehicle.estimatedDepartureMillis())
                                                    .atZone(zone).format(java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss"))
                                            : "unavailable")
                                    + " | routeElapsed=" + (Double.isFinite(vehicle.routeElapsedMillis())
                                            ? Math.round(vehicle.routeElapsedMillis()) + "ms" : "unavailable")
                                    + " | currentDwell=" + vehicle.elapsedDwellMillis() + "ms"), false);
                    source.sendSuccess(() -> Component.literal(
                            "Fallback estimate basis: MTR4 path running-time profile + current recorded dwell time; extra signal/block waits are not separately measured."), false);

                    var timetables = OudiaTimetableRepository.getAll();
                    if (timetables.isEmpty()) {
                        source.sendFailure(Component.literal("No OuDia timetables are loaded. Use /oudiabridge reload."));
                        return;
                    }

                    timetables.forEach((id, timetable) -> {
                        OudiaDepotDepartureMatcher.Result result =
                                OudiaDepotDepartureMatcher.analyze(timetable, vehicle, zone);
                        source.sendSuccess(() -> Component.literal(
                                "Timetable " + id + " | depotCandidates=" + result.candidateCount()
                                        + " | within±30s=" + result.matchCount()
                                        + " | " + result.reason()), false);
                        result.topCandidates().forEach(candidate -> source.sendSuccess(() ->
                                Component.literal(String.format(java.util.Locale.ROOT,
                                        "  #%d %s | depot=%s | scheduled=%s | departureRef=%s | delta=%s | match=%s",
                                        candidate.trainIndex(), candidate.direction(), candidate.depotStation(),
                                        candidate.scheduledDepartureText(), candidate.departureReferenceText(),
                                        candidate.deltaText(), candidate.withinTolerance())), false));
                    });
                }));
        source.sendSuccess(() -> Component.literal(
                "Collecting MTR4 vehicle state for depot departure diagnostics..."), false);
        return 1;
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
        if (currentTime != null) {
            try {
                OudiaScheduleAnalyzer.parseTime(currentTime);
            } catch (IllegalArgumentException exception) {
                source.sendFailure(Component.literal("Invalid time. Use HHmm, HHmmss, HH:mm, or HH:mm:ss."));
                return 0;
            }
        }

        final List<Simulator> simulators;
        try {
            simulators = Mtr4RuntimeAccess.getSimulators();
        } catch (Throwable throwable) {
            CommandErrorReporter.report(source, "MTR4 runtime access failed", throwable);
            return 0;
        }
        if (simulators.isEmpty()) {
            source.sendFailure(Component.literal("MTR4 simulator is not initialized yet."));
            return 0;
        }

        List<CompletableFuture<List<Mtr4RuntimeAccess.VehicleSnapshot>>> futures = new ArrayList<>();
        for (Simulator simulator : simulators) {
            futures.add(Mtr4RuntimeAccess.collectVehicles(simulator));
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).whenComplete((ignored, error) -> {
            source.getServer().execute(() -> {
                if (error != null) {
                    CommandErrorReporter.report(source, "Failed to read MTR4 vehicles", error);
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

                String resolvedTime = currentTime;
                String resolvedClockSource = "manual input";
                if (resolvedTime == null) {
                    if (selected.simulationMillis() <= 0) {
                        source.sendFailure(Component.literal(
                                "MTR4 simulator clock is unavailable. Supply an explicit time as HH:mm:ss."));
                        return;
                    }
                    java.time.ZoneId zone = java.time.ZoneId.systemDefault();
                    resolvedTime = java.time.Instant.ofEpochMilli(selected.simulationMillis())
                            .atZone(zone).toLocalTime()
                            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
                    resolvedClockSource = "MTR4 simulator clock (" + zone + ")";
                }
                final String effectiveTime = resolvedTime;
                final String clockSource = resolvedClockSource;

                OudiaScheduleAnalyzer.Analysis analysis = OudiaScheduleAnalyzer.analyze(
                        timetable, binding.trainIndex(), selected, effectiveTime);
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
                        + analysis.scheduledDepartureText() + " | next-station event: "
                        + analysis.scheduledArrivalText()), false);
                source.sendSuccess(() -> Component.literal("Clock: " + effectiveTime
                        + " | source: " + clockSource
                        + " | aligned: " + analysis.alignedClockText()
                        + " | deviation: " + analysis.deviationText()), false);
                source.sendSuccess(() -> Component.literal("Time to next-station event: "
                        + analysis.remainingText()), false);
                if (analysis.hasDistanceToNextStop()) {
                    source.sendSuccess(() -> Component.literal(String.format(
                            java.util.Locale.ROOT, "Distance to next ATO stop: %.1f m",
                            analysis.distanceToNextStopMeters())), false);
                } else {
                    source.sendSuccess(() -> Component.literal(
                            "Distance to next ATO stop: unavailable"), false);
                }
                if (analysis.hasCurrentSpeed()) {
                    source.sendSuccess(() -> Component.literal(String.format(
                            java.util.Locale.ROOT, "Current speed: %.1f km/h",
                            analysis.currentSpeedMetersPerSecond() * 3.6)), false);
                } else {
                    source.sendSuccess(() -> Component.literal("Current speed: unavailable"), false);
                }
                if (analysis.hasRequiredAverageSpeed()) {
                    source.sendSuccess(() -> Component.literal(String.format(
                            java.util.Locale.ROOT, "Required average speed to meet arrival: %.1f km/h (estimate)",
                            analysis.requiredAverageSpeedMetersPerSecond() * 3.6)), false);
                } else {
                    source.sendSuccess(() -> Component.literal(
                            "Required average speed: unavailable (no remaining scheduled time or stop distance)"), false);
                }
                source.sendSuccess(() -> Component.literal(
                        "Read-only estimate; MTR4 speed limits, braking, signals and reservations remain authoritative."), false);
            });
        });
        source.sendSuccess(() -> Component.literal(
                "Collecting MTR4 vehicle state for OuDia timing analysis..."), false);
        return 1;
    }

    /**
     * Prints raw MTR4 clock and departure-schedule values captured in one simulator tick task.
     * No timestamp conversion or schedule matching is performed.
     */
    private static int diagnoseTimeState(CommandSourceStack source, long vehicleId) {
        final List<Simulator> simulators;
        try {
            simulators = Mtr4RuntimeAccess.getSimulators();
        } catch (Throwable throwable) {
            CommandErrorReporter.report(source, "MTR4 runtime access failed", throwable);
            return 0;
        }
        if (simulators.isEmpty()) {
            source.sendFailure(Component.literal("MTR4 simulator is not initialized yet."));
            return 0;
        }

        List<CompletableFuture<Mtr4RuntimeAccess.TimeDiagnosticsSnapshot>> futures = new ArrayList<>();
        for (Simulator simulator : simulators) {
            futures.add(Mtr4RuntimeAccess.collectTimeDiagnostics(simulator, vehicleId));
        }

        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).whenComplete((ignored, error) ->
                source.getServer().execute(() -> {
                    if (error != null) {
                        CommandErrorReporter.report(source, "Failed to read MTR4 time diagnostics", error);
                        return;
                    }

                    Mtr4RuntimeAccess.TimeDiagnosticsSnapshot selected = null;
                    for (CompletableFuture<Mtr4RuntimeAccess.TimeDiagnosticsSnapshot> future : futures) {
                        Mtr4RuntimeAccess.TimeDiagnosticsSnapshot candidate = future.join();
                        if (candidate != null) {
                            selected = candidate;
                            break;
                        }
                    }
                    if (selected == null) {
                        source.sendFailure(Component.literal("MTR4 vehicle not found: " + vehicleId));
                        return;
                    }

                    final Mtr4RuntimeAccess.TimeDiagnosticsSnapshot state = selected;
                    source.sendSuccess(() -> Component.literal(
                            "MTR4 Time Diagnostics (raw values, same simulator-thread capture) | vehicle="
                                    + state.vehicleId() + " | dimension=" + state.dimension()), false);
                    source.sendSuccess(() -> Component.literal(
                            "[Simulator] currentMillis=" + state.currentMillis()
                                    + " | lastMillis=" + state.lastMillis()
                                    + " | gameMillis=" + state.gameMillis()), false);
                    source.sendSuccess(() -> Component.literal(
                            "[Simulator] gameMillisPerDay=" + state.gameMillisPerDay()
                                    + " | millisOfGameMidnight=" + state.millisOfGameMidnight()), false);
                    source.sendSuccess(() -> Component.literal(
                            "[Depot] depot=" + emptyAsDash(state.depotName())
                                    + " | found=" + state.depotFound()
                                    + " | repeatDepartures=" + state.repeatDepartures()), false);
                    source.sendSuccess(() -> Component.literal(
                            "[Depot] useRealTime=" + state.useRealTime()
                                    + " | repeatInfinitely=" + state.repeatInfinitely()
                                    + " | timeOffsetForRepeating=" + state.timeOffsetForRepeating()), false);
                    source.sendSuccess(() -> Component.literal(
                            "[Siding] getRepeatInterval(0)=" + state.repeatIntervalZero()
                                    + " | getRepeatInterval(86400000)=" + state.repeatIntervalDay()), false);
                    source.sendSuccess(() -> Component.literal(
                            "[Siding] departures.size=" + state.departureCount()
                                    + " | departures[departureIndex]=" + state.indexedDeparture()
                                    + " | matchesVehicleTime="
                                    + (state.indexedDeparture() >= 0
                                    && state.indexedDeparture() == state.sidingDepartureTime())), false);
                    source.sendSuccess(() -> Component.literal(
                            "[Vehicle] sidingDepartureTime=" + state.sidingDepartureTime()
                                    + " | departureIndex=" + state.departureIndex()), false);
                    source.sendSuccess(() -> Component.literal(
                            "[Derived] currentMillis-lastMillis="
                                    + (state.currentMillis() - state.lastMillis())), false);
                    source.sendSuccess(() -> Component.literal(
                            "Values are raw MTR4 values; no Unix-time conversion or OuDia matching was applied."), false);
                }));

        source.sendSuccess(() -> Component.literal(
                "Collecting MTR4 simulator and vehicle clock values on the simulator thread..."), false);
        return 1;
    }

    private static int execute(CommandSourceStack source, double radius) {
        if (!Double.isFinite(radius) || radius < 0 || radius > 4096) {
            source.sendFailure(Component.literal("Radius must be a finite value between 0 and 4096."));
            return 0;
        }

        final List<Simulator> simulators;
        try {
            simulators = Mtr4RuntimeAccess.getSimulators();
        } catch (Throwable throwable) {
            CommandErrorReporter.report(source, "MTR4 runtime access failed", throwable);
            return 0;
        }

        if (simulators.isEmpty()) {
            source.sendFailure(Component.literal("MTR4 simulator is not initialized yet."));
            return 0;
        }

        String currentDimension = source.getLevel().dimension().location().toString().replace(':', '/');
        Vec3 origin = source.getPosition();
        List<CompletableFuture<List<Mtr4RuntimeAccess.VehicleSnapshot>>> futures = new ArrayList<>();

        for (Simulator simulator : simulators) {
            futures.add(Mtr4RuntimeAccess.collectVehicles(simulator));
        }

        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).whenComplete((ignored, error) -> {
            MinecraftServer server = source.getServer();
            server.execute(() -> {
                if (error != null) {
                    CommandErrorReporter.report(source, "Failed to read MTR4 vehicles", error);
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
        ZoneId zone = ZoneId.systemDefault();
        for (var entry : OudiaTimetableRepository.getAll().entrySet()) {
            OudiaDepotDepartureMatcher.MatchResult result =
                    OudiaDepotDepartureMatcher.match(entry.getValue(), vehicle, zone);
            if (result.status() == OudiaDepotDepartureMatcher.Status.MATCHED) {
                matchedId = entry.getKey();
                matchedIndex = result.trainIndex();
                matches++;
            } else if (result.status() == OudiaDepotDepartureMatcher.Status.AMBIGUOUS) {
                matches += result.matchCount();
            }
        }
        if (matches == 1) {
            OudiaVehicleBindingService.bind(source.getLevel(), vehicle.id(), matchedId, matchedIndex);
            return matchedId + "#" + matchedIndex + "(depot-time)";
        }
        if (matches > 1) {
            return "ambiguous(" + matches + ")";
        }
        return "unmatched";
    }

    private static String emptyAsDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

}
