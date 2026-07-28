package com.foxseventeen.pearlrelay.relay;

import com.foxseventeen.pearlrelay.config.RelayConfigManager.TargetFingerprint;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelayTargetResolverTest {
	private static final Vec3 SPAWN = new Vec3(8.5, 64.0, 8.5);
	private static final Vec3 LOOK_AT = new Vec3(8.5, 65.5, 12.0);
	private static final BlockPos TARGET_POS = new BlockPos(8, 65, 12);

	@Test
	void resolvesLoadedReachableTargetFingerprint() {
		FakeWorld world = FakeWorld.ready();

		RelayTargetResolver.Result result = RelayTargetResolver.resolve(world, SPAWN, LOOK_AT);

		assertTrue(result.isSuccess());
		assertEquals(new TargetFingerprint(8, 65, 12, "minecraft:note_block"), result.target());
		assertNull(result.failure());
		assertEquals(1, world.clipCalls);
	}

	@Test
	void rejectsUnloadedSpawnChunkBeforeReadingWorld() {
		FakeWorld world = FakeWorld.ready();
		world.loadedChunks.clear();

		RelayTargetResolver.Result result = RelayTargetResolver.resolve(world, SPAWN, LOOK_AT);

		assertEquals(RelayFailure.SPAWN_CHUNK_UNLOADED, result.failure());
		assertEquals(0, world.clipCalls);
		assertFalse(world.spawnClearChecked);
	}

	@Test
	void rejectsUnloadedTargetChunkWithoutRaycast() {
		Vec3 spawnNearChunkBoundary = new Vec3(15.5, 64.0, 8.5);
		Vec3 lookAcrossChunkBoundary = new Vec3(16.0, 65.5, 8.5);
		FakeWorld world = FakeWorld.ready();

		RelayTargetResolver.Result result =
				RelayTargetResolver.resolve(world, spawnNearChunkBoundary, lookAcrossChunkBoundary);

		assertEquals(RelayFailure.TARGET_CHUNK_UNLOADED, result.failure());
		assertEquals(0, world.clipCalls);
	}

	@Test
	void rejectsBlockedSpawn() {
		FakeWorld world = FakeWorld.ready();
		world.spawnClear = false;

		RelayTargetResolver.Result result = RelayTargetResolver.resolve(world, SPAWN, LOOK_AT);

		assertEquals(RelayFailure.SPAWN_POSITION_BLOCKED, result.failure());
		assertEquals(0, world.clipCalls);
	}

	@Test
	void rejectsMissedTarget() {
		FakeWorld world = FakeWorld.ready();
		world.hit = BlockHitResult.miss(LOOK_AT, Direction.UP, BlockPos.containing(LOOK_AT));

		RelayTargetResolver.Result result = RelayTargetResolver.resolve(world, SPAWN, LOOK_AT);

		assertEquals(RelayFailure.TARGET_UNREACHABLE, result.failure());
	}

	@Test
	void rejectsBlockHitBeforeRequestedTarget() {
		FakeWorld world = FakeWorld.ready();
		world.hit = new BlockHitResult(
				new Vec3(8.5, 65.5, 11.0),
				Direction.NORTH,
				new BlockPos(8, 65, 11),
				false
		);

		RelayTargetResolver.Result result = RelayTargetResolver.resolve(world, SPAWN, LOOK_AT);

		assertEquals(RelayFailure.TARGET_UNREACHABLE, result.failure());
	}

	@Test
	void rejectsLookTargetOutsideSurvivalReach() {
		FakeWorld world = FakeWorld.ready();
		Vec3 tooFar = new Vec3(8.5, 65.62, 20.0);

		RelayTargetResolver.Result result = RelayTargetResolver.resolve(world, SPAWN, tooFar);

		assertEquals(RelayFailure.TARGET_UNREACHABLE, result.failure());
		assertEquals(0, world.clipCalls);
	}

	private static final class FakeWorld implements RelayTargetResolver.WorldView {
		private final Set<Long> loadedChunks = new HashSet<>();
		private boolean spawnClear = true;
		private boolean spawnClearChecked;
		private int clipCalls;
		private BlockHitResult hit = new BlockHitResult(LOOK_AT, Direction.NORTH, TARGET_POS, false);

		private static FakeWorld ready() {
			FakeWorld world = new FakeWorld();
			world.loadedChunks.add(chunkKey(0, 0));
			return world;
		}

		@Override
		public boolean isChunkLoaded(int chunkX, int chunkZ) {
			return loadedChunks.contains(chunkKey(chunkX, chunkZ));
		}

		@Override
		public boolean isSpawnClear(AABB bounds) {
			spawnClearChecked = true;
			return spawnClear;
		}

		@Override
		public BlockHitResult clip(Vec3 from, Vec3 to) {
			clipCalls++;
			return hit;
		}

		@Override
		public String blockId(BlockPos pos) {
			return "minecraft:note_block";
		}

		private static long chunkKey(int x, int z) {
			return ((long) x << 32) ^ (z & 0xffffffffL);
		}
	}
}
