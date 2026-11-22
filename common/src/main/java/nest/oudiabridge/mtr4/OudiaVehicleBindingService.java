package nest.oudiabridge.mtr4;

import net.minecraft.server.level.ServerLevel;
import nest.oudiabridge.parser.OudiaParser;

/** Connects a parsed OuDia timetable match to persistent MTR4 vehicle state. */
public final class OudiaVehicleBindingService {
    private OudiaVehicleBindingService() {
    }

    public static OudiaVehicleMatcher.MatchResult matchAndBind(
            ServerLevel level,
            String timetableId,
            OudiaParser.ParseResult timetable,
            Mtr4RuntimeAccess.VehicleSnapshot vehicle) {
        OudiaVehicleMatcher.MatchResult result = OudiaVehicleMatcher.match(timetable, vehicle);
        if (result.status() == OudiaVehicleMatcher.Status.MATCHED) {
            OudiaBridgeSavedData.get(level).bind(vehicle.id(), timetableId, result.trainIndex());
        }
        return result;
    }

    public static OudiaBridgeSavedData.VehicleBinding getBinding(ServerLevel level, long vehicleId) {
        return OudiaBridgeSavedData.get(level).getBinding(vehicleId);
    }

    public static void unbind(ServerLevel level, long vehicleId) {
        OudiaBridgeSavedData.get(level).unbind(vehicleId);
    }
}
