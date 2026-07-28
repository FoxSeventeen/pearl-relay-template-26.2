package com.foxseventeen.pearlrelay.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.foxseventeen.pearlrelay.PearlRelayMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;

/**
 * Client boundary tests which must remain in the gametest source set.
 *
 * <p>Lifecycle pattern:
 * https://docs.fabricmc.net/develop/automatic-testing#writing-game-tests
 */
@SuppressWarnings("UnstableApiUsage")
public final class PearlRelayClientGameTests implements FabricClientGameTest {
	private static final int RIGHT_MOUSE_BUTTON = 1;
	private static final int TEST_REPETITIONS = testRepetitions();
	private static final int STASIS_STABILITY_TICKS = 40;
	private static final int SCREEN_WIDTH = 1280;
	private static final int SCREEN_HEIGHT = 720;
	private static final int GUI_SCALE = 2;
	private static final int FOV = 70;
	private static final int RENDER_DISTANCE = 5;
	private static final String LANGUAGE = "en_us";
	private static final String RELAY_NAME = "e2e";
	private static final AtomicBoolean PRODUCTION_HOOKS_INITIALIZED =
			new AtomicBoolean();

	@Override
	public void runTest(ClientGameTestContext context) {
		ProductionArtifactProbe.assertMainClassLoadedFromReleaseJar();
		configureVisualEvidence(context);
		initializeProductionHooksForInProcessDedicatedServer();
		verifyPearlRelayTeleport(context);
		context.waitFor(client -> client.level == null && client.player == null);
	}

	private static void initializeProductionHooksForInProcessDedicatedServer() {
		if (PRODUCTION_HOOKS_INITIALIZED.compareAndSet(false, true)) {
			// Fabric Client GameTest starts its dedicated server in this client
			// JVM. Fabric Loader therefore does not rerun the server-only mod
			// entrypoint; register the exact production initializer from the
			// gametest source set before server startup.
			new PearlRelayMod().onInitialize();
		}
	}

