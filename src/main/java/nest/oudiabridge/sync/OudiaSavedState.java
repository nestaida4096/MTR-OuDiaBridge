package nest.oudiabridge.sync;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateManager;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.Map;

public class OudiaSavedState extends PersistentState {
    private static final String KEY = "oudiabridge_state";
    private final Map<String, OudiaWorldSyncState.DiagramProject> projects = new HashMap<>();
    private final Map<Long, OudiaWorldSyncState.TrainOperatingState> operatingStates = new HashMap<>();
    private String activeProjectId = "";

    public static OudiaSavedState createFromNbt(NbtCompound nbt) {
        OudiaSavedState state = new OudiaSavedState();
        state.readNbt(nbt);
        return state;
    }

    public static OudiaSavedState get(ServerWorld world) {
        if (world == null) return null;
        ServerWorld overworld = world.getServer() != null ? world.getServer().getOverworld() : world;
        PersistentStateManager manager = (overworld != null ? overworld : world).getPersistentStateManager();
        return manager.getOrCreate(OudiaSavedState::createFromNbt, OudiaSavedState::new, KEY);
    }

    public static OudiaSavedState get(World world) {
        if (world instanceof ServerWorld serverWorld) {
            return get(serverWorld);
        }
        return null;
    }

    public void clearAll() {
        this.projects.clear();
        this.operatingStates.clear();
        this.activeProjectId = "";
        this.markDirty();
    }

    public void applyToRuntime() {
        NbtCompound tempNbt = writeNbt(new NbtCompound());
        OudiaWorldSyncState.getInstance().readNbt(tempNbt);
    }

