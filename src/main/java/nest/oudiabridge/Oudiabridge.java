package nest.oudiabridge;

import com.mojang.brigadier.CommandDispatcher;
import nest.oudiabridge.command.TrainDiagnosticsCommand;
import nest.oudiabridge.sync.OudiaSavedState;
import nest.oudiabridge.sync.OudiaServerSpeedManager;
import nest.oudiabridge.sync.OudiaWorldSyncState;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Oudiabridge implements ModInitializer {
    public static final Identifier SYNC_PACKET_ID = new Identifier("oudiabridge", "sync_dia");
    public static final Identifier CLEAR_PACKET_ID = new Identifier("oudiabridge", "clear_dia");
    public static final Identifier SELECT_PROJECT_PACKET_ID = new Identifier("oudiabridge", "select_proj");
    public static final Identifier DELETE_PROJECT_PACKET_ID = new Identifier("oudiabridge", "delete_proj");
    public static final Identifier REQUEST_DIA_LIST_PACKET_ID = new Identifier("oudiabridge", "req_dia_list");
    public static final Identifier RESPONSE_DIA_LIST_PACKET_ID = new Identifier("oudiabridge", "res_dia_list");
    public static final Identifier RESPONSE_DIA_LIST_2_PACKET_ID = new Identifier("oudiabridge", "res_dia_list_2");
    public static final Identifier TIME_SYNC_ID = new Identifier("oudiabridge", "current_sec_sync");

    private static final Map<UUID, ChunkBuffer> INCOMPLETE_TRANSFERS = new ConcurrentHashMap<>();

    // ログ受信対象プレイヤーのリスト
    private static final Set<UUID> LOG_LISTENERS = Collections.synchronizedSet(new HashSet<>());
    private static MinecraftServer serverInstance;

    private static Long yoyakuSend_DIA_LIST_2;

    @Override
    public void onInitialize() {
        init();
    }

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

    /**
     * ログメッセージをチャット画面およびコンソールへ送信する
     */
    public static void logToChat(String message) {
        System.out.println(message); // 標準出力にも出力

        if (serverInstance == null) return;

        Text text = Text.literal("[OudiaBridge] " + message).formatted(Formatting.AQUA);

        synchronized (LOG_LISTENERS) {
            for (UUID uuid : LOG_LISTENERS) {
                ServerPlayerEntity player = serverInstance.getPlayerManager().getPlayer(uuid);
                if (player != null) {
                    player.sendMessage(text, false);
                }
            }
        }
    }


    public static void init() {
        System.out.println("Starting MTR-OuDiaSecondV2-Bridge [Main]");
        OudiaServerSpeedManager.register();

        // コマンド登録
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            registerCommands(dispatcher);
            TrainDiagnosticsCommand.register(dispatcher);
        });

        // サーバー起動完了時
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            serverInstance = server;
            ServerWorld overworld = server.getOverworld();
            if (overworld != null) {
                OudiaSavedState savedState = OudiaSavedState.get(overworld);
                if (savedState != null) {
                    savedState.applyToRuntime();
                    // 保存状態復元時にも全クライアントへ配信
                    OudiaServerSpeedManager.broadcastSyncData(server);
                }
            }
        });

        // サーバー停止前
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            ServerWorld overworld = server.getOverworld();
            if (overworld != null) {
                OudiaSavedState savedState = OudiaSavedState.get(overworld);
                if (savedState != null) {
                    savedState.copyFromRuntime();
                }
            }
            serverInstance = null;
        });

        // MTRダイヤプロジェクトの登録・更新ハンドラ
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
                    String projName = payload.contains("diaName") ? payload.getString("diaName") : "OuDia_Project";
                    String projId = payload.contains("projId") ? payload.getString("projId") : projName;

                    OudiaWorldSyncState.DiagramProject project = runtime.getOrCreateProject(projId, projName);
                    project.trainConfigs.clear();
                    project.stationConfigs.clear();

                    if (payload.contains("startStationId")) {
                        project.startStationId = payload.getLong("startStationId");
                    }

                    if (payload.contains("stations")) {
                        NbtCompound stations = payload.getCompound("stations");
                        for (String key : stations.getKeys()) {
                            NbtCompound value = stations.getCompound(key);
                            OudiaWorldSyncState.StationRuntimeConfig cfg = project.getOrCreateStationConfig(key);

                            cfg.mtrId = value.contains("mtrId") ? value.getLong("mtrId") : (value.contains("mtrStationId") ? value.getLong("mtrStationId") : -1L);
                            cfg.mappingType = value.contains("mappingType") ? value.getInt("mappingType") : 1;
                            cfg.mtrStationId = cfg.mtrId;
                            cfg.mtrStationName = value.getString("mtrStationName");
                            cfg.enabled = !value.contains("enabled") || value.getBoolean("enabled");
                        }
                    }

                    if (payload.contains("trains")) {
                        NbtCompound trains = payload.getCompound("trains");
                        for (String key : trains.getKeys()) {
                            try {
                                long trainId = Long.parseLong(key);
                                NbtCompound value = trains.getCompound(key);
                                OudiaWorldSyncState.TrainRuntimeConfig cfg = project.getOrCreateTrainConfig(trainId);
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
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }

                    runtime.setActiveProjectId(projId);

                    OudiaSavedState savedState = OudiaSavedState.get(player.getWorld());
                    if (savedState != null) {
                        savedState.copyFromRuntime();
                    }

                    sendServerStateToClient(player);
                    // 全クライアントへTrainRuntimeConfigの詳細情報を一括同期
                    OudiaServerSpeedManager.broadcastSyncData(server);
                });
            }
        });

        // 全クリアパケット
        ServerPlayNetworking.registerGlobalReceiver(CLEAR_PACKET_ID, (server, player, handler, buf, responseSender) -> {
            server.execute(() -> {
                OudiaWorldSyncState runtime = OudiaWorldSyncState.getInstance();
                runtime.clear();

                OudiaSavedState savedState = OudiaSavedState.get(player.getWorld());
                if (savedState != null) {
                    savedState.clearAll();
                }

                sendServerStateToClient(player);
                OudiaServerSpeedManager.broadcastSyncData(server);
            });
        });

        // 特定プロジェクトの作業プロジェクト化指定
        ServerPlayNetworking.registerGlobalReceiver(SELECT_PROJECT_PACKET_ID, (server, player, handler, buf, responseSender) -> {
            String projId = buf.readString();
            server.execute(() -> {
                OudiaWorldSyncState runtime = OudiaWorldSyncState.getInstance();
                if (runtime.getProjects().containsKey(projId)) {
                    runtime.setActiveProjectId(projId);

                    OudiaSavedState savedState = OudiaSavedState.get(player.getWorld());
                    if (savedState != null) {
                        savedState.copyFromRuntime();
                    }
                }
                sendServerStateToClient(player);
                OudiaServerSpeedManager.broadcastSyncData(server);
            });
        });

        // 特定プロジェクトの削除指定
        ServerPlayNetworking.registerGlobalReceiver(DELETE_PROJECT_PACKET_ID, (server, player, handler, buf, responseSender) -> {
            String projId = buf.readString();
            server.execute(() -> {
                OudiaWorldSyncState runtime = OudiaWorldSyncState.getInstance();
                runtime.removeProject(projId);

                OudiaSavedState savedState = OudiaSavedState.get(player.getWorld());
                if (savedState != null) {
                    savedState.copyFromRuntime();
                }

                sendServerStateToClient(player);
                OudiaServerSpeedManager.broadcastSyncData(server);
            });
        });

        // サーバー情報一覧リクエスト
        ServerPlayNetworking.registerGlobalReceiver(REQUEST_DIA_LIST_PACKET_ID, (server, player, handler, buf, responseSender) -> {
            server.execute(() -> sendServerStateToClient(player));
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            yoyakuSend_DIA_LIST_2 = System.currentTimeMillis() + 1000;
        });

        ServerTickEvents.END_SERVER_TICK.register((server) -> {
            if (!Objects.isNull(yoyakuSend_DIA_LIST_2)) {
                if (System.currentTimeMillis() > yoyakuSend_DIA_LIST_2) {
                    OudiaServerSpeedManager.broadcastSyncData(server);
                    yoyakuSend_DIA_LIST_2 = null;
                }
            }
        });
    }

    private static void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("oudiabridge")
                .then(CommandManager.literal("log")
                        .executes(context -> {
                            ServerCommandSource source = context.getSource();
                            if (source.getEntity() instanceof ServerPlayerEntity player) {
                                UUID uuid = player.getUuid();
                                if (LOG_LISTENERS.contains(uuid)) {
                                    LOG_LISTENERS.remove(uuid);
                                    player.sendMessage(Text.literal("[OudiaBridge] 運行ログの受信をオフにしました。").formatted(Formatting.YELLOW), false);
                                } else {
                                    LOG_LISTENERS.add(uuid);
                                    player.sendMessage(Text.literal("[OudiaBridge] 運行ログの受信をオンにしました。").formatted(Formatting.GREEN), false);
                                }
                            }
                            return 1;
                        })
                )
        );
    }

    private static void sendServerStateToClient(ServerPlayerEntity player) {
        OudiaWorldSyncState runtime = OudiaWorldSyncState.getInstance();
        NbtCompound compound = new NbtCompound();
        compound.putString("activeProjectId", runtime.getActiveProjectId());

        NbtList projList = new NbtList();
        for (OudiaWorldSyncState.DiagramProject proj : runtime.getProjects().values()) {
            NbtCompound pComp = new NbtCompound();
            pComp.putString("id", proj.id);
            pComp.putString("name", proj.name);
            pComp.putInt("trainCount", proj.trainConfigs.size());
            pComp.putInt("stationCount", proj.stationConfigs.size());
            projList.add(pComp);
        }
        compound.put("projects", projList);

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            NbtIo.writeCompressed(compound, baos);
            byte[] bytes = baos.toByteArray();

            PacketByteBuf resBuf = PacketByteBufs.create();
            resBuf.writeInt(bytes.length);
            resBuf.writeBytes(bytes);

            ServerPlayNetworking.send(player, RESPONSE_DIA_LIST_PACKET_ID, resBuf);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}