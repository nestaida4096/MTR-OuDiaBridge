package nest.oudiabridge.mtr4;

import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArraySet;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectImmutableList;
import nest.oudiabridge.mixin.InitAccessor;
import nest.oudiabridge.mixin.MainAccessor;
import nest.oudiabridge.mixin.SidingAccessor;
import nest.oudiabridge.mixin.VehicleSchemaAccessor;
import nest.oudiabridge.mixin.SimulatorAccessor;
import nest.oudiabridge.mixin.DepotAccessor;
import org.mtr.core.Main;
import org.mtr.core.data.PathData;
import org.mtr.core.data.Depot;
import org.mtr.core.data.Siding;
import org.mtr.core.data.Vehicle;
import org.mtr.core.data.VehicleExtraData;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Typed MTR4 runtime access. Mutable simulator state is read on its owning thread. */
public final class Mtr4RuntimeAccess {
    private Mtr4RuntimeAccess() {
    }

    public static List<Simulator> getSimulators() {
        Main main = InitAccessor.getMain();
        if (main == null) {
            return List.of();
        }
        ObjectImmutableList<Simulator> simulators =
                ((MainAccessor) (Object) main).getSimulators();
        return simulators == null ? List.of() : new ArrayList<>(simulators);
    }

    public static CompletableFuture<List<VehicleSnapshot>> collectVehicles(Simulator simulator) {
        CompletableFuture<List<VehicleSnapshot>> future = new CompletableFuture<>();
        simulator.run(() -> {
            try {
                future.complete(collectVehiclesNow(simulator));
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });
        return future;
    }

    private static List<VehicleSnapshot> collectVehiclesNow(Simulator simulator) {
        List<VehicleSnapshot> result = new ArrayList<>();
        for (Siding siding : simulator.sidings) {
            ObjectArraySet<Vehicle> vehicles =
                    ((SidingAccessor) (Object) siding).getVehicles();
            for (Vehicle vehicle : vehicles) {
                result.add(snapshot(simulator, siding, vehicle));
            }
        }
        return result;
    }

    /**
     * Captures simulator, depot, siding and vehicle schedule clocks in one simulator-thread task.
     * This is read-only and deliberately returns raw values without interpreting their time base.
     */
    public static CompletableFuture<TimeDiagnosticsSnapshot> collectTimeDiagnostics(
            Simulator simulator, long vehicleId) {
        CompletableFuture<TimeDiagnosticsSnapshot> future = new CompletableFuture<>();
        simulator.run(() -> {
            try {
                future.complete(collectTimeDiagnosticsNow(simulator, vehicleId));
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });
        return future;
    }

    private static TimeDiagnosticsSnapshot collectTimeDiagnosticsNow(Simulator simulator, long vehicleId) {
        SimulatorAccessor simulatorState = (SimulatorAccessor) (Object) simulator;
        long currentMillis = simulator.getCurrentMillis();
        long lastMillis = simulatorState.oudiaBridge$getLastMillis();
        long gameMillis = simulatorState.oudiaBridge$getGameMillis();
        long gameMillisPerDay = simulator.getGameMillisPerDay();
        long millisOfGameMidnight = simulator.getMillisOfGameMidnight();

        for (Siding siding : simulator.sidings) {
            SidingAccessor sidingState = (SidingAccessor) (Object) siding;
            ObjectArraySet<Vehicle> vehicles = sidingState.getVehicles();
            for (Vehicle vehicle : vehicles) {
                if (vehicle.getId() != vehicleId) {
                    continue;
                }

                Depot matchedDepot = null;
                for (Depot depot : simulator.depots) {
                    if (depot.savedRails.contains(siding)) {
                        matchedDepot = depot;
                        break;
                    }
                }

                long repeatDepartures = matchedDepot == null
                        ? -1L
                        : ((DepotAccessor) (Object) matchedDepot).oudiaBridge$getRepeatDepartures();
                long repeatInterval = sidingState.oudiaBridge$getRepeatInterval(0);
                long sidingDepartureTime =
                        ((VehicleSchemaAccessor) (Object) vehicle).oudiaBridge$getSidingDepartureTime();

                return new TimeDiagnosticsSnapshot(
                        vehicleId,
                        simulator.dimension,
                        siding.getDepotName(),
                        currentMillis,
                        lastMillis,
                        gameMillis,
                        gameMillisPerDay,
                        millisOfGameMidnight,
                        repeatDepartures,
                        repeatInterval,
                        sidingDepartureTime,
                        vehicle.getDepartureIndex(),
                        matchedDepot != null
                );
            }
        }
        return null;
    }

    private static VehicleSnapshot snapshot(Simulator simulator, Siding siding, Vehicle vehicle) {
        String dimension = simulator.dimension;
        long simulationMillis = simulator.getCurrentMillis();
        long id = vehicle.getId();
        boolean onRoute = vehicle.getIsOnRoute();
        boolean moving = vehicle.isMoving();
        long departureIndex = vehicle.getDepartureIndex();

        VehicleExtraData extra = vehicle.vehicleExtraData;
        VehicleSchemaAccessor vehicleState = (VehicleSchemaAccessor) (Object) vehicle;
        double speed = vehicleState.getSpeed();
        double railProgress = vehicleState.getRailProgress();
        double distanceToNextAtoStop = findDistanceToNextAtoStop(vehicleState, extra, railProgress);
        long elapsedDwellMillis = Math.max(0, vehicleState.getElapsedDwellTime());
        long recordedSidingDepartureMillis = vehicleState.oudiaBridge$getSidingDepartureTime();
        double routeElapsedMillis = siding.getTimeAlongRoute(railProgress);
        long estimatedDepartureMillis = onRoute && Double.isFinite(routeElapsedMillis) && routeElapsedMillis >= 0
                ? simulationMillis - Math.round(routeElapsedMillis + elapsedDwellMillis)
                : -1L;

        Vector position = vehicle.getHeadPosition();
        String positionStatus = position == null
                ? "head-position-null(" + describePath(extra.immutablePath, railProgress) + ",onRoute=" + onRoute + ")"
                : "ok";

        return new VehicleSnapshot(id, dimension, onRoute, moving, departureIndex,
                extra.getThisRouteName(), extra.getThisStationName(), extra.getNextStationName(),
                extra.getThisRouteDestination(),
                position == null ? Double.NaN : position.x(),
                position == null ? Double.NaN : position.y(),
                position == null ? Double.NaN : position.z(),
                positionStatus, simulationMillis, speed, railProgress, distanceToNextAtoStop,
                siding.getDepotName(), estimatedDepartureMillis, routeElapsedMillis, elapsedDwellMillis,
                recordedSidingDepartureMillis);
    }

    private static double findDistanceToNextAtoStop(VehicleSchemaAccessor vehicle,
                                                     VehicleExtraData extra, double railProgress) {
        if (!Double.isFinite(railProgress)) {
            return Double.NaN;
        }
        long index = vehicle.getNextStoppingIndexAto();
        ObjectImmutableList<PathData> path = extra.immutablePath;
        if (index < 0 || index >= path.size() - 1) {
            return Double.NaN;
        }
        PathData pathData = path.get((int) index);
        if (pathData.getDwellTime() <= 0) {
            return Double.NaN;
        }
        double distance = pathData.getEndDistance() - railProgress;
        return Double.isFinite(distance) && distance >= 0 ? distance : Double.NaN;
    }

    private static String describePath(ObjectImmutableList<PathData> path, double progress) {
        if (path.isEmpty()) {
            return "path-empty";
        }
        int linearIndex = -1;
        double previous = Double.NEGATIVE_INFINITY;
        boolean sorted = true;
        for (int i = 0; i < path.size(); i++) {
            double start = path.get(i).getStartDistance();
            if (start < previous) {
                sorted = false;
            }
            previous = start;
            if (start <= progress) {
                linearIndex = i;
            }
        }
        return "railProgress=" + progress + ",pathSize=" + path.size()
                + ",pathFirst=" + path.get(0).getStartDistance()
                + ",pathLast=" + path.get(path.size() - 1).getStartDistance()
                + ",linearIndex=" + linearIndex + ",pathSorted=" + sorted;
    }

    public record TimeDiagnosticsSnapshot(long vehicleId, String dimension, String depotName,
                                           long currentMillis, long lastMillis, long gameMillis,
                                           long gameMillisPerDay, long millisOfGameMidnight,
                                           long repeatDepartures, long repeatInterval,
                                           long sidingDepartureTime, long departureIndex,
                                           boolean depotFound) {
    }

    public record VehicleSnapshot(long id, String dimension, boolean onRoute, boolean moving,
                                  long departureIndex, String route, String station,
                                  String nextStation, String destination,
                                  double x, double y, double z, String positionStatus,
                                  long simulationMillis, double speedMetersPerMillisecond,
                                  double railProgress, double distanceToNextAtoStop,
                                  String depotName, long estimatedDepartureMillis,
                                  double routeElapsedMillis, long elapsedDwellMillis,
                                  long recordedSidingDepartureMillis) {
        public long departureReferenceMillis() {
            return recordedSidingDepartureMillis > 0
                    ? recordedSidingDepartureMillis : estimatedDepartureMillis;
        }

        public String departureReferenceSource() {
            return recordedSidingDepartureMillis > 0 ? "MTR4 sidingDepartureTime" : "route-timing estimate";
        }

        public boolean hasPosition() {
            return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
        }
    }
}
