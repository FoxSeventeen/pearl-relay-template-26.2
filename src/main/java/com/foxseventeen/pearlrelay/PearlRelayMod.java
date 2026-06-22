package com.foxseventeen.pearlrelay;

import com.foxseventeen.pearlrelay.command.PearlRelayCommand;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.api.ModInitializer;

public class PearlRelayMod implements ModInitializer {
	public static final String MOD_ID = "pearlrelay";

	@Override
	public void onInitialize() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> PearlRelayCommand.register(dispatcher));
		ServerTickEvents.END_SERVER_TICK.register(PearlRelayCommand::tick);
	}
}
