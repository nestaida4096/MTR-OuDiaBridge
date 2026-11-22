package nest.oudiabridge.mixin;
import it.unimi.dsi.fastutil.objects.ObjectImmutableList;
import org.mtr.core.Main;
import org.mtr.core.simulation.Simulator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(Main.class)
public interface MainAccessor {
    @Accessor("simulators")
    ObjectImmutableList<Simulator> getSimulators();
}
