package com.foxseventeen.pearlrelay.test;

import com.foxseventeen.pearlrelay.config.RelayConfigManager;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.netty.channel.embedded.EmbeddedChannel;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.TicketStorage;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.layout.PatternLayout;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

public final class PearlRelayCommandGameTests implements CustomTestMethodInvoker {
	private static final BlockPos TARGET = new BlockPos(0, 1, 3);

	@GameTest
	public void commandSnapshotSaveCapturesPlayerPositionDimensionAndViewHit(
			GameTestHelper context
	) throws Exception {
		prepareDevice(context);
		Vec3 relativeSpawn = new Vec3(0.375D, 0.0D, 0.625D);
		Vec3 spawn = context.absoluteVec(relativeSpawn);
		CapturingPlayer owner = createPlayer(context, "snapshot", relativeSpawn);
		String relayName = uniqueRelay("snapshot");

		try {
			Vec3 targetCenter = context.absoluteVec(Vec3.atCenterOf(TARGET));
			owner.lookAt(EntityAnchorArgument.Anchor.EYES, targetCenter);
			HitResult playerHit = owner.pick(
					Player.DEFAULT_BLOCK_INTERACTION_RANGE,
					1.0F,
					false
			);
			context.assertValueEqual(playerHit.getType(), HitResult.Type.BLOCK, "player view hit type");
			BlockHitResult blockHit = (BlockHitResult) playerHit;
			context.assertValueEqual(
					blockHit.getBlockPos(),
					context.absolutePos(TARGET),
					"player view target block"
			);
			Vec3 expectedHit = blockHit.getLocation();

			assertCommandSucceeded(context, "pearlrelay save " + relayName, owner);

			RelayConfigManager.RelayDefinition relay =
					RelayConfigManager.get(owner.getUUID(), relayName);
			BlockPos absoluteTarget = context.absolutePos(TARGET);
			context.assertValueEqual(relay.dimension(), context.getLevel().dimension().identifier(), "dimension");
			context.assertValueEqual(relay.spawn(), spawn, "exact player feet position");
			context.assertValueEqual(relay.lookAt(), expectedHit, "exact player block hit");
			context.assertValueEqual(relay.target().x(), absoluteTarget.getX(), "target x");
			context.assertValueEqual(relay.target().y(), absoluteTarget.getY(), "target y");
			context.assertValueEqual(relay.target().z(), absoluteTarget.getZ(), "target z");
			context.assertValueEqual(relay.target().blockId(), "minecraft:note_block", "target block");
			context.succeed();
		} finally {
			Files.deleteIfExists(playerConfig(owner.getUUID()));
			Files.deleteIfExists(playerConfig(owner.getUUID()).resolveSibling(
					playerConfig(owner.getUUID()).getFileName() + ".bak"
			));
			owner.close();
		}
	}

	@GameTest
	public void commandSnapshotSaveRejectsConsoleWithAStablePlayerRequiredCode(
			GameTestHelper context
	) {
		CommandOutcome outcome = execute(
				context.getLevel().getServer().createCommandSourceStack(),
				"pearlrelay save console_only"
		);

		context.assertTrue(outcome.error().contains("PLAYER_REQUIRED"), "console rejection code");
		context.assertValueEqual(outcome.result(), 0, "console rejection result");
		context.succeed();
	}

	@GameTest
	public void commandSnapshotSaveMissDoesNotWriteConfig(GameTestHelper context) throws Exception {
		prepareDevice(context);
		CapturingPlayer owner = createPlayer(
				context,
				"snapshot_miss",
				new Vec3(0.5D, 0.0D, 0.5D)
		);
		String relayName = uniqueRelay("miss");
		Path config = playerConfig(owner.getUUID());

		try {
			owner.setXRot(-90.0F);
			owner.xRotO = -90.0F;

			CommandOutcome outcome = execute(owner, "pearlrelay save " + relayName);

			context.assertTrue(
					outcome.error().contains("TARGET_UNREACHABLE"),
					"miss rejection code: " + outcome.error()
			);
			context.assertFalse(Files.exists(config), "miss rejection must not create config");
			context.assertFalse(
					Files.exists(config.resolveSibling(config.getFileName() + ".bak")),
					"miss rejection must not create backup"
			);
			context.succeed();
		} finally {
			owner.close();
		}
	}