	private static void verifyPearlRelayTeleport(ClientGameTestContext context) {
		Properties properties = new Properties();
		properties.setProperty("max-players", "4");
		properties.setProperty("view-distance", "5");
		properties.setProperty("simulation-distance", "5");

		try (
				TestDedicatedServerContext dedicated =
						context.worldBuilder().createServer(properties);
				TestServerConnection connection = dedicated.connect();
				RelayLogProbe logs = new RelayLogProbe()
		) {
			connection.getClientLevel().waitForChunksDownload();
			assertControlledWorldOpened(context);
			assertProductionCommandRegistered(dedicated);
			verifyRealInputCreatesOwnedPearl(context, dedicated);
			verifyVanillaStasisAndRelease(context, dedicated);

			PearlStasisFixture fixture = new PearlStasisFixture();
			fixture.build(dedicated);
			awaitFixtureReady(context, dedicated, fixture);

			ClientCommandDriver commands = new ClientCommandDriver();
			FakePlayerLifecycleProbe fakePlayers = new FakePlayerLifecycleProbe();
			commands.clearMessages();
			UUID playerId = connectedPlayerId(dedicated);
			String expectedBotName = expectedBotName(playerId, RELAY_NAME);

			for (int repetition = 1; repetition <= TEST_REPETITIONS; repetition++) {
				// Reusing the generated bot name lets Carpet reuse its cached
				// offline profile after the first execution instead of doing one
				// external profile lookup per repetition.
				String relayName = RELAY_NAME;
				fixture.reset(dedicated);
				preparePlayerForSnapshotSave(
						context,
						dedicated,
						playerId
				);

				int saveMessageMark = commands.mark();
				commands.send(
						context,
						saveCommand(relayName)
				);
				String saveMessage = commands.awaitMessageContaining(
						context,
						saveMessageMark,
						"Saved relay: " + relayName,
						80
				);
				assertSavedBotName(saveMessage, expectedBotName, repetition);
				if (repetition == 1) {
					takeEvidenceScreenshot(
							context,
							"pearlrelay-relay-snapshot-saved"
					);
				}

				preparePlayerForFixtureThrow(context, dedicated);
				assertPearls(
						dedicated,
						playerId,
						0,
						repetition,
						"before relay stasis input"
				);
				throwFixturePearl(context);
				awaitSingleOwnedPearl(
						context,
						dedicated,
						playerId,
						repetition
				);
				fixture.moveOwnerToHoldingArea(dedicated, playerId);
				awaitPearlAscendingInStasis(
						context,
						dedicated,
						playerId,
						repetition
				);
				assertStableStasisWindow(
						context,
						dedicated,
						playerId,
						repetition
				);
				if (repetition == 1) {
					lookAtFromClient(
							context,
							PearlStasisFixture.FAKE_PLAYER_SPAWN.add(
									0.0D,
									1.62D,
									0.0D
							)
					);
					takeEvidenceScreenshot(
							context,
							"pearlrelay-relay-before-trigger"
					);
				}

				int logMark = logs.mark();
				int fireMessageMark = commands.mark();
				commands.send(context, "pearlrelay fire " + relayName);
				commands.awaitMessageContaining(
						context,
						fireMessageMark,
						"Relay queued: " + relayName,
						80
				);

				fakePlayers.awaitVisibleAndCorrect(
						context,
						dedicated,
						expectedBotName,
						PearlStasisFixture.FAKE_PLAYER_SPAWN,
						PearlStasisFixture.ACTIVATION_LOOK_AT,
						repetition
				);
				if (repetition == 1) {
					takeEvidenceScreenshot(
							context,
							"pearlrelay-relay-fake-player-visible"
					);
				}

				int activationCount = awaitRelayReleaseAndTeleport(
						context,
						dedicated,
						fixture,
						playerId,
						repetition
				);
				if (activationCount != 1) {
					throw new AssertionError(
							"Expected exactly one fixture activation at repetition "
									+ repetition + ", observed " + activationCount
					);
				}
				if (repetition == 1) {
					context.getInput().lookAt(0.0F, 45.0F);
					takeEvidenceScreenshot(
							context,
							"pearlrelay-relay-after-teleport"
					);
				}

				commands.awaitMessageContaining(
						context,
						fireMessageMark,
						"Relay '" + relayName + "' completed",
						120
				);
				awaitCompletedLifecycle(
						context,
						logs,
						logMark,
						relayName,
						repetition
				);
				fakePlayers.awaitRemoved(
						context,
						dedicated,
						expectedBotName,
						repetition
				);
				assertOnlyOwnerRemains(dedicated, playerId, repetition);

				int removeMessageMark = commands.mark();
				commands.send(
						context,
						"pearlrelay remove " + relayName
				);
				commands.awaitMessageContaining(
						context,
						removeMessageMark,
						"Removed relay: " + relayName,
						80
				);
			}
		}
	}

	private static void configureVisualEvidence(ClientGameTestContext context) {
		context.runOnClient(client -> {
			client.getWindow().setWindowed(SCREEN_WIDTH, SCREEN_HEIGHT);
			client.options.guiScale().set(GUI_SCALE);
			client.options.fov().set(FOV);
			client.options.renderDistance().set(RENDER_DISTANCE);
			client.options.simulationDistance().set(RENDER_DISTANCE);
			client.options.languageCode = LANGUAGE;
			client.getLanguageManager().setSelected(LANGUAGE);
		});
		context.waitFor(client ->
				client.getWindow().getScreenWidth() == SCREEN_WIDTH
						&& client.getWindow().getScreenHeight() == SCREEN_HEIGHT
						&& client.options.guiScale().get() == GUI_SCALE
						&& client.options.fov().get() == FOV
						&& client.options.renderDistance().get() == RENDER_DISTANCE
						&& client.options.simulationDistance().get() == RENDER_DISTANCE
						&& LANGUAGE.equals(client.options.languageCode)
						&& LANGUAGE.equals(
								client.getLanguageManager().getSelected()
						)
		);
	}

