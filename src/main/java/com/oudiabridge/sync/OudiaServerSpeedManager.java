package com.oudiabridge.sync;

import com.oudiabridge.Oudiabridge;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class OudiaServerSpeedManager {
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
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            sendSyncDataToPlayer(handler.getPlayer());
        });
    }

    public static void sendSyncDataToPlayer(ServerPlayerEntity player) {
        if (player == null) return;

        byte[] bytes = createSyncDataBytes();
        if (bytes == null || bytes.length == 0) return;

        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeInt(bytes.length);
        buf.writeBytes(bytes);
        ServerPlayNetworking.send(player, Oudiabridge.RESPONSE_DIA_LIST_2_PACKET_ID, buf);
    }

    public static void broadcastSyncData(MinecraftServer server) {
        if (server == null) return;

        byte[] bytes = createSyncDataBytes();
        if (bytes == null || bytes.length == 0) return;

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeInt(bytes.length);
            buf.writeBytes(bytes);
            ServerPlayNetworking.send(player, Oudiabridge.RESPONSE_DIA_LIST_2_PACKET_ID, buf);
        }
    }

    private static byte[] createSyncDataBytes() {
        OudiaSavedState savedState = new OudiaSavedState();
        savedState.copyFromRuntime();

        NbtCompound root = savedState.writeNbt(new NbtCompound());

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            NbtIo.writeCompressed(root, baos);
            return baos.toByteArray();
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }
}