	@GameTest
	public void commandSnapshotSaveAllowsCreatorButFireRejectsUntilTheyLeave(
			GameTestHelper context
	) throws Exception {
		prepareDevice(context);
		CapturingPlayer owner = createPlayer(
				context,
				"snapshot_block",
				new Vec3(0.5D, 0.0D, 0.5D)
		);
		String relayName = uniqueRelay("blocked");

		try {
			owner.lookAt(
					EntityAnchorArgument.Anchor.EYES,
					context.absoluteVec(Vec3.atCenterOf(TARGET))
			);
			assertCommandSucceeded(context, "pearlrelay save " + relayName, owner);
			RelayConfigManager.RelayDefinition relay =
					RelayConfigManager.get(owner.getUUID(), relayName);
			ThrownEnderpearl pearl = spawnPearl(context, owner, TARGET);

			CommandOutcome outcome = execute(owner, "pearlrelay fire " + relayName);

			context.assertTrue(
					outcome.error().contains("SPAWN_POSITION_BLOCKED"),
					"occupied snapshot fire rejection"
			);
			context.assertTrue(pearl.isAlive(), "occupied rejection must not consume the pearl");
			context.assertTrue(
					context.getLevel().getServer().getPlayerList().getPlayerByName(relay.bot()) == null,
					"occupied rejection must not create the fake player"
			);
			context.assertBlockProperty(TARGET, net.minecraft.world.level.block.NoteBlock.NOTE, 0);
			context.succeed();
		} finally {
			Files.deleteIfExists(playerConfig(owner.getUUID()));
			owner.close();
		}
	}

	@GameTest
	public void commandTestSaveListRemoveAndSuggestionsAreIsolated(GameTestHelper context) throws Exception {
		context.setBlock(0, 1, 3, Blocks.NOTE_BLOCK);
		CapturingPlayer first = createPlayer(context, "first", new Vec3(3.5D, 0.0D, 0.5D));
		CapturingPlayer second = createPlayer(context, "second", new Vec3(-3.5D, 0.0D, 0.5D));
		LogCapture logs = new LogCapture();

		try {
			CommandOutcome test = execute(first, "pearlrelay test");
			context.assertValueEqual(test.result(), 1, "test command result");
			context.assertTrue(
					first.hasMessage("Pearl Relay command works."),
					"test command feedback"
			);

			assertCommandSucceeded(context, saveCommand(context, "shared"), first);
			assertCommandSucceeded(context, saveCommand(context, "alpha"), first);
			assertCommandSucceeded(context, saveCommand(context, "shared"), second);
			assertCommandSucceeded(context, saveCommand(context, "beta"), second);

			RelayConfigManager.RelayDefinition firstRelay =
					RelayConfigManager.get(first.getUUID(), "shared");
			RelayConfigManager.RelayDefinition secondRelay =
					RelayConfigManager.get(second.getUUID(), "shared");
			context.assertFalse(
					firstRelay.bot().equals(secondRelay.bot()),
					"same relay name must produce UUID-isolated fake-player names"
			);

			execute(first, "pearlrelay list");
			context.assertTrue(first.hasMessageContaining("alpha"), "first player list contains alpha");
			context.assertFalse(first.hasMessageContaining("beta"), "first player list excludes beta");

			execute(second, "pearlrelay list");
			context.assertTrue(second.hasMessageContaining("beta"), "second player list contains beta");
			context.assertFalse(second.hasMessageContaining("alpha"), "second player list excludes alpha");

			List<String> firstSuggestions = suggestions(first, "pearlrelay fire ");
			context.assertTrue(firstSuggestions.contains("alpha"), "first player completion contains alpha");
			context.assertFalse(firstSuggestions.contains("beta"), "first player completion excludes beta");

			List<String> secondSuggestions = suggestions(second, "pearlrelay fire ");
			context.assertTrue(secondSuggestions.contains("beta"), "second player completion contains beta");
			context.assertFalse(secondSuggestions.contains("alpha"), "second player completion excludes alpha");

			assertCommandSucceeded(context, "pearlrelay remove shared", first);
			context.assertTrue(first.hasMessageContaining("Removed relay: shared"), "remove feedback");
			CommandOutcome missingForFirst = execute(first, "pearlrelay fire shared");
			context.assertTrue(
					missingForFirst.error().contains("RELAY_NOT_FOUND"),
					"removed relay must be absent for its owner"
			);
			context.assertTrue(
					context.getLevel().getServer().getPlayerList().getPlayerByName(firstRelay.bot()) == null,
					"missing-relay rejection must not create a fake player"
			);
			context.assertBlockProperty(TARGET, net.minecraft.world.level.block.NoteBlock.NOTE, 0);
			assertLogCount(context, logs, "shared", "failure_code=RELAY_NOT_FOUND", 1);
			assertLogCount(context, logs, "shared", "action=accepted", 0);
			assertLogCount(context, logs, "shared", "action=terminal", 0);

			execute(second, "pearlrelay list");
			context.assertTrue(
					second.hasMessageContaining("shared"),
					"removing first player's relay must not remove second player's relay"
			);
			context.succeed();
		} finally {
			logs.close();
			first.close();
			second.close();
		}
	}

