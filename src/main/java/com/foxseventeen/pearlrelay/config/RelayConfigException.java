package com.foxseventeen.pearlrelay.config;

import java.io.IOException;

public final class RelayConfigException extends IOException {
	private final Code code;

	RelayConfigException(Code code, Throwable cause) {
		super(message(code), cause);
		this.code = code;
	}

	public Code code() {
		return code;
	}

	private static String message(Code code) {
		return switch (code) {
			case CONFIG_CORRUPT ->
					"[CONFIG_CORRUPT] Relay config is damaged and no valid same-player backup is available.";
			case CONFIG_RECOVERED_RETRY ->
					"[CONFIG_RECOVERED_RETRY] Relay config was restored from backup; retry the command.";
			case CONFIG_RECOVERY_FAILED ->
					"[CONFIG_RECOVERY_FAILED] Relay config recovery failed; no command action was performed.";
		};
	}

	public enum Code {
		CONFIG_CORRUPT,
		CONFIG_RECOVERED_RETRY,
		CONFIG_RECOVERY_FAILED
	}
}
