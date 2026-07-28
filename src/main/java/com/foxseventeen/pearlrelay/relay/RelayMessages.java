package com.foxseventeen.pearlrelay.relay;

import net.minecraft.network.chat.Component;

public final class RelayMessages {
	private RelayMessages() {
	}

	public static Component relayNotFound(String relayName) {
		return Component.literal(
				"[" + RelayFailure.RELAY_NOT_FOUND.code() + "] Relay not found: " + relayName + "."
		);
	}

	public static Component fireFailure(RelayFailure failure) {
		String detail = switch (failure) {
			case RELAY_NOT_FOUND -> "the relay does not exist";
			case RELAY_REQUIRES_RESAVE -> "save this legacy relay again before firing";
			case DIMENSION_UNAVAILABLE -> "the saved dimension is unavailable";
			case SPAWN_CHUNK_UNLOADED -> "the fake-player spawn chunk is not loaded";
			case TARGET_CHUNK_UNLOADED -> "the target chunk is not loaded, so no usable pearl can be present";
			case SPAWN_POSITION_BLOCKED -> "the fake-player spawn position is blocked";
			case TARGET_BLOCK_CHANGED -> "the target block type no longer matches the saved relay";
			case TARGET_UNREACHABLE -> "the saved target is no longer reachable from the fake-player spawn";
			case OWNED_PEARL_NOT_FOUND -> "no ender pearl owned by you exists in the target block chunk";
			case EXECUTION_ALREADY_ACTIVE -> "an execution for this relay bot is already active";
			case FAKE_PLAYER_NAME_IN_USE -> "the generated fake-player name is already in use";
			case FAKE_PLAYER_CREATE_FAILED -> "Carpet could not create the fake player";
			case FAKE_PLAYER_SPAWN_TIMEOUT -> "the fake player did not finish spawning before the deadline";
			case EXECUTION_INTERNAL_ERROR -> "an internal execution step failed";
			case EXECUTION_CLEANUP_TIMEOUT -> "the fake player could not be confirmed removed before the deadline";
		};
		return Component.literal("[" + failure.code() + "] Fire rejected: " + detail + ".");
	}

	public static Component terminal(String relayName, String executionId, RelayFailure failure) {
		if (failure == null) {
			return Component.literal(
					"Relay '" + relayName + "' completed (execution=" + executionId + ")."
			);
		}
		return Component.literal(
				"Relay '" + relayName + "' failed [" + failure.code() + "] (execution=" + executionId + ")."
		);
	}
}
