package nest.oudiabridge.mixin;
import org.mtr.core.generated.data.VehicleSchema;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(VehicleSchema.class)
public interface VehicleSchemaAccessor {
    @Accessor("speed") double getSpeed();
    @Accessor("railProgress") double getRailProgress();
    @Accessor("nextStoppingIndexAto") long getNextStoppingIndexAto();
}
