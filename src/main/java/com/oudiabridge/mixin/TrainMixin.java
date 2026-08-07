package com.oudiabridge.mixin;

import com.oudiabridge.Oudiabridge;
import com.oudiabridge.sync.OudiaWorldSyncState;
import mtr.data.Depot;
import mtr.data.Train;
import mtr.path.PathData;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Map;

@Mixin(Train.class)
public abstract class TrainMixin {

    @Shadow(remap = false)
    protected int nextStoppingIndex;

    @Shadow(remap = false)
    protected float speed;

    @Shadow(remap = false)
    protected boolean isOnRoute;

    @Shadow(remap = false)
    protected float elapsedDwellTicks;

    @Shadow(remap = false)
    public long sidingId;

    @Shadow(remap = false)
    public List<PathData> path;

    @Shadow(remap = false)
    protected boolean doorTarget;

    @Shadow(remap = false)
    protected float doorValue;

    private boolean lastIsOnRoute = false;
    private Long matchedTrainConfigId = null;

    // 折り返し時点でのMTR側の駅インデックス(stopIndex)を記憶
    private int turnaroundStopIndexOffset = 0;

    private int lastLoggedStopIndex = -1;
    private float lastLoggedSpeed = -1.0F;

    // 現在の駅に実際に停車した時点で確定させた総停車Tick数（毎ティック変動防止用）
    private int fixedCurrentDwellTicks = -1;

    private World world;
    private int broadcastTickCounter = 0;

