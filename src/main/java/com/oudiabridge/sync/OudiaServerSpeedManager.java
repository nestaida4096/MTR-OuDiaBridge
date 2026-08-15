package com.oudiabridge.sync;

import com.oudiabridge.Oudiabridge;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class OudiaServerSpeedManager {
    // サーバー側で保持する列車IDごとの最高速度マップ（必要に応じて参照用）
    private static final Map<Long, Float> serverSpeedMap = new ConcurrentHashMap<>();

    public static void setSpeed(long trainId, float speed) {
        serverSpeedMap.put(trainId, speed);
    }

    public static Float getSpeed(long trainId) {
        return serverSpeedMap.get(trainId);
    }

    public static void removeSpeed(long trainId) {
        serverSpeedMap.remove(trainId);
    }

    public static Map<Long, Float> getAllSpeeds() {
        return Collections.unmodifiableMap(serverSpeedMap);
    }

    public static void clear() {
        serverSpeedMap.clear();
    }

    public static void register() {
        // クライアント側で速度演算を行うため、毎ティックの個別速度パケット送信は不要です
    }

    /**
     * サーバー上でダイヤが登録・選択された際に、全クライアントへTrainRuntimeConfigを含む詳細情報を一括配信します。
     * @param server MinecraftServerインスタンス
     */
    public static void broadcastSyncData(MinecraftServer server) {
        if (server == null) return;

        OudiaWorldSyncState runtime = OudiaWorldSyncState.getInstance();

        NbtCompound root = new NbtCompound();
        root.putString("activeProjectId", runtime.getActiveProjectId());

        NbtList projList = new NbtList();
        for (OudiaWorldSyncState.DiagramProject proj : runtime.getProjects().values()) {
            NbtCompound pComp = new NbtCompound();
            pComp.putString("id", proj.id);
            pComp.putString("name", proj.name);

            // TrainRuntimeConfig の詳細データをNBTに書き込み
            NbtList trainList = new NbtList();
            for (Map.Entry<Long, OudiaWorldSyncState.TrainRuntimeConfig> entry : proj.trainConfigs.entrySet()) {
                NbtCompound tComp = new NbtCompound();
                tComp.putLong("configId", entry.getKey());

                OudiaWorldSyncState.TrainRuntimeConfig cfg = entry.getValue();
                if (cfg != null) {
                    tComp.putString("depotName", cfg.depotName != null ? cfg.depotName : "");
                    tComp.putString("depotLineName", cfg.depotLineName != null ? cfg.depotLineName : "");
                    tComp.putLong("depotId", cfg.depotId);
                    tComp.putDouble("speedMultiplier", cfg.speedMultiplier);
                    tComp.putInt("departureOffsetTicks", cfg.departureOffsetTicks);
                    tComp.putInt("minimumDwellTicks", cfg.minimumDwellTicks);
                    tComp.putInt("maxRailSpeed", cfg.maxRailSpeed);
                    tComp.putString("firstArrivalTime", cfg.firstArrivalTime != null ? cfg.firstArrivalTime : "");
                    tComp.putString("firstDepartureTime", cfg.firstDepartureTime != null ? cfg.firstDepartureTime : "");
                    tComp.putLong("startStationId", cfg.startStationId);

                    // 各列車の停車スケジュールの書き込み
                    NbtList stopList = new NbtList();
                    for (OudiaWorldSyncState.StopSchedule stop : cfg.stopSchedules) {
                        if (stop != null) {
                            NbtCompound sComp = new NbtCompound();
                            sComp.putString("arrivalTime", stop.arrivalTime != null ? stop.arrivalTime : "");
                            sComp.putString("departureTime", stop.departureTime != null ? stop.departureTime : "");
                            sComp.putString("stationName", stop.stationName != null ? stop.stationName : "");
                            stopList.add(sComp);
                        }
                    }
                    tComp.put("stopSchedules", stopList);
                }
                trainList.add(tComp);
            }
            pComp.put("trainConfigs", trainList);

            projList.add(pComp);
        }
        root.put("projects", projList);

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            NbtIo.writeCompressed(root, baos);
            byte[] bytes = baos.toByteArray();

            // 全接続クライアントにブロードキャスト送信
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                PacketByteBuf buf = PacketByteBufs.create();
                buf.writeInt(bytes.length);
                buf.writeBytes(bytes);
                ServerPlayNetworking.send(player, Oudiabridge.RESPONSE_DIA_LIST_2_PACKET_ID, buf);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}