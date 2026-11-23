package nest.oudiabridge.mixin;

import org.mtr.core.data.Depot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = Depot.class, remap = false)
public interface DepotAccessor {
    @Accessor(value = "repeatDepartures", remap = false)
    long oudiaBridge$getRepeatDepartures();

    @org.spongepowered.asm.mixin.gen.Invoker(value = "getUseRealTime", remap = false)
    boolean oudiaBridge$getUseRealTime();

    @org.spongepowered.asm.mixin.gen.Invoker(value = "getRepeatInfinitely", remap = false)
    boolean oudiaBridge$getRepeatInfinitely();

    @Accessor(value = "timeOffsetForRepeating", remap = false)
    double oudiaBridge$getTimeOffsetForRepeating();
}
