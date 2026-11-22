package nest.oudiabridge.mtr4;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Narrow compatibility boundary for MTR4's embedded Transport-Simulation-Core.
 *
 * MTR keeps Init.main private and Transport-Simulation-Core keeps Main.simulators private.
 * Reflection is deliberately isolated here so the rest of the bridge does not depend on
 * implementation details of those two owners.
 */
public final class Mtr4RuntimeAccess {
    private static final String INIT_CLASS = "org.mtr.mod.Init";

    private Mtr4RuntimeAccess() {
    }

    public static List<Object> getSimulators() {
        try {
            Class<?> initClass = Class.forName(INIT_CLASS);
            Object main = readField(initClass, null, "main");
            if (main == null) {
                return List.of();
            }
            Object simulators = readField(main.getClass(), main, "simulators");
            if (!(simulators instanceof Iterable<?> iterable)) {
                throw new IllegalStateException("MTR Main.simulators is not iterable");
            }
            List<Object> result = new ArrayList<>();
            iterable.forEach(result::add);
            return result;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to access the MTR4 runtime", exception);
        }
    }

    /**
     * Collect a snapshot on the simulator's own thread. Simulator state must not be read
     * directly from the Minecraft server thread when threaded simulation is enabled.
     */
    public static CompletableFuture<List<VehicleSnapshot>> collectVehicles(Object simulator) {
        CompletableFuture<List<VehicleSnapshot>> future = new CompletableFuture<>();
        try {
            Method run = simulator.getClass().getMethod("run", Runnable.class);
            run.invoke(simulator, (Runnable) () -> {
                try {
                    future.complete(collectVehiclesNow(simulator));
                } catch (Throwable throwable) {
                    future.completeExceptionally(throwable);
                }
            });
        } catch (ReflectiveOperationException exception) {
            future.completeExceptionally(new IllegalStateException("Unable to schedule work on MTR simulator thread", exception));
        }
        return future;
    }

    private static List<VehicleSnapshot> collectVehiclesNow(Object simulator) throws ReflectiveOperationException {
        Object sidings = readField(simulator.getClass(), simulator, "sidings");
        if (!(sidings instanceof Iterable<?> iterable)) {
            throw new IllegalStateException("MTR Simulator.sidings is not iterable");
        }

        List<VehicleSnapshot> result = new ArrayList<>();
        for (Object siding : iterable) {
            iterateSidingVehicles(siding, vehicle -> {
                try {
                    result.add(snapshot(simulator, vehicle));
                } catch (ReflectiveOperationException exception) {
                    throw new IllegalStateException("Unable to inspect an MTR vehicle", exception);
                }
            });
        }
        return result;
    }

    /**
     * MTR 4.0.5 stores active vehicles in Siding.vehicles (an ObjectArraySet).
     * Later core builds may expose iterateVehicles(Consumer), while older variants
     * may use a vehicleIdMap. Keep these compatibility paths isolated here.
     *
     * This method is called only from the simulator thread.
     */
    private static void iterateSidingVehicles(Object siding, Consumer<Object> consumer)
            throws ReflectiveOperationException {
        Method iterateVehicles = findMethod(siding.getClass(), "iterateVehicles", Consumer.class);
        if (iterateVehicles != null) {
            iterateVehicles.setAccessible(true);
            iterateVehicles.invoke(siding, consumer);
            return;
        }

        // MTR 4.0.5: private final ObjectArraySet<Vehicle> vehicles.
        Object vehicles = tryReadField(siding, "vehicles");
        if (vehicles != null && iterateCollection(vehicles, consumer, true)) {
            return;
        }

        // Compatibility with builds that used a map keyed by vehicle ID.
        Object vehicleMap = tryReadField(siding, "vehicleIdMap");
        if (vehicleMap != null && iterateCollection(vehicleMap, consumer, true)) {
            return;
        }

        // Last resort for future core versions that rename the backing collection.
        for (Class<?> type = siding.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                String name = field.getName().toLowerCase(java.util.Locale.ROOT);
                if (!name.contains("vehicle") && !name.contains("train")) {
                    continue;
                }
                field.setAccessible(true);
                Object candidate = field.get(siding);
                if (candidate != null && iterateCollection(candidate, consumer, false)) {
                    return;
                }
            }
        }