    private int parseOudiaTimeToSeconds(String timeStr) {
        if (timeStr == null || timeStr.trim().isEmpty()) {
            return -1;
        }
        String clean = timeStr.replaceAll("[^0-9]", "");
        if (clean.isEmpty()) return -1;

        if (clean.length() == 3) {
            clean = "0" + clean;
        }
        if (clean.length() == 4) {
            clean += "00";
        }
        if (clean.length() == 5) {
            clean = "0" + clean;
        }

        if (clean.length() != 6) {
            return -1;
        }
        try {
            int h = Integer.parseInt(clean.substring(0, 2));
            int m = Integer.parseInt(clean.substring(2, 4));
            int s = Integer.parseInt(clean.substring(4, 6));

            if (h < 0 || h >= 24 || m < 0 || m >= 60 || s < 0 || s >= 60) {
                return -1;
            }
            return h * 3600 + m * 60 + s;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private long getDepartedDepotId() {
        try {
            if (this.sidingId != 0) {
                OudiaWorldSyncState runtime = OudiaWorldSyncState.getInstance();
                OudiaWorldSyncState.DiagramProject activeProject = runtime.getActiveProject();
                if (activeProject != null) {
                    for (OudiaWorldSyncState.StationRuntimeConfig stationCfg : activeProject.stationConfigs.values()) {
                        if (stationCfg.mappingType == 2 && stationCfg.mtrId > 0) {
                            return stationCfg.mtrId;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return -1L;
    }

    private String cleanStationName(String name) {
        if (name == null) return "";
        String clean = name.trim();
        if (clean.contains("|")) {
            clean = clean.split("\\|")[0].trim();
        }
        return clean;
    }

    private OudiaWorldSyncState.StopSchedule getActualFirstStop(OudiaWorldSyncState.TrainRuntimeConfig cfg) {
        if (cfg == null || cfg.stopSchedules.isEmpty()) return null;
        for (OudiaWorldSyncState.StopSchedule stop : cfg.stopSchedules) {
            if (stop != null) {
                boolean hasArr = stop.arrivalTime != null && !stop.arrivalTime.trim().isEmpty();
                boolean hasDep = stop.departureTime != null && !stop.departureTime.trim().isEmpty();
                if (hasArr || hasDep) {
                    return stop;
                }
            }
        }
        return cfg.getStopSchedule(0);
    }

    private OudiaWorldSyncState.StopSchedule getActualLastStop(OudiaWorldSyncState.TrainRuntimeConfig cfg) {
        if (cfg == null || cfg.stopSchedules.isEmpty()) return null;
        for (int i = cfg.stopSchedules.size() - 1; i >= 0; i--) {
            OudiaWorldSyncState.StopSchedule stop = cfg.stopSchedules.get(i);
            if (stop != null) {
                boolean hasArr = stop.arrivalTime != null && !stop.arrivalTime.trim().isEmpty();
                boolean hasDep = stop.departureTime != null && !stop.departureTime.trim().isEmpty();
                if (hasArr || hasDep) {
                    return stop;
                }
            }
        }
        return cfg.getStopSchedule(cfg.stopSchedules.size() - 1);
    }

    /**
     * 現在のMTR上の生の駅インデックス(stopIndex)を取得する
     */
    private int getMtrRawStopIndex() {
        try {
            if (this.path != null && !this.path.isEmpty()) {
                int pathIndex = Math.min(Math.max(0, nextStoppingIndex), this.path.size() - 1);
                PathData currentPathData = this.path.get(pathIndex);
                if (currentPathData != null) {
                    return currentPathData.stopIndex;
                }
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    /**
     * OuDiaダイヤと照合するための、オフセット調整済みの駅インデックスを取得する
     */
    private int getCurrentStationStopIndex() {
        return Math.max(0, getMtrRawStopIndex() - turnaroundStopIndexOffset);
    }

    private OudiaWorldSyncState.TrainRuntimeConfig resolveOrSnapTrainConfig(long trainId) {
        OudiaWorldSyncState runtime = OudiaWorldSyncState.getInstance();
        OudiaWorldSyncState.DiagramProject activeProject = runtime.getActiveProject();

        if (activeProject == null) {
            return null;
        }

        int currentSec = OudiaWorldSyncState.getCurrentRealTimeSeconds();

        if (isOnRoute && !lastIsOnRoute) {
            lastIsOnRoute = true;
            turnaroundStopIndexOffset = 0; // ルート開始時にオフセットをリセット
            lastLoggedStopIndex = -1;
            fixedCurrentDwellTicks = -1;

            long currentDepotId = getDepartedDepotId();
            long bestMatchId = -1L;
            int minTimeDiff = Integer.MAX_VALUE;

            for (Map.Entry<Long, OudiaWorldSyncState.TrainRuntimeConfig> entry : activeProject.trainConfigs.entrySet()) {
                OudiaWorldSyncState.TrainRuntimeConfig cfg = entry.getValue();

                OudiaWorldSyncState.StopSchedule firstStop = getActualFirstStop(cfg);
                String startStationName = firstStop != null ? firstStop.stationName : "";

                OudiaWorldSyncState.StationRuntimeConfig stationCfg = activeProject.stationConfigs.get(startStationName);
                boolean isDepotStart = (stationCfg != null && stationCfg.mappingType == 2);
                boolean isMatchingDepot = (currentDepotId == -1L || (stationCfg != null && stationCfg.mtrId == currentDepotId));

                if (isDepotStart && isMatchingDepot) {
                    String firstDepStr = firstStop != null && !firstStop.departureTime.isEmpty() ? firstStop.departureTime : cfg.firstDepartureTime;
                    int targetSec = parseOudiaTimeToSeconds(firstDepStr);
                    if (targetSec >= 0) {
                        int diff = Math.abs(currentSec - targetSec);
                        if (diff > 43200) diff = 86400 - diff;

                        if (diff <= 600 && diff < minTimeDiff) {
                            minTimeDiff = diff;
                            bestMatchId = entry.getKey();
                        }
                    }
                }
            }

            if (bestMatchId != -1L) {
                matchedTrainConfigId = bestMatchId;
                System.out.println(String.format("[OudiaBridge] 車庫出庫スナップ成功: Train ID = %d (Depot ID = %d) -> ダイヤ ID = %d (現在時刻: %02d:%02d:%02d / 時刻差: %d秒)",
                        trainId, currentDepotId, bestMatchId,
                        currentSec / 3600, (currentSec % 3600) / 60, currentSec % 60,
                        minTimeDiff));
            } else {
                matchedTrainConfigId = null;
                System.out.println(String.format("[OudiaBridge] 車庫出庫検出: Train ID = %d (適合するOuDiaダイヤが見つかりませんでした。現在時刻: %02d:%02d:%02d)",
                        trainId, currentSec / 3600, (currentSec % 3600) / 60, currentSec % 60));
            }
        } else if (!isOnRoute && lastIsOnRoute) {
            lastIsOnRoute = false;
            matchedTrainConfigId = null;
            turnaroundStopIndexOffset = 0; // 運用終了時にもリセット
            fixedCurrentDwellTicks = -1;
        }

        if (matchedTrainConfigId != null) {
            OudiaWorldSyncState.TrainRuntimeConfig snappedConfig = activeProject.trainConfigs.get(matchedTrainConfigId);
            if (snappedConfig != null) return snappedConfig;
        }

        return activeProject.trainConfigs.get(trainId);
    }

    private void tryResnapForTurnaround(long trainId, int currentStopIndex) {
        if (currentStopIndex <= 0) return;

        OudiaWorldSyncState runtime = OudiaWorldSyncState.getInstance();
        OudiaWorldSyncState.DiagramProject activeProject = runtime.getActiveProject();
        if (activeProject == null || matchedTrainConfigId == null) return;

        OudiaWorldSyncState.TrainRuntimeConfig currentCfg = activeProject.trainConfigs.get(matchedTrainConfigId);
        if (currentCfg == null || currentCfg.stopSchedules.isEmpty()) return;

        int lastIndex = currentCfg.stopSchedules.size() - 1;
        if (currentStopIndex != lastIndex) return;

        OudiaWorldSyncState.StopSchedule lastStop = getActualLastStop(currentCfg);
        if (lastStop == null) return;

        String rawTerminusName = lastStop.stationName;
        String cleanTerminusName = cleanStationName(rawTerminusName);

        String terminusTimeStr = !lastStop.arrivalTime.isEmpty() ? lastStop.arrivalTime : lastStop.departureTime;
        int terminusSec = parseOudiaTimeToSeconds(terminusTimeStr);

        System.out.println(String.format("[OudiaBridge DEBUG] 終着駅到達判定: Train ID = %d, 現在のダイヤ ID = %d, 終着駅 = '%s', 終着時刻 = '%s' (%d秒)",
                trainId, matchedTrainConfigId, cleanTerminusName, terminusTimeStr, terminusSec));

        if (terminusSec < 0) {
            return;
        }

        long bestMatchId = -1L;
        int minTimeDiff = Integer.MAX_VALUE;

        String newLastStop = "";

        for (Map.Entry<Long, OudiaWorldSyncState.TrainRuntimeConfig> entry : activeProject.trainConfigs.entrySet()) {
            long candidateId = entry.getKey();
            if (candidateId == matchedTrainConfigId) continue;

            OudiaWorldSyncState.TrainRuntimeConfig candidateCfg = entry.getValue();
            OudiaWorldSyncState.StopSchedule firstStop = getActualFirstStop(candidateCfg);
            OudiaWorldSyncState.StopSchedule lastStop2 = getActualLastStop(candidateCfg);
            if (firstStop == null) continue;

            String cleanCandidateFirstName = cleanStationName(firstStop.stationName);
            String candidateDepStr = !firstStop.departureTime.isEmpty() ? firstStop.departureTime : firstStop.arrivalTime;
            int candidateDepSec = parseOudiaTimeToSeconds(candidateDepStr);

            boolean nameMatch = cleanCandidateFirstName.equalsIgnoreCase(cleanTerminusName);
            int diff = -1;
            boolean timeWithinLimit = false;

            if (nameMatch && candidateDepSec >= 0) {
                diff = candidateDepSec - terminusSec;
                if (diff < -43200) diff += 86400;
                if (diff > 43200) diff -= 86400;

                timeWithinLimit = (diff >= 0 && diff <= 60);
            }

            if (nameMatch && candidateDepSec >= 0 && timeWithinLimit) {
                if (diff < minTimeDiff) {
                    minTimeDiff = diff;
                    bestMatchId = candidateId;
                    newLastStop = cleanStationName(lastStop2.stationName);
                }
            }
        }

        if (bestMatchId != -1L) {
            matchedTrainConfigId = bestMatchId;

            // パス頂点のインデックスではなく、MTR側の「駅(stopIndex)」をそのままオフセット基準とする
            turnaroundStopIndexOffset = getMtrRawStopIndex();

            lastLoggedStopIndex = -1;
            fixedCurrentDwellTicks = -1;
            this.elapsedDwellTicks = 0.0F;
            System.out.println(String.format("[OudiaBridge] 折り返し再スナップ成功: Train ID = %d, 終着駅 = '%s' -> 新ダイヤ ID = %d (時間差: %d秒, StopIndex Offset = %d), 新ダイヤ終着駅 = '%s'",
                    trainId, cleanTerminusName, bestMatchId, minTimeDiff, turnaroundStopIndexOffset, newLastStop));
        } else {
            System.out.println(String.format("[OudiaBridge] 折り返し再スナップ失敗: Train ID = %d, 終着駅 = '%s' (条件に一致する折り返し用ダイヤが見つかりませんでした)",
                    trainId, cleanTerminusName));
        }
    }

    /**
     * OuDiaダイヤに基づいてドアの開閉目標（doorTarget）を判定する。
     *
     * true  = ドアを開ける（または開き続ける）
     * false = ドアを閉める（または閉め続ける）
     */
    private boolean shouldDoorsBeOpenByDiagram(OudiaWorldSyncState.TrainRuntimeConfig config, int stationStopIndex) {
        if (config == null) {
            return this.doorTarget;
        }

        OudiaWorldSyncState.StopSchedule stop = config.getStopSchedule(stationStopIndex);
        if (stop == null) {
            return this.doorTarget;
        }

        // 完全停車後1秒間（20Tick）経過するまではドアを開けない（到着直後の時差制御）
        if (this.elapsedDwellTicks < 20.0F) {
            return false;
        }

        String depTimeStr = (stop.departureTime != null && !stop.departureTime.trim().isEmpty())
                ? stop.departureTime
                : config.firstDepartureTime;

        int departureSec = parseOudiaTimeToSeconds(depTimeStr);
        if (departureSec < 0) {
            return true;
        }

        int currentSec = OudiaWorldSyncState.getCurrentRealTimeSeconds();
        int remainingSec = departureSec - currentSec;

        if (remainingSec < -43200) {
            remainingSec += 86400;
        } else if (remainingSec > 43200) {
            remainingSec -= 86400;
        }

        // ドア動作に必要な時間（ドア移動64Tick + ディレイ20Tick = 84Tick ≒ 4.2秒）
        int closeStartTicks = Train.DOOR_MOVE_TIME + 20;
        int remainingTicks = remainingSec * 20;

        // 発車予定時刻までの残り時間が閉扉開始タイマー以下になったら閉扉指示を発出
        return remainingTicks > closeStartTicks;
    }

    @Redirect(
            method = "simulateTrain",
            at = @At(
                    value = "INVOKE",
                    target = "Lmtr/data/Train;openDoors()Z",
                    ordinal = 0
            )
    )
    private boolean redirectOpenDoors0(Train instance) {
        long trainId = getTrainIdSafe();
        OudiaWorldSyncState.TrainRuntimeConfig config = resolveOrSnapTrainConfig(trainId);
        if (config != null) {
            int stationStopIndex = getCurrentStationStopIndex();
            return shouldDoorsBeOpenByDiagram(config, stationStopIndex);
        }
        return this.doorTarget;
    }

    @Redirect(
            method = "simulateTrain",
            at = @At(
                    value = "INVOKE",
                    target = "Lmtr/data/Train;openDoors()Z",
                    ordinal = 1
            )
    )
    private boolean redirectOpenDoors1(Train instance) {
        long trainId = getTrainIdSafe();
        OudiaWorldSyncState.TrainRuntimeConfig config = resolveOrSnapTrainConfig(trainId);
        if (config != null) {
            int stationStopIndex = getCurrentStationStopIndex();
            return shouldDoorsBeOpenByDiagram(config, stationStopIndex);
        }
        return this.doorTarget;
    }

    @Inject(method = "getRailSpeed", at = @At("RETURN"), cancellable = true)
    private void oudiaBridge$calculateHajikiSpeed(int index, CallbackInfoReturnable<Float> cir) {
        float baseRailSpeed = cir.getReturnValueF();
        if (baseRailSpeed <= 0.001F) {
            return;
        }

        long trainId = getTrainIdSafe();
        OudiaWorldSyncState.TrainRuntimeConfig config = resolveOrSnapTrainConfig(trainId);
        if (config == null) {
            return;
        }

        double userMultiplier = Math.max(0.1D, config.speedMultiplier <= 0 ? 1.0D : config.speedMultiplier);
        int stationStopIndex = getCurrentStationStopIndex();

        OudiaWorldSyncState.StopSchedule currentSchedule = config.getStopSchedule(stationStopIndex);
        String targetArrStr = (currentSchedule != null && !currentSchedule.arrivalTime.isEmpty())
                ? currentSchedule.arrivalTime
                : (currentSchedule != null ? currentSchedule.departureTime : config.firstArrivalTime);

        int currentSec = OudiaWorldSyncState.getCurrentRealTimeSeconds();
        int targetArrSec = parseOudiaTimeToSeconds(targetArrStr);

        float calculatedSpeed = baseRailSpeed;

        if (targetArrSec > 0 && this.path != null && index >= 0 && index < this.path.size()) {
            double remainingDistance = 0.0D;
            int targetStopIndex = this.path.get(index).stopIndex;
            for (int i = index; i < this.path.size(); i++) {
                PathData p = this.path.get(i);
                if (p != null) {
                    if (p.stopIndex != targetStopIndex) {
                        break;
                    }
                    if (p.rail != null) {
                        remainingDistance += p.rail.getLength();
                    }
                }
            }

            int timeRemainingSec = targetArrSec - currentSec;
            if (timeRemainingSec < -43200) timeRemainingSec += 86400;
            if (timeRemainingSec > 43200) timeRemainingSec -= 86400;

            if (timeRemainingSec > 0 && remainingDistance > 0) {
                double requiredSpeedPerSecond = remainingDistance / timeRemainingSec;
                float rawCalculatedSpeed = (float) (requiredSpeedPerSecond / 20.0D);

                float minAllowedSpeed = Math.max(0.1F, baseRailSpeed * 0.3F);
                calculatedSpeed = Math.max(rawCalculatedSpeed, minAllowedSpeed);
            } else if (timeRemainingSec <= 0) {
                calculatedSpeed = baseRailSpeed;
            }
        }

        calculatedSpeed = (float) (calculatedSpeed * userMultiplier);

        float finalSpeed = Math.min(calculatedSpeed, baseRailSpeed);

        if (config.maxRailSpeed > 0) {
            finalSpeed = Math.min(finalSpeed, config.maxRailSpeed);
        }

        finalSpeed = Math.max(0.01F, finalSpeed);

        if (Math.abs(finalSpeed - lastLoggedSpeed) > 0.05F) {
            lastLoggedSpeed = finalSpeed;
            System.out.println(String.format("[OudiaBridge] 列車速度補正: Train ID = %d, 補正後速度 = %.3f (元レールの最高速度: %.3f)",
                    trainId, finalSpeed, baseRailSpeed));
        }

        broadcastTickCounter++;
        if (!world.isClient() && broadcastTickCounter >= 20) {
            broadcastTickCounter = 0;
            List<ServerPlayerEntity> players = world.getServer().getPlayerManager().getPlayerList();
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeLong(trainId);
            buf.writeFloat(finalSpeed);
            buf.writeBoolean(this.doorTarget);
            for (ServerPlayerEntity player : players) {
                ServerPlayNetworking.send(player, Oudiabridge.TRAIN_STATE_BROADCAST_PACKET_ID, buf);
            }
        }

        cir.setReturnValue(finalSpeed);
    }

    @Inject(method = "simulateTrain", at = @At("HEAD"))
    private void simulateTrainHead(World world, float ticksElapsed, Depot depot, CallbackInfo ci) {
        this.world = world;

        try {
            if (this.path == null || this.path.isEmpty() || nextStoppingIndex >= this.path.size()) {
                return;
            }

            int stationStopIndex = getCurrentStationStopIndex();

            // 走行中（速度>0）の場合は固定停車時間をリセットする
            if (this.speed > 0) {
                fixedCurrentDwellTicks = -1;
            }

            // 速度0で、新しい駅に到着した瞬間を検知
            if (this.speed <= 0 && lastLoggedStopIndex != stationStopIndex) {
                lastLoggedStopIndex = stationStopIndex;
                this.elapsedDwellTicks = 0.0F; // 前駅の累計タイマーを0にリセットして正しく1から開始
                fixedCurrentDwellTicks = -1;

                tryResnapForTurnaround(getTrainIdSafe(), stationStopIndex);

                long trainId = getTrainIdSafe();
                OudiaWorldSyncState.TrainRuntimeConfig config = resolveOrSnapTrainConfig(trainId);
                if (config != null) {
                    OudiaWorldSyncState.StopSchedule currentSchedule = config.getStopSchedule(stationStopIndex);

                    String depTimeStr = currentSchedule != null && !currentSchedule.departureTime.isEmpty()
                            ? currentSchedule.departureTime
                            : config.firstDepartureTime;
                    int targetDepSec = parseOudiaTimeToSeconds(depTimeStr);
                    int currentSec = OudiaWorldSyncState.getCurrentRealTimeSeconds();
                    String stationName = currentSchedule != null ? currentSchedule.stationName : "未定義";

                    int minDwellTicks = Math.max(Train.DOOR_MOVE_TIME + 20 + 20, config.minimumDwellTicks);

                    if (targetDepSec > 0) {
                        int remainingSeconds = targetDepSec - currentSec;
                        if (remainingSeconds < -43200) {
                            remainingSeconds += 86400;
                        } else if (remainingSeconds > 43200) {
                            remainingSeconds -= 86400;
                        }

                        int calculatedTicks = remainingSeconds * 20;
                        // ダイヤ遅延時でも最小停車時間を確保し、MTR標準の長時間の停車に落とし込まない
                        fixedCurrentDwellTicks = Math.max(minDwellTicks, calculatedTicks);
                    } else {
                        fixedCurrentDwellTicks = minDwellTicks;
                    }

                    System.out.printf("[ARRIVE] stop=%d speed=%.2f elapsed=%.1f targetDepSec=%d fixed=%d station=%s%n",
                            stationStopIndex, speed, elapsedDwellTicks, targetDepSec, fixedCurrentDwellTicks, stationName);
                }
            }
        } catch (Exception ignored) {
        }
    }

    @Inject(method = "simulateTrain", at = @At("TAIL"))
    private void simulateTrainTail(World world, float ticksElapsed, Depot depot, CallbackInfo ci) {
        try {
            long trainId = getTrainIdSafe();
            OudiaWorldSyncState.TrainRuntimeConfig config = resolveOrSnapTrainConfig(trainId);
            if (config != null && this.speed <= 0) {
                int stationStopIndex = getCurrentStationStopIndex();
                boolean target = shouldDoorsBeOpenByDiagram(config, stationStopIndex);
                this.doorTarget = target;
                if (!target && this.elapsedDwellTicks < 20.0F) {
                    this.doorValue = 0.0F;
                }
            }
        } catch (Exception ignored) {
        }
    }

    @Inject(method = "getTotalDwellTicks", at = @At("RETURN"), cancellable = true)
    private void oudiaBridge$calculateDwellTicks(CallbackInfoReturnable<Integer> cir) {
        int mtrDefaultDwell = cir.getReturnValue();

        long trainId = getTrainIdSafe();
        OudiaWorldSyncState.TrainRuntimeConfig config = resolveOrSnapTrainConfig(trainId);
        if (config == null) {
            cir.setReturnValue(mtrDefaultDwell);
            return;
        }

        int minDwellTicks = Math.max(Train.DOOR_MOVE_TIME + 20 + 20, config.minimumDwellTicks);

        // OuDiaダイヤが紐づいている場合は、固定計算された時間（または遅延時の最小停車時間）を返し、MTR標準値には戻さない
        if (fixedCurrentDwellTicks > 0) {
            cir.setReturnValue(Math.max(minDwellTicks, fixedCurrentDwellTicks));
            return;
        }

        cir.setReturnValue(minDwellTicks);
    }

    private long getTrainIdSafe() {
        return ((Train) (Object) this).id;
    }

    private int safeGetStopIndex() {
        try {
            return nextStoppingIndex;
        } catch (Exception e) {
            return 0;
        }
    }
}