	private static void assertProductionCommandRegistered(
			TestServerContext serverContext
	) {
		boolean registered = serverContext.computeOnServer(server ->
				server.getCommands()
						.getDispatcher()
						.getRoot()
						.getChild("pearlrelay") != null
		);
		if (!registered) {
			throw new AssertionError(
					"Production Pearl Relay command was not registered"
			);
		}
	}

	private static UUID connectedPlayerId(TestServerContext serverContext) {
		return serverContext.computeOnServer(server -> {
			List<ServerPlayer> players = server.getPlayerList().getPlayers();
			if (players.size() != 1) {
				throw new AssertionError(
						"Expected one connected client player, found "
								+ players.size()
				);
			}
			return players.getFirst().getUUID();
		});
	}

	private static String saveCommand(String relayName) {
		return "pearlrelay save " + relayName;
	}

	private static String coordinates(Vec3 position) {
		return position.x + " " + position.y + " " + position.z;
	}

	private static int awaitRelayReleaseAndTeleport(
			ClientGameTestContext context,
			TestServerContext serverContext,
			PearlStasisFixture fixture,
			UUID playerId,
			int repetition
	) {
		boolean previouslyOpen = fixture.isOpen(serverContext);
		int activationCount = 0;
		PearlObservation lastPearl = null;
		Vec3 lastServerPosition = null;
		Vec3 lastClientPosition = null;

		for (int waitedTicks = 0; waitedTicks <= 160; waitedTicks++) {
			boolean currentlyOpen = fixture.isOpen(serverContext);
			if (previouslyOpen && !currentlyOpen) {
				activationCount++;
			} else if (!previouslyOpen && currentlyOpen) {
				throw new AssertionError(
						"Activation target reopened during relay execution at "
								+ "repetition " + repetition
				);
			}
			previouslyOpen = currentlyOpen;

			lastPearl = observePearls(serverContext, playerId);
			lastServerPosition = serverContext.computeOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayer(playerId);
				return player == null ? null : player.position();
			});
			lastClientPosition = context.computeOnClient(client ->
					client.player == null ? null : client.player.position()
			);
			if (activationCount == 1
					&& lastPearl.totalCount() == 0
					&& lastPearl.ownedCount() == 0
					&& lastServerPosition != null
					&& lastClientPosition != null
					&& PearlStasisFixture.DESTINATION_REGION.contains(
							lastServerPosition
					)
					&& PearlStasisFixture.DESTINATION_REGION.contains(
							lastClientPosition
					)) {
				return activationCount;
			}
			context.waitTick();
		}
		throw new AssertionError(
				"Relay did not release and teleport at repetition " + repetition
						+ ": activations=" + activationCount
						+ ", pearl=" + lastPearl
						+ ", serverPlayer=" + lastServerPosition
						+ ", clientPlayer=" + lastClientPosition
		);
	}

	private static void awaitCompletedLifecycle(
			ClientGameTestContext context,
			RelayLogProbe logs,
			int logMark,
			String relayName,
			int repetition
	) {
		RelayLogProbe.RelayEvents lastEvents = null;
		for (int waitedTicks = 0; waitedTicks <= 80; waitedTicks++) {
			lastEvents = logs.eventsSince(logMark, relayName);
			if (lastEvents.isOneCompletedLifecycle()) {
				return;
			}
			context.waitTick();
		}
		throw new AssertionError(
				"Expected one correlated accepted/completed lifecycle at "
						+ "repetition " + repetition + ": " + lastEvents
		);
	}

	private static void assertOnlyOwnerRemains(
			TestServerContext serverContext,
			UUID playerId,
			int repetition
	) {
		List<String> playerNames = serverContext.computeOnServer(server ->
				server.getPlayerList()
						.getPlayers()
						.stream()
						.map(player -> player.getGameProfile().name())
						.toList()
		);
		boolean ownerPresent = serverContext.computeOnServer(server ->
				server.getPlayerList().getPlayer(playerId) != null
		);
		if (!ownerPresent || playerNames.size() != 1) {
			throw new AssertionError(
					"Orphaned fake player after repetition " + repetition
							+ ": " + playerNames
			);
		}
	}

	private static void assertSavedBotName(
			String saveMessage,
			String expectedBotName,
			int repetition
	) {
		if (!saveMessage.contains("(bot=" + expectedBotName + ",")) {
			throw new AssertionError(
					"Save response did not expose the expected fake-player name at "
							+ "repetition " + repetition + ": " + saveMessage
			);
		}
	}

	private static String expectedBotName(UUID playerId, String relayName) {
		String shortUuid = playerId.toString().replace("-", "").substring(0, 8);
		String prefix = "pr_" + shortUuid + "_";
		int availableRelayNameLength = Math.max(1, 16 - prefix.length());
		String sanitizedRelayName = relayName.replaceAll("[^A-Za-z0-9_]", "_");
		if (sanitizedRelayName.isBlank()) {
			sanitizedRelayName = "bot";
		}
		return prefix + sanitizedRelayName.substring(
				0,
				Math.min(sanitizedRelayName.length(), availableRelayNameLength)
		);
	}

	private static void lookAtFromClient(
			ClientGameTestContext context,
			Vec3 target
	) {
		Vec3 eyePosition = context.computeOnClient(client -> {
			if (client.player == null) {
				throw new AssertionError("Cannot aim camera without a client player");
			}
			return client.player.getEyePosition();
		});
		Vec3 delta = target.subtract(eyePosition);
		double horizontalDistance = Math.sqrt(
				delta.x * delta.x + delta.z * delta.z
		);
		float yaw = (float) (
				Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0D
		);
		float pitch = (float) (
				-Math.toDegrees(Math.atan2(delta.y, horizontalDistance))
		);
		context.getInput().lookAt(yaw, pitch);
	}

	private static void preparePlayerForSnapshotSave(
			ClientGameTestContext context,
			TestServerContext serverContext,
			UUID playerId
	) {
		serverContext.runCommand(
				"tp @a " + coordinates(PearlStasisFixture.FAKE_PLAYER_SPAWN)
		);
		context.waitFor(client -> client.player != null
				&& client.player.position().distanceToSqr(
						PearlStasisFixture.FAKE_PLAYER_SPAWN
				) < 0.0001D);
		context.getInput().lookAt(180.0F, -80.0F);
		context.waitTick();
		ServerViewObservation awayView = null;
		for (int waitedTicks = 0; waitedTicks <= 80; waitedTicks++) {
			awayView = observeServerView(serverContext, playerId);
			if (awayView != null
					&& awayView.position().distanceToSqr(
							PearlStasisFixture.FAKE_PLAYER_SPAWN
					) < 0.0001D
					&& !awayView.hits(PearlStasisFixture.ACTIVATION_TARGET)) {
				break;
			}
			context.waitTick();
		}
		if (awayView == null
				|| awayView.position().distanceToSqr(
						PearlStasisFixture.FAKE_PLAYER_SPAWN
				) >= 0.0001D
				|| awayView.hits(PearlStasisFixture.ACTIVATION_TARGET)) {
			throw new AssertionError(
					"Client did not publish the deliberate look-away view: "
							+ awayView
			);
		}

		lookAtFromClient(context, PearlStasisFixture.ACTIVATION_LOOK_AT);

		ServerViewObservation targetView = null;
		for (int waitedTicks = 0; waitedTicks <= 120; waitedTicks++) {
			targetView = observeServerView(serverContext, playerId);
			if (targetView != null
					&& targetView.position().distanceToSqr(
							PearlStasisFixture.FAKE_PLAYER_SPAWN
					) < 0.0001D
					&& targetView.hits(PearlStasisFixture.ACTIVATION_TARGET)) {
				return;
			}
			context.waitTick();
		}
		throw new AssertionError(
				"Client did not publish the snapshot position and target view: "
						+ targetView
		);
	}

	private static ServerViewObservation observeServerView(
			TestServerContext serverContext,
			UUID playerId
	) {
		return serverContext.computeOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayer(playerId);
			if (player == null) {
				return null;
			}
			HitResult hit = player.pick(
					Player.DEFAULT_BLOCK_INTERACTION_RANGE,
					1.0F,
					false
			);
			BlockPos hitBlock = hit.getType() == HitResult.Type.BLOCK
					? ((BlockHitResult) hit).getBlockPos()
					: null;
			return new ServerViewObservation(
					player.position(),
					player.getYRot(),
					player.getXRot(),
					hit.getType(),
					hitBlock,
					hit.getLocation()
			);
		});
	}

	private static void takeEvidenceScreenshot(
			ClientGameTestContext context,
			String name
	) {
		// Fabric's screenshot call renders and writes synchronously without
		// advancing the GameTest tick, so evidence capture cannot change the
		// relay lifecycle outcome.
		Path screenshot = context.takeScreenshot(name);
		if (!Files.isRegularFile(screenshot)) {
			throw new AssertionError("Screenshot was not written: " + screenshot);
		}
	}

	private static void verifyVanillaStasisAndRelease(
			ClientGameTestContext context,
			TestServerContext serverContext
	) {
		PearlStasisFixture fixture = new PearlStasisFixture();
		fixture.build(serverContext);
		awaitFixtureReady(context, serverContext, fixture);

		for (int repetition = 1; repetition <= TEST_REPETITIONS; repetition++) {
			fixture.reset(serverContext);
			UUID playerId = preparePlayerForFixtureThrow(context, serverContext);
			assertPearls(serverContext, playerId, 0, repetition, "before stasis input");

			throwFixturePearl(context);
			awaitSingleOwnedPearl(context, serverContext, playerId, repetition);
			fixture.moveOwnerToHoldingArea(serverContext, playerId);

			awaitPearlAscendingInStasis(
					context,
					serverContext,
					playerId,
					repetition
			);
			assertStableStasisWindow(
					context,
					serverContext,
					playerId,
					repetition
			);
			if (repetition == 1) {
				context.takeScreenshot("pearlrelay-vanilla-stasis-before-release");
			}

			fixture.activateDirectly(serverContext, playerId);
			awaitPearlReleaseAndTeleport(
					context,
					serverContext,
					playerId,
					repetition
			);
		}
	}

	private static void awaitFixtureReady(
			ClientGameTestContext context,
			TestServerContext serverContext,
			PearlStasisFixture fixture
	) {
		for (int waitedTicks = 0; waitedTicks <= 80; waitedTicks++) {
			if (fixture.isReady(serverContext)) {
				return;
			}
			context.waitTick();
		}
		throw new AssertionError("Vanilla bubble-column fixture did not become ready");
	}

	private static void throwFixturePearl(ClientGameTestContext context) {
		context.getInput().lookAt(
				PearlStasisFixture.THROW_YAW,
				PearlStasisFixture.THROW_PITCH
		);
		// Let the client publish its post-teleport rotation, then keep one
		// physical gesture down across a client tick. The ender-pearl cooldown
		// guarantees this gesture can create at most one projectile.
		context.waitTick();
		context.getInput().holdMouseFor(RIGHT_MOUSE_BUTTON, 2);
	}

	private static UUID preparePlayerForFixtureThrow(
			ClientGameTestContext context,
			TestServerContext serverContext
	) {
		serverContext.runCommand(
				"tp @a "
						+ PearlStasisFixture.THROW_POSITION.x + " "
						+ PearlStasisFixture.THROW_POSITION.y + " "
						+ PearlStasisFixture.THROW_POSITION.z + " "
						+ PearlStasisFixture.THROW_YAW + " "
						+ PearlStasisFixture.THROW_PITCH
		);
		serverContext.runCommand(
				"item replace entity @a hotbar.0 with minecraft:ender_pearl 1"
		);
		context.waitFor(client -> client.player != null
				&& client.player.getInventory().getItem(0).is(Items.ENDER_PEARL)
				&& client.player.position().distanceToSqr(
						PearlStasisFixture.THROW_POSITION
				) < 0.01D);
		context.runOnClient(client -> client.player.getInventory().setSelectedSlot(0));
		context.waitForScreen(null);

		UUID playerId = serverContext.computeOnServer(server -> {
			List<ServerPlayer> players = server.getPlayerList().getPlayers();
			if (players.size() != 1) {
				throw new AssertionError(
						"Expected exactly one connected player, found " + players.size()
				);
			}
			ServerPlayer player = players.getFirst();
			player.getInventory().setSelectedSlot(0);
			return player.getUUID();
		});
		awaitPearlCooldownReady(context, serverContext);
		return playerId;
	}

	private static void awaitPearlAscendingInStasis(
			ClientGameTestContext context,
			TestServerContext serverContext,
			UUID playerId,
			int repetition
	) {
		for (int waitedTicks = 0; waitedTicks <= 160; waitedTicks++) {
			PearlObservation observation = observePearls(serverContext, playerId);
			if (observation.totalCount() != 1 || observation.ownedCount() != 1) {
				throw pearlMismatch(
						repetition,
						"while entering stasis",
						1,
						observation
				);
			}
			if (PearlStasisFixture.STASIS_REGION.contains(observation.ownedPosition())
					&& observation.ownedMovement().y > 0.0D) {
				return;
			}
			context.waitTick();
		}
		throw new AssertionError(
				"Pearl did not enter ascending stasis at repetition " + repetition
						+ ": " + observePearls(serverContext, playerId)
		);
	}

	private static void assertStableStasisWindow(
			ClientGameTestContext context,
			TestServerContext serverContext,
			UUID playerId,
			int repetition
	) {
		for (int stableTick = 1; stableTick <= STASIS_STABILITY_TICKS; stableTick++) {
			PearlObservation observation = observePearls(serverContext, playerId);
			if (observation.totalCount() != 1
					|| observation.ownedCount() != 1
					|| !PearlStasisFixture.STASIS_REGION.contains(
							observation.ownedPosition()
					)) {
				throw new AssertionError(
						"Pearl left stasis at repetition " + repetition
								+ ", stable tick " + stableTick
								+ ": " + observation
				);
			}
			Vec3 serverPlayerPosition = serverContext.computeOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayer(playerId);
				return player == null ? null : player.position();
			});
			if (serverPlayerPosition == null
					|| !PearlStasisFixture.HOLDING_REGION.contains(
							serverPlayerPosition
					)) {
				throw new AssertionError(
						"Player teleported before activation at repetition "
								+ repetition + ", stable tick " + stableTick
								+ ": " + serverPlayerPosition
				);
			}
			context.waitTick();
		}
	}

	private static void awaitPearlReleaseAndTeleport(
			ClientGameTestContext context,
			TestServerContext serverContext,
			UUID playerId,
			int repetition
	) {
		PearlObservation lastPearl = null;
		Vec3 lastServerPosition = null;
		Vec3 lastClientPosition = null;
		for (int waitedTicks = 0; waitedTicks <= 120; waitedTicks++) {
			lastPearl = observePearls(serverContext, playerId);
			lastServerPosition = serverContext.computeOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayer(playerId);
				return player == null ? null : player.position();
			});
			lastClientPosition = context.computeOnClient(client ->
					client.player == null ? null : client.player.position()
			);
			if (lastPearl.totalCount() == 0
					&& lastPearl.ownedCount() == 0
					&& lastServerPosition != null
					&& lastClientPosition != null
					&& PearlStasisFixture.DESTINATION_REGION.contains(
							lastServerPosition
					)
					&& PearlStasisFixture.DESTINATION_REGION.contains(
							lastClientPosition
					)) {
				return;
			}
			context.waitTick();
		}
		throw new AssertionError(
				"Pearl did not release and teleport at repetition " + repetition
						+ ": pearl=" + lastPearl
						+ ", serverPlayer=" + lastServerPosition
						+ ", clientPlayer=" + lastClientPosition
		);
	}

	private static void assertControlledWorldOpened(ClientGameTestContext context) {
		boolean worldOpened = context.computeOnClient(
				client -> client.level != null && client.player != null
		);
		if (!worldOpened) {
			throw new AssertionError("Controlled singleplayer world did not open");
		}
	}

	private static void verifyRealInputCreatesOwnedPearl(
			ClientGameTestContext context,
			TestServerContext serverContext
	) {
		serverContext.runCommand("gamemode creative @a");
		serverContext.runCommand("setblock 0 99 0 minecraft:stone");

		for (int repetition = 1; repetition <= TEST_REPETITIONS; repetition++) {
			UUID playerId = preparePlayerForThrow(context, serverContext);
			assertPearls(serverContext, playerId, 0, repetition, "before input");

			// TestInput reaches Minecraft's normal mouse handler; the test never
			// constructs a ThrownEnderpearl directly.
			// API: https://maven.fabricmc.net/docs/fabric-api-0.152.2%2B26.2/net/fabricmc/fabric/api/client/gametest/v1/TestInput.html
			context.getInput().lookAt(0.0F, 0.0F);
			context.getInput().pressMouse(RIGHT_MOUSE_BUTTON);

			awaitSingleOwnedPearl(context, serverContext, playerId, repetition);
			if (repetition == 1) {
				context.takeScreenshot("pearlrelay-owned-ender-pearl");
			}

			discardPearls(serverContext);
			awaitNoPearls(context, serverContext, playerId, repetition);
		}
	}

	private static UUID preparePlayerForThrow(
			ClientGameTestContext context,
			TestServerContext serverContext
	) {
		serverContext.runCommand("tp @a 0.5 100 0.5 0 0");
		serverContext.runCommand(
				"item replace entity @a hotbar.0 with minecraft:ender_pearl 1"
		);
		context.waitFor(client -> client.player != null
				&& client.player.getInventory().getItem(0).is(Items.ENDER_PEARL));
		context.runOnClient(client -> client.player.getInventory().setSelectedSlot(0));
		context.waitForScreen(null);

		UUID playerId = serverContext.computeOnServer(server -> {
			List<ServerPlayer> players = server.getPlayerList().getPlayers();
			if (players.size() != 1) {
				throw new AssertionError(
						"Expected exactly one connected player, found " + players.size()
				);
			}
			ServerPlayer player = players.getFirst();
			player.getInventory().setSelectedSlot(0);
			return player.getUUID();
		});
		awaitPearlCooldownReady(context, serverContext);
		return playerId;
	}

	private static void awaitPearlCooldownReady(
			ClientGameTestContext context,
			TestServerContext serverContext
	) {
		for (int waitedTicks = 0; waitedTicks <= 40; waitedTicks++) {
			boolean clientReady = context.computeOnClient(client -> {
				if (client.player == null) {
					return false;
				}
				return !client.player.getCooldowns().isOnCooldown(
						client.player.getInventory().getSelectedItem()
				);
			});
			boolean serverReady = serverContext.computeOnServer(server -> {
				List<ServerPlayer> players = server.getPlayerList().getPlayers();
				if (players.size() != 1) {
					return false;
				}
				ServerPlayer player = players.getFirst();
				return player.getInventory().getSelectedItem().is(Items.ENDER_PEARL)
						&& !player.getCooldowns().isOnCooldown(
								player.getInventory().getSelectedItem()
						);
			});
			if (clientReady && serverReady) {
				return;
			}
			context.waitTick();
		}
		throw new AssertionError("Ender pearl cooldown did not clear within 40 ticks");
	}

	private static void awaitSingleOwnedPearl(
			ClientGameTestContext context,
			TestServerContext serverContext,
			UUID playerId,
			int repetition
	) {
		PearlObservation lastObservation = null;
		for (int waitedTicks = 0; waitedTicks <= 40; waitedTicks++) {
			lastObservation = observePearls(serverContext, playerId);
			if (lastObservation.totalCount() == 1
					&& lastObservation.ownedCount() == 1) {
				return;
			}
			if (lastObservation.totalCount() > 1
					|| lastObservation.ownedCount() > 0) {
				break;
			}
			context.waitTick();
		}
		throw pearlMismatch(repetition, "after input", 1, lastObservation);
	}

	private static void awaitNoPearls(
			ClientGameTestContext context,
			TestServerContext serverContext,
			UUID playerId,
			int repetition
	) {
		PearlObservation lastObservation = null;
		for (int waitedTicks = 0; waitedTicks <= 10; waitedTicks++) {
			lastObservation = observePearls(serverContext, playerId);
			if (lastObservation.totalCount() == 0
					&& lastObservation.ownedCount() == 0) {
				return;
			}
			context.waitTick();
		}
		throw pearlMismatch(repetition, "after cleanup", 0, lastObservation);
	}

	private static void assertPearls(
			TestServerContext serverContext,
			UUID playerId,
			int expectedCount,
			int repetition,
			String phase
	) {
		PearlObservation observation = observePearls(serverContext, playerId);
		if (observation.totalCount() != expectedCount
				|| observation.ownedCount() != expectedCount) {
			throw pearlMismatch(repetition, phase, expectedCount, observation);
		}
	}

	private static AssertionError pearlMismatch(
			int repetition,
			String phase,
			int expectedCount,
			PearlObservation observation
	) {
		return new AssertionError(
				"Pearl mismatch at repetition " + repetition + " " + phase
						+ ": expected total/owned=" + expectedCount
						+ ", actual total=" + observation.totalCount()
						+ ", owned=" + observation.ownedCount()
		);
	}

	private static PearlObservation observePearls(
			TestServerContext serverContext,
			UUID playerId
	) {
		return serverContext.computeOnServer(server -> {
			List<? extends ThrownEnderpearl> pearls = server.overworld().getEntities(
					EntityTypeTest.forClass(ThrownEnderpearl.class),
					pearl -> true
			);
			int ownedCount = 0;
			Vec3 ownedPosition = null;
			Vec3 ownedMovement = null;
			for (ThrownEnderpearl pearl : pearls) {
				Entity owner = pearl.getOwner();
				if (owner != null && playerId.equals(owner.getUUID())) {
					ownedCount++;
					ownedPosition = pearl.position();
					ownedMovement = pearl.getDeltaMovement();
				}
			}
			return new PearlObservation(
					pearls.size(),
					ownedCount,
					ownedPosition,
					ownedMovement
			);
		});
	}

	private static void discardPearls(TestServerContext serverContext) {
		serverContext.runOnServer(server -> server.overworld().getEntities(
				EntityTypeTest.forClass(ThrownEnderpearl.class),
				pearl -> true
		).forEach(Entity::discard));
	}

	private static int testRepetitions() {
		String configured = System.getenv("PEARLRELAY_CLIENT_TEST_REPETITIONS");
		if (configured == null) {
			return 20;
		}
		int repetitions = Integer.parseInt(configured);
		if (repetitions < 1) {
			throw new IllegalArgumentException(
					"PEARLRELAY_CLIENT_TEST_REPETITIONS must be positive"
			);
		}
		return repetitions;
	}

	private record PearlObservation(
			int totalCount,
			int ownedCount,
			Vec3 ownedPosition,
			Vec3 ownedMovement
	) {
	}

	private record ServerViewObservation(
			Vec3 position,
			float yaw,
			float pitch,
			HitResult.Type hitType,
			BlockPos hitBlock,
			Vec3 hitLocation
	) {
		private boolean hits(BlockPos target) {
			return hitType == HitResult.Type.BLOCK && target.equals(hitBlock);
		}
	}
}
