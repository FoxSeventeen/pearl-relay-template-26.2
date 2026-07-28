package com.foxseventeen.pearlrelay.relay;

import com.foxseventeen.pearlrelay.config.RelayConfigManager.TargetFingerprint;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

public final class RelayTargetResolver {
	private static final double CHUNK_EDGE_EPSILON = 1.0E-7D;

	private RelayTargetResolver() {
	}

	public static Result resolve(ServerLevel level, Vec3 spawn, Vec3 lookAt) {
		return resolve(
				worldView(level),
				spawn,
				lookAt,
				new PlayerGeometry(
						EntityTypes.PLAYER.getDimensions().width(),
						EntityTypes.PLAYER.getDimensions().height(),
						EntityTypes.PLAYER.getDimensions().eyeHeight(),
						Player.DEFAULT_BLOCK_INTERACTION_RANGE
				)
		);
	}

	static WorldView worldView(ServerLevel level) {
		return new ServerLevelView(level);
	}

	static boolean isChunkReady(ChunkReadiness readiness, int chunkX, int chunkZ) {
		return readiness.isEntityTicking(ChunkPos.pack(chunkX, chunkZ));
	}

	static Result resolve(WorldView world, Vec3 spawn, Vec3 lookAt) {
		return resolve(world, spawn, lookAt, PlayerGeometry.DEFAULT);
	}

	private static Result resolve(WorldView world, Vec3 spawn, Vec3 lookAt, PlayerGeometry geometry) {
		if (!isFinite(spawn) || !isFinite(lookAt)) {
			return Result.failed(RelayFailure.TARGET_UNREACHABLE);
		}

		Vec3 eye = spawn.add(0.0D, geometry.eyeHeight(), 0.0D);
		if (eye.distanceToSqr(lookAt) > geometry.reach() * geometry.reach()) {
			return Result.failed(RelayFailure.TARGET_UNREACHABLE);
		}

		RelayFailure chunkFailure = unloadedChunkFailure(world, spawn, lookAt, geometry);
		if (chunkFailure != null) {
			return Result.failed(chunkFailure);
		}

		AABB spawnBounds = geometry.makeBoundingBox(spawn);
		if (!world.isSpawnClear(spawnBounds)) {
			return Result.failed(RelayFailure.SPAWN_POSITION_BLOCKED);
		}

		BlockHitResult hit = world.clip(eye, lookAt);
		if (hit.getType() != HitResult.Type.BLOCK) {
			return Result.failed(RelayFailure.TARGET_UNREACHABLE);
		}

		BlockPos targetPos = hit.getBlockPos();
		if (!targetPos.equals(BlockPos.containing(lookAt))) {
			return Result.failed(RelayFailure.TARGET_UNREACHABLE);
		}
		if (!world.isChunkLoaded(chunkCoordinate(targetPos.getX()), chunkCoordinate(targetPos.getZ()))) {
			return Result.failed(RelayFailure.TARGET_CHUNK_UNLOADED);
		}

		return Result.success(new TargetFingerprint(
				targetPos.getX(),
				targetPos.getY(),
				targetPos.getZ(),
				world.blockId(targetPos)
		));
	}

	static RelayFailure unloadedChunkFailure(WorldView world, Vec3 spawn, Vec3 lookAt) {
		// This is only an early, side-effect-free chunk gate. The full resolver
		// remains responsible for the stable TARGET_UNREACHABLE failure.
		if (!isFinite(spawn) || !isFinite(lookAt)) {
			return null;
		}

		PlayerGeometry geometry = PlayerGeometry.DEFAULT;
		Vec3 eye = spawn.add(0.0D, geometry.eyeHeight(), 0.0D);
		if (eye.distanceToSqr(lookAt) > geometry.reach() * geometry.reach()) {
			return null;
		}
		return unloadedChunkFailure(world, spawn, lookAt, geometry);
	}

	private static RelayFailure unloadedChunkFailure(
			WorldView world,
			Vec3 spawn,
			Vec3 lookAt,
			PlayerGeometry geometry
	) {
		AABB spawnBounds = geometry.makeBoundingBox(spawn);
		if (!allChunksLoaded(world, spawnBounds)) {
			return RelayFailure.SPAWN_CHUNK_UNLOADED;
		}

		Vec3 eye = spawn.add(0.0D, geometry.eyeHeight(), 0.0D);
		if (!allChunksLoaded(world, eye, lookAt)) {
			return RelayFailure.TARGET_CHUNK_UNLOADED;
		}
		return null;
	}

