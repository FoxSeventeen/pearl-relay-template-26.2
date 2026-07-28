package com.foxseventeen.pearlrelay;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PearlRelayModTest {
	@Test
	void exposesStableModId() {
		assertEquals("pearlrelay", PearlRelayMod.MOD_ID);
	}

	@Test
	void declaresVerifiedRuntimeDependencyMinimums() throws IOException {
		String metadata = Files.readString(Path.of("src/main/resources/fabric.mod.json"));

		assertTrue(metadata.contains("\"java\": \">=25\""));
		assertTrue(metadata.contains("\"fabric-api\": \">=0.152.1+26.2\""));
		assertTrue(metadata.contains("\"carpet\": \">=26.2\""));
	}
}
