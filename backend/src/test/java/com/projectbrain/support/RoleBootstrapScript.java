package com.projectbrain.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.testcontainers.utility.MountableFile;

/** Testcontainers copies a missing host file as an empty one, which psql then runs successfully. */
final class RoleBootstrapScript {

	private RoleBootstrapScript() {
	}

	static MountableFile mountable(Path script) {
		try {
			if (!Files.isRegularFile(script) || Files.size(script) == 0) {
				throw new IllegalStateException("Role bootstrap script is missing or empty: " + script.toAbsolutePath());
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Cannot read role bootstrap script: " + script.toAbsolutePath(), ex);
		}
		return MountableFile.forHostPath(script);
	}

}
