package com.foxseventeen.pearlrelay.test;

import com.foxseventeen.pearlrelay.PearlRelayMod;
import com.foxseventeen.pearlrelay.config.RelayConfigManager.RelayDefinition;
import com.foxseventeen.pearlrelay.config.RelayConfigManager.TargetFingerprint;
import com.foxseventeen.pearlrelay.relay.CarpetRelayRuntime;
import com.foxseventeen.pearlrelay.relay.RelayEventReporter;
import com.foxseventeen.pearlrelay.relay.RelayExecutionManager;
import com.foxseventeen.pearlrelay.relay.RelayFailure;
import com.foxseventeen.pearlrelay.relay.RelayPreflight;
import com.foxseventeen.pearlrelay.relay.RelayTargetResolver;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public final class PearlRelayGameTests implements CustomTestMethodInvoker {
	@GameTest
	public void testHarnessStartsServer(GameTestHelper context) {
		context.assertBlockPresent(Blocks.AIR, 0, 0, 0);
		context.succeed();
	}

	@GameTest
	public void resolvesReachableTargetWithoutCreatingPlayer(GameTestHelper context) {
		BlockPos target = new BlockPos(0, 1, 3);
		context.setBlock(target, Blocks.NOTE_BLOCK);
		int playerCountBefore = context.getLevel().getServer().getPlayerCount();

		RelayTargetResolver.Result result = resolve(context, target);

		BlockPos absoluteTarget = context.absolutePos(target);
		context.assertTrue(result.isSuccess(), "Expected reachable note block to resolve");
		context.assertValueEqual(result.target().x(), absoluteTarget.getX(), "target x");
		context.assertValueEqual(result.target().y(), absoluteTarget.getY(), "target y");
		context.assertValueEqual(result.target().z(), absoluteTarget.getZ(), "target z");
		context.assertValueEqual(result.target().blockId(), "minecraft:note_block", "target block id");
		context.assertValueEqual(
				context.getLevel().getServer().getPlayerCount(),
				playerCountBefore,
				"target resolution must not create a fake player"
		);
		context.succeed();
	}

	@GameTest
	public void preflightAcceptsOneOwnedPearlInTargetChunk(GameTestHelper context) {
		BlockPos target = new BlockPos(0, 1, 3);
		context.setBlock(target, Blocks.NOTE_BLOCK);
		Player owner = context.makeMockServerPlayer(GameType.SURVIVAL);
		spawnPearl(context, owner, target);

		RelayPreflight.Result result = preflight(context, owner, target, "minecraft:note_block");

		context.assertTrue(result.isSuccess(), "Expected one owned pearl to pass preflight");
		context.assertValueEqual(result.request().ownedPearlCount(), 1, "owned pearl count");
		context.succeed();
	}

	@GameTest
	public void preflightIgnoresAnotherPlayersPearl(GameTestHelper context) {
		BlockPos target = new BlockPos(0, 1, 3);
		context.setBlock(target, Blocks.NOTE_BLOCK);
		Player owner = context.makeMockServerPlayer(GameType.SURVIVAL);
		Player otherOwner = context.makeMockServerPlayer(GameType.SURVIVAL);
		spawnPearl(context, otherOwner, target);

		RelayPreflight.Result result = preflight(context, owner, target, "minecraft:note_block");

		context.assertValueEqual(
				result.failure(),
				RelayFailure.OWNED_PEARL_NOT_FOUND,
				"other-player pearl failure"
		);
		context.succeed();
	}

	@GameTest
	public void preflightAcceptsMultipleOwnedPearls(GameTestHelper context) {
		BlockPos target = new BlockPos(0, 1, 3);
		context.setBlock(target, Blocks.NOTE_BLOCK);
		Player owner = context.makeMockServerPlayer(GameType.SURVIVAL);
		spawnPearl(context, owner, target);
		spawnPearl(context, owner, target);

		RelayPreflight.Result result = preflight(context, owner, target, "minecraft:note_block");

		context.assertTrue(result.isSuccess(), "Expected multiple owned pearls to pass preflight");
		context.assertValueEqual(result.request().ownedPearlCount(), 2, "owned pearl count");
		context.succeed();
	}

	@GameTest
	public void preflightRejectsChangedTargetBeforePearlCheck(GameTestHelper context) {
		BlockPos target = new BlockPos(0, 1, 3);
		context.setBlock(target, Blocks.LEVER);
		Player owner = context.makeMockServerPlayer(GameType.SURVIVAL);
		String rejectedBot = "pr_gametest";

		RelayPreflight.Result result = preflight(context, owner, target, "minecraft:note_block");

		context.assertValueEqual(result.failure(), RelayFailure.TARGET_BLOCK_CHANGED, "changed block failure");
		context.assertTrue(
				context.getLevel().getServer().getPlayerList().getPlayerByName(rejectedBot) == null,
				"Rejected preflight must not create its fake player"
		);
		context.succeed();
	}

	@GameTest
	public void preflightAllowsSameBlockTypeAfterStateChange(GameTestHelper context) {
		BlockPos target = new BlockPos(0, 1, 3);
		context.setBlock(
				target,
				Blocks.REDSTONE_LAMP.defaultBlockState().setValue(BlockStateProperties.LIT, true)
		);
		Player owner = context.makeMockServerPlayer(GameType.SURVIVAL);
		spawnPearl(context, owner, target);

		RelayPreflight.Result result = preflight(context, owner, target, "minecraft:redstone_lamp");

		context.assertTrue(result.isSuccess(), "Expected block-state-only change to remain valid");
		context.succeed();
	}

	@GameTest
	public void preflightRejectsUnavailableDimension(GameTestHelper context) {
		BlockPos target = context.absolutePos(new BlockPos(0, 1, 3));
		Player owner = context.makeMockServerPlayer(GameType.SURVIVAL);
		RelayDefinition relay = new RelayDefinition(
				"pr_gametest",
				Identifier.parse("pearlrelay:missing_dimension"),
				context.absoluteVec(new Vec3(0.5D, 0.0D, 0.5D)),
				Vec3.atCenterOf(target),
				new TargetFingerprint(target.getX(), target.getY(), target.getZ(), "minecraft:note_block")
		);

		RelayPreflight.Result result =
				RelayPreflight.check(context.getLevel().getServer(), relay, owner.getUUID());

		context.assertValueEqual(
				result.failure(),
				RelayFailure.DIMENSION_UNAVAILABLE,
				"unavailable dimension failure"
		);
		context.succeed();
	}

	@GameTest(maxTicks = 80)
	public void executionRightClicksNoteOnceAndCleansFakePlayer(GameTestHelper context) {
		BlockPos spawnFloor = new BlockPos(0, -1, 0);
		BlockPos target = new BlockPos(0, 1, 3);
		context.setBlock(spawnFloor, Blocks.STONE);
		context.setBlock(target, Blocks.NOTE_BLOCK);

		BlockPos absoluteTarget = context.absolutePos(target);
		RelayDefinition relay = new RelayDefinition(
				"pr_gametest_exec",
				context.getLevel().dimension().identifier(),
				context.absoluteVec(new Vec3(0.5D, 0.0D, 0.5D)),
				context.absoluteVec(Vec3.atCenterOf(target)),
				new TargetFingerprint(
						absoluteTarget.getX(),
						absoluteTarget.getY(),
						absoluteTarget.getZ(),
						"minecraft:note_block"
				)
		);
		RelayPreflight.ValidatedRelay validated =
				new RelayPreflight.ValidatedRelay(context.getLevel(), relay, 1);
		List<RelayExecutionManager.TerminalResult> terminals = new ArrayList<>();
		RelayEventReporter reporter = new RelayEventReporter(PearlRelayMod.LOGGER);
		RelayExecutionManager manager = new RelayExecutionManager(
				new CarpetRelayRuntime(),
				new RelayExecutionManager.Timings(40, 2, 8, 10),
				reporter::accepted,
				terminal -> {
					reporter.terminal(terminal);
					terminals.add(terminal);
				}
		);

		RelayExecutionManager.StartResult start = manager.start(
				new RelayExecutionManager.ExecutionRequest(
						java.util.UUID.randomUUID(),
						"gametest",
						context.makeMockServerPlayer(GameType.SURVIVAL).getUUID(),
						validated
				)
		);
		context.assertTrue(start.isAccepted(), "Expected Carpet execution to start");
		for (int tick = 1; tick <= 60; tick++) {
			context.runAtTickTime(tick, manager::tick);
		}
		context.runAtTickTime(61, () -> {
			int activeBeforeShutdown = manager.activeCount();
			boolean fakePlayerPresent = context.getLevel().getServer()
					.getPlayerList()
					.getPlayerByName(relay.bot()) != null;
			int terminalCount = terminals.size();
			boolean terminalSuccess = terminalCount == 1 && terminals.getFirst().success();
			manager.shutdown();

			context.assertBlockProperty(target, net.minecraft.world.level.block.NoteBlock.NOTE, 1);
			context.assertValueEqual(activeBeforeShutdown, 0, "active executions after cleanup");
			context.assertFalse(fakePlayerPresent, "Fake player must be absent after cleanup");
			context.assertValueEqual(terminalCount, 1, "terminal result count");
			context.assertTrue(terminalSuccess, "Expected successful terminal result");
			context.succeed();
		});
	}

	@GameTest
	public void rejectsObstructedTarget(GameTestHelper context) {
		BlockPos target = new BlockPos(0, 1, 3);
		context.setBlock(new BlockPos(0, 1, 2), Blocks.STONE);
		context.setBlock(target, Blocks.NOTE_BLOCK);

		RelayTargetResolver.Result result = resolve(context, target);

		context.assertValueEqual(result.failure(), RelayFailure.TARGET_UNREACHABLE, "obstructed target failure");
		context.succeed();
	}

	@GameTest
	public void rejectsAirTarget(GameTestHelper context) {
		BlockPos target = new BlockPos(0, 1, 3);

		RelayTargetResolver.Result result = resolve(context, target);

		context.assertValueEqual(result.failure(), RelayFailure.TARGET_UNREACHABLE, "air target failure");
		context.succeed();
	}

	@GameTest
	public void rejectsTargetOutsideSurvivalReach(GameTestHelper context) {
		BlockPos target = new BlockPos(0, 1, 6);
		context.setBlock(target, Blocks.NOTE_BLOCK);

		RelayTargetResolver.Result result = resolve(context, target);

		context.assertValueEqual(result.failure(), RelayFailure.TARGET_UNREACHABLE, "out-of-reach target failure");
		context.succeed();
	}

	@GameTest
	public void fingerprintsBlockTypeWithoutMutableState(GameTestHelper context) {
		BlockPos target = new BlockPos(0, 1, 3);
		context.setBlock(target, Blocks.REDSTONE_LAMP.defaultBlockState());
		RelayTargetResolver.Result unlit = resolve(context, target);

		context.setBlock(
				target,
				Blocks.REDSTONE_LAMP.defaultBlockState().setValue(BlockStateProperties.LIT, true)
		);
		RelayTargetResolver.Result lit = resolve(context, target);

		context.assertTrue(unlit.isSuccess(), "Expected unlit lamp to resolve");
		context.assertTrue(lit.isSuccess(), "Expected lit lamp to resolve");
		context.assertValueEqual(unlit.target().blockId(), "minecraft:redstone_lamp", "unlit block id");
		context.assertValueEqual(lit.target().blockId(), unlit.target().blockId(), "state-independent block id");
		context.succeed();
	}

	private static RelayTargetResolver.Result resolve(GameTestHelper context, BlockPos relativeTarget) {
		Vec3 spawn = context.absoluteVec(new Vec3(0.5D, 0.0D, 0.5D));
		Vec3 lookAt = context.absoluteVec(Vec3.atCenterOf(relativeTarget));
		return RelayTargetResolver.resolve(context.getLevel(), spawn, lookAt);
	}

	private static RelayPreflight.Result preflight(
			GameTestHelper context,
			Player owner,
			BlockPos relativeTarget,
			String savedBlockId
	) {
		BlockPos target = context.absolutePos(relativeTarget);
		Vec3 spawn = context.absoluteVec(new Vec3(0.5D, 0.0D, 0.5D));
		Vec3 lookAt = context.absoluteVec(Vec3.atCenterOf(relativeTarget));
		RelayDefinition relay = new RelayDefinition(
				"pr_gametest",
				context.getLevel().dimension().identifier(),
				spawn,
				lookAt,
				new TargetFingerprint(target.getX(), target.getY(), target.getZ(), savedBlockId)
		);
		return RelayPreflight.check(context.getLevel().getServer(), relay, owner.getUUID());
	}

	private static void spawnPearl(GameTestHelper context, Player owner, BlockPos relativeTarget) {
		ThrownEnderpearl pearl = new ThrownEnderpearl(
				context.getLevel(),
				owner,
				Items.ENDER_PEARL.getDefaultInstance()
		);
		pearl.setPos(context.absoluteVec(Vec3.atCenterOf(relativeTarget)));
		context.getLevel().addFreshEntity(pearl);
	}

	@Override
	public void invokeTestMethod(GameTestHelper context, Method method) throws ReflectiveOperationException {
		context.setBlock(0, 0, 0, Blocks.AIR);
		method.invoke(this, context);
	}
}
