package nest.oudiabridge.client.mixin;

import nest.oudiabridge.client.screen.DiaConfigScreen;
import mtr.screen.DashboardScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.widget.ButtonWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = DashboardScreen.class, remap = false)
public abstract class DashboardScreenMixin {

    private ButtonWidget oudiaImportButton;

    @Inject(method = "init", at = @At("HEAD"))
    private void addOudiaImportButton(CallbackInfo ci) {
        DashboardScreen screen = (DashboardScreen)(Object)this;

        // ボタンのラベルを「Oudiaダイヤ設定」に変更し、ダッシュボードScreenを直接開く
        this.oudiaImportButton = mtr.mappings.UtilitiesClient.newButton(
                net.minecraft.text.Text.literal("Oudiaダイヤ設定"),
                button -> {
                    MinecraftClient.getInstance().setScreen(new DiaConfigScreen(screen, null));
                }
        );

        mtr.client.IDrawing.setPositionAndWidth(this.oudiaImportButton, screen.width - 280, screen.height - 20, 90);
        screen.addDrawableChild(this.oudiaImportButton);
    }
}