	@GameTest
	public void commandFireRejectsZeroAndOtherOwnedPearlsWithoutSideEffects(
			GameTestHelper context
	) throws Exception {
		prepareDevice(context);
		CapturingPlayer owner = createPlayer(context, "owner0", new Vec3(3.5D, 0.0D, 0.5D));
		CapturingPlayer other = createPlayer(context, "other0", new Vec3(-3.5D, 0.0D, 0.5D));
		String zeroRelay = uniqueRelay("zero");
		String otherRelay = uniqueRelay("other");
		LogCapture logs = new LogCapture();

		try {
			assertCommandSucceeded(context, saveCommand(context, zeroRelay), owner);
			assertCommandSucceeded(context, saveCommand(context, otherRelay), owner);
			String zeroBot = RelayConfigManager.get(owner.getUUID(), zeroRelay).bot();
			String otherBot = RelayConfigManager.get(owner.getUUID(), otherRelay).bot();

			CommandOutcome noPearl = execute(owner, "pearlrelay fire " + zeroRelay);
			context.assertTrue(
					noPearl.error().contains("OWNED_PEARL_NOT_FOUND"),
					"zero-pearl command error"
			);
			context.assertTrue(
					context.getLevel().getServer().getPlayerList().getPlayerByName(zeroBot) == null,
					"zero-pearl rejection must not create a fake player"
			);

			ThrownEnderpearl otherPearl = spawnPearl(context, other, TARGET);
			CommandOutcome wrongOwner = execute(owner, "pearlrelay fire " + otherRelay);
			context.assertTrue(
					wrongOwner.error().contains("OWNED_PEARL_NOT_FOUND"),
					"other-owner command error"
			);
			context.assertTrue(otherPearl.isAlive(), "rejection must not remove another player's pearl");
			context.assertTrue(
					context.getLevel().getServer().getPlayerList().getPlayerByName(otherBot) == null,
					"other-owner rejection must not create a fake player"
			);
			context.assertBlockProperty(TARGET, net.minecraft.world.level.block.NoteBlock.NOTE, 0);
			assertLogCount(context, logs, zeroRelay, "action=rejected", 1);
			assertLogCount(context, logs, otherRelay, "action=rejected", 1);
			assertLogCount(context, logs, zeroRelay, "action=accepted", 0);
			assertLogCount(context, logs, otherRelay, "action=terminal", 0);
			context.succeed();
		} finally {
			logs.close();
			owner.close();
			other.close();
		}
	}

