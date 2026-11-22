package nest.oudiabridge.forge;

import net.minecraft.commands.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import nest.oudiabridge.OudiaBridgeCommon;
import nest.oudiabridge.command.TrainDiagnosticsCommand;

@Mod("oudiabridge")
public final class OudiaBridgeForge {
    public OudiaBridgeForge() {
        OudiaBridgeCommon.initialize();
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        TrainDiagnosticsCommand.register(event.getDispatcher());
    }
}
