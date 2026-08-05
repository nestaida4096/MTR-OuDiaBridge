package com.oudiabridge.mixin;

import com.oudiabridge.sync.OudiaWorldSyncState;
import mtr.data.Train;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Train.class)
public abstract class TrainMixin {
    @Shadow public String trainId;

    /**
     * MTRのTrainから現在の停車駅インデックスを取得するShadowメソッド
     */
    @Shadow(remap = false)
    protected int nextStoppingIndex;

    /**
     * MTR内部の速度フィールド参照
     */
    @Shadow(remap = false)
    protected float speed;

    /**
     * レール走行速度の算出 (各駅間毎のはじき計算 ＋ リアルタイム遅延補正)
     * 現在の走行区間に対応する目標到着時刻・発車時刻からダイナミックに速度を計算します。
     */
    @Inject(method = "getRailSpeed", at = @At("RETURN"), cancellable = true)
    private void oudiaBridge$calculateHajikiSpeed(int index, CallbackInfoReturnable<Float> cir) {
        float baseSpeed = cir.getReturnValueF();
        if (baseSpeed <= 0.001F) {
            return;
        }

        OudiaWorldSyncState runtime = OudiaWorldSyncState.getInstance();
        OudiaWorldSyncState.TrainRuntimeConfig config = runtime.getTrainConfigs().get(getTrainIdSafe());
        if (config == null) {
            config = runtime.getDefaultTrainConfig();
        }
        if (config == null) {
            return;
        }

        // 基本設定による倍率適用
        double userMultiplier = Math.max(0.1D, config.speedMultiplier <= 0 ? 1.0D : config.speedMultiplier);

        // 現在の列車が向かっている駅のインデックス
        int currentStopIndex = safeGetStopIndex();

        // 当該駅（または始発・終着）の目標着発時刻を取得
        OudiaWorldSyncState.StopSchedule currentSchedule = config.getStopSchedule(currentStopIndex);
        String targetArrStr = currentSchedule != null ? currentSchedule.arrivalTime : config.firstArrivalTime;
        String targetDepStr = currentSchedule != null ? currentSchedule.departureTime : config.firstDepartureTime;

        int currentSec = OudiaWorldSyncState.getCurrentRealTimeSeconds();
        int targetArrSec = OudiaWorldSyncState.parseTimeToSeconds(targetArrStr);
        int targetDepSec = OudiaWorldSyncState.parseTimeToSeconds(targetDepStr);

        double dynamicMultiplier = 1.0D;

        // 目標時刻が存在する場合の動的遅延補正（はじき計算補正）
        if (targetArrSec > 0 || targetDepSec > 0) {
            int targetSec = targetArrSec > 0 ? targetArrSec : targetDepSec;
            if (currentSec > targetSec) {
                // 遅延発生時：遅延秒数に応じて速度を繰り上げて回復運転 (最大1.5倍)
                int delaySec = currentSec - targetSec;
                dynamicMultiplier = Math.min(1.5D, 1.0D + (delaySec * 0.008D));
            } else if (targetSec - currentSec > 60) {
                // 1分以上の早着傾向時：少し減速して調整
                dynamicMultiplier = 0.85D;
            }
        }

        float finalSpeed = (float) (baseSpeed * userMultiplier * dynamicMultiplier);

        // 最大速度制限 (maxRailSpeed) の適用
        if (config.maxRailSpeed > 0) {
            finalSpeed = Math.min(finalSpeed, config.maxRailSpeed);
        }

        cir.setReturnValue(Math.max(0.01F, finalSpeed));
    }

    /**
     * 各駅での停車時間の制御（全駅での定刻発車 ＆ 遅延時の最小停車時間保障）
     * 列車が停車している駅の全駅ダイヤデータから発車予定時刻を算出し、`getTotalDwellTicks` を決定します。
     */
    @Inject(method = "getTotalDwellTicks", at = @At("RETURN"), cancellable = true)
    private void oudiaBridge$calculateDwellTicks(CallbackInfoReturnable<Integer> cir) {
        int mtrDefaultDwell = cir.getReturnValue();

        OudiaWorldSyncState runtime = OudiaWorldSyncState.getInstance();
        OudiaWorldSyncState.TrainRuntimeConfig config = runtime.getTrainConfigs().get(getTrainIdSafe());
        if (config == null) {
            config = runtime.getDefaultTrainConfig();
        }
        if (config == null) {
            cir.setReturnValue(mtrDefaultDwell);
            return;
        }

        // 設定された最小停車Tick数 (1秒 = 20 Ticks)
        int minDwellTicks = Math.max(0, config.minimumDwellTicks);

        // 現在停車している駅のインデックスを取得
        int currentStopIndex = safeGetStopIndex();
        OudiaWorldSyncState.StopSchedule currentSchedule = config.getStopSchedule(currentStopIndex);

        String depTimeStr = currentSchedule != null && !currentSchedule.departureTime.isEmpty()
                ? currentSchedule.departureTime
                : config.firstDepartureTime;

        int targetDepSec = OudiaWorldSyncState.parseTimeToSeconds(depTimeStr);
        if (targetDepSec <= 0) {
            // 発車時刻が指定されていない駅ではデフォルトと最小値の大きい方を採用
            cir.setReturnValue(Math.max(mtrDefaultDwell, minDwellTicks));
            return;
        }

        // 現在の現実時間（秒）から定刻までの残り時間を計算
        int currentSec = OudiaWorldSyncState.getCurrentRealTimeSeconds();
        int remainingSeconds = targetDepSec - currentSec;

        // 日を跨ぐ場合（例: 23:59:50 到着、00:01:00 発車）の補正
        if (remainingSeconds < -43200) {
            remainingSeconds += 86400;
        }

        int calculatedDwellTicks = remainingSeconds * 20; // 秒をTick(20tps)に変換

        // 到着遅延時（残り時間が最小停車時間を下回る場合）
        if (calculatedDwellTicks < minDwellTicks) {
            // 遅延時は「最小停車時間」を確保して発車
            cir.setReturnValue(minDwellTicks);
        } else {
            // 早着・定刻到着時は「定刻発車時刻」までの残り時間を返却
            cir.setReturnValue(calculatedDwellTicks);
        }
    }

    private String getTrainIdSafe() {
        return trainId == null ? "" : trainId;
    }

    private int safeGetStopIndex() {
        try {
            return nextStoppingIndex;
        } catch (Exception e) {
            return 0;
        }
    }
}