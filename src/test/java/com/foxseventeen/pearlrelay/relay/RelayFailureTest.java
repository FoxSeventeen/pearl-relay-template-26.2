package com.foxseventeen.pearlrelay.relay;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RelayFailureTest {
	@Test
	void exposesStableUniqueFailureCodes() {
		Set<String> expected = Set.of(
				"RELAY_NOT_FOUND",
				"RELAY_REQUIRES_RESAVE",
				"DIMENSION_UNAVAILABLE",
				"SPAWN_CHUNK_UNLOADED",
				"TARGET_CHUNK_UNLOADED",
				"SPAWN_POSITION_BLOCKED",
				"TARGET_BLOCK_CHANGED",
				"TARGET_UNREACHABLE",
				"OWNED_PEARL_NOT_FOUND",
				"EXECUTION_ALREADY_ACTIVE",
				"FAKE_PLAYER_NAME_IN_USE",
				"FAKE_PLAYER_CREATE_FAILED",
				"FAKE_PLAYER_SPAWN_TIMEOUT",
				"EXECUTION_INTERNAL_ERROR",
				"EXECUTION_CLEANUP_TIMEOUT"
		);

		Set<String> actual = Arrays.stream(RelayFailure.values())
				.map(RelayFailure::code)
				.collect(Collectors.toSet());

		assertEquals(expected, actual);
		assertEquals(RelayFailure.values().length, actual.size());
	}
}
