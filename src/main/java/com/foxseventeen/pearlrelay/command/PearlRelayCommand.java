package com.foxseventeen.pearlrelay.command;

import com.foxseventeen.pearlrelay.config.RelayConfigManager;
import com.foxseventeen.pearlrelay.config.RelayConfigManager.RelayDefinition;
import com.foxseventeen.pearlrelay.relay.CarpetRelayRuntime;
import com.foxseventeen.pearlrelay.relay.RelayExecutionManager;
import com.foxseventeen.pearlrelay.relay.RelayExecutionManager.ExecutionRequest;
import com.foxseventeen.pearlrelay.relay.RelayExecutionManager.StartResult;
import com.foxseventeen.pearlrelay.relay.RelayFailure;
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
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Set;
import java.util.UUID;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;

public final class PearlRelayCommand {
	private static final UUID CONSOLE_OWNER = new UUID(0L, 0L);
	private static final RelayExecutionManager EXECUTIONS =
			new RelayExecutionManager(new CarpetRelayRuntime(), terminal -> {
			});
	private static final DynamicCommandExceptionType INVALID_DIMENSION = new DynamicCommandExceptionType(
			dimension -> Component.literal("Unknown or unloaded dimension: " + dimension)
	);
	private static final DynamicCommandExceptionType RELAY_NOT_FOUND = new DynamicCommandExceptionType(
			name -> Component.literal("Relay not found: " + name)
	);
	private static final DynamicCommandExceptionType RELAY_CONFIG_ERROR = new DynamicCommandExceptionType(
			message -> Component.literal("Relay config error: " + message)
	);
	private static final DynamicCommandExceptionType RELAY_SAVE_REJECTED = new DynamicCommandExceptionType(
			failure -> Component.literal(saveFailureMessage((RelayFailure) failure))
	);
	private static final DynamicCommandExceptionType RELAY_FIRE_REJECTED = new DynamicCommandExceptionType(
			failure -> Component.literal(fireFailureMessage((RelayFailure) failure))
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
		String bot = StringArgumentType.getString(context, "bot");
		Identifier dimension = IdentifierArgument.getId(context, "dimension");
		Vec3 spawnPos = Vec3Argument.getVec3(context, "spawn");
		Vec3 lookAtPos = Vec3Argument.getVec3(context, "lookAt");
		ServerLevel level = resolveDimension(context, dimension);
		RelayTargetResolver.Result target = RelayTargetResolver.resolve(level, spawnPos, lookAtPos);
		if (!target.isSuccess()) {
			throw RELAY_FIRE_REJECTED.create(target.failure());
		}

		RelayDefinition relay = new RelayDefinition(bot, dimension, spawnPos, lookAtPos, target.target());
		ServerPlayer player = context.getSource().getPlayer();
		RelayPreflight.ValidatedRelay validated = new RelayPreflight.ValidatedRelay(level, relay, -1);
		return startExecution(
				context,
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
			throw RELAY_CONFIG_ERROR.create(exception.getMessage());
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
		String name = StringArgumentType.getString(context, "name");
		ServerPlayer player = context.getSource().getPlayerOrException();
		RelayDefinition relay;
		try {
			relay = RelayConfigManager.get(player.getUUID(), name);
		} catch (IOException exception) {
			throw RELAY_CONFIG_ERROR.create(exception.getMessage());
		}

		if (relay == null) {
			throw RELAY_NOT_FOUND.create(name);
		}

		RelayPreflight.Result preflight = RelayPreflight.check(
				context.getSource().getServer(),
				relay,
				player.getUUID()
		);
		if (!preflight.isSuccess()) {
			throw RELAY_FIRE_REJECTED.create(preflight.failure());
		}

		RelayPreflight.ValidatedRelay request = preflight.request();
		return startExecution(context, name, player.getUUID(), request);
	}

	private static int listRelays(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		ServerPlayer player = context.getSource().getPlayerOrException();
		Set<String> names;
		try {
			names = RelayConfigManager.names(player.getUUID());
		} catch (IOException exception) {
			throw RELAY_CONFIG_ERROR.create(exception.getMessage());
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
			throw RELAY_CONFIG_ERROR.create(exception.getMessage());
		}

		if (!removed) {
			throw RELAY_NOT_FOUND.create(name);
		}

		context.getSource().sendSuccess(() -> Component.literal("Removed relay: " + name), false);
		return Command.SINGLE_SUCCESS;
	}

	private static int startExecution(
			CommandContext<CommandSourceStack> context,
			String relayName,
			UUID ownerId,
			RelayPreflight.ValidatedRelay validated
	) throws CommandSyntaxException {
		StartResult result = EXECUTIONS.start(new ExecutionRequest(relayName, ownerId, validated));
		if (!result.isAccepted()) {
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

	private static String fireFailureMessage(RelayFailure failure) {
		String detail = switch (failure) {
			case RELAY_REQUIRES_RESAVE -> "this relay was saved without a target fingerprint; save it again first";
			case DIMENSION_UNAVAILABLE -> "the saved dimension is unavailable";
			case SPAWN_CHUNK_UNLOADED -> "the fake-player spawn chunk is not loaded";
			case TARGET_CHUNK_UNLOADED -> "the target chunk is not loaded, so no usable pearl can be present";
			case SPAWN_POSITION_BLOCKED -> "the fake-player spawn position is blocked";
			case TARGET_BLOCK_CHANGED -> "the target block type no longer matches the saved relay";
			case TARGET_UNREACHABLE -> "the saved target is no longer reachable from the fake-player spawn";
			case OWNED_PEARL_NOT_FOUND -> "no ender pearl owned by you exists in the target block chunk";
			case EXECUTION_ALREADY_ACTIVE -> "an execution for this relay bot is already active";
			case FAKE_PLAYER_NAME_IN_USE -> "the generated fake-player name is already in use";
			case FAKE_PLAYER_CREATE_FAILED -> "Carpet could not create the fake player";
			case FAKE_PLAYER_SPAWN_TIMEOUT -> "the fake player did not finish spawning before the deadline";
			case EXECUTION_INTERNAL_ERROR -> "an internal execution step failed";
			case EXECUTION_CLEANUP_TIMEOUT -> "the fake player could not be confirmed removed before the deadline";
			default -> "the relay cannot be fired safely";
		};
		return "[" + failure.code() + "] Fire rejected: " + detail + ".";
	}
}
