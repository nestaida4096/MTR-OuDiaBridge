package nest.oudiabridge.fabric;
import net.fabricmc.api.ModInitializer;
import nest.oudiabridge.OudiaBridgeCommon;
public final class OudiaBridgeFabric implements ModInitializer {
    @Override public void onInitialize() { OudiaBridgeCommon.initialize(); }
}
