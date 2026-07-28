package com.foxseventeen.pearlrelay.relay;

import com.foxseventeen.pearlrelay.config.RelayConfigManager.RelayDefinition;
import com.foxseventeen.pearlrelay.config.RelayConfigManager.TargetFingerprint;
import com.foxseventeen.pearlrelay.relay.RelayExecutionManager.AcceptedResult;
import com.foxseventeen.pearlrelay.relay.RelayExecutionManager.ExecutionRequest;
import com.foxseventeen.pearlrelay.relay.RelayExecutionManager.TerminalResult;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.UUID;

public final class RelayEventReporter {
	private final LogSink sink;
	private final boolean notifyPlayers;

	public RelayEventReporter(Logger logger) {
		this(new Slf4jSink(logger), true);
	}

	RelayEventReporter(LogSink sink, boolean notifyPlayers) {
		this.sink = sink;
		this.notifyPlayers = notifyPlayers;
	}

	public void accepted(AcceptedResult result) {
		sink.info(format(
				"accepted",
				result.executionId(),
				result.request(),
				"prechecked",
				"accepted",
				null,
				0
		));
	}

	public void rejected(
			UUID executionId,
			String relayName,
			UUID ownerId,
			RelayDefinition relay,
			int pearlCount,
			RelayFailure failure
	) {
		sink.warn(format(
				"rejected",
				executionId,
				new EventContext(relayName, ownerId, relay, pearlCount),
				"preflight",
				"rejected",
				failure,
				0
		));
	}

	public void terminal(TerminalResult result) {
		String line = format(
				"terminal",
				result.executionId(),
				result.request(),
				"terminated",
				result.success() ? "completed" : "failed",
				result.failure(),
				result.durationTicks()
		);
		if (result.success()) {
			sink.info(line);
		} else {
			sink.warn(line);
		}

		if (notifyPlayers) {
			notifyOwner(result);
		}
	}

	private static String format(
			String action,
			UUID executionId,
			ExecutionRequest request,
			String phase,
			String result,
			RelayFailure failure,
			int durationTicks
	) {
		return format(
				action,
				executionId,
				new EventContext(
						request.relayName(),
						request.ownerId(),
						request.validated().relay(),
						request.validated().ownedPearlCount()
				),
				phase,
				result,
				failure,
				durationTicks
		);
	}

	private static String format(
			String action,
			UUID executionId,
			EventContext context,
			String phase,
			String result,
			RelayFailure failure,
			int durationTicks
	) {
		RelayDefinition relay = context.relay();
		TargetFingerprint target = relay == null ? null : relay.target();
		String targetChunk = target == null
				? "-"
				: Math.floorDiv(target.x(), 16) + "," + Math.floorDiv(target.z(), 16);
		return "event=relay_fire"
				+ " action=" + safe(action)
				+ " execution_id=" + safe(executionId)
				+ " relay=" + safe(context.relayName())
				+ " player_uuid=" + safe(context.ownerId())
				+ " bot=" + safe(relay == null ? null : relay.bot())
				+ " dimension=" + safe(relay == null ? null : relay.dimension())
				+ " target_chunk=" + safe(targetChunk)
				+ " pearl_count=" + context.pearlCount()
				+ " phase=" + safe(phase)
				+ " result=" + safe(result)
				+ " failure_code=" + safe(failure == null ? "none" : failure.code())
				+ " duration_ticks=" + durationTicks;
	}

	private static void notifyOwner(TerminalResult result) {
		ServerPlayer owner = result.request()
				.validated()
				.level()
				.getServer()
				.getPlayerList()
				.getPlayer(result.request().ownerId());
		if (owner != null) {
			owner.sendSystemMessage(RelayMessages.terminal(
					result.request().relayName(),
					result.executionId().toString(),
					result.failure()
			));
		}
	}

	private static String safe(Object value) {
		if (value == null) {
			return "-";
		}
		String text = value.toString().replaceAll("[^A-Za-z0-9_:.<>,@/\\-]", "_");
		return text.length() <= 128 ? text : text.substring(0, 128);
	}

	public interface LogSink {
		void info(String line);

		void warn(String line);
	}

	private record EventContext(
			String relayName,
			UUID ownerId,
			RelayDefinition relay,
			int pearlCount
	) {
	}

	private record Slf4jSink(Logger logger) implements LogSink {
		@Override
		public void info(String line) {
			logger.info(line);
		}

		@Override
		public void warn(String line) {
			logger.warn(line);
		}
	}
}