    public void copyFromRuntime() {
        OudiaWorldSyncState runtime = OudiaWorldSyncState.getInstance();
        this.activeProjectId = runtime.getActiveProjectId();
        this.projects.clear();

        for (Map.Entry<String, OudiaWorldSyncState.DiagramProject> entry : runtime.getProjects().entrySet()) {
            OudiaWorldSyncState.DiagramProject srcProj = entry.getValue();
            OudiaWorldSyncState.DiagramProject dstProj = new OudiaWorldSyncState.DiagramProject(srcProj.id, srcProj.name);
            dstProj.depotId = srcProj.depotId;
            dstProj.startStationId = srcProj.startStationId;

            for (Map.Entry<String, OudiaWorldSyncState.StationRuntimeConfig> stEntry : srcProj.stationConfigs.entrySet()) {
                OudiaWorldSyncState.StationRuntimeConfig srcSt = stEntry.getValue();
                OudiaWorldSyncState.StationRuntimeConfig dstSt = new OudiaWorldSyncState.StationRuntimeConfig();
                dstSt.mtrId = srcSt.mtrId;
                dstSt.mappingType = srcSt.mappingType;
                dstSt.mtrStationId = srcSt.mtrStationId;
                dstSt.mtrStationName = srcSt.mtrStationName;
                dstSt.enabled = srcSt.enabled;
                dstProj.stationConfigs.put(stEntry.getKey(), dstSt);
            }

            for (Map.Entry<Long, OudiaWorldSyncState.TrainRuntimeConfig> trEntry : srcProj.trainConfigs.entrySet()) {
                OudiaWorldSyncState.TrainRuntimeConfig srcTr = trEntry.getValue();
                OudiaWorldSyncState.TrainRuntimeConfig dstTr = new OudiaWorldSyncState.TrainRuntimeConfig();
                dstTr.depotName = srcTr.depotName;
                dstTr.depotLineName = srcTr.depotLineName;
                dstTr.depotId = srcTr.depotId;
                dstTr.speedMultiplier = srcTr.speedMultiplier;
                dstTr.departureOffsetTicks = srcTr.departureOffsetTicks;
                dstTr.minimumDwellTicks = srcTr.minimumDwellTicks;
                dstTr.maxRailSpeed = srcTr.maxRailSpeed;
                dstTr.firstArrivalTime = srcTr.firstArrivalTime;
                dstTr.firstDepartureTime = srcTr.firstDepartureTime;
                dstTr.startStationId = srcTr.startStationId;

                dstTr.stopSchedules.clear();
                for (OudiaWorldSyncState.StopSchedule stop : srcTr.stopSchedules) {
                    dstTr.stopSchedules.add(new OudiaWorldSyncState.StopSchedule(stop.arrivalTime, stop.departureTime, stop.stationName));
                }
                dstProj.trainConfigs.put(trEntry.getKey(), dstTr);
            }

            this.projects.put(entry.getKey(), dstProj);
        }

        this.operatingStates.clear();
        for (Map.Entry<Long, OudiaWorldSyncState.TrainOperatingState> entry : OudiaWorldSyncState.trainOperatingStates.entrySet()) {
            OudiaWorldSyncState.TrainOperatingState src = entry.getValue();
            OudiaWorldSyncState.TrainOperatingState dst = new OudiaWorldSyncState.TrainOperatingState(entry.getKey());
            dst.matchedTrainConfigId = src.matchedTrainConfigId;
            dst.lastIsOnRoute = src.lastIsOnRoute;
            dst.turnaroundStopIndexOffset = src.turnaroundStopIndexOffset;
            dst.lastLoggedStopIndex = src.lastLoggedStopIndex;
            dst.lockedStationStopIndex = src.lockedStationStopIndex;
            this.operatingStates.put(entry.getKey(), dst);
        }

        this.markDirty();
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {
        nbt.putString("activeProjectId", activeProjectId);

        NbtList projList = new NbtList();
        for (OudiaWorldSyncState.DiagramProject proj : projects.values()) {
            NbtCompound projNbt = new NbtCompound();
            projNbt.putString("id", proj.id);
            projNbt.putString("name", proj.name);
            projNbt.putLong("depotId", proj.depotId);
            projNbt.putLong("startStationId", proj.startStationId);

            NbtCompound stationTag = new NbtCompound();
            for (Map.Entry<String, OudiaWorldSyncState.StationRuntimeConfig> entry : proj.stationConfigs.entrySet()) {
                OudiaWorldSyncState.StationRuntimeConfig cfg = entry.getValue();
                NbtCompound value = new NbtCompound();
                value.putLong("mtrId", cfg.mtrId);
                value.putInt("mappingType", cfg.mappingType);
                value.putLong("mtrStationId", cfg.mtrStationId);
                value.putString("mtrStationName", cfg.mtrStationName);
                value.putBoolean("enabled", cfg.enabled);
                stationTag.put(entry.getKey(), value);
            }
            projNbt.put("stations", stationTag);

            NbtCompound trainTag = new NbtCompound();
            for (Map.Entry<Long, OudiaWorldSyncState.TrainRuntimeConfig> entry : proj.trainConfigs.entrySet()) {
                OudiaWorldSyncState.TrainRuntimeConfig cfg = entry.getValue();
                NbtCompound value = new NbtCompound();
                value.putString("depotName", cfg.depotName);
                value.putString("depotLineName", cfg.depotLineName);
                value.putLong("depotId", cfg.depotId);
                value.putDouble("speedMultiplier", cfg.speedMultiplier);
                value.putInt("departureOffsetTicks", cfg.departureOffsetTicks);
                value.putInt("minimumDwellTicks", cfg.minimumDwellTicks);
                value.putInt("maxRailSpeed", cfg.maxRailSpeed);
                value.putString("firstArrivalTime", cfg.firstArrivalTime);
                value.putString("firstDepartureTime", cfg.firstDepartureTime);
                value.putLong("startStationId", cfg.startStationId);

                NbtList stopsList = new NbtList();
                for (OudiaWorldSyncState.StopSchedule stop : cfg.stopSchedules) {
                    NbtCompound stopComp = new NbtCompound();
                    stopComp.putString("arr", stop.arrivalTime);
                    stopComp.putString("dep", stop.departureTime);
                    stopComp.putString("name", stop.stationName);
                    stopsList.add(stopComp);
                }
                value.put("stops", stopsList);

                trainTag.put(String.valueOf(entry.getKey()), value);
            }
            projNbt.put("trains", trainTag);

            projList.add(projNbt);
        }
        nbt.put("projects", projList);

        NbtList operatingList = new NbtList();
        for (Map.Entry<Long, OudiaWorldSyncState.TrainOperatingState> entry : operatingStates.entrySet()) {
            OudiaWorldSyncState.TrainOperatingState state = entry.getValue();
            NbtCompound value = new NbtCompound();
            value.putLong("trainId", entry.getKey());
            if (state.matchedTrainConfigId != null) {
                value.putLong("matchedTrainConfigId", state.matchedTrainConfigId);
            }
            value.putBoolean("lastIsOnRoute", state.lastIsOnRoute);
            value.putInt("turnaroundStopIndexOffset", state.turnaroundStopIndexOffset);
            value.putInt("lastLoggedStopIndex", state.lastLoggedStopIndex);
            value.putInt("lockedStationStopIndex", state.lockedStationStopIndex);
            operatingList.add(value);
        }

        nbt.put("operatingStates", operatingList);

        return nbt;
    }

    public void readNbt(NbtCompound nbt) {
        OudiaWorldSyncState tempRuntime = OudiaWorldSyncState.getInstance();
        tempRuntime.readNbt(nbt);
        this.activeProjectId = tempRuntime.getActiveProjectId();

        this.projects.clear();
        for (Map.Entry<String, OudiaWorldSyncState.DiagramProject> e : tempRuntime.getProjects().entrySet()) {
            this.projects.put(e.getKey(), e.getValue());
        }

        this.operatingStates.clear();
        for (Map.Entry<Long, OudiaWorldSyncState.TrainOperatingState> e : OudiaWorldSyncState.trainOperatingStates.entrySet()) {
            this.operatingStates.put(e.getKey(), e.getValue());
        }
    }
}