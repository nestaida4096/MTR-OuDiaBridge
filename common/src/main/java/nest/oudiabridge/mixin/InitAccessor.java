package nest.oudiabridge.mixin;
import org.mtr.core.Main;
import org.mtr.mod.Init;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(Init.class)
public interface InitAccessor {
    @Accessor("main")
    static Main getMain() { throw new AssertionError("Mixin accessor was not applied"); }
}
