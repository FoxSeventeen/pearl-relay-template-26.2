package com.foxseventeen.pearlrelay.relay;

import com.foxseventeen.pearlrelay.config.RelayConfigManager.TargetFingerprint;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
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
		world.entityTickingChunks.clear();

		RelayTargetResolver.Result result = RelayTargetResolver.resolve(world, SPAWN, LOOK_AT);

		assertEquals(RelayFailure.SPAWN_CHUNK_UNLOADED, result.failure());
		assertEquals(0, world.clipCalls);
		assertFalse(world.spawnClearChecked);
	}

	@Test
	void rejectsFullButNonTickingNeighborChunk() {
		FakeWorld world = FakeWorld.ready();
		world.entityTickingChunks.clear();

		assertTrue(world.fullChunks.contains(ChunkPos.pack(0, 0)));
		RelayTargetResolver.Result result = RelayTargetResolver.resolve(world, SPAWN, LOOK_AT);

		assertEquals(RelayFailure.SPAWN_CHUNK_UNLOADED, result.failure());
		assertEquals(0, world.clipCalls);
	}

	@Test
	void serverChunkReadinessUsesEntityTickingState() {
		long[] checkedChunk = {Long.MIN_VALUE};

		boolean ready = RelayTargetResolver.isChunkReady(
				chunkPos -> {
					checkedChunk[0] = chunkPos;
					return false;
				},
				100,
				-7
		);

		assertFalse(ready);
		assertEquals(ChunkPos.pack(100, -7), checkedChunk[0]);
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
		assertEquals(0, world.blockIdCalls);
	}

	@Test
	void rejectsNegativeTargetChunkBoundaryWithoutWorldReads() {
		Vec3 spawn = new Vec3(-15.5D, 64.0D, 8.5D);
		Vec3 lookAcrossNegativeBoundary = new Vec3(-16.1D, 65.5D, 8.5D);
		FakeWorld world = FakeWorld.ready();
		world.fullChunks.clear();
		world.entityTickingChunks.clear();
		world.addReadyChunk(-1, 0);

		RelayTargetResolver.Result result =
				RelayTargetResolver.resolve(world, spawn, lookAcrossNegativeBoundary);

		assertEquals(RelayFailure.TARGET_CHUNK_UNLOADED, result.failure());
		assertFalse(world.spawnClearChecked);
		assertEquals(0, world.clipCalls);
		assertEquals(0, world.blockIdCalls);
	}

	@Test
	void rejectsSpawnBoundsStraddlingNegativeChunkBoundary() {
		Vec3 spawnOnBoundary = new Vec3(-16.0D, 64.0D, 8.5D);
		Vec3 nearbyLookAt = new Vec3(-15.5D, 65.5D, 8.5D);
		FakeWorld world = FakeWorld.ready();
		world.fullChunks.clear();
		world.entityTickingChunks.clear();
		world.addReadyChunk(-1, 0);

		RelayTargetResolver.Result result =
				RelayTargetResolver.resolve(world, spawnOnBoundary, nearbyLookAt);

		assertEquals(RelayFailure.SPAWN_CHUNK_UNLOADED, result.failure());
		assertFalse(world.spawnClearChecked);
		assertEquals(0, world.clipCalls);
		assertEquals(0, world.blockIdCalls);
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
		private final Set<Long> fullChunks = new HashSet<>();
		private final Set<Long> entityTickingChunks = new HashSet<>();
		private boolean spawnClear = true;
		private boolean spawnClearChecked;
		private int clipCalls;
		private int blockIdCalls;
		private BlockHitResult hit = new BlockHitResult(LOOK_AT, Direction.NORTH, TARGET_POS, false);

		private static FakeWorld ready() {
			FakeWorld world = new FakeWorld();
			world.addReadyChunk(0, 0);
			return world;
		}

		private void addReadyChunk(int chunkX, int chunkZ) {
			fullChunks.add(chunkKey(chunkX, chunkZ));
			entityTickingChunks.add(chunkKey(chunkX, chunkZ));
		}

		@Override
		public boolean isChunkLoaded(int chunkX, int chunkZ) {
			long chunkKey = chunkKey(chunkX, chunkZ);
			return fullChunks.contains(chunkKey) && entityTickingChunks.contains(chunkKey);
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
			blockIdCalls++;
			return "minecraft:note_block";
		}

		private static long chunkKey(int x, int z) {
			return ((long) x << 32) ^ (z & 0xffffffffL);
		}
	}
}
