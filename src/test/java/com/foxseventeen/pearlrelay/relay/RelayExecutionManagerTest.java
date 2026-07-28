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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelayExecutionManagerTest {
	private static final long STRESS_SEED = 0x5EED_2026_0000_0001L;

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

		assertEquals(0, runtime.spawnCalls);
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
		assertEquals(0, runtime.spawnCalls);
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
		manager.tick();

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

	@Test
	void fixedSeedStressAlwaysTerminatesOnceWithoutOrphanBots() {
		int iterations = 1_000;
		Random random = new Random(STRESS_SEED);
		StressRuntime stressRuntime = new StressRuntime();
		List<TerminalResult> stressTerminals = new ArrayList<>();
		RelayExecutionManager stressManager = new RelayExecutionManager(
				stressRuntime,
				new RelayExecutionManager.Timings(3, 1, 1, 3),
				stressTerminals::add
		);
		Map<UUID, FaultStage> stages = new HashMap<>();

		for (int index = 0; index < iterations; index++) {
			UUID executionId = new UUID(STRESS_SEED, index + 1L);
			FaultStage stage = FaultStage.values()[
					random.nextInt(FaultStage.values().length)
			];
			stages.put(executionId, stage);
			stressRuntime.configure(executionId, stage);
			StartResult result = stressManager.start(request(
					executionId,
					"stress_" + index,
					"pr_stress_" + index
			));
			assertTrue(
					result.isAccepted(),
					() -> stressMessage(executionId, stage, "request rejected")
			);
		}

		for (int tick = 0; tick < 20 && stressManager.activeCount() > 0; tick++) {
			stressManager.tick();
		}

		assertEquals(0, stressManager.activeCount(), "seed=" + STRESS_SEED);
		assertEquals(iterations, stressTerminals.size(), "seed=" + STRESS_SEED);
		assertTrue(stressRuntime.liveBots.isEmpty(), "seed=" + STRESS_SEED);

		Map<UUID, Integer> terminalCounts = new HashMap<>();
		for (TerminalResult terminal : stressTerminals) {
			terminalCounts.merge(terminal.executionId(), 1, Integer::sum);
		}
		for (Map.Entry<UUID, FaultStage> entry : stages.entrySet()) {
			UUID executionId = entry.getKey();
			FaultStage stage = entry.getValue();
			assertEquals(
					1,
					terminalCounts.getOrDefault(executionId, 0),
					() -> stressMessage(executionId, stage, "terminal count")
			);
			assertTrue(
					stressRuntime.useCalls.getOrDefault(executionId, 0) <= 1,
					() -> stressMessage(executionId, stage, "use called more than once")
			);
		}
	}

	@Test
	void shutdownInterleavedWithCleanupReportsOnceAndLeavesNoOrphans() {
		int iterations = 256;
		StressRuntime stressRuntime = new StressRuntime();
		List<TerminalResult> stressTerminals = new ArrayList<>();
		RelayExecutionManager stressManager = new RelayExecutionManager(
				stressRuntime,
				new RelayExecutionManager.Timings(4, 1, 2, 4),
				stressTerminals::add
		);
		Map<UUID, FaultStage> stages = new HashMap<>();
		FaultStage[] shutdownStages = {
				FaultStage.SUCCESS,
				FaultStage.SPAWN_TIMEOUT,
				FaultStage.TARGET_CHANGED,
				FaultStage.USE_EXCEPTION,
				FaultStage.CLEANUP_EXCEPTION_ONCE,
				FaultStage.CLEANUP_FALSE_ONCE
		};

		for (int index = 0; index < iterations; index++) {
			UUID executionId = new UUID(STRESS_SEED + 1L, index + 1L);
			FaultStage stage = shutdownStages[index % shutdownStages.length];
			stages.put(executionId, stage);
			stressRuntime.configure(executionId, stage);
			assertTrue(stressManager.start(request(
					executionId,
					"shutdown_" + index,
					"pr_stop_" + index
			)).isAccepted());
		}

		stressManager.tick();
		stressManager.tick();
		stressManager.shutdown();
		stressManager.tick();
		stressManager.shutdown();

		assertEquals(0, stressManager.activeCount(), "seed=" + (STRESS_SEED + 1L));
		assertEquals(iterations, stressTerminals.size(), "seed=" + (STRESS_SEED + 1L));
		assertTrue(stressRuntime.liveBots.isEmpty(), "seed=" + (STRESS_SEED + 1L));

		Map<UUID, Integer> terminalCounts = new HashMap<>();
		for (TerminalResult terminal : stressTerminals) {
			terminalCounts.merge(terminal.executionId(), 1, Integer::sum);
		}
		for (Map.Entry<UUID, FaultStage> entry : stages.entrySet()) {
			UUID executionId = entry.getKey();
			FaultStage stage = entry.getValue();
			assertEquals(
					1,
					terminalCounts.getOrDefault(executionId, 0),
					() -> stressMessage(executionId, stage, "terminal count")
			);
			assertTrue(
					stressRuntime.useCalls.getOrDefault(executionId, 0) <= 1,
					() -> stressMessage(executionId, stage, "use called more than once")
			);
		}
	}

	private void tick(int count) {
		for (int i = 0; i < count; i++) {
			manager.tick();
		}
	}

	private static String stressMessage(UUID executionId, FaultStage stage, String detail) {
		return "seed=" + STRESS_SEED
				+ ", execution=" + executionId
				+ ", stage=" + stage
				+ ": " + detail;
	}

	private static ExecutionRequest request() {
		return request("home", "pr_11111111_home");
	}

	private static ExecutionRequest request(String relayName, String bot) {
		return request(UUID.randomUUID(), relayName, bot);
	}

	private static ExecutionRequest request(UUID executionId, String relayName, String bot) {
		RelayDefinition relay = new RelayDefinition(
				bot,
				Identifier.parse("minecraft:overworld"),
				new Vec3(8.5D, 64.0D, 8.5D),
				new Vec3(8.5D, 65.5D, 12.0D),
				new TargetFingerprint(8, 65, 12, "minecraft:note_block")
		);
		return new ExecutionRequest(
				executionId,
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

	private enum FaultStage {
		SUCCESS,
		SPAWN_EXCEPTION,
		SPAWN_FAILED,
		SPAWN_TIMEOUT,
		STATUS_EXCEPTION,
		AIM_EXCEPTION,
		VALIDATE_EXCEPTION,
		TARGET_CHANGED,
		USE_EXCEPTION,
		CLEANUP_EXCEPTION_ONCE,
		CLEANUP_FALSE_ONCE
	}

	private static final class StressRuntime implements RelayExecutionManager.Runtime {
		private final Map<UUID, FaultStage> stages = new HashMap<>();
		private final Map<UUID, Integer> useCalls = new HashMap<>();
		private final Map<UUID, Integer> cleanupCalls = new HashMap<>();
		private final Set<String> liveBots = new HashSet<>();

		private void configure(UUID executionId, FaultStage stage) {
			stages.put(executionId, stage);
		}

		@Override
		public RelayFailure checkName(ExecutionRequest request) {
			return null;
		}

		@Override
		public SpawnStatus spawn(ExecutionRequest request) {
			FaultStage stage = stage(request);
			if (stage == FaultStage.SPAWN_EXCEPTION) {
				throw new IllegalStateException("injected spawn failure");
			}
			if (stage == FaultStage.SPAWN_FAILED) {
				return SpawnStatus.FAILED;
			}
			liveBots.add(request.validated().relay().bot());
			if (stage == FaultStage.SPAWN_TIMEOUT || stage == FaultStage.STATUS_EXCEPTION) {
				return SpawnStatus.SPAWNING;
			}
			return SpawnStatus.READY;
		}

		@Override
		public SpawnStatus status(ExecutionRequest request) {
			if (stage(request) == FaultStage.STATUS_EXCEPTION) {
				throw new IllegalStateException("injected status failure");
			}
			return SpawnStatus.SPAWNING;
		}

		@Override
		public void aim(ExecutionRequest request) {
			if (stage(request) == FaultStage.AIM_EXCEPTION) {
				throw new IllegalStateException("injected aim failure");
			}
		}

		@Override
		public RelayFailure validateTarget(ExecutionRequest request) {
			FaultStage stage = stage(request);
			if (stage == FaultStage.VALIDATE_EXCEPTION) {
				throw new IllegalStateException("injected validation failure");
			}
			return stage == FaultStage.TARGET_CHANGED
					? RelayFailure.TARGET_BLOCK_CHANGED
					: null;
		}

		@Override
		public void useOnce(ExecutionRequest request) {
			useCalls.merge(request.executionId(), 1, Integer::sum);
			if (stage(request) == FaultStage.USE_EXCEPTION) {
				throw new IllegalStateException("injected use failure");
			}
		}

		@Override
		public boolean cleanup(ExecutionRequest request) {
			int calls = cleanupCalls.merge(request.executionId(), 1, Integer::sum);
			FaultStage stage = stage(request);
			if (calls == 1 && stage == FaultStage.CLEANUP_EXCEPTION_ONCE) {
				throw new IllegalStateException("injected cleanup failure");
			}
			if (calls == 1 && stage == FaultStage.CLEANUP_FALSE_ONCE) {
				return false;
			}
			liveBots.remove(request.validated().relay().bot());
			return true;
		}

		private FaultStage stage(ExecutionRequest request) {
			return stages.getOrDefault(request.executionId(), FaultStage.SUCCESS);
		}
	}
}
