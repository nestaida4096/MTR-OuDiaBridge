package nest.oudiabridge.mixin;

import org.mtr.core.Main;
import org.mtr.mod.Init;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = Init.class, remap = false)
public interface InitAccessor {
    @Accessor(value = "main", remap = false)
    static Main getMain() {
        throw new AssertionError("Mixin accessor was not applied");
    }
}
