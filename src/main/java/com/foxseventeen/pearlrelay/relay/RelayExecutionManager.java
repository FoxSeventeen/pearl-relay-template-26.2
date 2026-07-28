package com.foxseventeen.pearlrelay.relay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

public final class RelayExecutionManager {
	private static final Timings DEFAULT_TIMINGS = new Timings(100, 5, 20, 20);

	private final Runtime runtime;
	private final Timings timings;
	private final Consumer<AcceptedResult> acceptedListener;
	private final Consumer<TerminalResult> terminalListener;
	private final Map<String, Execution> activeByBot = new LinkedHashMap<>();

	public RelayExecutionManager(Runtime runtime, Consumer<TerminalResult> terminalListener) {
		this(runtime, DEFAULT_TIMINGS, accepted -> {
		}, terminalListener);
	}

	public RelayExecutionManager(
			Runtime runtime,
			Consumer<AcceptedResult> acceptedListener,
			Consumer<TerminalResult> terminalListener
	) {
		this(runtime, DEFAULT_TIMINGS, acceptedListener, terminalListener);
	}

	public RelayExecutionManager(Runtime runtime, Timings timings, Consumer<TerminalResult> terminalListener) {
		this(runtime, timings, accepted -> {
		}, terminalListener);
	}

	public RelayExecutionManager(
			Runtime runtime,
			Timings timings,
			Consumer<AcceptedResult> acceptedListener,
			Consumer<TerminalResult> terminalListener
	) {
		this.runtime = runtime;
		this.timings = timings;
		this.acceptedListener = acceptedListener;
		this.terminalListener = terminalListener;
	}

	public StartResult start(ExecutionRequest request) {
		String bot = request.validated().relay().bot();
		if (activeByBot.containsKey(bot)) {
			return StartResult.rejected(RelayFailure.EXECUTION_ALREADY_ACTIVE);
		}

		RelayFailure nameFailure;
		try {
			nameFailure = runtime.checkName(request);
		} catch (RuntimeException exception) {
			return StartResult.rejected(RelayFailure.EXECUTION_INTERNAL_ERROR);
		}
		if (nameFailure != null) {
			return StartResult.rejected(nameFailure);
		}

		Execution execution = new Execution(request.executionId(), request);
		activeByBot.put(bot, execution);
		notifyAccepted(new AcceptedResult(execution.id, request));
		try {
			SpawnStatus spawnStatus = runtime.spawn(request);
			switch (spawnStatus) {
				case READY -> aim(execution);
				case SPAWNING -> execution.phase = Phase.WAITING_FOR_FAKE_PLAYER;
				case FAILED -> beginCleanup(execution, RelayFailure.FAKE_PLAYER_CREATE_FAILED);
			}
		} catch (RuntimeException exception) {
			beginCleanup(execution, RelayFailure.EXECUTION_INTERNAL_ERROR);
		}
		return StartResult.accepted(execution.id);
	}

	public void tick() {
		for (Execution execution : new ArrayList<>(activeByBot.values())) {
			if (activeByBot.containsKey(execution.bot())) {
				tick(execution);
			}
		}
	}

	public void shutdown() {
		for (Execution execution : new ArrayList<>(activeByBot.values())) {
			if (execution.phase != Phase.CLEANING_UP) {
				beginCleanup(execution, RelayFailure.EXECUTION_INTERNAL_ERROR);
			}
		}

		for (int attempt = 0; attempt < timings.cleanupTimeoutTicks() && !activeByBot.isEmpty(); attempt++) {
			for (Execution execution : new ArrayList<>(activeByBot.values())) {
				if (execution.phase == Phase.CLEANING_UP) {
					attemptCleanup(execution);
				}
			}
		}
	}

	public int activeCount() {
		return activeByBot.size();
	}

	private void tick(Execution execution) {
		execution.durationTicks++;
		try {
			switch (execution.phase) {
				case WAITING_FOR_FAKE_PLAYER -> tickWaiting(execution);
				case AIMING -> tickAiming(execution);
				case POST_USE_DELAY -> tickPostUse(execution);
				case CLEANING_UP -> attemptCleanup(execution);
			}
		} catch (RuntimeException exception) {
			beginCleanup(execution, RelayFailure.EXECUTION_INTERNAL_ERROR);
		}
	}

	private void tickWaiting(Execution execution) {
		execution.phaseTicks++;
		SpawnStatus status = runtime.status(execution.request);
		if (status == SpawnStatus.READY) {
			aim(execution);
			return;
		}
		if (status == SpawnStatus.FAILED || execution.phaseTicks >= timings.spawnTimeoutTicks()) {
			beginCleanup(execution, RelayFailure.FAKE_PLAYER_SPAWN_TIMEOUT);
		}
	}

	private void aim(Execution execution) {
		runtime.aim(execution.request);
		execution.phase = Phase.AIMING;
		execution.phaseTicks = 0;
	}

