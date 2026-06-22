package com.foxseventeen.pearlrelay.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
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

public final class RelayConfigManager {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PLAYERS_DIR = FabricLoader.getInstance()
			.getConfigDir()
			.resolve("pearlrelay")
			.resolve("players");

	private RelayConfigManager() {
	}

	public static RelayDefinition get(UUID playerId, String name) throws IOException {
		PlayerRelayFile file = load(playerId);
		return file.relays.get(name);
	}

	public static Set<String> names(UUID playerId) throws IOException {
		PlayerRelayFile file = load(playerId);
		return Collections.unmodifiableSet(file.relays.keySet());
	}

	public static RelayDefinition put(UUID playerId, String playerName, String name, Identifier dimension, Vec3 spawn, Vec3 lookAt) throws IOException {
		PlayerRelayFile file = load(playerId);
		file.playerName = playerName;
		RelayDefinition relay = new RelayDefinition(generateBotName(playerId, name), dimension, spawn, lookAt);
		file.relays.put(name, relay);
		save(playerId, file);
		return relay;
	}

	public static boolean remove(UUID playerId, String name) throws IOException {
		PlayerRelayFile file = load(playerId);
		boolean removed = file.relays.remove(name) != null;
		if (removed) {
			save(playerId, file);
		}
		return removed;
	}

	private static PlayerRelayFile load(UUID playerId) throws IOException {
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

	private static void save(UUID playerId, PlayerRelayFile file) throws IOException {
		Files.createDirectories(PLAYERS_DIR);
		try (Writer writer = Files.newBufferedWriter(pathFor(playerId))) {
			GSON.toJson(file, writer);
		}
	}

	private static Path pathFor(UUID playerId) {
		return PLAYERS_DIR.resolve(playerId + ".json");
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

	public record RelayDefinition(String bot, Identifier dimension, Vec3 spawn, Vec3 lookAt) {
		private boolean isValid() {
			return bot != null && dimension != null && spawn != null && lookAt != null;
		}
	}

	private static final class PlayerRelayFile {
		private String playerName;
		private Map<String, RelayDefinition> relays = new LinkedHashMap<>();
	}
}
