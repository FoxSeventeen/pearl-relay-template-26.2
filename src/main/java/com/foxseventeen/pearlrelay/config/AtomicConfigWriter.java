package com.foxseventeen.pearlrelay.config;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

final class AtomicConfigWriter {
	private final FileOperations files;

	AtomicConfigWriter() {
		this(new NioFileOperations());
	}

	AtomicConfigWriter(FileOperations files) {
		this.files = files;
	}

	void write(Path target, byte[] content) throws IOException {
		Path parent = target.getParent();
		files.createDirectories(parent);
		Path pending = prepare(parent, target.getFileName() + ".", content);
		Path backupPending = null;

		try {
			if (files.exists(target)) {
				backupPending = prepareCopy(
						target,
						parent,
						target.getFileName() + ".bak."
				);
				files.replace(backupPending, backupPath(target));
				backupPending = null;
			}

			files.replace(pending, target);
			pending = null;
		} finally {
			if (backupPending != null) {
				files.deleteIfExists(backupPending);
			}
			if (pending != null) {
				files.deleteIfExists(pending);
			}
		}
	}

	void replaceWithoutBackup(Path target, byte[] content) throws IOException {
		Path parent = target.getParent();
		files.createDirectories(parent);
		Path pending = prepare(parent, target.getFileName() + ".", content);

		try {
			files.replace(pending, target);
			pending = null;
		} finally {
			if (pending != null) {
				files.deleteIfExists(pending);
			}
		}
	}

	private Path prepare(Path parent, String prefix, byte[] content) throws IOException {
		Path pending = files.createTempFile(parent, prefix, ".tmp");
		boolean ready = false;
		try {
			files.write(pending, content);
			files.sync(pending);
			ready = true;
			return pending;
		} finally {
			if (!ready) {
				files.deleteIfExists(pending);
			}
		}
	}

	private Path prepareCopy(Path source, Path parent, String prefix) throws IOException {
		Path pending = files.createTempFile(parent, prefix, ".tmp");
		boolean ready = false;
		try {
			files.copy(source, pending);
			files.sync(pending);
			ready = true;
			return pending;
		} finally {
			if (!ready) {
				files.deleteIfExists(pending);
			}
		}
	}

	static Path backupPath(Path target) {
		return target.resolveSibling(target.getFileName() + ".bak");
	}

	interface FileOperations {
		void createDirectories(Path directory) throws IOException;

		Path createTempFile(Path directory, String prefix, String suffix) throws IOException;

		void write(Path path, byte[] content) throws IOException;

		void sync(Path path) throws IOException;

		boolean exists(Path path);

		void copy(Path source, Path target) throws IOException;

		void replace(Path source, Path target) throws IOException;

		void deleteIfExists(Path path) throws IOException;
	}

	static class NioFileOperations implements FileOperations {
		@Override
		public void createDirectories(Path directory) throws IOException {
			Files.createDirectories(directory);
		}

		@Override
		public Path createTempFile(Path directory, String prefix, String suffix) throws IOException {
			return Files.createTempFile(directory, prefix, suffix);
		}

		@Override
		public void write(Path path, byte[] content) throws IOException {
			Files.write(path, content, StandardOpenOption.TRUNCATE_EXISTING);
		}

		@Override
		public void sync(Path path) throws IOException {
			try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
				channel.force(true);
			}
		}

		@Override
		public boolean exists(Path path) {
			return Files.exists(path);
		}

		@Override
		public void copy(Path source, Path target) throws IOException {
			Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
		}

		@Override
		public void replace(Path source, Path target) throws IOException {
			try {
				Files.move(
						source,
						target,
						StandardCopyOption.ATOMIC_MOVE,
						StandardCopyOption.REPLACE_EXISTING
				);
			} catch (AtomicMoveNotSupportedException exception) {
				Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
			}
		}

		@Override
		public void deleteIfExists(Path path) throws IOException {
			Files.deleteIfExists(path);
		}
	}
}
