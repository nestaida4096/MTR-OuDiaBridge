package com.oudiabridge.client;

import com.oudiabridge.Oudiabridge;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class OudiabridgeClient implements ClientModInitializer {

    public static Map<Long, Float> finalSpeedManager = new ConcurrentHashMap<>();

    public static Map<Long, Boolean> trainDoorsManager = new ConcurrentHashMap<>();

    @Override
    public void onInitializeClient() {
        // TODO: TrainMixin.javaにおいてクライアントにfinalSpeedの転送を行うので、ClientPlayNetworking.registerReceiverで受信を待機するレシーバーを作成し、
        //  Map<Long, Float>のスレッドセーフな共有ハッシュマップでTrain側のidとfinalSpeedを保有する

        ClientPlayNetworking.registerGlobalReceiver(Oudiabridge.TRAIN_STATE_BROADCAST_PACKET_ID, (minecraftClient,
                                                                                                      playNetworkHandler,
                                                                                                      buf,
                                                                                                      packetSender) -> {
            long id = buf.readLong();
            float finalSpeed = buf.readFloat();
            boolean doorsOpen = buf.readBoolean();
            finalSpeedManager.put(id, finalSpeed);
            trainDoorsManager.put(id, doorsOpen);
        });
    }
}