	@GameTest(maxTicks = 80)
	public void commandFireWithOnePearlRejectsDuplicateAndUsesOnce(
			GameTestHelper context
	) throws Exception {
		prepareDevice(context);
		CapturingPlayer owner = createPlayer(context, "owner1", new Vec3(3.5D, 0.0D, 0.5D));
		String relayName = uniqueRelay("one");
		LogCapture logs = new LogCapture();

		try {
			assertCommandSucceeded(context, saveCommand(context, relayName), owner);
			String bot = RelayConfigManager.get(owner.getUUID(), relayName).bot();
			spawnPearl(context, owner, TARGET);

			CommandOutcome accepted = execute(owner, "pearlrelay fire " + relayName);
			context.assertValueEqual(accepted.result(), 1, "one-pearl fire result");
			context.assertTrue(owner.hasMessageContaining("ownedPearls=1"), "one-pearl queued message");

			CommandOutcome duplicate = execute(owner, "pearlrelay fire " + relayName);
			context.assertTrue(
					duplicate.error().contains("EXECUTION_ALREADY_ACTIVE"),
					"duplicate fire error"
			);
			context.assertTrue(
					context.getLevel().getServer().getPlayerList().getPlayerByName(bot) == null,
					"same-tick duplicate rejection must not create an extra fake player"
			);

			context.runAtTickTime(60, () -> {
				try {
					context.assertBlockProperty(TARGET, net.minecraft.world.level.block.NoteBlock.NOTE, 1);
					context.assertTrue(
							context.getLevel().getServer().getPlayerList().getPlayerByName(bot) == null,
							"completed execution must remove its fake player"
					);
					context.assertTrue(
							owner.hasMessageContaining("completed"),
							"owner receives terminal completion"
					);
					assertLogCount(context, logs, relayName, "action=accepted", 1);
					assertLogCount(context, logs, relayName, "action=rejected", 1);
					assertLogCount(context, logs, relayName, "action=terminal", 1);
					context.assertTrue(
							logs.hasLine(relayName, "failure_code=EXECUTION_ALREADY_ACTIVE"),
							"duplicate rejection log code"
					);
					assertAcceptedAndTerminalIdsMatch(context, logs, relayName);
					context.succeed();
				} finally {
					logs.close();
					owner.close();
				}
			});
		} catch (Exception exception) {
			logs.close();
			owner.close();
			throw exception;
		}
	}

	@GameTest(maxTicks = 80)
	public void commandFireWithMultiplePearlsStillUsesOnce(GameTestHelper context) throws Exception {
		prepareDevice(context);
		CapturingPlayer owner = createPlayer(context, "owner2", new Vec3(3.5D, 0.0D, 0.5D));
		String relayName = uniqueRelay("multi");
		LogCapture logs = new LogCapture();

		try {
			assertCommandSucceeded(context, saveCommand(context, relayName), owner);
			String bot = RelayConfigManager.get(owner.getUUID(), relayName).bot();
			spawnPearl(context, owner, TARGET);
			spawnPearl(context, owner, TARGET);

			CommandOutcome accepted = execute(owner, "pearlrelay fire " + relayName);
			context.assertValueEqual(accepted.result(), 1, "multiple-pearl fire result");
			context.assertTrue(owner.hasMessageContaining("ownedPearls=2"), "multiple-pearl queued message");

			context.runAtTickTime(60, () -> {
				try {
					context.assertBlockProperty(TARGET, net.minecraft.world.level.block.NoteBlock.NOTE, 1);
					context.assertTrue(
							context.getLevel().getServer().getPlayerList().getPlayerByName(bot) == null,
							"multiple-pearl execution must clean fake player"
					);
					assertLogCount(context, logs, relayName, "action=accepted", 1);
					assertLogCount(context, logs, relayName, "action=terminal", 1);
					assertAcceptedAndTerminalIdsMatch(context, logs, relayName);
					context.succeed();
				} finally {
					logs.close();
					owner.close();
				}
			});
		} catch (Exception exception) {
			logs.close();
			owner.close();
			throw exception;
		}
	}

	@GameTest
	public void legacyRelayRequiresSameNameResave(GameTestHelper context) throws Exception {
		prepareDevice(context);
		CapturingPlayer owner = createPlayer(context, "legacy", new Vec3(3.5D, 0.0D, 0.5D));
		String relayName = uniqueRelay("legacy");
		Path config = playerConfig(owner.getUUID());
		LogCapture logs = new LogCapture();

		try {
			writeConfig(context, owner, relayName, null);
			execute(owner, "pearlrelay list");
			context.assertTrue(owner.hasMessageContaining(relayName), "legacy relay remains listable");

			CommandOutcome rejected = execute(owner, "pearlrelay fire " + relayName);
			context.assertTrue(
					rejected.error().contains("RELAY_REQUIRES_RESAVE"),
					"legacy fire requires resave"
			);
			String bot = RelayConfigManager.get(owner.getUUID(), relayName).bot();
			context.assertTrue(
					context.getLevel().getServer().getPlayerList().getPlayerByName(bot) == null,
					"legacy rejection must not create fake player"
			);

			assertCommandSucceeded(context, saveCommand(context, relayName), owner);
			context.assertFalse(
					RelayConfigManager.get(owner.getUUID(), relayName).requiresResave(),
					"same-name save upgrades the legacy relay"
			);
			assertLogCount(context, logs, relayName, "failure_code=RELAY_REQUIRES_RESAVE", 1);
			assertLogCount(context, logs, relayName, "action=accepted", 0);
			context.succeed();
		} finally {
			logs.close();
			Files.deleteIfExists(config);
			owner.close();
		}
	}

