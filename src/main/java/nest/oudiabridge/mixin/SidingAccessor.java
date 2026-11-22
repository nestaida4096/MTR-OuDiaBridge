package nest.oudiabridge.mixin;

import mtr.data.Siding;
import mtr.data.TrainServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Set;

@Mixin(value = Siding.class, remap = false)
public interface SidingAccessor {
    @Accessor("trains")
    Set<TrainServer> oudiabridge$getTrains();
}