        throw new NoSuchFieldException(
                "No iterable vehicle collection found in " + siding.getClass().getName()
                        + "; fields=" + describeFields(siding.getClass()));
    }

    private static boolean iterateCollection(Object candidate, Consumer<Object> consumer, boolean acceptEmpty)
            throws ReflectiveOperationException {
        if (candidate instanceof Iterable<?> iterable) {
            boolean foundVehicle = false;
            for (Object value : iterable) {
                if (value != null && isVehicle(value)) {
                    foundVehicle = true;
                    consumer.accept(value);
                }
            }
            return foundVehicle || acceptEmpty;
        }

        Method valuesMethod = findMethod(candidate.getClass(), "values");
        if (valuesMethod != null) {
            valuesMethod.setAccessible(true);
            Object values = valuesMethod.invoke(candidate);
            if (values instanceof Iterable<?> iterable) {
                boolean foundVehicle = false;
                for (Object value : iterable) {
                    if (value != null && isVehicle(value)) {
                        foundVehicle = true;
                        consumer.accept(value);
                    }
                }
                return foundVehicle || acceptEmpty;
            }
        }
        return false;
    }

    private static boolean isVehicle(Object value) {
        for (Class<?> type = value.getClass(); type != null; type = type.getSuperclass()) {
            if ("org.mtr.core.data.Vehicle".equals(type.getName())) {
                return true;
            }
        }
        return false;
    }

    private static Object tryReadField(Object target, String name) throws ReflectiveOperationException {
        try {
            Field field = findField(target.getClass(), name);
            field.setAccessible(true);
            return field.get(target);
        } catch (NoSuchFieldException ignored) {
            return null;
        }
    }

    private static String describeFields(Class<?> type) {
        List<String> names = new ArrayList<>();
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                names.add(field.getName() + ":" + field.getType().getTypeName());
            }
        }
        return names.toString();
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        try {
            return type.getMethod(name, parameterTypes);
        } catch (NoSuchMethodException ignored) {
        }

        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredMethod(name, parameterTypes);
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    private static VehicleSnapshot snapshot(Object simulator, Object vehicle) throws ReflectiveOperationException {
        String dimension = String.valueOf(readField(simulator.getClass(), simulator, "dimension"));
        Object rawId = invokeOptional(vehicle, "getId");
        if (!(rawId instanceof Number idNumber)) {
            throw new NoSuchMethodException(vehicle.getClass().getName()
                    + ".getId() did not return a numeric vehicle ID");
        }
        long id = idNumber.longValue();
        boolean onRoute = booleanValue(invokeOptional(vehicle, "getIsOnRoute"));
        boolean moving = booleanValue(invokeOptional(vehicle, "isMoving"));
        long departureIndex = number(invokeOptional(vehicle, "getDepartureIndex"), -1L);

        Object extra = readField(vehicle.getClass(), vehicle, "vehicleExtraData");
        String route = stringValue(invokeOptional(extra, "getThisRouteName"));
        String station = stringValue(invokeOptional(extra, "getThisStationName"));
        String nextStation = stringValue(invokeOptional(extra, "getNextStationName"));
        String destination = stringValue(invokeOptional(extra, "getThisRouteDestination"));

        Object head = invokeOptional(vehicle, "getHeadPositionAndTiltAngle");
        double[] coordinates = findCoordinates(head, 0);
        return new VehicleSnapshot(id, dimension, onRoute, moving, departureIndex,
                route, station, nextStation, destination,
                coordinates == null ? Double.NaN : coordinates[0],
                coordinates == null ? Double.NaN : coordinates[1],
                coordinates == null ? Double.NaN : coordinates[2]);
    }

    private static double[] findCoordinates(Object value, int depth) {
        if (value == null || depth > 3) {
            return null;
        }
        Double x = coordinate(value, "x", "getX");
        Double y = coordinate(value, "y", "getY");
        Double z = coordinate(value, "z", "getZ");
        if (x != null && y != null && z != null) {
            return new double[]{x, y, z};
        }
        for (Class<?> type = value.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (field.getType().isPrimitive() || field.getType().isEnum() || field.getName().startsWith("this$")) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object nested = field.get(value);
                    if (nested != null && nested != value) {
                        double[] coordinates = findCoordinates(nested, depth + 1);
                        if (coordinates != null) {
                            return coordinates;
                        }
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                }
            }
        }
        return null;
    }

    private static Double coordinate(Object target, String fieldName, String methodName) {
        // Transport-Simulation-Core 4.0.5 uses record components in Vector:
        // Vector.x(), Vector.y(), Vector.z(). Older versions may expose getX().
        Object value = invokeOptional(target, fieldName);
        if (!(value instanceof Number)) {
            value = invokeOptional(target, methodName);
        }
        if (!(value instanceof Number)) {
            try {
                Field field = findField(target.getClass(), fieldName);
                field.setAccessible(true);
                value = field.get(target);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return null;
            }
        }
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private static Object invokeOptional(Object target, String name) {
        if (target == null) return null;
        try {
            Method method = target.getClass().getMethod(name);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static Object readField(Class<?> type, Object target, String name) throws ReflectiveOperationException {
        Field field = findField(type, name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    private static long number(Object value, long fallback) {
        return value instanceof Number number ? number.longValue() : fallback;
    }

    private static boolean booleanValue(Object value) {
        return value instanceof Boolean result && result;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public record VehicleSnapshot(long id, String dimension, boolean onRoute, boolean moving,
                                  long departureIndex, String route, String station,
                                  String nextStation, String destination,
                                  double x, double y, double z) {
        public boolean hasPosition() {
            return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
        }
    }
}
