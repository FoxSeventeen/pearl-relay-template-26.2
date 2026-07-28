package com.foxseventeen.pearlrelay.config;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

public final class RelayConfigManager {
	private static final Path PLAYERS_DIR = FabricLoader.getInstance()
			.getConfigDir()
			.resolve("pearlrelay")
			.resolve("players");
	private static final RelayConfigStore STORE = new RelayConfigStore(PLAYERS_DIR);

	private RelayConfigManager() {
	}

	public static RelayDefinition get(UUID playerId, String name) throws IOException {
		return STORE.get(playerId, name);
	}

	public static Set<String> names(UUID playerId) throws IOException {
		return STORE.names(playerId);
	}

	public static RelayDefinition put(UUID playerId, String playerName, String name, Identifier dimension, Vec3 spawn, Vec3 lookAt) throws IOException {
		return STORE.put(playerId, playerName, name, dimension, spawn, lookAt, null);
	}

	public static RelayDefinition put(
			UUID playerId,
			String playerName,
			String name,
			Identifier dimension,
			Vec3 spawn,
			Vec3 lookAt,
			TargetFingerprint target
	) throws IOException {
		return STORE.put(playerId, playerName, name, dimension, spawn, lookAt, target);
	}

	public static boolean remove(UUID playerId, String name) throws IOException {
		return STORE.remove(playerId, name);
	}

	public record RelayDefinition(
			String bot,
			Identifier dimension,
			Vec3 spawn,
			Vec3 lookAt,
			TargetFingerprint target
	) {
		boolean isValid() {
			return bot != null && dimension != null && spawn != null && lookAt != null;
		}

		public boolean requiresResave() {
			return target == null || !target.isValid();
		}
	}

	public record TargetFingerprint(int x, int y, int z, String blockId) {
		boolean isValid() {
			return blockId != null && Identifier.tryParse(blockId) != null;
		}
	}
}
