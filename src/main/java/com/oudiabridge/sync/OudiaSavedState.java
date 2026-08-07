package com.oudiabridge.sync;

import net.minecraft.datafixer.DataFixTypes;
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
    private String activeProjectId = "";

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

    public void clearAll() {
        this.projects.clear();
        this.activeProjectId = "";
        this.markDirty();
    }

    public void applyToRuntime() {
        OudiaWorldSyncState runtime = OudiaWorldSyncState.getInstance();
        runtime.clear();
        runtime.setActiveProjectId(activeProjectId);

        for (Map.Entry<String, OudiaWorldSyncState.DiagramProject> entry : projects.entrySet()) {
            OudiaWorldSyncState.DiagramProject srcProj = entry.getValue();
            OudiaWorldSyncState.DiagramProject dstProj = runtime.getOrCreateProject(srcProj.id, srcProj.name);
            dstProj.depotId = srcProj.depotId;
            dstProj.startStationId = srcProj.startStationId;

            for (Map.Entry<String, OudiaWorldSyncState.StationRuntimeConfig> stEntry : srcProj.stationConfigs.entrySet()) {
                OudiaWorldSyncState.StationRuntimeConfig srcSt = stEntry.getValue();
                OudiaWorldSyncState.StationRuntimeConfig dstSt = dstProj.getOrCreateStationConfig(stEntry.getKey());
                dstSt.mtrId = srcSt.mtrId;
                dstSt.mappingType = srcSt.mappingType;
                dstSt.mtrStationId = srcSt.mtrStationId;
                dstSt.mtrStationName = srcSt.mtrStationName;
                dstSt.enabled = srcSt.enabled;
            }

            for (Map.Entry<Long, OudiaWorldSyncState.TrainRuntimeConfig> trEntry : srcProj.trainConfigs.entrySet()) {
                OudiaWorldSyncState.TrainRuntimeConfig srcTr = trEntry.getValue();
                OudiaWorldSyncState.TrainRuntimeConfig dstTr = dstProj.getOrCreateTrainConfig(trEntry.getKey());
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
            }
        }
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

        return nbt;
    }

    public void readNbt(NbtCompound nbt) {
        activeProjectId = nbt.contains("activeProjectId") ? nbt.getString("activeProjectId") : "";
        projects.clear();

        if (nbt.contains("projects")) {
            NbtList projList = nbt.getList("projects", 10);
            for (int i = 0; i < projList.size(); i++) {
                NbtCompound projNbt = projList.getCompound(i);
                String id = projNbt.getString("id");
                String name = projNbt.getString("name");

                OudiaWorldSyncState.DiagramProject proj = new OudiaWorldSyncState.DiagramProject(id, name);
                proj.depotId = projNbt.contains("depotId") ? projNbt.getLong("depotId") : -1L;
                proj.startStationId = projNbt.contains("startStationId") ? projNbt.getLong("startStationId") : -1L;

                if (projNbt.contains("stations")) {
                    NbtCompound stationTag = projNbt.getCompound("stations");
                    for (String key : stationTag.getKeys()) {
                        NbtCompound value = stationTag.getCompound(key);
                        OudiaWorldSyncState.StationRuntimeConfig cfg = new OudiaWorldSyncState.StationRuntimeConfig();
                        cfg.mtrId = value.contains("mtrId") ? value.getLong("mtrId") : (value.contains("mtrStationId") ? value.getLong("mtrStationId") : -1L);
                        cfg.mappingType = value.contains("mappingType") ? value.getInt("mappingType") : 1;
                        cfg.mtrStationId = cfg.mtrId;
                        cfg.mtrStationName = value.getString("mtrStationName");
                        cfg.enabled = !value.contains("enabled") || value.getBoolean("enabled");
                        proj.stationConfigs.put(key, cfg);
                    }
                }

                if (projNbt.contains("trains")) {
                    NbtCompound trainTag = projNbt.getCompound("trains");
                    for (String key : trainTag.getKeys()) {
                        try {
                            long trainId = Long.parseLong(key);
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
                                for (int j = 0; j < stopsList.size(); j++) {
                                    NbtCompound stopComp = stopsList.getCompound(j);
                                    cfg.stopSchedules.add(new OudiaWorldSyncState.StopSchedule(
                                            stopComp.getString("arr"),
                                            stopComp.getString("dep"),
                                            stopComp.contains("name") ? stopComp.getString("name") : ""
                                    ));
                                }
                            }

                            proj.trainConfigs.put(trainId, cfg);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }

                projects.put(id, proj);
            }
        }
    }
}