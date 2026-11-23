package nest.oudiabridge.mixin;

import org.mtr.core.simulation.Simulator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = Simulator.class, remap = false)
public interface SimulatorAccessor {
    @Accessor(value = "lastMillis", remap = false)
    long oudiaBridge$getLastMillis();

    @Accessor(value = "gameMillis", remap = false)
    long oudiaBridge$getGameMillis();
}
