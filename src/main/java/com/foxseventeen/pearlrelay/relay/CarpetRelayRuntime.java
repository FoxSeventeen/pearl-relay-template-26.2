package com.foxseventeen.pearlrelay.relay;

import carpet.fakes.ServerPlayerInterface;
import carpet.helpers.EntityPlayerActionPack;
import carpet.patches.EntityPlayerMPFake;
import carpet.script.utils.Tracer;
import com.foxseventeen.pearlrelay.config.RelayConfigManager.RelayDefinition;
import com.foxseventeen.pearlrelay.config.RelayConfigManager.TargetFingerprint;
import com.foxseventeen.pearlrelay.relay.RelayExecutionManager.ExecutionRequest;
import com.foxseventeen.pearlrelay.relay.RelayExecutionManager.SpawnStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

public final class CarpetRelayRuntime implements RelayExecutionManager.Runtime {
	@Override
	public RelayFailure checkName(ExecutionRequest request) {
		MinecraftServer server = server(request);
		String bot = bot(request);
		if (server.getPlayerList().getPlayerByName(bot) != null || EntityPlayerMPFake.isSpawningPlayer(bot)) {
			return RelayFailure.FAKE_PLAYER_NAME_IN_USE;
		}
		return null;
	}

	@Override
	public SpawnStatus spawn(ExecutionRequest request) {
		RelayDefinition relay = request.validated().relay();
		Facing facing = calculateFacing(relay.spawn(), relay.lookAt());
		boolean created = EntityPlayerMPFake.createFake(
				relay.bot(),
				server(request),
				relay.spawn(),
				facing.yaw(),
				facing.pitch(),
				request.validated().level().dimension(),
				GameType.SURVIVAL,
				false
		);

		SpawnStatus status = status(request);
		if (!created && status != SpawnStatus.SPAWNING && status != SpawnStatus.READY) {
			return SpawnStatus.FAILED;
		}
		return status;
	}

	@Override
	public SpawnStatus status(ExecutionRequest request) {
		String bot = bot(request);
		ServerPlayer player = server(request).getPlayerList().getPlayerByName(bot);
		if (player instanceof EntityPlayerMPFake) {
			return SpawnStatus.READY;
		}
		if (player != null) {
			return SpawnStatus.FAILED;
		}
		return EntityPlayerMPFake.isSpawningPlayer(bot) ? SpawnStatus.SPAWNING : SpawnStatus.FAILED;
	}

	@Override
	public void aim(ExecutionRequest request) {
		RelayDefinition relay = request.validated().relay();
		ServerPlayer player = fakePlayer(request);
		Facing facing = calculateFacing(relay.spawn(), relay.lookAt());
		player.teleportTo(
				request.validated().level(),
				relay.spawn().x,
				relay.spawn().y,
				relay.spawn().z,
				java.util.Set.of(),
				facing.yaw(),
				facing.pitch(),
				true
		);
		((ServerPlayerInterface) player).getActionPack().lookAt(relay.lookAt());
	}

	@Override
	public RelayFailure validateTarget(ExecutionRequest request) {
		ServerLevel level = request.validated().level();
		TargetFingerprint target = request.validated().relay().target();
		int chunkX = Math.floorDiv(target.x(), 16);
		int chunkZ = Math.floorDiv(target.z(), 16);
		if (!RelayTargetResolver.isChunkReady(
				level::isPositionTickingWithEntitiesLoaded,
				chunkX,
				chunkZ
		)) {
			return RelayFailure.TARGET_CHUNK_UNLOADED;
		}

		BlockPos targetPos = new BlockPos(target.x(), target.y(), target.z());
		String currentBlockId = BuiltInRegistries.BLOCK.getKey(level.getBlockState(targetPos).getBlock()).toString();
		if (!Objects.equals(target.blockId(), currentBlockId)) {
			return RelayFailure.TARGET_BLOCK_CHANGED;
		}

		ServerPlayer player = fakePlayer(request);
		if (player.level() != level) {
			return RelayFailure.TARGET_UNREACHABLE;
		}
		// Carpet's USE action ray-traces entities as well as blocks. Validate with
		// the same tracer so a player entering the line of interaction cannot turn
		// a completed lifecycle into a silent missed block click.
		HitResult hit = Tracer.rayTrace(player, 1.0F, player.blockInteractionRange(), false);
		if (!(hit instanceof BlockHitResult blockHit) || !blockHit.getBlockPos().equals(targetPos)) {
			return RelayFailure.TARGET_UNREACHABLE;
		}
		return null;
	}

	@Override
	public void useOnce(ExecutionRequest request) {
		((ServerPlayerInterface) fakePlayer(request)).getActionPack().start(
				EntityPlayerActionPack.ActionType.USE,
				EntityPlayerActionPack.Action.once()
		);
	}

	@Override
	public boolean cleanup(ExecutionRequest request) {
		String bot = bot(request);
		ServerPlayer player = server(request).getPlayerList().getPlayerByName(bot);
		if (player instanceof EntityPlayerMPFake fakePlayer) {
			fakePlayer.kill(Component.literal("Pearl Relay execution finished."));
		}
		if (EntityPlayerMPFake.isSpawningPlayer(bot)) {
			return false;
		}
		return !(server(request).getPlayerList().getPlayerByName(bot) instanceof EntityPlayerMPFake);
	}

	private static ServerPlayer fakePlayer(ExecutionRequest request) {
		ServerPlayer player = server(request).getPlayerList().getPlayerByName(bot(request));
		if (!(player instanceof EntityPlayerMPFake)) {
			throw new IllegalStateException("Expected active Carpet fake player: " + bot(request));
		}
		return player;
	}

	private static MinecraftServer server(ExecutionRequest request) {
		return request.validated().level().getServer();
	}

	private static String bot(ExecutionRequest request) {
		return request.validated().relay().bot();
	}

	private static Facing calculateFacing(Vec3 spawn, Vec3 lookAt) {
		double dx = lookAt.x - spawn.x;
		double dy = lookAt.y - (spawn.y + 1.62D);
		double dz = lookAt.z - spawn.z;
		double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
		float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
		float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontalDistance)));
		return new Facing(yaw, pitch);
	}

	private record Facing(float yaw, float pitch) {
	}
}
