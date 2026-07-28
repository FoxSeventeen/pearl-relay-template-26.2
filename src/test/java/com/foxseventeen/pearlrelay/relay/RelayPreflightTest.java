package com.foxseventeen.pearlrelay.relay;

import com.foxseventeen.pearlrelay.config.RelayConfigManager.RelayDefinition;
import com.foxseventeen.pearlrelay.config.RelayConfigManager.TargetFingerprint;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelayPreflightTest {
	private static final UUID OWNER = UUID.fromString("11111111-2222-3333-4444-555555555555");
	private static final UUID OTHER_OWNER = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
	private static final Vec3 SPAWN = new Vec3(8.5D, 64.0D, 8.5D);
	private static final Vec3 LOOK_AT = new Vec3(8.5D, 65.5D, 12.0D);
	private static final BlockPos TARGET_POS = new BlockPos(8, 65, 12);

	@Test
	void rejectsLegacyRelayBeforeWorldAccess() {
		FakeWorld world = FakeWorld.ready();
		RelayDefinition legacy = new RelayDefinition(
				"pr_11111111_home",
				Identifier.parse("minecraft:overworld"),
				SPAWN,
				LOOK_AT,
				null
		);

		RelayPreflight.Decision result = RelayPreflight.check(world, (owner, target) -> 1, legacy, OWNER);

		assertEquals(RelayFailure.RELAY_REQUIRES_RESAVE, result.failure());
		assertEquals(0, world.readCount);
	}

	@Test
	void rejectsUnloadedSavedTargetChunkBeforeBlockRead() {
		FakeWorld world = FakeWorld.ready();
		world.entityTickingChunks.clear();

		RelayPreflight.Decision result = RelayPreflight.check(world, (owner, target) -> 1, relay(), OWNER);

		assertEquals(RelayFailure.TARGET_CHUNK_UNLOADED, result.failure());
		assertEquals(0, world.blockIdCalls);
		assertEquals(0, world.clipCalls);
	}

	@Test
	void rejectsChangedTargetBlockBeforeRaycast() {
		FakeWorld world = FakeWorld.ready();
		world.blockId = "minecraft:air";

		RelayPreflight.Decision result = RelayPreflight.check(world, (owner, target) -> 1, relay(), OWNER);

		assertEquals(RelayFailure.TARGET_BLOCK_CHANGED, result.failure());
		assertEquals(0, world.clipCalls);
	}

	@Test
	void propagatesSpawnValidationFailure() {
		FakeWorld world = FakeWorld.ready();
		world.spawnClear = false;

		RelayPreflight.Decision result = RelayPreflight.check(world, (owner, target) -> 1, relay(), OWNER);

		assertEquals(RelayFailure.SPAWN_POSITION_BLOCKED, result.failure());
	}

	@Test
	void rejectsRayThatNoLongerHitsSavedTarget() {
		FakeWorld world = FakeWorld.ready();
		world.hit = new BlockHitResult(
				new Vec3(8.5D, 65.5D, 11.0D),
				Direction.NORTH,
				new BlockPos(8, 65, 11),
				false
		);

		RelayPreflight.Decision result = RelayPreflight.check(world, (owner, target) -> 1, relay(), OWNER);

		assertEquals(RelayFailure.TARGET_UNREACHABLE, result.failure());
	}

	@Test
	void rejectsWhenOnlyAnotherPlayersPearlExists() {
		FakeWorld world = FakeWorld.ready();

		RelayPreflight.Decision result = RelayPreflight.check(
				world,
				(owner, target) -> owner.equals(OTHER_OWNER) ? 1 : 0,
				relay(),
				OWNER
		);

		assertEquals(RelayFailure.OWNED_PEARL_NOT_FOUND, result.failure());
	}

	@Test
	void acceptsOneOwnedPearl() {
		FakeWorld world = FakeWorld.ready();

		RelayPreflight.Decision result = RelayPreflight.check(world, (owner, target) -> 1, relay(), OWNER);

		assertTrue(result.isSuccess());
		assertEquals(1, result.ownedPearlCount());
		assertNull(result.failure());
	}

	@Test
	void acceptsMultipleOwnedPearlsWithoutSelectingOne() {
		FakeWorld world = FakeWorld.ready();

		RelayPreflight.Decision result = RelayPreflight.check(world, (owner, target) -> 3, relay(), OWNER);

		assertTrue(result.isSuccess());
		assertEquals(3, result.ownedPearlCount());
	}

	@Test
	void checksPearlsOnlyAfterAllWorldValidationPasses() {
		FakeWorld world = FakeWorld.ready();
		world.blockId = "minecraft:lever";
		boolean[] pearlCounterCalled = {false};

		RelayPreflight.Decision result = RelayPreflight.check(
				world,
				(owner, target) -> {
					pearlCounterCalled[0] = true;
					return 1;
				},
				relay(),
				OWNER
		);

		assertEquals(RelayFailure.TARGET_BLOCK_CHANGED, result.failure());
		assertFalse(pearlCounterCalled[0]);
	}

	private static RelayDefinition relay() {
		return new RelayDefinition(
				"pr_11111111_home",
				Identifier.parse("minecraft:overworld"),
				SPAWN,
				LOOK_AT,
				new TargetFingerprint(
						TARGET_POS.getX(),
						TARGET_POS.getY(),
						TARGET_POS.getZ(),
						"minecraft:note_block"
				)
		);
	}

	private static final class FakeWorld implements RelayTargetResolver.WorldView {
		private final Set<Long> fullChunks = new HashSet<>();
		private final Set<Long> entityTickingChunks = new HashSet<>();
		private boolean spawnClear = true;
		private String blockId = "minecraft:note_block";
		private int readCount;
		private int blockIdCalls;
		private int clipCalls;
		private BlockHitResult hit = new BlockHitResult(LOOK_AT, Direction.NORTH, TARGET_POS, false);

		private static FakeWorld ready() {
			FakeWorld world = new FakeWorld();
			world.fullChunks.add(chunkKey(0, 0));
			world.entityTickingChunks.add(chunkKey(0, 0));
			return world;
		}

		@Override
		public boolean isChunkLoaded(int chunkX, int chunkZ) {
			readCount++;
			long chunkKey = chunkKey(chunkX, chunkZ);
			return fullChunks.contains(chunkKey) && entityTickingChunks.contains(chunkKey);
		}

		@Override
		public boolean isSpawnClear(AABB bounds) {
			readCount++;
			return spawnClear;
		}

		@Override
		public BlockHitResult clip(Vec3 from, Vec3 to) {
			readCount++;
			clipCalls++;
			return hit;
		}

		@Override
		public String blockId(BlockPos pos) {
			readCount++;
			blockIdCalls++;
			return blockId;
		}

		private static long chunkKey(int x, int z) {
			return ((long) x << 32) ^ (z & 0xffffffffL);
		}
	}
}
