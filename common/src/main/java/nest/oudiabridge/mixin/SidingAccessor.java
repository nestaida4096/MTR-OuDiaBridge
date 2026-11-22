package nest.oudiabridge.mixin;

import it.unimi.dsi.fastutil.objects.ObjectArraySet;
import org.mtr.core.data.Siding;
import org.mtr.core.data.Vehicle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = Siding.class, remap = false)
public interface SidingAccessor {
    @Accessor(value = "vehicles", remap = false)
    ObjectArraySet<Vehicle> getVehicles();
}
