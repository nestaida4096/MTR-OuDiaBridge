package com.oudiabridge.sync;

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
    private final Map<String, OudiaWorldSyncState.StationRuntimeConfig> stations = new HashMap<>();
    private final Map<String, OudiaWorldSyncState.TrainRuntimeConfig> trains = new HashMap<>();
    private String depotName = "";
    private String depotLineName = "";
    private long depotId = -1L;
    private long startStationId = -1L;

    public static OudiaSavedState createFromNbt(NbtCompound nbt) {
        OudiaSavedState state = new OudiaSavedState();
        state.readNbt(nbt);
        return state;
    }

    public static OudiaSavedState get(ServerWorld world) {
        PersistentStateManager manager = world.getPersistentStateManager();
        return manager.getOrCreate(OudiaSavedState::createFromNbt, OudiaSavedState::new, KEY);
    }

    public static OudiaSavedState get(World world) {
        if (world instanceof ServerWorld serverWorld) {
            return get(serverWorld);
        }
        return null;
    }

    public void applyToRuntime() {
        OudiaWorldSyncState runtime = OudiaWorldSyncState.getInstance();
        runtime.clear();
        runtime.setDepotName(depotName);
        runtime.setDepotLineName(depotLineName);
        runtime.getDefaultTrainConfig().depotName = depotName;
        runtime.getDefaultTrainConfig().depotLineName = depotLineName;
        runtime.getDefaultTrainConfig().depotId = depotId;
        runtime.getDefaultTrainConfig().startStationId = startStationId;

        runtime.getStationConfigs().clear();
        for (Map.Entry<String, OudiaWorldSyncState.StationRuntimeConfig> entry : stations.entrySet()) {
            OudiaWorldSyncState.StationRuntimeConfig src = entry.getValue();
            OudiaWorldSyncState.StationRuntimeConfig dst = runtime.getOrCreateStationConfig(entry.getKey());
            dst.mtrId = src.mtrId;
            dst.mappingType = src.mappingType;
            dst.mtrStationId = src.mtrStationId;
            dst.mtrStationName = src.mtrStationName;
            dst.enabled = src.enabled;
        }

        runtime.getTrainConfigs().clear();
        for (Map.Entry<String, OudiaWorldSyncState.TrainRuntimeConfig> entry : trains.entrySet()) {
            OudiaWorldSyncState.TrainRuntimeConfig src = entry.getValue();
            OudiaWorldSyncState.TrainRuntimeConfig dst = runtime.getOrCreateTrainConfig(entry.getKey());
            dst.depotName = src.depotName;
            dst.depotLineName = src.depotLineName;
            dst.depotId = src.depotId;
            dst.speedMultiplier = src.speedMultiplier;
            dst.departureOffsetTicks = src.departureOffsetTicks;
            dst.minimumDwellTicks = src.minimumDwellTicks;
            dst.maxRailSpeed = src.maxRailSpeed;
            dst.firstArrivalTime = src.firstArrivalTime;
            dst.firstDepartureTime = src.firstDepartureTime;
            dst.startStationId = src.startStationId;

            dst.stopSchedules.clear();
            for (OudiaWorldSyncState.StopSchedule stop : src.stopSchedules) {
                dst.stopSchedules.add(new OudiaWorldSyncState.StopSchedule(stop.arrivalTime, stop.departureTime, stop.stationName));
            }
        }

        // ランタイム適用後にDepotスケジュールを再構築する
        runtime.rebuildDepotSchedules();
    }

    public void copyFromRuntime() {
        OudiaWorldSyncState runtime = OudiaWorldSyncState.getInstance();
        this.depotName = runtime.getDepotName();
        this.depotLineName = runtime.getDepotLineName();
        this.depotId = runtime.getDefaultTrainConfig().depotId;
        this.startStationId = runtime.getDefaultTrainConfig().startStationId;

        this.stations.clear();
        for (Map.Entry<String, OudiaWorldSyncState.StationRuntimeConfig> entry : runtime.getStationConfigs().entrySet()) {
            OudiaWorldSyncState.StationRuntimeConfig src = entry.getValue();
            OudiaWorldSyncState.StationRuntimeConfig dst = new OudiaWorldSyncState.StationRuntimeConfig();
            dst.mtrId = src.mtrId;
            dst.mappingType = src.mappingType;
            dst.mtrStationId = src.mtrStationId;
            dst.mtrStationName = src.mtrStationName;
            dst.enabled = src.enabled;
            this.stations.put(entry.getKey(), dst);
        }

        this.trains.clear();
        for (Map.Entry<String, OudiaWorldSyncState.TrainRuntimeConfig> entry : runtime.getTrainConfigs().entrySet()) {
            OudiaWorldSyncState.TrainRuntimeConfig src = entry.getValue();
            OudiaWorldSyncState.TrainRuntimeConfig dst = new OudiaWorldSyncState.TrainRuntimeConfig();
            dst.depotName = src.depotName;
            dst.depotLineName = src.depotLineName;
            dst.depotId = src.depotId;
            dst.speedMultiplier = src.speedMultiplier;
            dst.departureOffsetTicks = src.departureOffsetTicks;
            dst.minimumDwellTicks = src.minimumDwellTicks;
            dst.maxRailSpeed = src.maxRailSpeed;
            dst.firstArrivalTime = src.firstArrivalTime;
            dst.firstDepartureTime = src.firstDepartureTime;
            dst.startStationId = src.startStationId;

            dst.stopSchedules.clear();
            for (OudiaWorldSyncState.StopSchedule stop : src.stopSchedules) {
                dst.stopSchedules.add(new OudiaWorldSyncState.StopSchedule(stop.arrivalTime, stop.departureTime, stop.stationName));
            }
            this.trains.put(entry.getKey(), dst);
        }

        this.markDirty();
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {
        nbt.putString("depotName", depotName);
        nbt.putString("depotLineName", depotLineName);
        nbt.putLong("depotId", depotId);
        nbt.putLong("startStationId", startStationId);

        if (!stations.isEmpty()) {
            NbtCompound stationTag = new NbtCompound();
            for (Map.Entry<String, OudiaWorldSyncState.StationRuntimeConfig> entry : stations.entrySet()) {
                OudiaWorldSyncState.StationRuntimeConfig cfg = entry.getValue();
                NbtCompound value = new NbtCompound();
                value.putLong("mtrId", cfg.mtrId);
                value.putInt("mappingType", cfg.mappingType);
                value.putLong("mtrStationId", cfg.mtrStationId);
                value.putString("mtrStationName", cfg.mtrStationName);
                value.putBoolean("enabled", cfg.enabled);
                stationTag.put(entry.getKey(), value);
            }
            nbt.put("stations", stationTag);
        }

        if (!trains.isEmpty()) {
            NbtCompound trainTag = new NbtCompound();
            for (Map.Entry<String, OudiaWorldSyncState.TrainRuntimeConfig> entry : trains.entrySet()) {
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

                trainTag.put(entry.getKey(), value);
            }
            nbt.put("trains", trainTag);
        }

        return nbt;
    }

    public void readNbt(NbtCompound nbt) {
        depotName = nbt.getString("depotName");
        depotLineName = nbt.getString("depotLineName");
        depotId = nbt.contains("depotId") ? nbt.getLong("depotId") : -1L;
        startStationId = nbt.contains("startStationId") ? nbt.getLong("startStationId") : -1L;

        stations.clear();
        trains.clear();

        if (nbt.contains("stations")) {
            NbtCompound stationTag = nbt.getCompound("stations");
            for (String key : stationTag.getKeys()) {
                NbtCompound value = stationTag.getCompound(key);
                OudiaWorldSyncState.StationRuntimeConfig cfg = new OudiaWorldSyncState.StationRuntimeConfig();
                cfg.mtrId = value.contains("mtrId") ? value.getLong("mtrId") : (value.contains("mtrStationId") ? value.getLong("mtrStationId") : -1L);
                cfg.mappingType = value.contains("mappingType") ? value.getInt("mappingType") : 1;
                cfg.mtrStationId = cfg.mtrId;
                cfg.mtrStationName = value.getString("mtrStationName");
                cfg.enabled = !value.contains("enabled") || value.getBoolean("enabled");
                stations.put(key, cfg);
            }
        }

        if (nbt.contains("trains")) {
            NbtCompound trainTag = nbt.getCompound("trains");
            for (String key : trainTag.getKeys()) {
                NbtCompound value = trainTag.getCompound(key);
                OudiaWorldSyncState.TrainRuntimeConfig cfg = new OudiaWorldSyncState.TrainRuntimeConfig();
                cfg.depotName = value.getString("depotName");
                cfg.depotLineName = value.getString("depotLineName");
                cfg.depotId = value.contains("depotId") ? value.getLong("depotId") : -1L;
                cfg.speedMultiplier = value.getDouble("speedMultiplier");
                cfg.departureOffsetTicks = value.getInt("departureOffsetTicks");
                cfg.minimumDwellTicks = value.getInt("minimumDwellTicks");
                cfg.maxRailSpeed = value.getInt("maxRailSpeed");
                cfg.firstArrivalTime = value.getString("firstArrivalTime");
                cfg.firstDepartureTime = value.getString("firstDepartureTime");
                cfg.startStationId = value.contains("startStationId") ? value.getLong("startStationId") : -1L;

                if (value.contains("stops")) {
                    NbtList stopsList = value.getList("stops", 10);
                    cfg.stopSchedules.clear();
                    for (int i = 0; i < stopsList.size(); i++) {
                        NbtCompound stopComp = stopsList.getCompound(i);
                        cfg.stopSchedules.add(new OudiaWorldSyncState.StopSchedule(
                                stopComp.getString("arr"),
                                stopComp.getString("dep"),
                                stopComp.contains("name") ? stopComp.getString("name") : ""
                        ));
                    }
                }

                trains.put(key, cfg);
            }
        }
    }
}