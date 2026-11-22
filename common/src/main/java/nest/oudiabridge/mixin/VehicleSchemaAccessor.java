package nest.oudiabridge.mixin;

import org.mtr.core.generated.data.VehicleSchema;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = VehicleSchema.class, remap = false)
public interface VehicleSchemaAccessor {
    @Accessor(value = "speed", remap = false)
    double getSpeed();

    @Accessor(value = "railProgress", remap = false)
    double getRailProgress();

    @Accessor(value = "nextStoppingIndexAto", remap = false)
    long getNextStoppingIndexAto();
}
