package com.oudiabridge.client.mixin;

import com.oudiabridge.sync.OudiaWorldSyncState;
import mtr.data.Train;
import mtr.path.PathData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Map;

@Mixin(Train.class)
public abstract class TrainMixinClient {

    @Shadow(remap = false)
    protected int nextStoppingIndex;

    @Shadow(remap = false)
    protected float speed;

    @Shadow(remap = false)
    protected boolean isOnRoute;

    @Shadow(remap = false)
    public List<PathData> path;

    private boolean lastIsOnRoute = false;
    private Long matchedTrainConfigId = null;

    private int turnaroundStopIndexOffset = 0;

    private boolean isFirstRailSpeedCall = true;
    private float currentTickCalculatedSpeed = -1.0F;

    private int lockedStationStopIndex = -1;

    /**
     * OuDia形式の時刻文字列（HH:mm:ss, HH:mm, HHmmssなど）を秒数に変換する
     */
    private int parseOudiaTimeToSeconds(String timeStr) {
        if (timeStr == null || timeStr.trim().isEmpty()) {
            return -1;
        }
        String clean = timeStr.trim();

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
        if (this.speed <= 0.001F && this.lockedStationStopIndex >= 0) {
            return this.lockedStationStopIndex;
        }
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
            turnaroundStopIndexOffset = 0;
            lockedStationStopIndex = -1;

            long bestMatchId = -1L;
            int minTimeDiff = Integer.MAX_VALUE;

            for (Map.Entry<Long, OudiaWorldSyncState.TrainRuntimeConfig> entry : activeProject.trainConfigs.entrySet()) {
                OudiaWorldSyncState.TrainRuntimeConfig cfg = entry.getValue();
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

            if (bestMatchId != -1L) {
                matchedTrainConfigId = bestMatchId;
            } else {
                matchedTrainConfigId = null;
            }
        } else if (!isOnRoute && lastIsOnRoute) {
            lastIsOnRoute = false;
            matchedTrainConfigId = null;
            turnaroundStopIndexOffset = 0;
            lockedStationStopIndex = -1;
        }

        if (matchedTrainConfigId != null) {
            return activeProject.trainConfigs.get(matchedTrainConfigId);
        }

        return null;
    }

    @Inject(method = "getRailSpeed", at = @At("RETURN"), cancellable = true)
    private void getRailSpeed(int index, CallbackInfoReturnable<Float> cir) {
        float baseRailSpeed = cir.getReturnValueF();
        if (baseRailSpeed <= 0.001F) {
            return;
        }

        long trainId = ((Train) (Object) this).id;
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

            if (timeRemainingSec > 5 && remainingDistance > 0) {
                double requiredSpeedPerSecond = remainingDistance / timeRemainingSec;
                float targetSpeedPerTick = (float) (requiredSpeedPerSecond / 20.0D);
                this.currentTickCalculatedSpeed = targetSpeedPerTick;
            } else {
                this.currentTickCalculatedSpeed = baseRailSpeed;
            }
        }

        float calculatedSpeed = this.currentTickCalculatedSpeed > 0.0F ? this.currentTickCalculatedSpeed : baseRailSpeed;
        calculatedSpeed = (float) (calculatedSpeed * userMultiplier);

        float finalSpeed = Math.min(calculatedSpeed, baseRailSpeed);

        if (config.maxRailSpeed > 0) {
            finalSpeed = Math.min(finalSpeed, config.maxRailSpeed);
        }

        finalSpeed = Math.max(0.01F, finalSpeed);
        cir.setReturnValue(finalSpeed);
    }
}