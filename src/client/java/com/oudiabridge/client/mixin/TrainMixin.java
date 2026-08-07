package com.oudiabridge.client.mixin;

import com.oudiabridge.client.OudiabridgeClient;
import mtr.data.Depot;
import mtr.data.Train;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Train.class)
public class TrainMixin {

    @Shadow(remap = false)
    protected boolean doorTarget;

    @Inject(method = "getRailSpeed", at = @At("RETURN"), cancellable = true)
    private void getRailSpeed(int index, CallbackInfoReturnable<Float> cir) {

        long id = ((Train) (Object)this).id;

        float defaultRailSpeed = cir.getReturnValueF();
        if (OudiabridgeClient.finalSpeedManager.containsKey(id)) cir.setReturnValue(OudiabridgeClient.finalSpeedManager.get(id));
    }
}
