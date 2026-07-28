package com.foxseventeen.pearlrelay.test;

import com.foxseventeen.pearlrelay.relay.RelayFailure;
import com.foxseventeen.pearlrelay.relay.RelayTargetResolver;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;

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

	@Override
	public void invokeTestMethod(GameTestHelper context, Method method) throws ReflectiveOperationException {
		context.setBlock(0, 0, 0, Blocks.AIR);
		method.invoke(this, context);
	}
}
