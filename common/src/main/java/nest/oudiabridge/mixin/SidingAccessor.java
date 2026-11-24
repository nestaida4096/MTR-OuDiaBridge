package nest.oudiabridge.mixin;

import org.mtr.core.data.Siding;
import org.mtr.core.data.Vehicle;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArraySet;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongArrayList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = Siding.class, remap = false)
public interface SidingAccessor {
    @Accessor(value = "vehicles", remap = false)
    ObjectArraySet<Vehicle> getVehicles();

    @Accessor(value = "departures", remap = false)
    LongArrayList oudiaBridge$getDepartures();
    @org.spongepowered.asm.mixin.gen.Invoker(value = "getRepeatInterval", remap = false)
    long oudiaBridge$getRepeatInterval(long interval);

    @Accessor(value = "timeOffsetForRepeating", remap = false)
    double oudiaBridge$getTimeOffsetForRepeating();
}