	private static boolean allChunksLoaded(WorldView world, AABB bounds) {
		int minChunkX = chunkCoordinate(bounds.minX);
		int maxChunkX = chunkCoordinate(bounds.maxX - CHUNK_EDGE_EPSILON);
		int minChunkZ = chunkCoordinate(bounds.minZ);
		int maxChunkZ = chunkCoordinate(bounds.maxZ - CHUNK_EDGE_EPSILON);
		return allChunksLoaded(world, minChunkX, maxChunkX, minChunkZ, maxChunkZ);
	}

	private static boolean allChunksLoaded(WorldView world, Vec3 from, Vec3 to) {
		int minChunkX = chunkCoordinate(Math.min(from.x, to.x));
		int maxChunkX = chunkCoordinate(Math.max(from.x, to.x));
		int minChunkZ = chunkCoordinate(Math.min(from.z, to.z));
		int maxChunkZ = chunkCoordinate(Math.max(from.z, to.z));
		return allChunksLoaded(world, minChunkX, maxChunkX, minChunkZ, maxChunkZ);
	}

	private static boolean allChunksLoaded(
			WorldView world,
			int minChunkX,
			int maxChunkX,
			int minChunkZ,
			int maxChunkZ
	) {
		for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
			for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
				if (!world.isChunkLoaded(chunkX, chunkZ)) {
					return false;
				}
			}
		}
		return true;
	}

	private static int chunkCoordinate(double blockCoordinate) {
		return Math.floorDiv((int) Math.floor(blockCoordinate), 16);
	}

	private static int chunkCoordinate(int blockCoordinate) {
		return Math.floorDiv(blockCoordinate, 16);
	}

	private static boolean isFinite(Vec3 position) {
		return Double.isFinite(position.x) && Double.isFinite(position.y) && Double.isFinite(position.z);
	}

	interface WorldView {
		boolean isChunkLoaded(int chunkX, int chunkZ);

		boolean isSpawnClear(AABB bounds);

		BlockHitResult clip(Vec3 from, Vec3 to);

		String blockId(BlockPos pos);
	}

	@FunctionalInterface
	interface ChunkReadiness {
		boolean isEntityTicking(long chunkPos);
	}

	public record Result(TargetFingerprint target, RelayFailure failure) {
		static Result success(TargetFingerprint target) {
			return new Result(target, null);
		}

		static Result failed(RelayFailure failure) {
			return new Result(null, failure);
		}

		public boolean isSuccess() {
			return target != null;
		}
	}

	private record PlayerGeometry(double width, double height, double eyeHeight, double reach) {
		private static final PlayerGeometry DEFAULT = new PlayerGeometry(0.6D, 1.8D, 1.62D, 4.5D);

		private AABB makeBoundingBox(Vec3 position) {
			double radius = width / 2.0D;
			return new AABB(
					position.x - radius,
					position.y,
					position.z - radius,
					position.x + radius,
					position.y + height,
					position.z + radius
			);
		}
	}

	private record ServerLevelView(ServerLevel level) implements WorldView {
		@Override
		public boolean isChunkLoaded(int chunkX, int chunkZ) {
			// getChunkNow can expose a FULL neighbor cached around a ticketed chunk even
			// though that neighbor is not entity-ticking. Spawning there would create
			// the very chunk activity that preflight is required to avoid.
			return isChunkReady(level::isPositionTickingWithEntitiesLoaded, chunkX, chunkZ);
		}

		@Override
		public boolean isSpawnClear(AABB bounds) {
			return level.noCollision(bounds);
		}

		@Override
		public BlockHitResult clip(Vec3 from, Vec3 to) {
			return level.clip(new ClipContext(
					from,
					to,
					ClipContext.Block.OUTLINE,
					ClipContext.Fluid.NONE,
					CollisionContext.empty()
			));
		}

		@Override
		public String blockId(BlockPos pos) {
			return BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString();
		}
	}
}
