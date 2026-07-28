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
		Path pending = null;
		Path backupPending = null;

		try {
			pending = files.createTempFile(parent, target.getFileName() + ".", ".tmp");
			files.write(pending, content);
			files.sync(pending);

			if (files.exists(target)) {
				backupPending = files.createTempFile(parent, target.getFileName() + ".bak.", ".tmp");
				files.copy(target, backupPending);
				files.sync(backupPending);
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

	static final class NioFileOperations implements FileOperations {
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
