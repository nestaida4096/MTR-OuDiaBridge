package com.oudiabridge.mixin;

import com.oudiabridge.Oudiabridge;
import com.oudiabridge.sync.OudiaServerSpeedManager;
import com.oudiabridge.sync.OudiaWorldSyncState;
import mtr.data.Depot;
import mtr.data.Train;
import mtr.path.PathData;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Final;
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

    @Shadow(remap = false)
    protected double railProgress;

    @Shadow(remap = false) @Final
    protected List<Double> distances;

    @Shadow(remap = false)
    protected abstract boolean openDoors();

    private boolean lastIsOnRoute = false;
    private Long matchedTrainConfigId = null;

    private int turnaroundStopIndexOffset = 0;
    private int lastLoggedStopIndex = -1;
    private float lastLoggedSpeed = -1.0F;

    private boolean isFirstRailSpeedCall = false;
    private float currentTickCalculatedSpeed = -1.0F;

    private World world;
    private Depot currentDepot;
    private int broadcastTickCounter = 0;

    // 停車中の駅インデックスの固定用フィールド
    private int lockedStationStopIndex = -1;

    /**
     * OuDia形式の時刻文字列（HH:mm:ss, HH:mm, HHmmssなど）を秒数に変換する
     */
    private int parseOudiaTimeToSeconds(String timeStr) {
        if (timeStr == null || timeStr.trim().isEmpty()) {
            return -1;
        }
        String clean = timeStr.trim();

        // HH:mm:ss または HH:mm 形式の処理
        if (clean.contains(":")) {
            String[] parts = clean.split(":");
            try {
                int h = Integer.parseInt(parts[0]);
                int m = Integer.parseInt(parts[1]);
                int s = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
                if (h < 0 || h >= 24 || m < 0 || m >= 60 || s < 0 || s >= 60) return -1;
                return h * 3600 + m * 60 + s;
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        // 数字のみの文字列処理
        clean = clean.replaceAll("[^0-9]", "");
        if (clean.isEmpty()) return -1;

        if (clean.length() == 3) {
            clean = "0" + clean + "00";
        } else if (clean.length() == 4) {
            clean += "00";
        } else if (clean.length() == 5) {
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
        if (this.currentDepot != null) {
            return this.currentDepot.id;
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
        if (cfg == null || cfg.stopSchedules == null || cfg.stopSchedules.isEmpty()) return null;
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
        if (cfg == null || cfg.stopSchedules == null || cfg.stopSchedules.isEmpty()) return null;
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

    private int getCurrentStationStopIndex() {
        // 停車中で固定中のインデックスがあれば優先的に使用する
        if (this.speed <= 0.001F && this.lockedStationStopIndex >= 0) {
            return this.lockedStationStopIndex;
        }
        return Math.max(0, getMtrRawStopIndex() - turnaroundStopIndexOffset);
    }

    /**
     * 該当駅の有効な発車時刻文字列を取得する（途中駅で誤って始発駅時刻へフォールバックするのを防ぐ）
     */
    private String getEffectiveDepartureTime(OudiaWorldSyncState.TrainRuntimeConfig config, OudiaWorldSyncState.StopSchedule stop, int stationStopIndex) {
        if (stop != null) {
            if (stop.departureTime != null && !stop.departureTime.trim().isEmpty()) {
                return stop.departureTime.trim();
            }
            if (stop.arrivalTime != null && !stop.arrivalTime.trim().isEmpty()) {
                return stop.arrivalTime.trim();
            }
        }
        if (stationStopIndex == 0 && config != null && config.firstDepartureTime != null && !config.firstDepartureTime.trim().isEmpty()) {
            return config.firstDepartureTime.trim();
        }
        return null;
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
            turnaroundStopIndexOffset = 0;
            lastLoggedStopIndex = -1;
            lockedStationStopIndex = -1;

            long currentDepotId = getDepartedDepotId();

            boolean isDepotRegisteredInProject = false;
            if (currentDepotId > 0) {
                for (OudiaWorldSyncState.StationRuntimeConfig stationCfg : activeProject.stationConfigs.values()) {
                    if (stationCfg.mappingType == 2 && stationCfg.mtrId == currentDepotId) {
                        isDepotRegisteredInProject = true;
                        break;
                    }
                }
                if (activeProject.depotId == currentDepotId) {
                    isDepotRegisteredInProject = true;
                }
            }

            if (currentDepotId > 0 && !isDepotRegisteredInProject) {
                matchedTrainConfigId = null;
                Oudiabridge.logToChat(String.format("ダイヤ対象外車庫からの出庫を検出: Train ID = %d (Depot ID = %d は登録されていません)", trainId, currentDepotId));
                return null;
            }

            long bestMatchId = -1L;
            int minTimeDiff = Integer.MAX_VALUE;

            for (Map.Entry<Long, OudiaWorldSyncState.TrainRuntimeConfig> entry : activeProject.trainConfigs.entrySet()) {
                OudiaWorldSyncState.TrainRuntimeConfig cfg = entry.getValue();

                boolean isMatchingDepot = true;

                if (currentDepotId > 0) {
                    if (cfg.depotId > 0) {
                        if (cfg.depotId != currentDepotId) {
                            isMatchingDepot = false;
                        }
                    } else {
                        OudiaWorldSyncState.StopSchedule firstStop = getActualFirstStop(cfg);
                        String startStationName = firstStop != null ? firstStop.stationName : "";
                        OudiaWorldSyncState.StationRuntimeConfig stationCfg = activeProject.stationConfigs.get(startStationName);

                        if (stationCfg != null && stationCfg.mappingType == 2 && stationCfg.mtrId > 0) {
                            if (stationCfg.mtrId != currentDepotId) {
                                isMatchingDepot = false;
                            }
                        }
                    }
                }

                if (isMatchingDepot) {
                    OudiaWorldSyncState.StopSchedule firstStop = getActualFirstStop(cfg);

                    String firstDepStr = (cfg.firstDepartureTime != null && !cfg.firstDepartureTime.trim().isEmpty())
                            ? cfg.firstDepartureTime
                            : (firstStop != null && !firstStop.departureTime.trim().isEmpty() ? firstStop.departureTime : (firstStop != null ? firstStop.arrivalTime : ""));

                    int targetSec = parseOudiaTimeToSeconds(firstDepStr);
                    if (targetSec >= 0) {
                        int diff = Math.abs(currentSec - targetSec);
                        if (diff > 43200) diff = 86400 - diff;

                        if (diff <= 60 && diff < minTimeDiff) {
                            minTimeDiff = diff;
                            bestMatchId = entry.getKey();
                        }
                    }
                }
            }

            if (bestMatchId != -1L) {
                matchedTrainConfigId = bestMatchId;
                Oudiabridge.logToChat(String.format("車庫出庫スナップ成功: Train ID = %d (Depot ID = %d) -> ダイヤ ID = %d (現在時刻: %02d:%02d:%02d / 時刻差: %d秒)",
                        trainId, currentDepotId, bestMatchId,
                        currentSec / 3600, (currentSec % 3600) / 60, currentSec % 60,
                        minTimeDiff));
            } else {
                matchedTrainConfigId = null;
                Oudiabridge.logToChat(String.format("車庫出庫検出: Train ID = %d (適合するOuDiaダイヤが見つかりませんでした。現在時刻: %02d:%02d:%02d)",
                        trainId, currentSec / 3600, (currentSec % 3600) / 60, currentSec % 60));
            }
        } else if (!isOnRoute && lastIsOnRoute) {
            lastIsOnRoute = false;
            matchedTrainConfigId = null;
            turnaroundStopIndexOffset = 0;
            lockedStationStopIndex = -1;
        }

        if (matchedTrainConfigId != null) {
            OudiaWorldSyncState.TrainRuntimeConfig snappedConfig = activeProject.trainConfigs.get(matchedTrainConfigId);
            if (snappedConfig != null) {
                return snappedConfig;
            }
        }

        return null;
    }

    private void tryResnapForTurnaround(long trainId, int currentStopIndex) {
        if (currentStopIndex <= 0) return;

        OudiaWorldSyncState runtime = OudiaWorldSyncState.getInstance();
        OudiaWorldSyncState.DiagramProject activeProject = runtime.getActiveProject();
        if (activeProject == null || matchedTrainConfigId == null) return;

        OudiaWorldSyncState.TrainRuntimeConfig currentCfg = activeProject.trainConfigs.get(matchedTrainConfigId);
        if (currentCfg == null || currentCfg.stopSchedules == null || currentCfg.stopSchedules.isEmpty()) return;

        // 該当運用における「実際の最終停車駅」のインデックスを取得
        int actualLastIndex = -1;
        for (int i = currentCfg.stopSchedules.size() - 1; i >= 0; i--) {
            OudiaWorldSyncState.StopSchedule stop = currentCfg.stopSchedules.get(i);
            if (stop != null) {
                boolean hasArr = stop.arrivalTime != null && !stop.arrivalTime.trim().isEmpty();
                boolean hasDep = stop.departureTime != null && !stop.departureTime.trim().isEmpty();
                if (hasArr || hasDep) {
                    actualLastIndex = i;
                    break;
                }
            }
        }

        // 現在の停車駅インデックスが実際の終着駅インデックスと一致しない場合は折り返し処理を行わない
        if (actualLastIndex == -1 || currentStopIndex != actualLastIndex) return;

        OudiaWorldSyncState.StopSchedule lastStop = getActualLastStop(currentCfg);
        if (lastStop == null) return;

        String rawTerminusName = lastStop.stationName;
        String cleanTerminusName = cleanStationName(rawTerminusName);

        String terminusTimeStr = (lastStop.arrivalTime != null && !lastStop.arrivalTime.isEmpty()) ? lastStop.arrivalTime : lastStop.departureTime;
        int terminusSec = parseOudiaTimeToSeconds(terminusTimeStr);

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
            String candidateDepStr = (firstStop.departureTime != null && !firstStop.departureTime.isEmpty()) ? firstStop.departureTime : firstStop.arrivalTime;
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
                    newLastStop = lastStop2 != null ? cleanStationName(lastStop2.stationName) : "";
                }
            }
        }

        if (bestMatchId != -1L) {
            matchedTrainConfigId = bestMatchId;
            turnaroundStopIndexOffset = getMtrRawStopIndex();
            lastLoggedStopIndex = -1;
            lockedStationStopIndex = -1;
            this.elapsedDwellTicks = 0.0F;
            Oudiabridge.logToChat(String.format("折り返し再スナップ成功: Train ID = %d, 終着駅 = '%s' -> 新ダイヤ ID = %d (時間差: %d秒, StopIndex Offset = %d), 新ダイヤ終着駅 = '%s'",
                    trainId, cleanTerminusName, bestMatchId, minTimeDiff, turnaroundStopIndexOffset, newLastStop));
        }
    }

    /**
     * ダイヤに従ってドアを開けるべきか判定する
     */
    private boolean shouldDoorsBeOpenByDiagram(OudiaWorldSyncState.TrainRuntimeConfig config, int stationStopIndex) {
        if (config == null) return this.doorTarget;

        int minDwellTicks = Math.max(Train.DOOR_MOVE_TIME + 40, config.minimumDwellTicks);
        int doorCloseLeadingTicks = Train.DOOR_MOVE_TIME + 10;

        // 1. 最小停車時間を満たすまでは、発車時刻に関わらず必ずドアを開け続ける
        if (this.elapsedDwellTicks < (minDwellTicks - doorCloseLeadingTicks)) {
            return true;
        }

        OudiaWorldSyncState.StopSchedule stop = config.getStopSchedule(stationStopIndex);
        String depTimeStr = getEffectiveDepartureTime(config, stop, stationStopIndex);
        int departureSec = parseOudiaTimeToSeconds(depTimeStr);

        // 2. 発車時刻が未定義の場合は、最小停車時間までドアを開ける
        if (departureSec < 0) {
            return this.elapsedDwellTicks < minDwellTicks;
        }

        int currentSec = OudiaWorldSyncState.getCurrentRealTimeSeconds();
        int remainingSec = departureSec - currentSec;

        if (remainingSec < -43200) remainingSec += 86400;
        else if (remainingSec > 43200) remainingSec -= 86400;

        int remainingTicks = remainingSec * 20;

        // 3. 発車時刻直前（ドア閉鎖所要時間）までドアを開け続ける
        return remainingTicks > doorCloseLeadingTicks;
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
        return openDoors();
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
        return openDoors();
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
        String targetArrStr = null;
        if (currentSchedule != null) {
            if (currentSchedule.arrivalTime != null && !currentSchedule.arrivalTime.trim().isEmpty()) {
                targetArrStr = currentSchedule.arrivalTime.trim();
            } else if (currentSchedule.departureTime != null && !currentSchedule.departureTime.trim().isEmpty()) {
                targetArrStr = currentSchedule.departureTime.trim();
            }
        }
        if (targetArrStr == null && stationStopIndex == 0) {
            targetArrStr = config.firstArrivalTime;
        }

        int targetArrSec = parseOudiaTimeToSeconds(targetArrStr);
        int currentSec = OudiaWorldSyncState.getCurrentRealTimeSeconds();

        boolean calculateThisTick = this.isFirstRailSpeedCall;
        this.isFirstRailSpeedCall = false;

        if (calculateThisTick && targetArrSec > 0 && this.path != null && index >= 0 && index < this.path.size()) {
            double remainingDistance = this.distances.get(this.nextStoppingIndex) - this.railProgress;

            int timeRemainingSec = targetArrSec - currentSec;
            if (timeRemainingSec < -43200) timeRemainingSec += 86400;
            if (timeRemainingSec > 43200) timeRemainingSec -= 86400;

            // 定時到着のための目標速度計算（5秒以上余裕がある場合）
            if (timeRemainingSec > 5 && remainingDistance > 0) {
                double requiredSpeedPerSecond = remainingDistance / timeRemainingSec;
                float targetSpeedPerTick = (float) (requiredSpeedPerSecond / 20.0D);

                // 線路の最高速度と必要速度の小さい方を採用して過度な加速を防ぐ
                this.currentTickCalculatedSpeed = targetSpeedPerTick;
            } else {
                // 直前または遅延時は線路の通常最高速度を維持
                this.currentTickCalculatedSpeed = baseRailSpeed;
            }

            if (Math.abs(this.currentTickCalculatedSpeed - lastLoggedSpeed) > 0.05F) {
                lastLoggedSpeed = this.currentTickCalculatedSpeed;
                Oudiabridge.logToChat(String.format("列車速度補正: Train ID = %d, 計算目標速度 = %.3f (残り時間: %d秒, 残り距離: %.1f)",
                        trainId, this.currentTickCalculatedSpeed, timeRemainingSec, remainingDistance));
            }
        }

        float calculatedSpeed = this.currentTickCalculatedSpeed > 0.0F ? this.currentTickCalculatedSpeed : baseRailSpeed;
        calculatedSpeed = (float) (calculatedSpeed * userMultiplier);

        float finalSpeed = Math.min(calculatedSpeed, baseRailSpeed);

        if (config.maxRailSpeed > 0) {
            finalSpeed = Math.min(finalSpeed, config.maxRailSpeed);
        }

        finalSpeed = Math.max(0.01F, finalSpeed);

        OudiaServerSpeedManager.setSpeed(trainId, finalSpeed);
        cir.setReturnValue(finalSpeed);
    }

    @Inject(method = "simulateTrain", at = @At("HEAD"))
    private void simulateTrainHead(World world, float ticksElapsed, Depot depot, CallbackInfo ci) {
        this.world = world;
        this.currentDepot = depot;
        this.isFirstRailSpeedCall = true;

        try {
            if (this.path == null || this.path.isEmpty() || nextStoppingIndex >= this.path.size()) {
                return;
            }

            int stationStopIndex = getCurrentStationStopIndex();

            if (this.speed <= 0) {
                // 停車中にインデックスが切り替わるのを防ぐため固定する
                if (this.lockedStationStopIndex < 0) {
                    this.lockedStationStopIndex = Math.max(0, getMtrRawStopIndex() - turnaroundStopIndexOffset);
                }

                long trainId = getTrainIdSafe();
                OudiaWorldSyncState.TrainRuntimeConfig config = resolveOrSnapTrainConfig(trainId);
                if (config != null) {
                    this.elapsedDwellTicks += ticksElapsed;
                }
            } else {
                // 発車後はインデックスのロックを解除
                this.lockedStationStopIndex = -1;
            }

            if (this.speed <= 0 && lastLoggedStopIndex != stationStopIndex) {
                lastLoggedStopIndex = stationStopIndex;
                this.elapsedDwellTicks = 0.0F;

                long trainId = getTrainIdSafe();
                OudiaWorldSyncState.TrainRuntimeConfig config = resolveOrSnapTrainConfig(trainId);

                // --- 停車時の駅情報・時刻ログ記録 ---
                if (config != null) {
                    OudiaWorldSyncState.StopSchedule stop = config.getStopSchedule(stationStopIndex);
                    String stationName = stop != null ? stop.stationName : "不明な駅";
                    String arrTimeStr = (stop != null && stop.arrivalTime != null && !stop.arrivalTime.isEmpty()) ? stop.arrivalTime : "未設定";
                    String depTimeStr = getEffectiveDepartureTime(config, stop, stationStopIndex);
                    if (depTimeStr == null || depTimeStr.isEmpty()) depTimeStr = "未設定";

                    int minDwellSec = config.minimumDwellTicks / 20;

                    Oudiabridge.logToChat(String.format("列車が停車しました: [Train ID: %d] 駅インデックス: %d | 駅名: %s | 到着時刻: %s | 発車時刻: %s | 最小停車時間: %d秒",
                            trainId, stationStopIndex, stationName, arrTimeStr, depTimeStr, minDwellSec));
                }

                tryResnapForTurnaround(trainId, stationStopIndex);
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
            }
        } catch (Exception ignored) {
        }
    }

    @Inject(method = "getTotalDwellTicks", at = @At("RETURN"), cancellable = true)
    private void oudiaBridge$calculateDwellTicks(CallbackInfoReturnable<Integer> cir) {
        long trainId = getTrainIdSafe();
        OudiaWorldSyncState.TrainRuntimeConfig config = resolveOrSnapTrainConfig(trainId);

        if (config == null) {
            return;
        }

        int stationStopIndex = getCurrentStationStopIndex();
        int minDwellTicks = Math.max(Train.DOOR_MOVE_TIME + 40, config.minimumDwellTicks);

        // 1. 最小停車時間を満たすまでは無条件で発車待機
        if (this.elapsedDwellTicks < minDwellTicks) {
            cir.setReturnValue((int) this.elapsedDwellTicks + 20);
            return;
        }

        // 2. ダイヤの指示でドアを開けるべき、またはドアが完全に閉まりきっていない場合は発車待機
        boolean target = shouldDoorsBeOpenByDiagram(config, stationStopIndex);
        if (target || this.doorValue > 0.01F) {
            cir.setReturnValue((int) this.elapsedDwellTicks + 20);
            return;
        }

        // 3. 発車時刻の判定（定刻到達まで発車を完全に止める）
        OudiaWorldSyncState.StopSchedule stop = config.getStopSchedule(stationStopIndex);
        String depTimeStr = getEffectiveDepartureTime(config, stop, stationStopIndex);
        int departureSec = parseOudiaTimeToSeconds(depTimeStr);

        if (departureSec >= 0) {
            int currentSec = OudiaWorldSyncState.getCurrentRealTimeSeconds();
            int remainingSec = departureSec - currentSec;

            if (remainingSec < -43200) remainingSec += 86400;
            else if (remainingSec > 43200) remainingSec -= 86400;

            // 発車時刻（定刻）に到達していない場合は発車許可を出さず待機
            if (remainingSec > 0) {
                cir.setReturnValue((int) this.elapsedDwellTicks + 20);
                return;
            }
        }

        // すべての条件を満たした場合（最小停車時間経過 ＋ 定刻到達 ＋ ドア完全閉鎖）に発車を許可
        cir.setReturnValue((int) this.elapsedDwellTicks);
    }

    private long getTrainIdSafe() {
        return ((Train) (Object) this).id;
    }

    private Train getTrain() {
        return ((Train) (Object) this);
    }
}