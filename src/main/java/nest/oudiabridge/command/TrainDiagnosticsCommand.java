package nest.oudiabridge.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import mtr.data.RailwayData;
import mtr.data.Siding;
import mtr.data.Train;
import mtr.data.TrainServer;
import nest.oudiabridge.mixin.SidingAccessor;
import nest.oudiabridge.mixin.TrainDiagnosticsAccessor;
import nest.oudiabridge.sync.OudiaWorldSyncState;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class TrainDiagnosticsCommand {
    private static final int DEFAULT_RADIUS = 64;
    private static final int MAX_RESULTS = 20;

    private TrainDiagnosticsCommand() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("oudiabridge")
                .then(CommandManager.literal("trains")
                        .executes(context -> execute(context.getSource(), DEFAULT_RADIUS))
                        .then(CommandManager.argument("radius", IntegerArgumentType.integer(1, 256))
                                .executes(context -> execute(
                                        context.getSource(),
                                        IntegerArgumentType.getInteger(context, "radius")
                                ))
                        )
                )
        );
    }

    private static int execute(ServerCommandSource source, int radius) {
        final Vec3d origin = source.getPosition();
        final double radiusSquared = (double) radius * radius;
        final RailwayData railwayData = RailwayData.getInstance(source.getWorld());
        final OudiaWorldSyncState runtime = OudiaWorldSyncState.getInstance();
        final OudiaWorldSyncState.DiagramProject project = runtime.getActiveProject();
        final List<NearbyTrain> nearbyTrains = new ArrayList<>();

        for (Siding siding : railwayData.sidings) {
            for (TrainServer train : ((SidingAccessor) siding).oudiabridge$getTrains()) {
                if (!train.isOnRoute()) {
                    continue;
                }

                final TrainDiagnosticsAccessor accessor = (TrainDiagnosticsAccessor) train;
                final Vec3d position;
                try {
                    position = accessor.oudiabridge$getRoutePosition(
                            train.isReversed() ? train.trainCars : 0,
                            train.spacing
                    );
                } catch (Exception exception) {
                    continue;
                }

                final double distanceSquared = position.squaredDistanceTo(origin);
                if (distanceSquared <= radiusSquared) {
                    nearbyTrains.add(new NearbyTrain(siding.id, train, position, Math.sqrt(distanceSquared), accessor));
                }
            }
        }

        nearbyTrains.sort(Comparator.comparingDouble(value -> value.distance));
        source.sendFeedback(() -> Text.literal(
                String.format("[OuDiaBridge] 半径%dブロック以内の運行列車: %d編成", radius, nearbyTrains.size())
        ), false);

        if (nearbyTrains.isEmpty()) {
            source.sendFeedback(() -> Text.literal("対象となる列車はありません。"), false);
            return 0;
        }

        int shown = Math.min(MAX_RESULTS, nearbyTrains.size());
        for (int i = 0; i < shown; i++) {
            final NearbyTrain nearby = nearbyTrains.get(i);
            final int resultIndex = i + 1;
            final long trainId = nearby.train.id;
            final OudiaWorldSyncState.TrainOperatingState operatingState =
                    OudiaWorldSyncState.trainOperatingStates.get(trainId);
            final Long matchedConfigId = operatingState == null ? null : operatingState.matchedTrainConfigId;
            final OudiaWorldSyncState.TrainRuntimeConfig config =
                    project == null || matchedConfigId == null ? null : project.trainConfigs.get(matchedConfigId);

            int rawStopIndex = 0;
            final int nextStoppingIndex = nearby.accessor.oudiabridge$getNextStoppingIndex();
            if (nearby.train.path != null && !nearby.train.path.isEmpty()
                    && nextStoppingIndex >= 0 && nextStoppingIndex < nearby.train.path.size()) {
                rawStopIndex = nearby.train.path.get(nextStoppingIndex).stopIndex;
            }

            final int offset = operatingState != null && operatingState.turnaroundStopIndexOffset > 0
                    ? operatingState.turnaroundStopIndexOffset : 1;
            final int scheduleIndex = Math.max(0, rawStopIndex - offset);
            final OudiaWorldSyncState.StopSchedule stop =
                    config == null ? null : config.getStopSchedule(scheduleIndex);

            final String stationName = stop == null || stop.stationName == null || stop.stationName.isEmpty()
                    ? "未取得" : stop.stationName;
            final String arrival = stop == null || stop.arrivalTime == null || stop.arrivalTime.isEmpty()
                    ? "未設定" : stop.arrivalTime;
            final String departure = stop == null || stop.departureTime == null || stop.departureTime.isEmpty()
                    ? "未設定" : stop.departureTime;

            final String matched = matchedConfigId == null ? "なし" : String.valueOf(matchedConfigId);
            final String doorState = nearby.accessor.oudiabridge$getDoorTarget() ? "開指令" :
                    nearby.train.getDoorValue() > 0.01F ? "閉動作中" : "閉鎖";
            final String routeState = nearby.train.isOnRoute() ? "営業運転中" : "車庫内";

            source.sendFeedback(() -> Text.literal(String.format(
                    "#%d Train=%d Siding=%d 距離=%.1fm 座標=(%.1f, %.1f, %.1f)",
                    resultIndex, trainId, nearby.sidingId, nearby.distance,
                    nearby.position.x, nearby.position.y, nearby.position.z
            )), false);
            source.sendFeedback(() -> Text.literal(String.format(
                    "  状態=%s 速度=%.4f ドア=%s doorValue=%.3f 停車経過=%.0ft",
                    routeState, nearby.train.getSpeed(), doorState,
                    nearby.train.getDoorValue(), nearby.train.getElapsedDwellTicks()
            )), false);
            source.sendFeedback(() -> Text.literal(String.format(
                    "  ダイヤID=%s 駅Index=%d (MTR raw=%d) 駅=%s 到着=%s 発車=%s",
                    matched, scheduleIndex, rawStopIndex, stationName, arrival, departure
            )), false);
        }

        if (nearbyTrains.size() > shown) {
            source.sendFeedback(() -> Text.literal(
                    String.format("残り%d編成は省略しました。半径を小さくするか、位置を移動してください。",
                            nearbyTrains.size() - shown)
            ), false);
        }
        return shown;
    }

    private static final class NearbyTrain {
        private final long sidingId;
        private final TrainServer train;
        private final Vec3d position;
        private final double distance;
        private final TrainDiagnosticsAccessor accessor;

        private NearbyTrain(long sidingId, TrainServer train, Vec3d position, double distance,
                            TrainDiagnosticsAccessor accessor) {
            this.sidingId = sidingId;
            this.train = train;
            this.position = position;
            this.distance = distance;
            this.accessor = accessor;
        }
    }
}
