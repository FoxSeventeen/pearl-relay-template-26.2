package com.foxseventeen.pearlrelay;

import com.foxseventeen.pearlrelay.command.PearlRelayCommand;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PearlRelayMod implements ModInitializer {
	public static final String MOD_ID = "pearlrelay";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> PearlRelayCommand.register(dispatcher));
		ServerTickEvents.END_SERVER_TICK.register(PearlRelayCommand::tick);
		ServerLifecycleEvents.SERVER_STOPPING.register(PearlRelayCommand::shutdown);
	}
}
