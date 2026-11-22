package nest.oudiabridge.mixin;

import mtr.data.Train;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = Train.class, remap = false)
public interface TrainDiagnosticsAccessor {
    @Accessor("nextStoppingIndex")
    int oudiabridge$getNextStoppingIndex();

    @Accessor("doorTarget")
    boolean oudiabridge$getDoorTarget();

    @Invoker("getRoutePosition")
    Vec3d oudiabridge$getRoutePosition(int car, int trainSpacing);
}