	@GameTest
	public void unloadedTargetCommandDoesNotAddTicketsOrEntities(GameTestHelper context)
			throws Exception {
		CapturingPlayer owner = createPlayer(context, "unload", new Vec3(3.5D, 0.0D, 0.5D));
		String relayName = uniqueRelay("unload");
		Path config = playerConfig(owner.getUUID());
		LogCapture logs = new LogCapture();
		BlockPos farTarget = context.absolutePos(new BlockPos(1600, 1, 1600));
		ChunkPos targetChunk = new ChunkPos(
				Math.floorDiv(farTarget.getX(), 16),
				Math.floorDiv(farTarget.getZ(), 16)
		);
		long targetChunkKey = ChunkPos.pack(targetChunk.x(), targetChunk.z());
		TicketStorage tickets = context.getLevel()
				.getChunkSource()
				.getDataStorage()
				.computeIfAbsent(TicketStorage.TYPE);

		try {
			writeConfig(context, owner, relayName, farTarget);
			String bot = RelayConfigManager.get(owner.getUUID(), relayName).bot();
			List<String> ticketsBefore = ticketSnapshot(tickets, targetChunkKey);
			boolean tickingBefore =
					context.getLevel().isPositionTickingWithEntitiesLoaded(targetChunkKey);
			Object chunkBefore =
					context.getLevel().getChunkSource().getChunkNow(targetChunk.x(), targetChunk.z());

			CommandOutcome rejected = execute(owner, "pearlrelay fire " + relayName);

			context.assertTrue(
					rejected.error().contains("TARGET_CHUNK_UNLOADED"),
					"unloaded target command error"
			);
			context.assertValueEqual(
					ticketSnapshot(tickets, targetChunkKey),
					ticketsBefore,
					"target chunk tickets after rejection"
			);
			context.assertValueEqual(
					context.getLevel().isPositionTickingWithEntitiesLoaded(targetChunkKey),
					tickingBefore,
					"target chunk entity-ticking state after rejection"
			);
			context.assertTrue(chunkBefore == null, "far target must start unloaded");
			context.assertTrue(
					context.getLevel().getChunkSource().getChunkNow(targetChunk.x(), targetChunk.z()) == null,
					"rejection must not materialize the far target chunk"
			);
			context.assertTrue(
					context.getLevel().getServer().getPlayerList().getPlayerByName(bot) == null,
					"unloaded-target rejection must not create fake player"
			);
			assertLogCount(context, logs, relayName, "action=rejected", 1);
			assertLogCount(context, logs, relayName, "action=accepted", 0);
			assertLogCount(context, logs, relayName, "action=terminal", 0);
			context.succeed();
		} finally {
			logs.close();
			Files.deleteIfExists(config);
			owner.close();
		}
	}

	private static String saveCommand(GameTestHelper context, String relayName) {
		Vec3 spawn = context.absoluteVec(new Vec3(0.5D, 0.0D, 0.5D));
		Vec3 lookAt = context.absoluteVec(new Vec3(0.5D, 1.5D, 3.5D));
		return "pearlrelay save " + relayName
				+ " " + context.getLevel().dimension().identifier()
				+ " " + coordinates(spawn)
				+ " " + coordinates(lookAt);
	}

	private static String coordinates(Vec3 position) {
		return String.format(
				Locale.ROOT,
				"%.3f %.3f %.3f",
				position.x,
				position.y,
				position.z
		);
	}

	private static void prepareDevice(GameTestHelper context) {
		context.setBlock(new BlockPos(0, -1, 0), Blocks.STONE);
		context.setBlock(TARGET, Blocks.NOTE_BLOCK);
	}

	private static String uniqueRelay(String prefix) {
		return prefix + UUID.randomUUID().toString().substring(0, 8);
	}

	private static ThrownEnderpearl spawnPearl(
			GameTestHelper context,
			Player owner,
			BlockPos relativeTarget
	) {
		ThrownEnderpearl pearl = new ThrownEnderpearl(
				context.getLevel(),
				owner,
				Items.ENDER_PEARL.getDefaultInstance()
		);
		pearl.setPos(context.absoluteVec(Vec3.atCenterOf(relativeTarget)));
		context.getLevel().addFreshEntity(pearl);
		return pearl;
	}

