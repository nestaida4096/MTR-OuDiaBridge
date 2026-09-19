package nest.oudiabridge.mixin;

import nest.oudiabridge.Oudiabridge;
import nest.oudiabridge.sync.OudiaSavedState;
import nest.oudiabridge.sync.OudiaWorldSyncState;
import nest.oudiabridge.sync.OudiaServerSpeedManager;
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
import java.util.Objects;

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

    private float lastLoggedSpeed = -1.0F;

    private boolean isFirstRailSpeedCall = false;
    private float currentTickCalculatedSpeed = -1.0F;

    private World world;
    private Depot currentDepot;

    private int count = 0;

    /**
     * サーバーとクライアントで統一された現在時間（秒）を取得します。
     * クライアント側ではサーバーから同期された時間基準の秒数を返します。
     */
    private int getCurrentSyncedTimeSeconds() {
        if (this.world != null && this.world.isClient()) {
            return OudiaWorldSyncState.getClientSyncedCurrentTimeSeconds();
        }
        return OudiaWorldSyncState.getCurrentRealTimeSeconds();
    }

    /**
     * 目標時刻と現在時刻の差分（残り秒数）を計算します。
     * 日跨ぎ補正を行いつつ、過度な遅延（過剰な過去時刻）は即時発車（<=0）として処理します。
     */
    private int calculateRemainingSeconds(int targetSec, int currentSec) {
        if (targetSec < 0) return -1;
        int diff = targetSec - currentSec;

        if (diff < -43200) diff += 86400;
        else if (diff > 43200) diff -= 86400;

        return diff;
    }

    private OudiaWorldSyncState.TrainOperatingState getOperatingState() {
        long trainId = getTrainIdSafe();
        return OudiaWorldSyncState.getOrCreateOperatingState(trainId);
    }

    private void markSavedStateDirty() {
        if (this.world != null && !this.world.isClient()) {
            OudiaSavedState savedState = OudiaSavedState.get(this.world);
            if (savedState != null) {
                savedState.copyFromRuntime();
            }
            if (this.world.getServer() != null) {
                OudiaServerSpeedManager.broadcastSyncData(this.world.getServer());
            }
        }
    }

    private int parseOudiaTimeToSeconds(String timeStr) {
        return OudiaWorldSyncState.parseTimeToSeconds(timeStr);
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
        OudiaWorldSyncState.TrainOperatingState opState = getOperatingState();
        if (this.speed <= 0.001F && opState.lockedStationStopIndex >= 0) {
            return opState.lockedStationStopIndex;
        }
        return Math.max(0, getMtrRawStopIndex() - opState.turnaroundStopIndexOffset);
    }

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

        OudiaWorldSyncState.TrainOperatingState opState = getOperatingState();

        if (this.world != null && this.world.isClient()) {
            if (opState.matchedTrainConfigId != null && activeProject.trainConfigs.containsKey(opState.matchedTrainConfigId)) {
                return activeProject.trainConfigs.get(opState.matchedTrainConfigId);
            }
            return null;
        }

        int currentSec = getCurrentSyncedTimeSeconds();

        if (this.world != null && !this.world.isClient()) {
            if (count < 0) {
                PacketByteBuf buf = PacketByteBufs.create();
                buf.writeInt(currentSec);
                for (ServerPlayerEntity player : Objects.requireNonNull(this.world.getServer()).getPlayerManager().getPlayerList()) {
                    ServerPlayNetworking.send(player, Oudiabridge.TIME_SYNC_ID, buf);
                }
                count = 100;
            }
            count--;
        }

        if (opState.matchedTrainConfigId != null && activeProject.trainConfigs.containsKey(opState.matchedTrainConfigId)) {
            return activeProject.trainConfigs.get(opState.matchedTrainConfigId);
        }

        if (isOnRoute && !opState.lastIsOnRoute) {
            opState.lastIsOnRoute = true;
            opState.turnaroundStopIndexOffset = 0;
            opState.lastLoggedStopIndex = -1;
            opState.lockedStationStopIndex = -1;

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
                opState.matchedTrainConfigId = null;
                Oudiabridge.logToChat(String.format("ダイヤ対象外車庫からの出庫を検出: Train ID = %d (Depot ID = %d は登録されていません)", trainId, currentDepotId));
                markSavedStateDirty();
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
                opState.matchedTrainConfigId = bestMatchId;
                Oudiabridge.logToChat(String.format("車庫出庫スナップ成功: Train ID = %d (Depot ID = %d) -> ダイヤ ID = %d (現在時刻: %02d:%02d:%02d / 時刻差: %d秒)",
                        trainId, currentDepotId, bestMatchId,
                        currentSec / 3600, (currentSec % 3600) / 60, currentSec % 60,
                        minTimeDiff));
                markSavedStateDirty();
            } else {
                opState.matchedTrainConfigId = null;
                Oudiabridge.logToChat(String.format("車庫出庫検出: Train ID = %d (適合するOuDiaダイヤが見つかりませんでした。現在時刻: %02d:%02d:%02d)",
                        trainId, currentSec / 3600, (currentSec % 3600) / 60, currentSec % 60));
                markSavedStateDirty();
            }
        } else if (!isOnRoute && opState.lastIsOnRoute) {
            opState.lastIsOnRoute = false;
            opState.matchedTrainConfigId = null;
            opState.turnaroundStopIndexOffset = 0;
            opState.lockedStationStopIndex = -1;
            markSavedStateDirty();
        }

        if (opState.matchedTrainConfigId != null) {
            OudiaWorldSyncState.TrainRuntimeConfig snappedConfig = activeProject.trainConfigs.get(opState.matchedTrainConfigId);
            if (snappedConfig != null) {
                return snappedConfig;
            }
        }

        return null;
    }

    private void tryResnapForTurnaround(long trainId, int currentStopIndex) {
        if (currentStopIndex <= 0 || (this.world != null && this.world.isClient())) return;

        OudiaWorldSyncState runtime = OudiaWorldSyncState.getInstance();
        OudiaWorldSyncState.DiagramProject activeProject = runtime.getActiveProject();
        OudiaWorldSyncState.TrainOperatingState opState = getOperatingState();

        if (activeProject == null || opState.matchedTrainConfigId == null) return;

        OudiaWorldSyncState.TrainRuntimeConfig currentCfg = activeProject.trainConfigs.get(opState.matchedTrainConfigId);
        if (currentCfg == null || currentCfg.stopSchedules == null || currentCfg.stopSchedules.isEmpty()) return;

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
            if (candidateId == opState.matchedTrainConfigId) continue;

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
            opState.matchedTrainConfigId = bestMatchId;
            opState.turnaroundStopIndexOffset = getMtrRawStopIndex();
            opState.lastLoggedStopIndex = -1;
            opState.lockedStationStopIndex = -1;
            this.elapsedDwellTicks = 0.0F;

            Oudiabridge.logToChat(String.format("折り返し再スナップ成功: Train ID = %d, 終着駅 = '%s' -> 新ダイヤ ID = %d (時間差: %d秒, StopIndex Offset = %d), 新ダイヤ終着駅 = '%s'",
                    trainId, cleanTerminusName, bestMatchId, minTimeDiff, opState.turnaroundStopIndexOffset, newLastStop));

            markSavedStateDirty();
        }
    }

    private boolean shouldDoorsBeOpenByDiagram(OudiaWorldSyncState.TrainRuntimeConfig config, int stationStopIndex) {
        if (config == null) return this.doorTarget;

        int minDwellTicks = Math.max(Train.DOOR_MOVE_TIME + 40, config.minimumDwellTicks);
        int doorCloseLeadingTicks = Train.DOOR_MOVE_TIME + 10;

        if (this.elapsedDwellTicks < (minDwellTicks - doorCloseLeadingTicks)) {
            return true;
        }

        OudiaWorldSyncState.StopSchedule stop = config.getStopSchedule(stationStopIndex);
        String depTimeStr = getEffectiveDepartureTime(config, stop, stationStopIndex);
        int departureSec = parseOudiaTimeToSeconds(depTimeStr);

        if (departureSec < 0) {
            return this.elapsedDwellTicks < minDwellTicks;
        }

        int currentSec = getCurrentSyncedTimeSeconds();
        int remainingSec = calculateRemainingSeconds(departureSec, currentSec);

        // 発車時間（または遅延時）を過ぎた場合はドアを閉じる指示
        if (remainingSec <= 0) {
            return false;
        }

        int remainingTicks = remainingSec * 20;

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
        int currentSec = getCurrentSyncedTimeSeconds();

        boolean calculateThisTick = this.isFirstRailSpeedCall;
        this.isFirstRailSpeedCall = false;

        if (calculateThisTick && targetArrSec > 0 && this.path != null && index >= 0 && index < this.path.size()) {
            double remainingDistance = this.distances.get(this.nextStoppingIndex) - this.railProgress;

            int timeRemainingSec = calculateRemainingSeconds(targetArrSec, currentSec);

            if (timeRemainingSec > 5 && remainingDistance > 0) {
                double requiredSpeedPerSecond = remainingDistance / timeRemainingSec;
                float targetSpeedPerTick = (float) (requiredSpeedPerSecond / 20.0D);
                this.currentTickCalculatedSpeed = targetSpeedPerTick;
            } else {
                this.currentTickCalculatedSpeed = baseRailSpeed;
            }

            if (this.world != null && !this.world.isClient()) {
                if (Math.abs(this.currentTickCalculatedSpeed - lastLoggedSpeed) > 0.05F) {
                    lastLoggedSpeed = this.currentTickCalculatedSpeed;
                    Oudiabridge.logToChat(String.format("列車速度補正: Train ID = %d, 計算目標速度 = %.3f (残り時間: %d秒, 残り距離: %.1f)",
                            trainId, this.currentTickCalculatedSpeed, timeRemainingSec, remainingDistance));
                }
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

            OudiaWorldSyncState.TrainOperatingState opState = getOperatingState();
            int stationStopIndex = getCurrentStationStopIndex();

            if (this.speed <= 0) {
                if (opState.lockedStationStopIndex < 0) {
                    opState.lockedStationStopIndex = Math.max(0, getMtrRawStopIndex() - opState.turnaroundStopIndexOffset);
                }
            } else {
                opState.lockedStationStopIndex = -1;
            }

            if (this.speed <= 0 && opState.lastLoggedStopIndex != stationStopIndex) {
                opState.lastLoggedStopIndex = stationStopIndex;
                this.elapsedDwellTicks = 0.0F;

                long trainId = getTrainIdSafe();
                OudiaWorldSyncState.TrainRuntimeConfig config = resolveOrSnapTrainConfig(trainId);

                if (config != null && world != null && !world.isClient()) {
                    OudiaWorldSyncState.StopSchedule stop = config.getStopSchedule(stationStopIndex);
                    String stationName = stop != null ? stop.stationName : "不明な駅";
                    String arrTimeStr = (stop != null && stop.arrivalTime != null && !stop.arrivalTime.isEmpty()) ? stop.arrivalTime : "未設定";
                    String depTimeStr = getEffectiveDepartureTime(config, stop, stationStopIndex);
                    if (depTimeStr == null || depTimeStr.isEmpty()) depTimeStr = "未設定";

                    int minDwellSec = config.minimumDwellTicks / 20;

                    Oudiabridge.logToChat(String.format("列車が停車しました: [Train ID: %d] 駅インデックス: %d | 駅名: %s | 到着時刻: %s | 発車時刻: %s | 最小停車時間: %d秒",
                            trainId, stationStopIndex, stationName, arrTimeStr, depTimeStr, minDwellSec));

                    markSavedStateDirty();
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

        // 1. 最小停車時間に達していない場合は延長
        if (this.elapsedDwellTicks < minDwellTicks) {
            cir.setReturnValue((int) this.elapsedDwellTicks + 20);
            return;
        }

        // セーフティタイムアウト: 最小停車時間を30分（1200*30ticks）超過しても停車している場合は強制発車
        if (this.elapsedDwellTicks > minDwellTicks + (1200 * 30)) {
            cir.setReturnValue((int) this.elapsedDwellTicks);
            return;
        }

        boolean target = shouldDoorsBeOpenByDiagram(config, stationStopIndex);

        // 2. ダイヤ上まだドアを開けるべき場合は延長
        if (target) {
            cir.setReturnValue((int) this.elapsedDwellTicks + 20);
            return;
        }

        // 3. ドアが閉まりきるまでの待機（ドア閉開始から5秒以上経過した場合は不具合防止のため強制通過）
        if (this.doorValue > 0.01F) {
            if (this.elapsedDwellTicks > minDwellTicks + 100) {
                cir.setReturnValue((int) this.elapsedDwellTicks);
                return;
            }
            cir.setReturnValue((int) this.elapsedDwellTicks + 20);
            return;
        }

        OudiaWorldSyncState.StopSchedule stop = config.getStopSchedule(stationStopIndex);
        String depTimeStr = getEffectiveDepartureTime(config, stop, stationStopIndex);
        int departureSec = parseOudiaTimeToSeconds(depTimeStr);

        if (departureSec >= 0) {
            int currentSec = getCurrentSyncedTimeSeconds();
            int remainingSec = calculateRemainingSeconds(departureSec, currentSec);

            if (remainingSec > 0) {
                cir.setReturnValue((int) this.elapsedDwellTicks + 20);
                return;
            }
        }

        // すべての条件を満たしたら（ドアが閉じて発車時刻到達）、現在の elapsedDwellTicks を返して即時発車
        cir.setReturnValue((int) this.elapsedDwellTicks);
    }

    private long getTrainIdSafe() {
        return ((Train) (Object) this).id;
    }


}