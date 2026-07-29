package com.foxseventeen.pearlrelay.config;

import com.foxseventeen.pearlrelay.PearlRelayMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class RelayConfigStore {
	private static final int CURRENT_SCHEMA_VERSION = 2;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private final Path playersDir;
	private final AtomicConfigWriter writer;

	RelayConfigStore(Path playersDir) {
		this(playersDir, new AtomicConfigWriter());
	}

	RelayConfigStore(Path playersDir, AtomicConfigWriter writer) {
		this.playersDir = playersDir;
		this.writer = writer;
	}

	RelayConfigManager.RelayDefinition get(UUID playerId, String name) throws IOException {
		PlayerRelayFile file = load(playerId);
		return file.relays.get(name);
	}

	Set<String> names(UUID playerId) throws IOException {
		PlayerRelayFile file = load(playerId);
		return Collections.unmodifiableSet(file.relays.keySet());
	}

	RelayConfigManager.RelayDefinition put(
			UUID playerId,
			String playerName,
			String name,
			Identifier dimension,
			Vec3 spawn,
			Vec3 lookAt,
			RelayConfigManager.TargetFingerprint target
	) throws IOException {
		PlayerRelayFile file = load(playerId);
		file.playerName = playerName;
		RelayConfigManager.RelayDefinition relay = new RelayConfigManager.RelayDefinition(
				generateBotName(playerId, name),
				dimension,
				spawn,
				lookAt,
				target
		);
		file.relays.put(name, relay);
		save(playerId, file);
		return relay;
	}

	boolean remove(UUID playerId, String name) throws IOException {
		PlayerRelayFile file = load(playerId);
		boolean removed = file.relays.remove(name) != null;
		if (removed) {
			save(playerId, file);
		}
		return removed;
	}

	private PlayerRelayFile load(UUID playerId) throws IOException {
		Path path = pathFor(playerId);
		if (!Files.exists(path)) {
			return new PlayerRelayFile();
		}

		try {
			return readValid(path);
		} catch (InvalidConfigException exception) {
			recover(playerId, path, exception);
			throw new AssertionError("Config recovery must return by throwing");
		}
	}

	private PlayerRelayFile readValid(Path path) throws IOException, InvalidConfigException {
		try (Reader reader = Files.newBufferedReader(path)) {
			PlayerRelayFile file;
			try {
				file = GSON.fromJson(reader, PlayerRelayFile.class);
			} catch (JsonParseException | IllegalStateException exception) {
				throw new InvalidConfigException(exception);
			}
			validate(file);
			return file;
		}
	}

	private void validate(PlayerRelayFile file) throws InvalidConfigException {
		if (file == null
				|| file.schemaVersion < 0
				|| file.schemaVersion > CURRENT_SCHEMA_VERSION
				|| file.relays == null) {
			throw new InvalidConfigException();
		}

		for (Map.Entry<String, RelayConfigManager.RelayDefinition> entry : file.relays.entrySet()) {
			if (entry.getKey() == null
					|| entry.getKey().isBlank()
					|| !isValid(entry.getValue())) {
				throw new InvalidConfigException();
			}
		}
	}

	private static boolean isValid(RelayConfigManager.RelayDefinition relay) {
		if (relay == null
				|| relay.bot() == null
				|| relay.bot().isBlank()
				|| relay.bot().length() > 16
				|| relay.dimension() == null
				|| !isFinite(relay.spawn())
				|| !isFinite(relay.lookAt())) {
			return false;
		}
		// A player may upgrade legacy relays one at a time, leaving a mixed schema-v2 file.
		return relay.target() == null || relay.target().isValid();
	}

	private static boolean isFinite(Vec3 position) {
		return position != null
				&& Double.isFinite(position.x)
				&& Double.isFinite(position.y)
				&& Double.isFinite(position.z);
	}

	private void recover(UUID playerId, Path path, InvalidConfigException cause) throws IOException {
		Path backup = AtomicConfigWriter.backupPath(path);
		if (!Files.exists(backup) || !isValidBackup(backup)) {
			logRecovery(playerId, path, RelayConfigException.Code.CONFIG_CORRUPT);
			throw new RelayConfigException(RelayConfigException.Code.CONFIG_CORRUPT, cause);
		}

		try {
			byte[] corruptContent = Files.readAllBytes(path);
			writer.replaceWithoutBackup(nextCorruptPath(path), corruptContent);
			writer.replaceWithoutBackup(path, Files.readAllBytes(backup));
		} catch (IOException exception) {
			logRecovery(playerId, path, RelayConfigException.Code.CONFIG_RECOVERY_FAILED);
			throw new RelayConfigException(
					RelayConfigException.Code.CONFIG_RECOVERY_FAILED,
					exception
			);
		}

		logRecovery(playerId, path, RelayConfigException.Code.CONFIG_RECOVERED_RETRY);
		throw new RelayConfigException(RelayConfigException.Code.CONFIG_RECOVERED_RETRY, cause);
	}

	private boolean isValidBackup(Path backup) {
		try {
			readValid(backup);
			return true;
		} catch (IOException | InvalidConfigException exception) {
			return false;
		}
	}

	private static Path nextCorruptPath(Path path) {
		Path candidate = path.resolveSibling(path.getFileName() + ".corrupt-1");
		int index = 1;
		while (Files.exists(candidate)) {
			index++;
			candidate = path.resolveSibling(path.getFileName() + ".corrupt-" + index);
		}
		return candidate;
	}

	private static void logRecovery(
			UUID playerId,
			Path path,
			RelayConfigException.Code code
	) {
		PearlRelayMod.LOGGER.warn(
				"event=relay_config action=load failure_code={} player_uuid={} file={}",
				code,
				playerId,
				path.getFileName()
		);
	}

	private void save(UUID playerId, PlayerRelayFile file) throws IOException {
		file.schemaVersion = CURRENT_SCHEMA_VERSION;
		writer.write(pathFor(playerId), GSON.toJson(file).getBytes(StandardCharsets.UTF_8));
	}

	private Path pathFor(UUID playerId) {
		return playersDir.resolve(playerId + ".json");
	}

	private static String generateBotName(UUID playerId, String relayName) {
		String shortUuid = playerId.toString().replace("-", "").substring(0, 8);
		String sanitizedRelayName = relayName.replaceAll("[^A-Za-z0-9_]", "_");
		if (sanitizedRelayName.isBlank()) {
			sanitizedRelayName = "bot";
		}

		String prefix = "pr_" + shortUuid + "_";
		int availableRelayNameLength = Math.max(1, 16 - prefix.length());
		if (sanitizedRelayName.length() > availableRelayNameLength) {
			sanitizedRelayName = sanitizedRelayName.substring(0, availableRelayNameLength);
		}
		return prefix + sanitizedRelayName;
	}

	private static final class PlayerRelayFile {
		private int schemaVersion;
		private String playerName;
		private Map<String, RelayConfigManager.RelayDefinition> relays = new LinkedHashMap<>();
	}

	private static final class InvalidConfigException extends Exception {
		private InvalidConfigException() {
		}

		private InvalidConfigException(Throwable cause) {
			super(cause);
		}
	}
}
