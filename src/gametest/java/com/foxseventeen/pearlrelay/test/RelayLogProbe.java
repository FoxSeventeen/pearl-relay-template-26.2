package com.foxseventeen.pearlrelay.test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.layout.PatternLayout;

/**
 * Test-only observer for Pearl Relay's structured lifecycle events.
 */
@SuppressWarnings("deprecation")
final class RelayLogProbe extends AbstractAppender implements AutoCloseable {
	private final Logger logger;
	private final List<String> lines = new CopyOnWriteArrayList<>();
	private boolean closed;

	RelayLogProbe() {
		super(
				"PearlRelayClientGameTest-" + java.util.UUID.randomUUID(),
				null,
				PatternLayout.createDefaultLayout(),
				true
		);
		logger = (Logger) LogManager.getLogger("pearlrelay");
		start();
		logger.addAppender(this);
	}

	@Override
	public void append(LogEvent event) {
		String line = event.toImmutable().getMessage().getFormattedMessage();
		if (line.contains("event=relay_fire")) {
			lines.add(line);
		}
	}

	int mark() {
		return lines.size();
	}

	RelayEvents eventsSince(int fromIndex, String relayName) {
		List<String> snapshot = List.copyOf(lines);
		List<String> matching = snapshot.subList(
						Math.min(fromIndex, snapshot.size()),
						snapshot.size()
				).stream()
				.filter(line -> line.contains(" relay=" + relayName + " "))
				.toList();
		List<String> accepted = matching.stream()
				.filter(line -> line.contains(" action=accepted "))
				.toList();
		List<String> terminals = matching.stream()
				.filter(line -> line.contains(" action=terminal "))
				.toList();
		return new RelayEvents(accepted, terminals);
	}

	@Override
	public void close() {
		if (closed) {
			return;
		}
		closed = true;
		logger.removeAppender(this);
		stop();
	}

	record RelayEvents(List<String> accepted, List<String> terminals) {
		boolean isOneCompletedLifecycle() {
			return accepted.size() == 1
					&& terminals.size() == 1
					&& terminals.getFirst().contains(" result=completed ")
					&& terminals.getFirst().contains(" failure_code=none ")
					&& !executionId(accepted.getFirst()).isEmpty()
					&& executionId(accepted.getFirst()).equals(
							executionId(terminals.getFirst())
					);
		}

		private static String executionId(String line) {
			for (String part : line.split(" ")) {
				if (part.startsWith("execution_id=")) {
					return part.substring("execution_id=".length());
				}
			}
			return "";
		}
	}
}
