package nest.oudiabridge.mtr4;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;

/** Persistent mapping between MTR4 vehicle IDs and a train index in an OuDia project. */
public final class OudiaBridgeSavedData extends SavedData {
    private static final String DATA_NAME = "oudiabridge_mtr4";
    private final Map<Long, VehicleBinding> bindings = new HashMap<>();

    public static OudiaBridgeSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new Factory<>(
                        OudiaBridgeSavedData::new,
                        OudiaBridgeSavedData::load,
                        null
                ),
                DATA_NAME
        );
    }

    public VehicleBinding getBinding(long vehicleId) {
        return bindings.get(vehicleId);
    }

    public void bind(long vehicleId, String timetableId, int trainIndex) {
        if (timetableId == null || timetableId.isBlank() || trainIndex < 0) {
            throw new IllegalArgumentException("Invalid OuDia vehicle binding");
        }
        VehicleBinding next = new VehicleBinding(timetableId, trainIndex);
        if (!next.equals(bindings.put(vehicleId, next))) {
            setDirty();
        }
    }

    public void unbind(long vehicleId) {
        if (bindings.remove(vehicleId) != null) {
            setDirty();
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        bindings.forEach((vehicleId, binding) -> {
            CompoundTag entry = new CompoundTag();
            entry.putLong("vehicleId", vehicleId);
            entry.putString("timetableId", binding.timetableId());
            entry.putInt("trainIndex", binding.trainIndex());
            list.add(entry);
        });
        tag.put("bindings", list);
        return tag;
    }

    public static OudiaBridgeSavedData load(CompoundTag tag) {
        OudiaBridgeSavedData data = new OudiaBridgeSavedData();
        ListTag list = tag.getList("bindings", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            long vehicleId = entry.getLong("vehicleId");
            String timetableId = entry.getString("timetableId");
            int trainIndex = entry.getInt("trainIndex");
            if (!timetableId.isBlank() && trainIndex >= 0) {
                data.bindings.put(vehicleId, new VehicleBinding(timetableId, trainIndex));
            }
        }
        return data;
    }

    public record VehicleBinding(String timetableId, int trainIndex) {
    }
}