	private static Path playerConfig(UUID playerId) {
		return FabricLoader.getInstance()
				.getConfigDir()
				.resolve("pearlrelay")
				.resolve("players")
				.resolve(playerId + ".json");
	}

	private static void writeConfig(
			GameTestHelper context,
			CapturingPlayer owner,
			String relayName,
			BlockPos explicitTarget
	) throws IOException {
		Vec3 spawn = context.absoluteVec(new Vec3(0.5D, 0.0D, 0.5D));
		Vec3 lookAt = context.absoluteVec(new Vec3(0.5D, 1.5D, 3.5D));
		var dimension = context.getLevel().dimension().identifier();
		String bot = generatedBotName(owner.getUUID(), relayName);
		String schema = explicitTarget == null ? "" : "  \"schemaVersion\": 2,\n";
		String target = explicitTarget == null
				? ""
				: String.format(
						Locale.ROOT,
						"""
						        ,"target": {
						          "x": %d,
						          "y": %d,
						          "z": %d,
						          "blockId": "minecraft:note_block"
						        }
						""",
						explicitTarget.getX(),
						explicitTarget.getY(),
						explicitTarget.getZ()
				);
		String json = String.format(
				Locale.ROOT,
				"""
				{
				%s  "playerName": "%s",
				  "relays": {
				    "%s": {
				      "bot": "%s",
				      "dimension": {
				        "namespace": "%s",
				        "path": "%s"
				      },
				      "spawn": {
				        "x": %.3f,
				        "y": %.3f,
				        "z": %.3f
				      },
				      "lookAt": {
				        "x": %.3f,
				        "y": %.3f,
				        "z": %.3f
				      }%s
				    }
				  }
				}
				""",
				schema,
				owner.getGameProfile().name(),
				relayName,
				bot,
				dimension.getNamespace(),
				dimension.getPath(),
				spawn.x,
				spawn.y,
				spawn.z,
				lookAt.x,
				lookAt.y,
				lookAt.z,
				target
		);

		Path path = playerConfig(owner.getUUID());
		Files.createDirectories(path.getParent());
		Files.writeString(path, json);
	}

	private static String generatedBotName(UUID playerId, String relayName) {
		String prefix = "pr_" + playerId.toString().replace("-", "").substring(0, 8) + "_";
		String sanitized = relayName.replaceAll("[^A-Za-z0-9_]", "_");
		int available = Math.max(1, 16 - prefix.length());
		return prefix + sanitized.substring(0, Math.min(available, sanitized.length()));
	}

	private static List<String> ticketSnapshot(TicketStorage storage, long chunkKey) {
		return storage.getTickets(chunkKey)
				.stream()
				.map(PearlRelayCommandGameTests::ticketDescription)
				.sorted()
				.toList();
	}

	private static String ticketDescription(Ticket ticket) {
		return ticket.getType() + ":" + ticket.getTicketLevel();
	}

	private static void assertLogCount(
			GameTestHelper context,
			LogCapture logs,
			String relayName,
			String token,
			int expected
	) {
		context.assertValueEqual(
				logs.count(relayName, token),
				(long) expected,
				"relay log count for " + token
		);
	}

	private static void assertAcceptedAndTerminalIdsMatch(
			GameTestHelper context,
			LogCapture logs,
			String relayName
	) {
		String acceptedId = logs.executionId(relayName, "action=accepted");
		String terminalId = logs.executionId(relayName, "action=terminal");
		context.assertValueEqual(terminalId, acceptedId, "accepted/terminal execution ID");
	}

	private static void assertCommandSucceeded(
			GameTestHelper context,
			String command,
			CapturingPlayer player
	) {
		CommandOutcome outcome = execute(player, command);
		context.assertValueEqual(outcome.error(), "", command + " error");
		context.assertValueEqual(outcome.result(), 1, command + " result");
	}

	private static CommandOutcome execute(CapturingPlayer player, String command) {
		player.clearMessages();
		return execute(player.createCommandSourceStack(), command);
	}

	private static CommandOutcome execute(CommandSourceStack source, String command) {
		try {
			int result = source.getServer()
					.getCommands()
					.getDispatcher()
					.execute(command, source);
			return new CommandOutcome(result, "");
		} catch (CommandSyntaxException exception) {
			return new CommandOutcome(0, exception.getRawMessage().getString());
		}
	}

