package com.foxseventeen.pearlrelay;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PearlRelayModTest {
	@Test
	void exposesStableModId() {
		assertEquals("pearlrelay", PearlRelayMod.MOD_ID);
	}
}
