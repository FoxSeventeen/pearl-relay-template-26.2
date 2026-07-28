package com.foxseventeen.pearlrelay.relay;

import com.foxseventeen.pearlrelay.config.RelayConfigManager.RelayDefinition;
import com.foxseventeen.pearlrelay.config.RelayConfigManager.TargetFingerprint;
import com.foxseventeen.pearlrelay.relay.RelayExecutionManager.AcceptedResult;
import com.foxseventeen.pearlrelay.relay.RelayExecutionManager.ExecutionRequest;
import com.foxseventeen.pearlrelay.relay.RelayExecutionManager.TerminalResult;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelayEventReporterTest {
	private static final UUID EXECUTION_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
	private static final UUID OWNER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

	@Test
	void acceptedEventContainsQueryableLifecycleFields() {
		CapturingSink sink = new CapturingSink();
		RelayEventReporter reporter = new RelayEventReporter(sink, false);
		ExecutionRequest request = request();

		reporter.accepted(new AcceptedResult(EXECUTION_ID, request));

		assertEquals(1, sink.lines.size());
		String line = sink.lines.getFirst();
		assertTrue(line.contains("event=relay_fire"));
		assertTrue(line.contains("action=accepted"));
		assertTrue(line.contains("execution_id=" + EXECUTION_ID));
		assertTrue(line.contains("relay=home"));
		assertTrue(line.contains("player_uuid=" + OWNER_ID));
		assertTrue(line.contains("dimension=minecraft:overworld"));
		assertTrue(line.contains("target_chunk=0,0"));
		assertTrue(line.contains("pearl_count=2"));
		assertTrue(line.contains("phase=prechecked"));
		assertTrue(line.contains("result=accepted"));
		assertTrue(line.contains("failure_code=none"));
	}

	@Test
	void rejectedEventIncludesStableFailureCodeAndSameCorrelationId() {
		CapturingSink sink = new CapturingSink();
		RelayEventReporter reporter = new RelayEventReporter(sink, false);

		reporter.rejected(
				EXECUTION_ID,
				"home",
				OWNER_ID,
				request().validated().relay(),
				0,
				RelayFailure.OWNED_PEARL_NOT_FOUND
		);

		assertEquals(1, sink.lines.size());
		String line = sink.lines.getFirst();
		assertTrue(line.contains("action=rejected"));
		assertTrue(line.contains("execution_id=" + EXECUTION_ID));
		assertTrue(line.contains("result=rejected"));
		assertTrue(line.contains("failure_code=OWNED_PEARL_NOT_FOUND"));
	}

	@Test
	void terminalEventIncludesDurationAndExactlyOneResult() {
		CapturingSink sink = new CapturingSink();
		RelayEventReporter reporter = new RelayEventReporter(sink, false);

		reporter.terminal(new TerminalResult(
				EXECUTION_ID,
				request(),
				false,
				RelayFailure.TARGET_BLOCK_CHANGED,
				17
		));

		assertEquals(1, sink.lines.size());
		String line = sink.lines.getFirst();
		assertTrue(line.contains("action=terminal"));
		assertTrue(line.contains("phase=terminated"));
		assertTrue(line.contains("result=failed"));
		assertTrue(line.contains("failure_code=TARGET_BLOCK_CHANGED"));
		assertTrue(line.contains("duration_ticks=17"));
	}

	private static ExecutionRequest request() {
		RelayDefinition relay = new RelayDefinition(
				"pr_11111111_home",
				Identifier.parse("minecraft:overworld"),
				new Vec3(8.5D, 64.0D, 8.5D),
				new Vec3(8.5D, 65.5D, 12.0D),
				new TargetFingerprint(8, 65, 12, "minecraft:note_block")
		);
		return new ExecutionRequest(
				EXECUTION_ID,
				"home",
				OWNER_ID,
				new RelayPreflight.ValidatedRelay(null, relay, 2)
		);
	}

	private static final class CapturingSink implements RelayEventReporter.LogSink {
		private final List<String> lines = new ArrayList<>();

		@Override
		public void info(String line) {
			lines.add(line);
		}

		@Override
		public void warn(String line) {
			lines.add(line);
		}
	}
}
