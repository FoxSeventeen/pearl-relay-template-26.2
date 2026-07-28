package com.foxseventeen.pearlrelay.command;

import com.foxseventeen.pearlrelay.PearlRelayMod;
import com.foxseventeen.pearlrelay.config.RelayConfigException;
import com.foxseventeen.pearlrelay.config.RelayConfigManager;
import com.foxseventeen.pearlrelay.config.RelayConfigManager.RelayDefinition;
import com.foxseventeen.pearlrelay.relay.CarpetRelayRuntime;
import com.foxseventeen.pearlrelay.relay.RelayEventReporter;
import com.foxseventeen.pearlrelay.relay.RelayExecutionManager;
import com.foxseventeen.pearlrelay.relay.RelayExecutionManager.ExecutionRequest;
import com.foxseventeen.pearlrelay.relay.RelayExecutionManager.StartResult;
import com.foxseventeen.pearlrelay.relay.RelayFailure;
import com.foxseventeen.pearlrelay.relay.RelayMessages;
import com.foxseventeen.pearlrelay.relay.RelayPreflight;
import com.foxseventeen.pearlrelay.relay.RelayTargetResolver;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Set;
import java.util.UUID;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;

public final class PearlRelayCommand {
	private static final UUID CONSOLE_OWNER = new UUID(0L, 0L);
	private static final RelayEventReporter REPORTER = new RelayEventReporter(PearlRelayMod.LOGGER);
	private static final RelayExecutionManager EXECUTIONS =
			new RelayExecutionManager(new CarpetRelayRuntime(), REPORTER::accepted, REPORTER::terminal);
	private static final DynamicCommandExceptionType INVALID_DIMENSION = new DynamicCommandExceptionType(
			dimension -> Component.literal("Unknown or unloaded dimension: " + dimension)
	);
	private static final DynamicCommandExceptionType RELAY_NOT_FOUND = new DynamicCommandExceptionType(
			name -> RelayMessages.relayNotFound(name.toString())
	);
	private static final DynamicCommandExceptionType RELAY_CONFIG_ERROR = new DynamicCommandExceptionType(
			message -> Component.literal("Relay config error: " + message)
	);
	private static final DynamicCommandExceptionType RELAY_SAVE_REJECTED = new DynamicCommandExceptionType(
			failure -> Component.literal(saveFailureMessage((RelayFailure) failure))
	);
	private static final DynamicCommandExceptionType RELAY_FIRE_REJECTED = new DynamicCommandExceptionType(
			failure -> RelayMessages.fireFailure((RelayFailure) failure)
	);

