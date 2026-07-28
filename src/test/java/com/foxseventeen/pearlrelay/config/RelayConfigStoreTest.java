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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelayConfigStoreTest {
	private static final UUID PLAYER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
	private static final UUID OTHER_PLAYER_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

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
	void overwritingConfigKeepsThePreviousSuccessfulFileAsBackup() throws Exception {
		RelayConfigStore store = new RelayConfigStore(playersDir);
		RelayConfigManager.TargetFingerprint firstTarget =
				new RelayConfigManager.TargetFingerprint(10, 64, 20, "minecraft:note_block");
		RelayConfigManager.TargetFingerprint secondTarget =
				new RelayConfigManager.TargetFingerprint(11, 64, 20, "minecraft:stone_button");

		store.put(
				PLAYER_ID,
				"PlayerOne",
				"home",
				Identifier.parse("minecraft:overworld"),
				new Vec3(10.5, 64.0, 18.5),
				new Vec3(10.5, 64.5, 20.5),
				firstTarget
		);
		String firstSuccessfulFile = Files.readString(playersDir.resolve(PLAYER_ID + ".json"));

		store.put(
				PLAYER_ID,
				"PlayerOne",
				"home",
				Identifier.parse("minecraft:overworld"),
				new Vec3(11.5, 64.0, 18.5),
				new Vec3(11.5, 64.5, 20.5),
				secondTarget
		);

		assertEquals(
				JsonParser.parseString(firstSuccessfulFile),
				JsonParser.parseString(Files.readString(playersDir.resolve(PLAYER_ID + ".json.bak")))
		);
		assertEquals(secondTarget, store.get(PLAYER_ID, "home").target());
	}

	@Test
	void rejectsTheWholeFileWhenAnyRelayIsInvalid() throws Exception {
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

		RelayConfigException exception =
				assertThrows(RelayConfigException.class, () -> store.names(PLAYER_ID));

		assertEquals(RelayConfigException.Code.CONFIG_CORRUPT, exception.code());
		assertTrue(Files.readString(playersDir.resolve(PLAYER_ID + ".json")).contains("\"broken\""));
	}

	@Test
	void restoresAValidSamePlayerBackupAndRequiresTheCommandToRetry() throws Exception {
		RelayConfigStore store = new RelayConfigStore(playersDir);
		RelayConfigManager.TargetFingerprint previousTarget =
				new RelayConfigManager.TargetFingerprint(10, 64, 20, "minecraft:note_block");
		RelayConfigManager.TargetFingerprint currentTarget =
				new RelayConfigManager.TargetFingerprint(11, 64, 20, "minecraft:stone_button");
		put(store, previousTarget);
		put(store, currentTarget);
		String corruptContent = "{\"schemaVersion\":2,\"relays\":";
		Files.writeString(playersDir.resolve(PLAYER_ID + ".json"), corruptContent);

		RelayConfigException exception =
				assertThrows(RelayConfigException.class, () -> store.names(PLAYER_ID));

		assertEquals(RelayConfigException.Code.CONFIG_RECOVERED_RETRY, exception.code());
		assertEquals(previousTarget, store.get(PLAYER_ID, "home").target());
		try (var paths = Files.list(playersDir)) {
			Path corruptCopy = paths
					.filter(path -> path.getFileName().toString().startsWith(PLAYER_ID + ".json.corrupt-"))
					.findFirst()
					.orElseThrow();
			assertEquals(corruptContent, Files.readString(corruptCopy));
		}
	}

	@Test
	void refusesRecoveryWhenTheSamePlayerBackupIsInvalid() throws Exception {
		Path config = playersDir.resolve(PLAYER_ID + ".json");
		Path backup = playersDir.resolve(PLAYER_ID + ".json.bak");
		Files.writeString(config, "{\"schemaVersion\":2,\"relays\":");
		Files.writeString(backup, "{\"schemaVersion\":2,\"relays\":null}");
		RelayConfigStore store = new RelayConfigStore(playersDir);

		RelayConfigException exception =
				assertThrows(RelayConfigException.class, () -> store.names(PLAYER_ID));

		assertEquals(RelayConfigException.Code.CONFIG_CORRUPT, exception.code());
		assertEquals("{\"schemaVersion\":2,\"relays\":", Files.readString(config));
	}

	@Test
	void neverUsesAnotherPlayersValidBackup() throws Exception {
		Path config = playersDir.resolve(PLAYER_ID + ".json");
		Files.writeString(config, "{\"schemaVersion\":2,\"relays\":");
		Files.writeString(
				playersDir.resolve(OTHER_PLAYER_ID + ".json.bak"),
				readFixture("relay-config-v2.json")
		);
		RelayConfigStore store = new RelayConfigStore(playersDir);

		RelayConfigException exception =
				assertThrows(RelayConfigException.class, () -> store.names(PLAYER_ID));

		assertEquals(RelayConfigException.Code.CONFIG_CORRUPT, exception.code());
		assertEquals("{\"schemaVersion\":2,\"relays\":", Files.readString(config));
	}

	@Test
	void reportsRecoveryFailureWithoutReplacingTheCorruptMainFile() throws Exception {
		Path config = playersDir.resolve(PLAYER_ID + ".json");
		Path backup = playersDir.resolve(PLAYER_ID + ".json.bak");
		String corruptContent = "{\"schemaVersion\":2,\"relays\":";
		Files.writeString(config, corruptContent);
		Files.writeString(backup, readFixture("relay-config-v2.json"));
		AtomicConfigWriter.FileOperations files = new AtomicConfigWriter.NioFileOperations() {
			@Override
			public void replace(Path source, Path target) throws IOException {
				if (target.equals(config)) {
					throw new IOException("injected restore failure");
				}
				super.replace(source, target);
			}
		};
		RelayConfigStore store =
				new RelayConfigStore(playersDir, new AtomicConfigWriter(files));

		RelayConfigException exception =
				assertThrows(RelayConfigException.class, () -> store.names(PLAYER_ID));

		assertEquals(RelayConfigException.Code.CONFIG_RECOVERY_FAILED, exception.code());
		assertEquals(corruptContent, Files.readString(config));
		assertEquals(
				JsonParser.parseString(readFixture("relay-config-v2.json")),
				JsonParser.parseString(Files.readString(backup))
		);
	}

	private void put(
			RelayConfigStore store,
			RelayConfigManager.TargetFingerprint target
	) throws IOException {
		store.put(
				PLAYER_ID,
				"PlayerOne",
				"home",
				Identifier.parse("minecraft:overworld"),
				new Vec3(target.x() + 0.5, 64.0, 18.5),
				new Vec3(target.x() + 0.5, 64.5, 20.5),
				target
		);
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
