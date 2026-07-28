package com.foxseventeen.pearlrelay.test;

import java.util.UUID;

import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Test-only vanilla bubble-column ender-pearl stasis chamber.
 */
@SuppressWarnings("UnstableApiUsage")
final class PearlStasisFixture {
	static final Vec3 THROW_POSITION = new Vec3(8.5D, 124.0D, 8.5D);
	static final float THROW_YAW = 0.0F;
	static final float THROW_PITCH = 90.0F;
	static final Vec3 HOLDING_POSITION = new Vec3(-8.5D, 101.0D, -8.5D);
	static final Vec3 FAKE_PLAYER_SPAWN = new Vec3(8.5D, 121.0D, 5.5D);
	static final BlockPos ACTIVATION_TARGET = new BlockPos(8, 121, 8);
	static final Vec3 ACTIVATION_LOOK_AT = new Vec3(8.5D, 121.5D, 8.99D);
	static final AABB STASIS_REGION = new AABB(
			8.0D,
			118.0D,
			8.0D,
			9.0D,
			126.0D,
			9.0D
	);
	static final AABB HOLDING_REGION = new AABB(
			-9.0D,
			100.75D,
			-9.0D,
			-8.0D,
			103.0D,
			-8.0D
	);
	static final AABB DESTINATION_REGION = new AABB(
			7.5D,
			117.0D,
			7.5D,
			9.5D,
			126.0D,
			9.5D
	);

	private static final int BUBBLE_COLUMN_BOTTOM_Y = 91;
	private static final int BUBBLE_COLUMN_TOP_Y = 120;

	void build(TestServerContext serverContext) {
		serverContext.runCommand("difficulty peaceful");
		serverContext.runCommand("fill -12 80 -12 12 130 12 minecraft:air");
		serverContext.runCommand("fill -10 100 -10 -7 100 -7 minecraft:smooth_stone");
		serverContext.runCommand("fill 7 90 7 9 120 9 minecraft:glass");
		serverContext.runCommand("fill 8 90 8 8 120 8 minecraft:air");
		serverContext.runCommand("setblock 8 90 8 minecraft:soul_sand");
		serverContext.runCommand("fill 8 91 8 8 120 8 minecraft:water");
		serverContext.runCommand("fill 7 120 4 9 120 7 minecraft:smooth_stone");
		open(serverContext);
	}

	boolean isReady(TestServerContext serverContext) {
		return serverContext.computeOnServer(server -> {
			for (int y = BUBBLE_COLUMN_BOTTOM_Y; y <= BUBBLE_COLUMN_TOP_Y; y++) {
				if (!server.overworld().getBlockState(new BlockPos(8, y, 8)).is(Blocks.BUBBLE_COLUMN)) {
					return false;
				}
			}
			return isOpen(server);
		});
	}

	void reset(TestServerContext serverContext) {
		open(serverContext);
		serverContext.runCommand("kill @e[type=minecraft:ender_pearl]");
	}

	void moveOwnerToHoldingArea(TestServerContext serverContext, UUID ownerId) {
		serverContext.runOnServer(server -> {
			ServerPlayer owner = server.getPlayerList().getPlayer(ownerId);
			if (owner == null) {
				throw new AssertionError("Connected pearl owner disappeared");
			}
			owner.teleportTo(
					server.overworld(),
					HOLDING_POSITION.x,
					HOLDING_POSITION.y,
					HOLDING_POSITION.z,
					java.util.Set.of(),
					0.0F,
					0.0F,
					true
			);
		});
	}

	void activateDirectly(TestServerContext serverContext, UUID ownerId) {
		serverContext.runOnServer(server -> {
			ServerPlayer owner = server.getPlayerList().getPlayer(ownerId);
			if (owner == null) {
				throw new AssertionError("Connected pearl owner disappeared");
			}
			var level = server.overworld();
			var state = level.getBlockState(ACTIVATION_TARGET);
			if (!state.is(Blocks.OAK_TRAPDOOR)
					|| !state.getValue(BlockStateProperties.OPEN)) {
				throw new AssertionError("Expected an open oak trapdoor before activation");
			}
			state.useWithoutItem(
					level,
					owner,
					new BlockHitResult(
							ACTIVATION_LOOK_AT,
							Direction.NORTH,
							ACTIVATION_TARGET,
							false
					)
			);
			if (level.getBlockState(ACTIVATION_TARGET).getValue(BlockStateProperties.OPEN)) {
				throw new AssertionError("Direct activation did not close the trapdoor");
			}
		});
	}

	boolean isOpen(TestServerContext serverContext) {
		return serverContext.computeOnServer(PearlStasisFixture::isOpen);
	}

	private void open(TestServerContext serverContext) {
		serverContext.runOnServer(server -> server.overworld().setBlockAndUpdate(
				ACTIVATION_TARGET,
				Blocks.OAK_TRAPDOOR.defaultBlockState()
						.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
						.setValue(BlockStateProperties.OPEN, true)
						.setValue(BlockStateProperties.POWERED, false)
						.setValue(BlockStateProperties.WATERLOGGED, false)
		));
	}

	private static boolean isOpen(net.minecraft.server.MinecraftServer server) {
		var state = server.overworld().getBlockState(ACTIVATION_TARGET);
		return state.is(Blocks.OAK_TRAPDOOR)
				&& state.getValue(BlockStateProperties.OPEN);
	}
}
