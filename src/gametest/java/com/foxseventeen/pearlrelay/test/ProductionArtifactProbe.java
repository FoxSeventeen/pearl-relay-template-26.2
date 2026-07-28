package com.foxseventeen.pearlrelay.test;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.foxseventeen.pearlrelay.PearlRelayMod;

final class ProductionArtifactProbe {
	private static final String ENABLED_PROPERTY =
			"pearlrelay.production.client.gametest";

	private ProductionArtifactProbe() {
	}

	static void assertMainClassLoadedFromReleaseJar() {
		if (!Boolean.getBoolean(ENABLED_PROPERTY)) {
			return;
		}

		if (PearlRelayMod.class.getProtectionDomain().getCodeSource() == null) {
			throw new AssertionError(
					"Production main class has no discoverable code source"
			);
		}

		Path codeSource;
		try {
			codeSource = Path.of(
					PearlRelayMod.class
							.getProtectionDomain()
							.getCodeSource()
							.getLocation()
							.toURI()
			);
		} catch (URISyntaxException exception) {
			throw new AssertionError(
					"Production main class has an invalid code source URI",
					exception
			);
		}

		Path fileName = codeSource.getFileName();
		if (!Files.isRegularFile(codeSource)
				|| fileName == null
				|| !isReleaseJarName(fileName.toString())) {
			throw new AssertionError(
					"Production main class was not loaded from the release JAR: "
							+ codeSource
			);
		}
	}

	private static boolean isReleaseJarName(String fileName) {
		return fileName.startsWith("pearlrelay-")
				&& fileName.endsWith(".jar")
				&& !fileName.contains("-gametest")
				&& !fileName.contains("-sources");
	}
}
