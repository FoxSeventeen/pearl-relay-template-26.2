package com.foxseventeen.pearlrelay.test;

import java.util.List;

import carpet.patches.EntityPlayerMPFake;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Observes the player-visible portion of a real Carpet relay execution.
 */
@SuppressWarnings("UnstableApiUsage")
final class FakePlayerLifecycleProbe {
	private static final double POSITION_TOLERANCE_SQUARED = 0.0001D;
	private static final double MINIMUM_LOOK_DOT_PRODUCT = 0.999D;

	FakePlayerObservation awaitVisibleAndCorrect(
			ClientGameTestContext context,
			TestServerContext serverContext,
			String expectedName,
			Vec3 expectedPosition,
			Vec3 expectedLookAt,
			int repetition
	) {
		FakePlayerObservation lastObservation = null;
		boolean lastClientVisible = false;
		for (int waitedTicks = 0; waitedTicks <= 100; waitedTicks++) {
			lastObservation = observe(serverContext, expectedName);
			lastClientVisible = isClientVisible(context, expectedName);
			if (lastObservation != null && lastClientVisible) {
				assertExpected(
						lastObservation,
						expectedName,
						expectedPosition,
						expectedLookAt,
						repetition
				);
				return lastObservation;
			}
			context.waitTick();
		}
		throw new AssertionError(
				"Fake player did not become visible on both sides at repetition "
						+ repetition + ": server=" + lastObservation
						+ ", clientVisible=" + lastClientVisible
		);
	}

	void awaitRemoved(
			ClientGameTestContext context,
			TestServerContext serverContext,
			String expectedName,
			int repetition
	) {
		FakePlayerObservation lastObservation = null;
		boolean lastClientVisible = true;
		for (int waitedTicks = 0; waitedTicks <= 100; waitedTicks++) {
			lastObservation = observe(serverContext, expectedName);
			lastClientVisible = isClientVisible(context, expectedName);
			if (lastObservation == null && !lastClientVisible) {
				return;
			}
			context.waitTick();
		}
		throw new AssertionError(
				"Fake player remained after terminal state at repetition "
						+ repetition + ": server=" + lastObservation
						+ ", clientVisible=" + lastClientVisible
		);
	}

	private static FakePlayerObservation observe(
			TestServerContext serverContext,
			String expectedName
	) {
		return serverContext.computeOnServer(server -> {
			List<ServerPlayer> matching = server.getPlayerList()
					.getPlayers()
					.stream()
					.filter(player -> player.getGameProfile().name().equals(expectedName))
					.toList();
			if (matching.size() > 1) {
				throw new AssertionError(
						"Expected at most one fake player named " + expectedName
								+ ", found " + matching.size()
				);
			}
			if (matching.isEmpty()) {
				return null;
			}
			ServerPlayer player = matching.getFirst();
			return new FakePlayerObservation(
					player instanceof EntityPlayerMPFake,
					player.getGameProfile().name(),
					player.position(),
					player.getEyePosition(),
					player.getLookAngle()
			);
		});
	}

	private static boolean isClientVisible(
			ClientGameTestContext context,
			String expectedName
	) {
		return context.computeOnClient(client ->
				client.level != null
						&& client.level.players()
						.stream()
						.anyMatch(player ->
								player.getGameProfile().name().equals(expectedName)
						)
		);
	}

	private static void assertExpected(
			FakePlayerObservation observation,
			String expectedName,
			Vec3 expectedPosition,
			Vec3 expectedLookAt,
			int repetition
	) {
		if (!observation.carpetFakePlayer()) {
			throw new AssertionError(
					"Relay player was not a Carpet fake player at repetition "
							+ repetition + ": " + observation
			);
		}
		if (!expectedName.equals(observation.name())) {
			throw new AssertionError(
					"Unexpected fake-player name at repetition " + repetition
							+ ": " + observation.name()
			);
		}
		if (observation.position().distanceToSqr(expectedPosition)
				> POSITION_TOLERANCE_SQUARED) {
			throw new AssertionError(
					"Fake player spawned at the wrong position at repetition "
							+ repetition + ": expected=" + expectedPosition
							+ ", actual=" + observation.position()
			);
		}

		Vec3 expectedLookDirection = expectedLookAt
				.subtract(observation.eyePosition())
				.normalize();
		double lookDotProduct = expectedLookDirection.dot(
				observation.lookDirection().normalize()
		);
		if (lookDotProduct < MINIMUM_LOOK_DOT_PRODUCT) {
			throw new AssertionError(
					"Fake player did not face the saved target at repetition "
							+ repetition + ": dot=" + lookDotProduct
							+ ", expected=" + expectedLookDirection
							+ ", actual=" + observation.lookDirection()
			);
		}
	}

	record FakePlayerObservation(
			boolean carpetFakePlayer,
			String name,
			Vec3 position,
			Vec3 eyePosition,
			Vec3 lookDirection
	) {
	}
}
