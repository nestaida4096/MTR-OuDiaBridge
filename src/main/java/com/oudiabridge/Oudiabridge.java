package com.oudiabridge;

import com.oudiabridge.sync.OudiaSavedState;
import com.oudiabridge.sync.OudiaWorldSyncState;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtIo;
import net.minecraft.util.Identifier;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Oudiabridge implements ModInitializer {
    public static final Identifier SYNC_PACKET_ID = new Identifier("oudiabridge", "sync_dia");

    // 分割データの受信バッファを保持するConcurrentHashMap
    private static final Map<UUID, ChunkBuffer> INCOMPLETE_TRANSFERS = new ConcurrentHashMap<>();

    // パケット分割データを復元・再構築するための内部クラス
    private static class ChunkBuffer {
        final byte[][] chunks;
        final int totalChunks;
        int receivedChunks = 0;

        ChunkBuffer(int totalChunks) {
            this.totalChunks = totalChunks;
            this.chunks = new byte[totalChunks][];
        }

        synchronized boolean addChunk(int index, byte[] data) {
            if (chunks[index] == null) {
                chunks[index] = data;
                receivedChunks++;
            }
            return receivedChunks == totalChunks;
        }

        synchronized byte[] assemble() {
            int totalLength = 0;
            for (byte[] chunk : chunks) {
                if (chunk == null) return null;
                totalLength += chunk.length;
            }

            byte[] result = new byte[totalLength];
            int currentOffset = 0;
            for (byte[] chunk : chunks) {
                System.arraycopy(chunk, 0, result, currentOffset, chunk.length);
                currentOffset += chunk.length;
            }
            return result;
        }
    }

    @Override
    public void onInitialize() {
        // クライアントからの分割パケット受信ハンドラ
        ServerPlayNetworking.registerGlobalReceiver(SYNC_PACKET_ID, (server, player, handler, buf, responseSender) -> {
            UUID transferId = buf.readUuid();
            int chunkIndex = buf.readInt();
            int totalChunks = buf.readInt();
            int length = buf.readInt();
            byte[] data = new byte[length];
            buf.readBytes(data);

            ChunkBuffer buffer = INCOMPLETE_TRANSFERS.computeIfAbsent(transferId, id -> new ChunkBuffer(totalChunks));

            if (buffer.addChunk(chunkIndex, data)) {
                INCOMPLETE_TRANSFERS.remove(transferId);
                byte[] completeData = buffer.assemble();

                if (completeData == null) return;

                server.execute(() -> {
                    NbtCompound payload;
                    try {
                        payload = NbtIo.readCompressed(new ByteArrayInputStream(completeData));
                    } catch (IOException e) {
                        e.printStackTrace();
                        return;
                    }

                    if (payload == null) return;

                    OudiaWorldSyncState runtime = OudiaWorldSyncState.getInstance();
                    runtime.clear();

                    if (payload.contains("depotName")) {
                        runtime.setDepotName(payload.getString("depotName"));
                    }
                    if (payload.contains("depotId")) {
                        runtime.getDefaultTrainConfig().depotId = payload.getLong("depotId");
                    }
                    if (payload.contains("startStationId")) {
                        runtime.getDefaultTrainConfig().startStationId = payload.getLong("startStationId");
                    }

                    if (payload.contains("stations")) {
                        NbtCompound stations = payload.getCompound("stations");
                        for (String key : stations.getKeys()) {
                            NbtCompound value = stations.getCompound(key);
                            OudiaWorldSyncState.StationRuntimeConfig cfg = runtime.getOrCreateStationConfig(key);

                            // 新しいマッピングIDと種別の取得
                            cfg.mtrId = value.contains("mtrId") ? value.getLong("mtrId") : (value.contains("mtrStationId") ? value.getLong("mtrStationId") : -1L);
                            cfg.mappingType = value.contains("mappingType") ? value.getInt("mappingType") : 1;

                            // 互換性維持
                            cfg.mtrStationId = cfg.mtrId;
                            cfg.mtrStationName = value.getString("mtrStationName");
                            cfg.enabled = !value.contains("enabled") || value.getBoolean("enabled");
                        }
                    }

                    if (payload.contains("trains")) {
                        NbtCompound trains = payload.getCompound("trains");
                        for (String key : trains.getKeys()) {
                            NbtCompound value = trains.getCompound(key);
                            OudiaWorldSyncState.TrainRuntimeConfig cfg = runtime.getOrCreateTrainConfig(key);
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

                            // 全駅の時刻リスト (stops) ＋ 駅名の取り出し
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
                        }
                    }

                    // ワールド保存用状態クラスへ反映
                    OudiaSavedState savedState = OudiaSavedState.get(player.getWorld());
                    if (savedState != null) {
                        savedState.copyFromRuntime();
                    }

                    // 各車庫ごとの出庫スケジュールとインデックスを再構築
                    runtime.rebuildDepotSchedules();
                });
            }
        });
    }
}