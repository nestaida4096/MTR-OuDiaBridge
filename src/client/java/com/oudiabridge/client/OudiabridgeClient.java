package com.oudiabridge.client;

import com.oudiabridge.Oudiabridge;
import com.oudiabridge.sync.OudiaWorldSyncState;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class OudiabridgeClient implements ClientModInitializer {

    public static Map<Long, Float> finalSpeedManager = new ConcurrentHashMap<>();

    @Override
    public void onInitializeClient() {
        System.out.println("Starting MTR-OuDiaSecondV2-Bridge [Client]");

        // サーバーからのダイヤ同期パケット受信処理
        ClientPlayNetworking.registerGlobalReceiver(Oudiabridge.RESPONSE_DIA_LIST_2_PACKET_ID, (client, handler, buf, responseSender) -> {
            int length = buf.readInt();
            byte[] data = new byte[length];
            buf.readBytes(data);
            System.out.println("Received RESPONSE_DIA_LIST_2_PACKET_ID");

            client.execute(() -> {
                try {
                    NbtCompound payload = NbtIo.readCompressed(new ByteArrayInputStream(data));
                    if (payload == null) return;

                    OudiaWorldSyncState runtime = OudiaWorldSyncState.getInstance();

                    if (payload.contains("activeProjectId")) {
                        runtime.setActiveProjectId(payload.getString("activeProjectId"));
                    }

                    if (payload.contains("projects")) {
                        NbtList projList = payload.getList("projects", 10);
                        for (int i = 0; i < projList.size(); i++) {
                            NbtCompound pComp = projList.getCompound(i);
                            String projId = pComp.getString("id");
                            String projName = pComp.getString("name");

                            OudiaWorldSyncState.DiagramProject project = runtime.getOrCreateProject(projId, projName);

                            // TrainRuntimeConfig の詳細情報が含まれている場合はパースして更新
                            if (pComp.contains("trainConfigs")) {
                                project.trainConfigs.clear();
                                NbtList trainList = pComp.getList("trainConfigs", 10);
                                for (int j = 0; j < trainList.size(); j++) {
                                    NbtCompound tComp = trainList.getCompound(j);
                                    long configId = tComp.getLong("configId");

                                    OudiaWorldSyncState.TrainRuntimeConfig cfg = project.getOrCreateTrainConfig(configId);
                                    cfg.depotName = tComp.getString("depotName");
                                    cfg.depotLineName = tComp.getString("depotLineName");
                                    cfg.depotId = tComp.getLong("depotId");
                                    cfg.speedMultiplier = tComp.getDouble("speedMultiplier");
                                    cfg.departureOffsetTicks = tComp.getInt("departureOffsetTicks");
                                    cfg.minimumDwellTicks = tComp.getInt("minimumDwellTicks");
                                    cfg.maxRailSpeed = tComp.getInt("maxRailSpeed");
                                    cfg.firstArrivalTime = tComp.getString("firstArrivalTime");
                                    cfg.firstDepartureTime = tComp.getString("firstDepartureTime");
                                    cfg.startStationId = tComp.getLong("startStationId");

                                    if (tComp.contains("stopSchedules")) {
                                        cfg.stopSchedules.clear();
                                        NbtList stopList = tComp.getList("stopSchedules", 10);
                                        for (int k = 0; k < stopList.size(); k++) {
                                            NbtCompound sComp = stopList.getCompound(k);
                                            cfg.stopSchedules.add(new OudiaWorldSyncState.StopSchedule(
                                                    sComp.getString("arrivalTime"),
                                                    sComp.getString("departureTime"),
                                                    sComp.getString("stationName")
                                            ));
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
        });
    }
}