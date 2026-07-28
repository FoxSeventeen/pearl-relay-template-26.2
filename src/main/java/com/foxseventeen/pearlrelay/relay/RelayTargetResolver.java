package com.foxseventeen.pearlrelay.relay;

import com.foxseventeen.pearlrelay.config.RelayConfigManager.TargetFingerprint;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.player.Player;
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
				new ServerLevelView(level),
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

		AABB spawnBounds = geometry.makeBoundingBox(spawn);
		if (!allChunksLoaded(world, spawnBounds)) {
			return Result.failed(RelayFailure.SPAWN_CHUNK_UNLOADED);
		}
		if (!allChunksLoaded(world, eye, lookAt)) {
			return Result.failed(RelayFailure.TARGET_CHUNK_UNLOADED);
		}
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
			return level.getChunkSource().getChunkNow(chunkX, chunkZ) != null;
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
