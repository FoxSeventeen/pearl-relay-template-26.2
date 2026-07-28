package com.foxseventeen.pearlrelay.test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

/**
 * Sends commands over the real client connection and captures game messages.
 */
@SuppressWarnings("UnstableApiUsage")
final class ClientCommandDriver {
	private static final AtomicBoolean REGISTERED = new AtomicBoolean();
	private static final List<String> RECEIVED_MESSAGES = new CopyOnWriteArrayList<>();

	ClientCommandDriver() {
		if (REGISTERED.compareAndSet(false, true)) {
			ClientReceiveMessageEvents.GAME.register(
					(message, overlay) -> RECEIVED_MESSAGES.add(message.getString())
			);
		}
	}

	void clearMessages() {
		RECEIVED_MESSAGES.clear();
	}

	int mark() {
		return RECEIVED_MESSAGES.size();
	}

	void send(ClientGameTestContext context, String command) {
		context.runOnClient(client -> {
			if (client.getConnection() == null) {
				throw new AssertionError(
						"Cannot send command without a client/server connection"
				);
			}
			client.getConnection().sendCommand(command);
		});
	}

	String awaitMessageContaining(
			ClientGameTestContext context,
			int fromIndex,
			String expected,
			int timeoutTicks
	) {
		for (int waitedTicks = 0; waitedTicks <= timeoutTicks; waitedTicks++) {
			List<String> snapshot = List.copyOf(RECEIVED_MESSAGES);
			for (int index = fromIndex; index < snapshot.size(); index++) {
				String message = snapshot.get(index);
				if (message.contains(expected)) {
					return message;
				}
			}
			context.waitTick();
		}
		List<String> snapshot = List.copyOf(RECEIVED_MESSAGES);
		throw new AssertionError(
				"Client did not receive message containing '" + expected
						+ "'. Received since mark: "
						+ snapshot.subList(
								Math.min(fromIndex, snapshot.size()),
								snapshot.size()
						)
		);
	}
}
