package com.foxseventeen.pearlrelay;

import com.mojang.brigadier.Command;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.api.ModInitializer;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PearlRelayMod implements ModInitializer {
	public static final String MOD_ID = "pearlrelay";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
				Commands.literal("pearlrelay")
						.then(Commands.literal("test")
								.executes(context -> {
									context.getSource().sendSuccess(() -> Component.literal("Pearl Relay command works."), false);
									return Command.SINGLE_SUCCESS;
								}))
		));

		LOGGER.info("Pearl Relay loaded.");
	}
}
