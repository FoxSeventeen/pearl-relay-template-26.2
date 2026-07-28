package com.foxseventeen.pearlrelay.relay;

import com.foxseventeen.pearlrelay.config.RelayConfigManager.RelayDefinition;
import com.foxseventeen.pearlrelay.config.RelayConfigManager.TargetFingerprint;
import com.foxseventeen.pearlrelay.relay.RelayExecutionManager.AcceptedResult;
import com.foxseventeen.pearlrelay.relay.RelayExecutionManager.ExecutionRequest;
import com.foxseventeen.pearlrelay.relay.RelayExecutionManager.SpawnStatus;
import com.foxseventeen.pearlrelay.relay.RelayExecutionManager.StartResult;
import com.foxseventeen.pearlrelay.relay.RelayExecutionManager.TerminalResult;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelayExecutionManagerTest {
	private FakeRuntime runtime;
	private List<AcceptedResult> accepted;
	private List<TerminalResult> terminals;
	private RelayExecutionManager manager;

	@BeforeEach
	void setUp() {
		runtime = new FakeRuntime();
		accepted = new ArrayList<>();
		terminals = new ArrayList<>();
		manager = new RelayExecutionManager(
				runtime,
				new RelayExecutionManager.Timings(3, 2, 2, 3),
				accepted::add,
				terminals::add
		);
	}

	@Test
	void delayedSpawnAimsUsesOnceAndCleansUp() {
		runtime.spawnStatus = SpawnStatus.SPAWNING;
		runtime.status = SpawnStatus.SPAWNING;

		StartResult start = manager.start(request());
		manager.tick();
		runtime.status = SpawnStatus.READY;
		manager.tick();
		manager.tick();
		manager.tick();
		manager.tick();
		manager.tick();

		assertTrue(start.isAccepted());
		assertEquals(1, runtime.aimCalls);
		assertEquals(1, runtime.useCalls);
		assertEquals(1, runtime.cleanupCalls);
		assertEquals(0, manager.activeCount());
		assertEquals(1, terminals.size());
		assertTrue(terminals.getFirst().success());
	}

	@Test
	void successfulExecutionUsesExactlyOnce() {
		StartResult start = manager.start(request());

		tick(8);

		assertTrue(start.isAccepted());
		assertEquals(1, runtime.useCalls);
		assertEquals(1, accepted.size());
		assertEquals(1, terminals.size());
		assertTrue(terminals.getFirst().success());
	}

	@Test
	void spawnTimeoutTerminatesAndCleansUp() {
		runtime.spawnStatus = SpawnStatus.SPAWNING;
		runtime.status = SpawnStatus.SPAWNING;
		manager.start(request());

		tick(5);

		assertEquals(0, runtime.useCalls);
		assertEquals(1, runtime.cleanupCalls);
		assertEquals(0, manager.activeCount());
		assertEquals(RelayFailure.FAKE_PLAYER_SPAWN_TIMEOUT, terminals.getFirst().failure());
	}

	@Test
	void targetRemovalAfterPreflightPreventsUseAndCleansUp() {
		runtime.targetFailure = RelayFailure.TARGET_BLOCK_CHANGED;
		manager.start(request());

		tick(4);

		assertEquals(0, runtime.useCalls);
		assertEquals(1, runtime.cleanupCalls);
		assertEquals(RelayFailure.TARGET_BLOCK_CHANGED, terminals.getFirst().failure());
	}

	@Test
	void internalUseExceptionStillCleansUp() {
		runtime.throwOnUse = true;
		manager.start(request());

		tick(4);

		assertEquals(1, runtime.useCalls);
		assertEquals(1, runtime.cleanupCalls);
		assertEquals(RelayFailure.EXECUTION_INTERNAL_ERROR, terminals.getFirst().failure());
	}

	@Test
	void duplicateActiveBotIsRejectedWithoutSecondSpawn() {
		StartResult first = manager.start(request());
		StartResult second = manager.start(request());

		assertTrue(first.isAccepted());
		assertFalse(second.isAccepted());
		assertEquals(RelayFailure.EXECUTION_ALREADY_ACTIVE, second.failure());
		assertEquals(1, runtime.spawnCalls);
		assertEquals(1, accepted.size());
		assertEquals(1, manager.activeCount());
	}

	@Test
	void preexistingPlayerNameRejectsBeforeExecutionCreation() {
		runtime.nameFailure = RelayFailure.FAKE_PLAYER_NAME_IN_USE;

		StartResult start = manager.start(request());

		assertFalse(start.isAccepted());
		assertEquals(RelayFailure.FAKE_PLAYER_NAME_IN_USE, start.failure());
		assertEquals(0, runtime.spawnCalls);
		assertEquals(0, runtime.cleanupCalls);
		assertEquals(0, manager.activeCount());
		assertTrue(terminals.isEmpty());
	}

	@Test
	void failedFakePlayerCreationAttemptsCleanup() {
		runtime.spawnStatus = SpawnStatus.FAILED;

		StartResult start = manager.start(request());

		assertTrue(start.isAccepted());
		assertEquals(1, runtime.cleanupCalls);
		assertEquals(0, manager.activeCount());
		assertEquals(RelayFailure.FAKE_PLAYER_CREATE_FAILED, terminals.getFirst().failure());
	}

	@Test
	void cleanupOfAlreadyAbsentBotIsSuccessfulAndIdempotent() {
		manager.start(request());

		tick(8);
		manager.tick();

		assertEquals(1, runtime.cleanupCalls);
		assertEquals(1, terminals.size());
		assertEquals(0, manager.activeCount());
	}

	@Test
	void cleanupRetriesUntilBotIsAbsent() {
		runtime.cleanupResults.add(false);
		runtime.cleanupResults.add(true);
		manager.start(request());

		tick(8);

		assertEquals(2, runtime.cleanupCalls);
		assertEquals(1, terminals.size());
		assertTrue(terminals.getFirst().success());
	}

	@Test
	void cleanupTimeoutProducesOneTerminalResult() {
		runtime.defaultCleanupResult = false;
		manager.start(request());

		tick(12);

		assertEquals(0, manager.activeCount());
		assertEquals(1, terminals.size());
		assertFalse(terminals.getFirst().success());
		assertEquals(RelayFailure.EXECUTION_CLEANUP_TIMEOUT, terminals.getFirst().failure());
	}

	@Test
	void shutdownAttemptsCleanupForEveryActiveExecution() {
		manager.start(request("home", "pr_11111111_home"));
		manager.start(request("backup", "pr_11111111_back"));

		manager.shutdown();

		assertEquals(2, runtime.cleanupCalls);
		assertEquals(0, manager.activeCount());
		assertEquals(2, terminals.size());
	}

	private void tick(int count) {
		for (int i = 0; i < count; i++) {
			manager.tick();
		}
	}

	private static ExecutionRequest request() {
		return request("home", "pr_11111111_home");
	}

	private static ExecutionRequest request(String relayName, String bot) {
		RelayDefinition relay = new RelayDefinition(
				bot,
				Identifier.parse("minecraft:overworld"),
				new Vec3(8.5D, 64.0D, 8.5D),
				new Vec3(8.5D, 65.5D, 12.0D),
				new TargetFingerprint(8, 65, 12, "minecraft:note_block")
		);
		return new ExecutionRequest(
				UUID.randomUUID(),
				relayName,
				UUID.fromString("11111111-2222-3333-4444-555555555555"),
				new RelayPreflight.ValidatedRelay(null, relay, 1)
		);
	}

	private static final class FakeRuntime implements RelayExecutionManager.Runtime {
		private RelayFailure nameFailure;
		private SpawnStatus spawnStatus = SpawnStatus.READY;
		private SpawnStatus status = SpawnStatus.READY;
		private RelayFailure targetFailure;
		private boolean throwOnUse;
		private boolean defaultCleanupResult = true;
		private final Deque<Boolean> cleanupResults = new ArrayDeque<>();
		private int spawnCalls;
		private int aimCalls;
		private int useCalls;
		private int cleanupCalls;

		@Override
		public RelayFailure checkName(ExecutionRequest request) {
			return nameFailure;
		}

		@Override
		public SpawnStatus spawn(ExecutionRequest request) {
			spawnCalls++;
			return spawnStatus;
		}

		@Override
		public SpawnStatus status(ExecutionRequest request) {
			return status;
		}

		@Override
		public void aim(ExecutionRequest request) {
			aimCalls++;
		}

		@Override
		public RelayFailure validateTarget(ExecutionRequest request) {
			return targetFailure;
		}

		@Override
		public void useOnce(ExecutionRequest request) {
			useCalls++;
			if (throwOnUse) {
				throw new IllegalStateException("injected use failure");
			}
		}

		@Override
		public boolean cleanup(ExecutionRequest request) {
			cleanupCalls++;
			return cleanupResults.isEmpty() ? defaultCleanupResult : cleanupResults.removeFirst();
		}
	}
}
