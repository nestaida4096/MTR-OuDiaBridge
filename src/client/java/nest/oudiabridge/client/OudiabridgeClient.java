package nest.oudiabridge.client;

import nest.oudiabridge.Oudiabridge;
import nest.oudiabridge.sync.OudiaWorldSyncState;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * クライアント側Mod初期化クラス
 * サーバーから受信したNBTデータ（OudiaSavedState形式）をOudiaWorldSyncStateへ一括反映します。
 */
public class OudiabridgeClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        System.out.println("Starting MTR-OuDiaSecondV2-Bridge [Client]");

        // サーバーからのダイヤ同期パケット受信処理
        ClientPlayNetworking.registerGlobalReceiver(Oudiabridge.RESPONSE_DIA_LIST_2_PACKET_ID, (client, handler, buf, responseSender) -> {
            int length = buf.readInt();
            byte[] data = new byte[length];
            buf.readBytes(data);

            client.execute(() -> {
                try {
                    NbtCompound payload = NbtIo.readCompressed(new ByteArrayInputStream(data));
                    if (payload == null) return;
                    System.out.println("Payload Received");

                    // OudiaWorldSyncState の readNbt メソッドを使用してサーバーから送信されたNBTを一括反映
                    OudiaWorldSyncState.getInstance().readNbt(payload);

                } catch (IOException e) {
                    System.err.println("[OudiabridgeClient] Failed to process sync NBT payload:");
                    e.printStackTrace();
                }
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(Oudiabridge.TIME_SYNC_ID, ((minecraftClient, clientPlayNetworkHandler, packetByteBuf, packetSender) -> {
            int currentSec = packetByteBuf.readInt();
            minecraftClient.execute(() -> OudiaWorldSyncState.updateClientSyncedTime(currentSec));
        }));
    }
}