	private void tickAiming(Execution execution) {
		execution.phaseTicks++;
		if (execution.phaseTicks < timings.aimDelayTicks()) {
			return;
		}

		RelayFailure validationFailure = runtime.validateTarget(execution.request);
		if (validationFailure != null) {
			beginCleanup(execution, validationFailure);
			return;
		}

		runtime.useOnce(execution.request);
		execution.phase = Phase.POST_USE_DELAY;
		execution.phaseTicks = 0;
	}

	private void tickPostUse(Execution execution) {
		execution.phaseTicks++;
		if (execution.phaseTicks >= timings.postUseDelayTicks()) {
			beginCleanup(execution, null);
		}
	}

	private void beginCleanup(Execution execution, RelayFailure failure) {
		if (!activeByBot.containsKey(execution.bot())) {
			return;
		}
		if (execution.phase != Phase.CLEANING_UP) {
			execution.failure = failure;
			execution.phase = Phase.CLEANING_UP;
			execution.phaseTicks = 0;
		} else if (execution.failure == null && failure != null) {
			execution.failure = failure;
		}
		attemptCleanup(execution);
	}

	private void attemptCleanup(Execution execution) {
		if (!activeByBot.containsKey(execution.bot())) {
			return;
		}

		boolean cleaned;
		try {
			cleaned = runtime.cleanup(execution.request);
		} catch (RuntimeException exception) {
			cleaned = false;
			if (execution.failure == null) {
				execution.failure = RelayFailure.EXECUTION_INTERNAL_ERROR;
			}
		}

		execution.phaseTicks++;
		if (cleaned) {
			terminate(execution, execution.failure);
		} else if (execution.phaseTicks >= timings.cleanupTimeoutTicks()) {
			terminate(execution, RelayFailure.EXECUTION_CLEANUP_TIMEOUT);
		}
	}

	private void terminate(Execution execution, RelayFailure failure) {
		if (activeByBot.remove(execution.bot(), execution)) {
			notifyTerminal(new TerminalResult(
					execution.id,
					execution.request,
					failure == null,
					failure,
					execution.durationTicks
			));
		}
	}

	private void notifyAccepted(AcceptedResult result) {
		try {
			acceptedListener.accept(result);
		} catch (RuntimeException ignored) {
			// Telemetry and notification failures must not change relay execution.
		}
	}

	private void notifyTerminal(TerminalResult result) {
		try {
			terminalListener.accept(result);
		} catch (RuntimeException ignored) {
			// Telemetry and notification failures must not change relay execution.
		}
	}

	public interface Runtime {
		RelayFailure checkName(ExecutionRequest request);

		SpawnStatus spawn(ExecutionRequest request);

		SpawnStatus status(ExecutionRequest request);

		void aim(ExecutionRequest request);

		RelayFailure validateTarget(ExecutionRequest request);

		void useOnce(ExecutionRequest request);

		boolean cleanup(ExecutionRequest request);
	}

	public enum SpawnStatus {
		READY,
		SPAWNING,
		FAILED
	}

	private enum Phase {
		WAITING_FOR_FAKE_PLAYER,
		AIMING,
		POST_USE_DELAY,
		CLEANING_UP
	}

	public record Timings(
			int spawnTimeoutTicks,
			int aimDelayTicks,
			int postUseDelayTicks,
			int cleanupTimeoutTicks
	) {
		public Timings {
			if (spawnTimeoutTicks < 1 || aimDelayTicks < 1 || postUseDelayTicks < 1 || cleanupTimeoutTicks < 1) {
				throw new IllegalArgumentException("All execution timings must be positive");
			}
		}
	}

	public record ExecutionRequest(
			UUID executionId,
			String relayName,
			UUID ownerId,
			RelayPreflight.ValidatedRelay validated
	) {
	}

	public record StartResult(UUID executionId, RelayFailure failure) {
		static StartResult accepted(UUID executionId) {
			return new StartResult(executionId, null);
		}

		static StartResult rejected(RelayFailure failure) {
			return new StartResult(null, failure);
		}

		public boolean isAccepted() {
			return executionId != null;
		}
	}

	public record TerminalResult(
			UUID executionId,
			ExecutionRequest request,
			boolean success,
			RelayFailure failure,
			int durationTicks
	) {
	}

	public record AcceptedResult(
			UUID executionId,
			ExecutionRequest request
	) {
	}

	private static final class Execution {
		private final UUID id;
		private final ExecutionRequest request;
		private Phase phase = Phase.WAITING_FOR_FAKE_PLAYER;
		private int phaseTicks;
		private int durationTicks;
		private RelayFailure failure;

		private Execution(UUID id, ExecutionRequest request) {
			this.id = id;
			this.request = request;
		}

		private String bot() {
			return request.validated().relay().bot();
		}
	}
}
