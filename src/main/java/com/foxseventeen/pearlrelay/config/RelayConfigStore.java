package com.foxseventeen.pearlrelay.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
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

	RelayConfigStore(Path playersDir) {
		this.playersDir = playersDir;
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

		try (Reader reader = Files.newBufferedReader(path)) {
			PlayerRelayFile file = GSON.fromJson(reader, PlayerRelayFile.class);
			if (file == null) {
				file = new PlayerRelayFile();
			}
			if (file.relays == null) {
				file.relays = new LinkedHashMap<>();
			}
			file.relays.entrySet().removeIf(entry -> entry.getValue() == null || !entry.getValue().isValid());
			return file;
		}
	}

	private void save(UUID playerId, PlayerRelayFile file) throws IOException {
		Files.createDirectories(playersDir);
		file.schemaVersion = CURRENT_SCHEMA_VERSION;
		try (Writer writer = Files.newBufferedWriter(pathFor(playerId))) {
			GSON.toJson(file, writer);
		}
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
		private int schemaVersion = CURRENT_SCHEMA_VERSION;
		private String playerName;
		private Map<String, RelayConfigManager.RelayDefinition> relays = new LinkedHashMap<>();
	}
}
