package com.foxseventeen.pearlrelay.config;

import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelayConfigStoreTest {
	private static final UUID PLAYER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

	@TempDir
	Path playersDir;

	@Test
	void writesAndReadsVersionedTargetFingerprint() throws Exception {
		RelayConfigStore store = new RelayConfigStore(playersDir);
		RelayConfigManager.TargetFingerprint target =
				new RelayConfigManager.TargetFingerprint(10, 64, 20, "minecraft:note_block");

		store.put(
				PLAYER_ID,
				"PlayerOne",
				"home",
				Identifier.parse("minecraft:overworld"),
				new Vec3(10.5, 64.0, 18.5),
				new Vec3(10.5, 64.5, 20.5),
				target
		);

		RelayConfigManager.RelayDefinition loaded = store.get(PLAYER_ID, "home");
		String json = Files.readString(playersDir.resolve(PLAYER_ID + ".json"));

		assertEquals(target, loaded.target());
		assertFalse(loaded.requiresResave());
		assertEquals(
				JsonParser.parseString(readFixture("relay-config-v2.json")),
				JsonParser.parseString(json)
		);
	}

	@Test
	void keepsLegacyRelayVisibleAndMarksItForResave() throws Exception {
		writeFixture("relay-config-v1.json");
		RelayConfigStore store = new RelayConfigStore(playersDir);

		assertEquals(Set.of("home"), store.names(PLAYER_ID));
		assertTrue(store.get(PLAYER_ID, "home").requiresResave());
	}

	@Test
	void overwritingLegacyRelayAddsTargetFingerprint() throws Exception {
		writeFixture("relay-config-v1.json");
		RelayConfigStore store = new RelayConfigStore(playersDir);

		store.put(
				PLAYER_ID,
				"PlayerOne",
				"home",
				Identifier.parse("minecraft:overworld"),
				new Vec3(10.5, 64.0, 18.5),
				new Vec3(10.5, 64.5, 20.5),
				new RelayConfigManager.TargetFingerprint(10, 64, 20, "minecraft:note_block")
		);

		assertFalse(store.get(PLAYER_ID, "home").requiresResave());
	}

	@Test
	void filtersRelayMissingRequiredActivationData() throws Exception {
		Files.writeString(playersDir.resolve(PLAYER_ID + ".json"), """
				{
				  "schemaVersion": 2,
				  "playerName": "PlayerOne",
				  "relays": {
				    "broken": {
				      "bot": "pr_11111111_bad",
				      "dimension": null,
				      "spawn": {"x": 10.5, "y": 64.0, "z": 18.5},
				      "lookAt": {"x": 10.5, "y": 64.5, "z": 20.5},
				      "target": {
				        "x": 10,
				        "y": 64,
				        "z": 20,
				        "blockId": "minecraft:note_block"
				      }
				    }
				  }
				}
				""");
		RelayConfigStore store = new RelayConfigStore(playersDir);

		assertTrue(store.names(PLAYER_ID).isEmpty());
	}

	private void writeFixture(String name) throws IOException {
		Files.createDirectories(playersDir);
		Files.writeString(playersDir.resolve(PLAYER_ID + ".json"), readFixture(name));
	}

	private static String readFixture(String name) throws IOException {
		try (InputStream input = RelayConfigStoreTest.class.getResourceAsStream("/fixtures/" + name)) {
			if (input == null) {
				throw new IOException("Missing test fixture: " + name);
			}
			return new String(input.readAllBytes());
		}
	}
}
