package com.foxseventeen.pearlrelay.command;

import carpet.fakes.ServerPlayerInterface;
import carpet.helpers.EntityPlayerActionPack;
import carpet.patches.EntityPlayerMPFake;
import com.foxseventeen.pearlrelay.config.RelayConfigManager;
import com.foxseventeen.pearlrelay.config.RelayConfigManager.RelayDefinition;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.arguments.IdentifierArgument;
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
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;

public final class PearlRelayCommand {
	private static final List<PendingLookAt> PENDING_LOOK_AT = new ArrayList<>();
	private static final List<PendingUse> PENDING_USE = new ArrayList<>();
	private static final List<PendingCleanup> PENDING_CLEANUP = new ArrayList<>();
	private static final DynamicCommandExceptionType INVALID_DIMENSION = new DynamicCommandExceptionType(
			dimension -> Component.literal("Unknown or unloaded dimension: " + dimension)
	);
	private static final DynamicCommandExceptionType PLAYER_NAME_IN_USE = new DynamicCommandExceptionType(
			bot -> Component.literal("Player name is already used by a real player: " + bot)
	);
	private static final DynamicCommandExceptionType FAKE_PLAYER_CREATE_FAILED = new DynamicCommandExceptionType(
			bot -> Component.literal("Failed to create Carpet fake player: " + bot)
	);
	private static final DynamicCommandExceptionType RELAY_NOT_FOUND = new DynamicCommandExceptionType(
			name -> Component.literal("Relay not found: " + name)
	);
	private static final DynamicCommandExceptionType RELAY_CONFIG_ERROR = new DynamicCommandExceptionType(
			message -> Component.literal("Relay config error: " + message)
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
													.then(Commands.argument("spawnX", DoubleArgumentType.doubleArg())
															.then(Commands.argument("spawnY", DoubleArgumentType.doubleArg())
																	.then(Commands.argument("spawnZ", DoubleArgumentType.doubleArg())
																			.then(Commands.argument("lookX", DoubleArgumentType.doubleArg())
																					.then(Commands.argument("lookY", DoubleArgumentType.doubleArg())
																							.then(Commands.argument("lookZ", DoubleArgumentType.doubleArg())
																									.executes(PearlRelayCommand::fireRaw))))))))))
							.then(Commands.literal("save")
									.then(Commands.argument("name", StringArgumentType.word())
											.then(Commands.argument("dimension", IdentifierArgument.id())
													.then(Commands.argument("spawnX", DoubleArgumentType.doubleArg())
															.then(Commands.argument("spawnY", DoubleArgumentType.doubleArg())
																	.then(Commands.argument("spawnZ", DoubleArgumentType.doubleArg())
																			.then(Commands.argument("lookX", DoubleArgumentType.doubleArg())
																					.then(Commands.argument("lookY", DoubleArgumentType.doubleArg())
																							.then(Commands.argument("lookZ", DoubleArgumentType.doubleArg())
																									.executes(PearlRelayCommand::saveRelay))))))))))
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

	public static void tick(MinecraftServer server) {
		tickLookAt(server);
		tickUse(server);
		tickCleanup(server);
	}

	private static void tickLookAt(MinecraftServer server) {
		Iterator<PendingLookAt> iterator = PENDING_LOOK_AT.iterator();
		while (iterator.hasNext()) {
			PendingLookAt pendingLookAt = iterator.next();
			if (pendingLookAt.ticksRemaining() > 0) {
				pendingLookAt.decrement();
				continue;
			}

			ServerPlayer player = server.getPlayerList().getPlayerByName(pendingLookAt.bot());
			if (player instanceof EntityPlayerMPFake) {
				lookAt(player, pendingLookAt.target());
			} else if (EntityPlayerMPFake.isSpawningPlayer(pendingLookAt.bot())) {
				pendingLookAt.delay(5);
				continue;
			}
			iterator.remove();
		}
	}

	private static void tickUse(MinecraftServer server) {
		Iterator<PendingUse> iterator = PENDING_USE.iterator();
		while (iterator.hasNext()) {
			PendingUse pendingUse = iterator.next();
			if (pendingUse.ticksRemaining() > 0) {
				pendingUse.decrement();
				continue;
			}

			ServerPlayer player = server.getPlayerList().getPlayerByName(pendingUse.bot());
			if (player instanceof EntityPlayerMPFake) {
				lookAt(player, pendingUse.target());
				useOnce(player);
			} else if (EntityPlayerMPFake.isSpawningPlayer(pendingUse.bot())) {
				pendingUse.delay(5);
				continue;
			}
			iterator.remove();
		}
	}

	private static void tickCleanup(MinecraftServer server) {
		Iterator<PendingCleanup> iterator = PENDING_CLEANUP.iterator();
		while (iterator.hasNext()) {
			PendingCleanup pendingCleanup = iterator.next();
			if (pendingCleanup.ticksRemaining() > 0) {
				pendingCleanup.decrement();
				continue;
			}

			ServerPlayer player = server.getPlayerList().getPlayerByName(pendingCleanup.bot());
			if (player instanceof EntityPlayerMPFake fakePlayer) {
				fakePlayer.kill(Component.literal("Pearl Relay finished."));
			} else if (EntityPlayerMPFake.isSpawningPlayer(pendingCleanup.bot())) {
				pendingCleanup.delay(5);
				continue;
			}
			iterator.remove();
		}
	}

	private static int test(CommandContext<CommandSourceStack> context) {
		context.getSource().sendSuccess(() -> Component.literal("Pearl Relay command works."), false);
		return Command.SINGLE_SUCCESS;
	}

	private static int fireRaw(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		String bot = StringArgumentType.getString(context, "bot");
		Identifier dimension = IdentifierArgument.getId(context, "dimension");
		Vec3 spawnPos = getVec3(context, "spawn");
		Vec3 lookAtPos = getVec3(context, "look");
		return trigger(context, bot, dimension, spawnPos, lookAtPos);
	}

	private static int saveRelay(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		String name = StringArgumentType.getString(context, "name");
		Identifier dimension = IdentifierArgument.getId(context, "dimension");
		Vec3 spawnPos = getVec3(context, "spawn");
		Vec3 lookAtPos = getVec3(context, "look");
		ServerPlayer player = context.getSource().getPlayerOrException();
		RelayDefinition relay;

		try {
			relay = RelayConfigManager.put(player.getUUID(), player.getGameProfile().name(), name, dimension, spawnPos, lookAtPos);
		} catch (IOException exception) {
			throw RELAY_CONFIG_ERROR.create(exception.getMessage());
		}

		context.getSource().sendSuccess(() -> Component.literal("Saved relay: " + name + " (bot=" + relay.bot() + ")"), false);
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

		return trigger(context, relay.bot(), relay.dimension(), relay.spawn(), relay.lookAt());
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

	private static int trigger(CommandContext<CommandSourceStack> context, String bot, Identifier dimension, Vec3 spawnPos, Vec3 lookAtPos) throws CommandSyntaxException {
		ServerLevel level = resolveDimension(context, dimension);
		ResourceKey<Level> dimensionKey = level.dimension();
		FakePlayerResult fakePlayerResult = ensureFakePlayer(context.getSource().getServer(), bot, level, spawnPos, lookAtPos);
		if (fakePlayerResult.player() != null) {
		lookAt(fakePlayerResult.player(), lookAtPos);
		}
		queueLookAt(bot, lookAtPos);
		queueUse(bot, lookAtPos);
		queueCleanup(bot);

		String message = String.format(
				Locale.ROOT,
				"bot=%s, fakePlayer=%s, use=queued, cleanup=queued, dimension=%s, spawn=(%.3f, %.3f, %.3f), lookAt=(%.3f, %.3f, %.3f)",
				bot,
				fakePlayerResult.status(),
				dimensionKey.identifier(),
				spawnPos.x,
				spawnPos.y,
				spawnPos.z,
				lookAtPos.x,
				lookAtPos.y,
				lookAtPos.z
		);

		context.getSource().sendSuccess(() -> Component.literal(message), false);
		return Command.SINGLE_SUCCESS;
	}

	private static Vec3 getVec3(CommandContext<CommandSourceStack> context, String prefix) {
		return new Vec3(
				DoubleArgumentType.getDouble(context, prefix + "X"),
				DoubleArgumentType.getDouble(context, prefix + "Y"),
				DoubleArgumentType.getDouble(context, prefix + "Z")
		);
	}

	private static void lookAt(ServerPlayer player, Vec3 lookAtPos) {
		((ServerPlayerInterface) player).getActionPack().lookAt(lookAtPos);
	}

	private static void useOnce(ServerPlayer player) {
		((ServerPlayerInterface) player).getActionPack().start(
				EntityPlayerActionPack.ActionType.USE,
				EntityPlayerActionPack.Action.once()
		);
	}

	private static void queueLookAt(String bot, Vec3 lookAtPos) {
		PENDING_LOOK_AT.add(new PendingLookAt(bot, lookAtPos, 5));
		PENDING_LOOK_AT.add(new PendingLookAt(bot, lookAtPos, 10));
		PENDING_LOOK_AT.add(new PendingLookAt(bot, lookAtPos, 20));
	}

	private static void queueUse(String bot, Vec3 lookAtPos) {
		PENDING_USE.add(new PendingUse(bot, lookAtPos, 25));
	}

	private static void queueCleanup(String bot) {
		PENDING_CLEANUP.add(new PendingCleanup(bot, 45));
	}

	private static FakePlayerResult ensureFakePlayer(MinecraftServer server, String bot, ServerLevel level, Vec3 spawnPos, Vec3 lookAtPos) throws CommandSyntaxException {
		Facing facing = calculateFacing(spawnPos, lookAtPos);
		ServerPlayer existingPlayer = server.getPlayerList().getPlayerByName(bot);
		if (existingPlayer != null) {
			if (!(existingPlayer instanceof EntityPlayerMPFake)) {
				throw PLAYER_NAME_IN_USE.create(bot);
			}

			existingPlayer.teleportTo(level, spawnPos.x, spawnPos.y, spawnPos.z, Set.of(), facing.yaw(), facing.pitch(), true);
			return new FakePlayerResult(existingPlayer, "reused");
		}

		boolean created = EntityPlayerMPFake.createFake(
				bot,
				server,
				spawnPos,
				facing.yaw(),
				facing.pitch(),
				level.dimension(),
				GameType.SURVIVAL,
				false
		);

		ServerPlayer createdPlayer = server.getPlayerList().getPlayerByName(bot);
		if (!created && !EntityPlayerMPFake.isSpawningPlayer(bot)) {
			throw FAKE_PLAYER_CREATE_FAILED.create(bot);
		}

		if (createdPlayer == null) {
			return new FakePlayerResult(null, "creating");
		}

		return new FakePlayerResult(createdPlayer, "created");
	}

	private static Facing calculateFacing(Vec3 spawnPos, Vec3 lookAtPos) {
		double dx = lookAtPos.x - spawnPos.x;
		double dy = lookAtPos.y - (spawnPos.y + 1.62D);
		double dz = lookAtPos.z - spawnPos.z;
		double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
		float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
		float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontalDistance)));
		return new Facing(yaw, pitch);
	}

	private static ServerLevel resolveDimension(CommandContext<CommandSourceStack> context, Identifier dimension) throws CommandSyntaxException {
		ResourceKey<Level> dimensionKey = ResourceKey.create(Registries.DIMENSION, dimension);
		ServerLevel level = context.getSource().getServer().getLevel(dimensionKey);
		if (level == null) {
			throw INVALID_DIMENSION.create(dimension.toString());
		}

		return level;
	}

	private record FakePlayerResult(ServerPlayer player, String status) {
	}

	private record Facing(float yaw, float pitch) {
	}

	private static final class PendingLookAt {
		private final String bot;
		private final Vec3 target;
		private int ticksRemaining;

		private PendingLookAt(String bot, Vec3 target, int ticksRemaining) {
			this.bot = bot;
			this.target = target;
			this.ticksRemaining = ticksRemaining;
		}

		private String bot() {
			return bot;
		}

		private Vec3 target() {
			return target;
		}

		private int ticksRemaining() {
			return ticksRemaining;
		}

		private void decrement() {
			ticksRemaining--;
		}

		private void delay(int ticks) {
			ticksRemaining = ticks;
		}
	}

	private static final class PendingUse {
		private final String bot;
		private final Vec3 target;
		private int ticksRemaining;

		private PendingUse(String bot, Vec3 target, int ticksRemaining) {
			this.bot = bot;
			this.target = target;
			this.ticksRemaining = ticksRemaining;
		}

		private String bot() {
			return bot;
		}

		private Vec3 target() {
			return target;
		}

		private int ticksRemaining() {
			return ticksRemaining;
		}

		private void decrement() {
			ticksRemaining--;
		}

		private void delay(int ticks) {
			ticksRemaining = ticks;
		}
	}

	private static final class PendingCleanup {
		private final String bot;
		private int ticksRemaining;

		private PendingCleanup(String bot, int ticksRemaining) {
			this.bot = bot;
			this.ticksRemaining = ticksRemaining;
		}

		private String bot() {
			return bot;
		}

		private int ticksRemaining() {
			return ticksRemaining;
		}

		private void decrement() {
			ticksRemaining--;
		}

		private void delay(int ticks) {
			ticksRemaining = ticks;
		}
	}
}