	private PearlRelayCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(
				Commands.literal("pearlrelay")
						.then(Commands.literal("test")
								.executes(PearlRelayCommand::test))
							.then(Commands.literal("fireRaw")
									.then(Commands.argument("bot", StringArgumentType.word())
											.then(Commands.argument("dimension", IdentifierArgument.id())
													.suggests(PearlRelayCommand::suggestDimensions)
													.then(Commands.argument("spawn", Vec3Argument.vec3())
															.then(Commands.argument("lookAt", Vec3Argument.vec3())
																	.executes(PearlRelayCommand::fireRaw))))))
						.then(Commands.literal("save")
								.then(Commands.argument("name", StringArgumentType.word())
										.executes(PearlRelayCommand::savePlayerSnapshot)
										.then(Commands.argument("dimension", IdentifierArgument.id())
												.suggests(PearlRelayCommand::suggestDimensions)
													.then(Commands.argument("spawn", Vec3Argument.vec3())
															.then(Commands.argument("lookAt", Vec3Argument.vec3())
																	.executes(PearlRelayCommand::saveRelay))))))
							.then(Commands.literal("fire")
									.then(Commands.argument("name", StringArgumentType.word())
											.suggests(PearlRelayCommand::suggestRelayNames)
											.executes(PearlRelayCommand::fireRelay)))
							.then(Commands.literal("list")
									.executes(PearlRelayCommand::listRelays))
							.then(Commands.literal("remove")
									.then(Commands.argument("name", StringArgumentType.word())
											.suggests(PearlRelayCommand::suggestRelayNames)
											.executes(PearlRelayCommand::removeRelay)))
			);
	}

	private static CompletableFuture<Suggestions> suggestRelayNames(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
		ServerPlayer player = context.getSource().getPlayer();
		if (player == null) {
			return builder.buildFuture();
		}

		try {
			return SharedSuggestionProvider.suggest(RelayConfigManager.names(player.getUUID()), builder);
		} catch (IOException exception) {
			return builder.buildFuture();
		}
	}

	private static CompletableFuture<Suggestions> suggestDimensions(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
		return SharedSuggestionProvider.suggestResource(
				context.getSource().levels().stream().map(ResourceKey::identifier),
				builder
		);
	}

	public static void tick(MinecraftServer server) {
		EXECUTIONS.tick();
	}

	public static void shutdown(MinecraftServer server) {
		EXECUTIONS.shutdown();
	}

	private static int test(CommandContext<CommandSourceStack> context) {
		context.getSource().sendSuccess(() -> Component.literal("Pearl Relay command works."), false);
		return Command.SINGLE_SUCCESS;
	}

	private static int fireRaw(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		UUID executionId = UUID.randomUUID();
		String bot = StringArgumentType.getString(context, "bot");
		Identifier dimension = IdentifierArgument.getId(context, "dimension");
		Vec3 spawnPos = Vec3Argument.getVec3(context, "spawn");
		Vec3 lookAtPos = Vec3Argument.getVec3(context, "lookAt");
		ServerLevel level = resolveDimension(context, dimension);
		RelayTargetResolver.Result target = RelayTargetResolver.resolve(level, spawnPos, lookAtPos);
		if (!target.isSuccess()) {
			REPORTER.rejected(executionId, "<raw>", CONSOLE_OWNER, null, -1, target.failure());
			throw RELAY_FIRE_REJECTED.create(target.failure());
		}

		RelayDefinition relay = new RelayDefinition(bot, dimension, spawnPos, lookAtPos, target.target());
		ServerPlayer player = context.getSource().getPlayer();
		RelayPreflight.ValidatedRelay validated = new RelayPreflight.ValidatedRelay(level, relay, -1);
		return startExecution(
				context,
				executionId,
				"<raw>",
				player == null ? CONSOLE_OWNER : player.getUUID(),
				validated
		);
	}

	private static int saveRelay(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		String name = StringArgumentType.getString(context, "name");
		Identifier dimension = IdentifierArgument.getId(context, "dimension");
		Vec3 spawnPos = Vec3Argument.getVec3(context, "spawn");
		Vec3 lookAtPos = Vec3Argument.getVec3(context, "lookAt");
		ServerPlayer player = context.getSource().getPlayerOrException();
		ServerLevel level = resolveDimension(context, dimension);
		return saveRelay(context, name, player, level, dimension, spawnPos, lookAtPos);
	}

	private static int savePlayerSnapshot(
			CommandContext<CommandSourceStack> context
	) throws CommandSyntaxException {
		String name = StringArgumentType.getString(context, "name");
		ServerPlayer player = context.getSource().getPlayerOrException();
		HitResult hit = player.pick(Player.DEFAULT_BLOCK_INTERACTION_RANGE, 1.0F, false);
		if (hit.getType() != HitResult.Type.BLOCK) {
			throw RELAY_SAVE_REJECTED.create(RelayFailure.TARGET_UNREACHABLE);
		}

		ServerLevel level = player.level();
		Identifier dimension = level.dimension().identifier();
		return saveRelay(
				context,
				name,
				player,
				level,
				dimension,
				player.position(),
				hit.getLocation()
		);
	}

	private static int saveRelay(
			CommandContext<CommandSourceStack> context,
			String name,
			ServerPlayer player,
			ServerLevel level,
			Identifier dimension,
			Vec3 spawnPos,
			Vec3 lookAtPos
	) throws CommandSyntaxException {
		RelayTargetResolver.Result targetResult = RelayTargetResolver.resolve(level, spawnPos, lookAtPos);
		if (!targetResult.isSuccess()) {
			throw RELAY_SAVE_REJECTED.create(targetResult.failure());
		}
		RelayDefinition relay;

		try {
			relay = RelayConfigManager.put(
					player.getUUID(),
					player.getGameProfile().name(),
					name,
					dimension,
					spawnPos,
					lookAtPos,
					targetResult.target()
			);
		} catch (IOException exception) {
			throw configError(exception);
		}

		context.getSource().sendSuccess(
				() -> Component.literal(
						"Saved relay: " + name + " (bot=" + relay.bot()
								+ ", target=" + relay.target().blockId()
								+ "@" + relay.target().x() + "," + relay.target().y() + "," + relay.target().z() + ")"
				),
				false
		);
		return Command.SINGLE_SUCCESS;
	}

	private static int fireRelay(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		UUID executionId = UUID.randomUUID();
		String name = StringArgumentType.getString(context, "name");
		ServerPlayer player = context.getSource().getPlayerOrException();
		RelayDefinition relay;
		try {
			relay = RelayConfigManager.get(player.getUUID(), name);
		} catch (IOException exception) {
			REPORTER.rejected(
					executionId,
					name,
					player.getUUID(),
					null,
					0,
					RelayFailure.EXECUTION_INTERNAL_ERROR
			);
			throw configError(exception);
		}

		if (relay == null) {
			REPORTER.rejected(
					executionId,
					name,
					player.getUUID(),
					null,
					0,
					RelayFailure.RELAY_NOT_FOUND
			);
			throw RELAY_NOT_FOUND.create(name);
		}

		RelayPreflight.Result preflight = RelayPreflight.check(
				context.getSource().getServer(),
				relay,
				player.getUUID()
		);
		if (!preflight.isSuccess()) {
			REPORTER.rejected(
					executionId,
					name,
					player.getUUID(),
					relay,
					0,
					preflight.failure()
			);
			throw RELAY_FIRE_REJECTED.create(preflight.failure());
		}

		RelayPreflight.ValidatedRelay request = preflight.request();
		return startExecution(context, executionId, name, player.getUUID(), request);
	}

	private static int listRelays(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		ServerPlayer player = context.getSource().getPlayerOrException();
		Set<String> names;
		try {
			names = RelayConfigManager.names(player.getUUID());
		} catch (IOException exception) {
			throw configError(exception);
		}

		String message = names.isEmpty() ? "No pearl relays saved." : "Pearl relays: " + String.join(", ", names);
		context.getSource().sendSuccess(() -> Component.literal(message), false);
		return Command.SINGLE_SUCCESS;
	}

	private static int removeRelay(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		String name = StringArgumentType.getString(context, "name");
		ServerPlayer player = context.getSource().getPlayerOrException();
		boolean removed;
		try {
			removed = RelayConfigManager.remove(player.getUUID(), name);
		} catch (IOException exception) {
			throw configError(exception);
		}

		if (!removed) {
			throw RELAY_NOT_FOUND.create(name);
		}

		context.getSource().sendSuccess(() -> Component.literal("Removed relay: " + name), false);
		return Command.SINGLE_SUCCESS;
	}

	private static int startExecution(
			CommandContext<CommandSourceStack> context,
			UUID executionId,
			String relayName,
			UUID ownerId,
			RelayPreflight.ValidatedRelay validated
	) throws CommandSyntaxException {
		StartResult result = EXECUTIONS.start(new ExecutionRequest(executionId, relayName, ownerId, validated));
		if (!result.isAccepted()) {
			REPORTER.rejected(
					executionId,
					relayName,
					ownerId,
					validated.relay(),
					validated.ownedPearlCount(),
					result.failure()
			);
			throw RELAY_FIRE_REJECTED.create(result.failure());
		}

		context.getSource().sendSuccess(
				() -> Component.literal(
						"Relay queued: " + relayName
								+ " (execution=" + result.executionId()
								+ ", bot=" + validated.relay().bot()
								+ ", ownedPearls=" + validated.ownedPearlCount() + ")"
				),
				false
		);
		return Command.SINGLE_SUCCESS;
	}

	private static ServerLevel resolveDimension(CommandContext<CommandSourceStack> context, Identifier dimension) throws CommandSyntaxException {
		ResourceKey<Level> dimensionKey = ResourceKey.create(Registries.DIMENSION, dimension);
		ServerLevel level = context.getSource().getServer().getLevel(dimensionKey);
		if (level == null) {
			throw INVALID_DIMENSION.create(dimension.toString());
		}

		return level;
	}

	private static String saveFailureMessage(RelayFailure failure) {
		String detail = switch (failure) {
			case SPAWN_CHUNK_UNLOADED -> "the fake-player spawn chunk is not loaded";
			case TARGET_CHUNK_UNLOADED -> "the target path enters an unloaded chunk";
			case SPAWN_POSITION_BLOCKED -> "the fake-player spawn position is blocked";
			case TARGET_UNREACHABLE -> "no reachable target block was hit";
			default -> "target validation failed";
		};
		return "[" + failure.code() + "] Cannot save relay: " + detail + ".";
	}

	private static CommandSyntaxException configError(IOException exception) {
		String detail = exception instanceof RelayConfigException
				? exception.getMessage()
				: "[CONFIG_IO_ERROR] Relay config operation failed.";
		return RELAY_CONFIG_ERROR.create(detail);
	}

}
