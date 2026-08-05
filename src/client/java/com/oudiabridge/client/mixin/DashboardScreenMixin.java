package com.oudiabridge.client.mixin;

import com.oudiabridge.client.screen.OudiaImportScreen;
import mtr.screen.DashboardScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = DashboardScreen.class, remap = false)
public abstract class DashboardScreenMixin {  // ★継承完全削除

    private ButtonWidget oudiaImportButton;

    @Inject(method = "init", at = @At("HEAD"))
    private void addOudiaImportButton(CallbackInfo ci) {
        DashboardScreen screen = (DashboardScreen)(Object)this;

        // ★MTR公式と同じボタン作成方法
        this.oudiaImportButton = mtr.mappings.UtilitiesClient.newButton(
                net.minecraft.text.Text.literal(".oud2インポート"),
                button -> {
                    System.out.println("🟢 ★★★ MTR公式ボタンクリック成功！！！★★★");
                    MinecraftClient.getInstance().setScreen(new OudiaImportScreen(screen));
                }
        );

        // ★MTR公式と同じ位置設定
        mtr.client.IDrawing.setPositionAndWidth(this.oudiaImportButton, screen.width - 280, screen.height - 20, 80);
        screen.addDrawableChild(this.oudiaImportButton);
    }
}
