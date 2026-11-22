package nest.oudiabridge.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import nest.oudiabridge.OudiaBridgeCommon;
import nest.oudiabridge.command.TrainDiagnosticsCommand;

public final class OudiaBridgeFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        OudiaBridgeCommon.initialize();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                TrainDiagnosticsCommand.register(dispatcher));
    }
}
