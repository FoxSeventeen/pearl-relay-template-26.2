package com.foxseventeen.pearlrelay.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AtomicConfigWriterTest {
	@TempDir
	Path playersDir;

	@Test
	void everyPersistenceFailureKeepsTheLastSuccessfulMainFile() throws Exception {
		byte[] previous = "{\"relays\":{\"home\":{}}}".getBytes();
		byte[] unrelated = "{\"relays\":{\"other\":{}}}".getBytes();
		byte[] replacement = "{\"relays\":{\"new\":{}}}".getBytes();

		for (FailurePoint failurePoint : FailurePoint.values()) {
			Path caseDir = Files.createDirectory(playersDir.resolve(failurePoint.name()));
			Path target = caseDir.resolve("11111111.json");
			Path otherPlayer = caseDir.resolve("22222222.json");
			Files.write(target, previous);
			Files.write(otherPlayer, unrelated);

			AtomicConfigWriter writer = new AtomicConfigWriter(
					new FailingFileOperations(failurePoint)
			);

			assertThrows(IOException.class, () -> writer.write(target, replacement));
			assertArrayEquals(previous, Files.readAllBytes(target), failurePoint.name());
			assertArrayEquals(unrelated, Files.readAllBytes(otherPlayer), failurePoint.name());
			try (var paths = Files.list(caseDir)) {
				assertFalse(
						paths.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")),
						failurePoint.name()
				);
			}
		}
	}

	private enum FailurePoint {
		WRITE_PENDING,
		SYNC_PENDING,
		COPY_BACKUP,
		SYNC_BACKUP,
		REPLACE_BACKUP,
		REPLACE_TARGET
	}

	private static final class FailingFileOperations implements AtomicConfigWriter.FileOperations {
		private final AtomicConfigWriter.NioFileOperations delegate =
				new AtomicConfigWriter.NioFileOperations();
		private final FailurePoint failurePoint;

		private FailingFileOperations(FailurePoint failurePoint) {
			this.failurePoint = failurePoint;
		}

		@Override
		public void createDirectories(Path directory) throws IOException {
			delegate.createDirectories(directory);
		}

		@Override
		public Path createTempFile(Path directory, String prefix, String suffix) throws IOException {
			return delegate.createTempFile(directory, prefix, suffix);
		}

		@Override
		public void write(Path path, byte[] content) throws IOException {
			failIf(FailurePoint.WRITE_PENDING);
			delegate.write(path, content);
		}

		@Override
		public void sync(Path path) throws IOException {
			failIf(
					isBackupTemporary(path)
							? FailurePoint.SYNC_BACKUP
							: FailurePoint.SYNC_PENDING
			);
			delegate.sync(path);
		}

		@Override
		public boolean exists(Path path) {
			return delegate.exists(path);
		}

		@Override
		public void copy(Path source, Path target) throws IOException {
			failIf(FailurePoint.COPY_BACKUP);
			delegate.copy(source, target);
		}

		@Override
		public void replace(Path source, Path target) throws IOException {
			failIf(
					target.getFileName().toString().endsWith(".bak")
							? FailurePoint.REPLACE_BACKUP
							: FailurePoint.REPLACE_TARGET
			);
			delegate.replace(source, target);
		}

		@Override
		public void deleteIfExists(Path path) throws IOException {
			delegate.deleteIfExists(path);
		}

		private void failIf(FailurePoint operation) throws IOException {
			if (failurePoint == operation) {
				throw new IOException("injected " + operation);
			}
		}

		private static boolean isBackupTemporary(Path path) {
			return path.getFileName().toString().contains(".bak.");
		}
	}
}
