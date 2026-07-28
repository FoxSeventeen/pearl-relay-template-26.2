package com.foxseventeen.pearlrelay.relay;

import com.foxseventeen.pearlrelay.config.RelayConfigManager.RelayDefinition;
import com.foxseventeen.pearlrelay.config.RelayConfigManager.TargetFingerprint;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;
import java.util.UUID;

public final class RelayPreflight {
	private RelayPreflight() {
	}

	public static Result check(MinecraftServer server, RelayDefinition relay, UUID ownerId) {
		if (relay.requiresResave()) {
			return Result.failed(RelayFailure.RELAY_REQUIRES_RESAVE);
		}

		ResourceKey<Level> dimensionKey = ResourceKey.create(Registries.DIMENSION, relay.dimension());
		ServerLevel level = server.getLevel(dimensionKey);
		if (level == null) {
			return Result.failed(RelayFailure.DIMENSION_UNAVAILABLE);
		}

		Decision decision = check(
				RelayTargetResolver.worldView(level),
				(candidateOwner, target) -> countOwnedPearls(level, candidateOwner, target),
				spawn -> hasPlayerAtSpawn(level, spawn),
				relay,
				ownerId
		);
		if (!decision.isSuccess()) {
			return Result.failed(decision.failure());
		}

		return Result.success(new ValidatedRelay(level, relay, decision.ownedPearlCount()));
	}

	static Decision check(
			RelayTargetResolver.WorldView world,
			PearlCounter pearlCounter,
			RelayDefinition relay,
			UUID ownerId
	) {
		return check(world, pearlCounter, spawn -> false, relay, ownerId);
	}

	static Decision check(
			RelayTargetResolver.WorldView world,
			PearlCounter pearlCounter,
			SpawnOccupancy spawnOccupancy,
			RelayDefinition relay,
			UUID ownerId
	) {
		if (relay.requiresResave()) {
			return Decision.failed(RelayFailure.RELAY_REQUIRES_RESAVE);
		}

		TargetFingerprint savedTarget = relay.target();
		BlockPos targetPos = new BlockPos(savedTarget.x(), savedTarget.y(), savedTarget.z());
		int targetChunkX = Math.floorDiv(savedTarget.x(), 16);
		int targetChunkZ = Math.floorDiv(savedTarget.z(), 16);
		if (!world.isChunkLoaded(targetChunkX, targetChunkZ)) {
			return Decision.failed(RelayFailure.TARGET_CHUNK_UNLOADED);
		}
		RelayFailure chunkFailure =
				RelayTargetResolver.unloadedChunkFailure(world, relay.spawn(), relay.lookAt());
		if (chunkFailure != null) {
			return Decision.failed(chunkFailure);
		}
		if (!Objects.equals(savedTarget.blockId(), world.blockId(targetPos))) {
			return Decision.failed(RelayFailure.TARGET_BLOCK_CHANGED);
		}

		RelayTargetResolver.Result targetResult = RelayTargetResolver.resolve(world, relay.spawn(), relay.lookAt());
		if (!targetResult.isSuccess()) {
			return Decision.failed(targetResult.failure());
		}
		if (!savedTarget.equals(targetResult.target())) {
			return Decision.failed(RelayFailure.TARGET_UNREACHABLE);
		}
		if (spawnOccupancy.hasPlayer(relay.spawn())) {
			return Decision.failed(RelayFailure.SPAWN_POSITION_BLOCKED);
		}

		int ownedPearlCount = pearlCounter.count(ownerId, targetPos);
		if (ownedPearlCount < 1) {
			return Decision.failed(RelayFailure.OWNED_PEARL_NOT_FOUND);
		}
		return Decision.success(ownedPearlCount);
	}

	private static int countOwnedPearls(ServerLevel level, UUID ownerId, BlockPos target) {
		int chunkX = Math.floorDiv(target.getX(), 16);
		int chunkZ = Math.floorDiv(target.getZ(), 16);
		double minX = chunkX * 16.0D;
		double minZ = chunkZ * 16.0D;
		AABB chunkBounds = new AABB(
				minX,
				level.getMinY(),
				minZ,
				minX + 16.0D,
				level.getMaxY(),
				minZ + 16.0D
		);

		return level.getEntities(
				EntityTypeTest.forClass(ThrownEnderpearl.class),
				chunkBounds,
				pearl -> isOwnedBy(pearl, ownerId)
						&& pearl.chunkPosition().x() == chunkX
						&& pearl.chunkPosition().z() == chunkZ
		).size();
	}

	private static boolean hasPlayerAtSpawn(ServerLevel level, Vec3 spawn) {
		double radius = EntityTypes.PLAYER.getDimensions().width() / 2.0D;
		AABB bounds = new AABB(
				spawn.x - radius,
				spawn.y,
				spawn.z - radius,
				spawn.x + radius,
				spawn.y + EntityTypes.PLAYER.getDimensions().height(),
				spawn.z + radius
		);
		return !level.getEntities(
				EntityTypeTest.forClass(ServerPlayer.class),
				bounds,
				player -> true
		).isEmpty();
	}

	private static boolean isOwnedBy(ThrownEnderpearl pearl, UUID ownerId) {
		Entity owner = pearl.getOwner();
		return owner != null && ownerId.equals(owner.getUUID());
	}

	@FunctionalInterface
	interface PearlCounter {
		int count(UUID ownerId, BlockPos target);
	}

	@FunctionalInterface
	interface SpawnOccupancy {
		boolean hasPlayer(Vec3 spawn);
	}

	record Decision(int ownedPearlCount, RelayFailure failure) {
		static Decision success(int ownedPearlCount) {
			return new Decision(ownedPearlCount, null);
		}

		static Decision failed(RelayFailure failure) {
			return new Decision(0, failure);
		}

		boolean isSuccess() {
			return failure == null;
		}
	}

	public record ValidatedRelay(ServerLevel level, RelayDefinition relay, int ownedPearlCount) {
	}

	public record Result(ValidatedRelay request, RelayFailure failure) {
		static Result success(ValidatedRelay request) {
			return new Result(request, null);
		}

		static Result failed(RelayFailure failure) {
			return new Result(null, failure);
		}

		public boolean isSuccess() {
			return request != null;
		}
	}
}
