package com.foxseventeen.pearlrelay.relay;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelayMessagesTest {
	@Test
	void everyStableFailureHasAnExactPlayerMessage() {
		Map<RelayFailure, String> expected = Map.ofEntries(
				Map.entry(RelayFailure.RELAY_NOT_FOUND, "the relay does not exist"),
				Map.entry(RelayFailure.RELAY_REQUIRES_RESAVE, "save this legacy relay again before firing"),
				Map.entry(RelayFailure.DIMENSION_UNAVAILABLE, "the saved dimension is unavailable"),
				Map.entry(RelayFailure.SPAWN_CHUNK_UNLOADED, "the fake-player spawn chunk is not loaded"),
				Map.entry(RelayFailure.TARGET_CHUNK_UNLOADED, "the target chunk is not loaded, so no usable pearl can be present"),
				Map.entry(RelayFailure.SPAWN_POSITION_BLOCKED, "the fake-player spawn position is blocked"),
				Map.entry(RelayFailure.TARGET_BLOCK_CHANGED, "the target block type no longer matches the saved relay"),
				Map.entry(RelayFailure.TARGET_UNREACHABLE, "the saved target is no longer reachable from the fake-player spawn"),
				Map.entry(RelayFailure.OWNED_PEARL_NOT_FOUND, "no ender pearl owned by you exists in the target block chunk"),
				Map.entry(RelayFailure.EXECUTION_ALREADY_ACTIVE, "an execution for this relay bot is already active"),
				Map.entry(RelayFailure.FAKE_PLAYER_NAME_IN_USE, "the generated fake-player name is already in use"),
				Map.entry(RelayFailure.FAKE_PLAYER_CREATE_FAILED, "Carpet could not create the fake player"),
				Map.entry(RelayFailure.FAKE_PLAYER_SPAWN_TIMEOUT, "the fake player did not finish spawning before the deadline"),
				Map.entry(RelayFailure.EXECUTION_INTERNAL_ERROR, "an internal execution step failed"),
				Map.entry(RelayFailure.EXECUTION_CLEANUP_TIMEOUT, "the fake player could not be confirmed removed before the deadline")
		);

		assertEquals(expected.keySet(), SetSupport.failures());
		for (Map.Entry<RelayFailure, String> entry : expected.entrySet()) {
			String message = RelayMessages.fireFailure(entry.getKey()).getString();
			assertEquals(
					"[" + entry.getKey().code() + "] Fire rejected: " + entry.getValue() + ".",
					message
			);
		}
	}

	@Test
	void terminalMessagesExposeExecutionIdAndFailureCode() {
		String success = RelayMessages.terminal("home", "abc-123", null).getString();
		String failure = RelayMessages.terminal(
				"home",
				"abc-123",
				RelayFailure.TARGET_BLOCK_CHANGED
		).getString();

		assertTrue(success.contains("completed"));
		assertTrue(success.contains("abc-123"));
		assertTrue(failure.contains("TARGET_BLOCK_CHANGED"));
		assertTrue(failure.contains("failed"));
	}

	private static final class SetSupport {
		private static java.util.Set<RelayFailure> failures() {
			return java.util.Set.of(RelayFailure.values());
		}
	}
}