	private static List<String> suggestions(CapturingPlayer player, String command) {
		CommandSourceStack source = player.createCommandSourceStack();
		var dispatcher = player.server().getCommands().getDispatcher();
		var parsed = dispatcher.parse(command, source);
		return dispatcher.getCompletionSuggestions(parsed)
				.join()
				.getList()
				.stream()
				.map(suggestion -> suggestion.getText())
				.toList();
	}

	@SuppressWarnings("resource")
	private static CapturingPlayer createPlayer(
			GameTestHelper context,
			String label,
			Vec3 relativePosition
	) {
		MinecraftServer server = context.getLevel().getServer();
		ServerLevel level = context.getLevel();
		UUID id = UUID.randomUUID();
		String name = ("prt_" + label + "_" + id.toString().substring(0, 6));
		GameProfile profile = new GameProfile(id, name.substring(0, Math.min(16, name.length())));
		CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
		CapturingPlayer player = new CapturingPlayer(
				server,
				level,
				cookie.gameProfile(),
				cookie.clientInformation()
		);
		Connection connection = new Connection(PacketFlow.SERVERBOUND);
		EmbeddedChannel channel = new EmbeddedChannel(connection);
		player.attachChannel(channel);
		server.getPlayerList().placeNewPlayer(connection, player, cookie);
		player.setPos(context.absoluteVec(relativePosition));
		player.clearMessages();
		return player;
	}

	@Override
	public void invokeTestMethod(GameTestHelper context, Method method) throws ReflectiveOperationException {
		context.setBlock(0, 0, 0, Blocks.AIR);
		method.invoke(this, context);
	}

	private record CommandOutcome(int result, String error) {
	}

	private static final class CapturingPlayer extends ServerPlayer implements AutoCloseable {
		private final MinecraftServer server;
		private final List<String> messages = new ArrayList<>();
		private EmbeddedChannel channel;
		private boolean closed;

		private CapturingPlayer(
				MinecraftServer server,
				ServerLevel level,
				GameProfile profile,
				ClientInformation clientInformation
		) {
			super(server, level, profile, clientInformation);
			this.server = server;
		}

		private MinecraftServer server() {
			return server;
		}

		private void attachChannel(EmbeddedChannel channel) {
			this.channel = channel;
		}

		@Override
		public void sendSystemMessage(Component message, boolean overlay) {
			messages.add(message.getString());
		}

		private void clearMessages() {
			messages.clear();
		}

		private boolean hasMessage(String expected) {
			return messages.contains(expected);
		}

		private boolean hasMessageContaining(String expected) {
			return messages.stream().anyMatch(message -> message.contains(expected));
		}

		@Override
		public void close() {
			if (closed) {
				return;
			}
			closed = true;
			if (server.getPlayerList().getPlayer(getUUID()) == this) {
				server.getPlayerList().remove(this);
			}
			if (channel != null) {
				channel.finishAndReleaseAll();
			}
		}
	}

	private static final class LogCapture extends AbstractAppender implements AutoCloseable {
		private final Logger logger;
		private final List<String> lines = new CopyOnWriteArrayList<>();
		private boolean closed;

		private LogCapture() {
			super(
					"PearlRelayGameTest-" + UUID.randomUUID(),
					null,
					PatternLayout.createDefaultLayout(),
					true
			);
			logger = (Logger) LogManager.getLogger("pearlrelay");
			start();
			logger.addAppender(this);
		}

		@Override
		public void append(LogEvent event) {
			lines.add(event.toImmutable().getMessage().getFormattedMessage());
		}

		private long count(String relayName, String token) {
			return lines.stream()
					.filter(line -> line.contains("event=relay_fire"))
					.filter(line -> line.contains(" relay=" + relayName + " "))
					.filter(line -> line.contains(token))
					.count();
		}

		private boolean hasLine(String relayName, String token) {
			return count(relayName, token) > 0;
		}

		private String executionId(String relayName, String action) {
			String line = lines.stream()
					.filter(candidate -> candidate.contains(" relay=" + relayName + " "))
					.filter(candidate -> candidate.contains(action))
					.findFirst()
					.orElse("");
			for (String part : line.split(" ")) {
				if (part.startsWith("execution_id=")) {
					return part.substring("execution_id=".length());
				}
			}
			return "";
		}

		@Override
		public void close() {
			if (closed) {
				return;
			}
			closed = true;
			logger.removeAppender(this);
			stop();
		}
	}
}
