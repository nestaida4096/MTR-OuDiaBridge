package com.oudiabridge.mixin;

import com.oudiabridge.sync.OudiaWorldSyncState;
import mtr.data.Depot;
import mtr.data.Train;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(value = Depot.class, remap = false)
public abstract class DepotMixin {
    @Shadow private long lastDeployedMillis;

    // 出庫（deployTrain）メソッド開始時のタイマー値を保持し、出庫成功を判定するための変数
    private long oudiaBridge$previousDeployedMillis = 0;

    /**
     * MTRのDepotが出庫（void deployTrain）を試みるタイミングをフックします。
     * OuDiaでこのDepotが車庫としてマッピングされている場合、次に出庫すべき時刻に達していなければ
     * ci.cancel() でMTRの出庫処理をスキップさせます。
     */
    @Inject(method = "deployTrain", at = @At("HEAD"), cancellable = true)
    private void oudiaBridge$checkDepartureTimeHead(CallbackInfo ci) {
        // 現在の出庫済みタイマーを記録
        this.oudiaBridge$previousDeployedMillis = this.lastDeployedMillis;

        OudiaWorldSyncState runtime = OudiaWorldSyncState.getInstance();

        // このDepotに設定されたOuDia上の出庫予定時刻リストを取得
        List<Integer> schedule = runtime.getDepotSchedule(((Depot) (Object) this).id);
        if (schedule == null || schedule.isEmpty()) {
            return; // 対象外（マッピングされていない場合はMTRの標準動作に任せる）
        }

        int currentIndex = runtime.getDepotNextIndex(((Depot) (Object) this).id);
        if (currentIndex >= schedule.size()) {
            // 今日の（リスト上の）出庫予定をすべて消化済みなので、これ以上出庫させない
            ci.cancel();
            return;
        }

        int targetDepSec = schedule.get(currentIndex);
        int currentSec = OudiaWorldSyncState.getCurrentRealTimeSeconds();

        // 出庫時刻より前（早すぎる）場合は出庫処理全体をキャンセルして車庫待機
        if (currentSec < targetDepSec) {
            int diff = targetDepSec - currentSec;
            // 23時台～0時台などの日跨ぎ以外の待機判定
            if (diff < 43200) {
                ci.cancel();
            }
        }
    }

    /**
     * 出庫処理（deployTrain）の終了後、実際に出庫が行われたかをチェックします。
     * 出庫が行われていれば（lastDeployedMillis が更新されていれば）、スケジュールのインデックスを進めます。
     */
    @Inject(method = "deployTrain", at = @At("RETURN"))
    private void oudiaBridge$checkDepartureTimeReturn(CallbackInfo ci) {
        // 出庫が成功し、MTR側でデプロイ時刻が更新されたかを判定
        if (this.lastDeployedMillis != this.oudiaBridge$previousDeployedMillis) {
            OudiaWorldSyncState runtime = OudiaWorldSyncState.getInstance();
            // 対象Depotのスケジュール進行インデックスを1つ進める
            runtime.advanceDepotNextIndex(((Depot) (Object) this).id);
        }
    }
}