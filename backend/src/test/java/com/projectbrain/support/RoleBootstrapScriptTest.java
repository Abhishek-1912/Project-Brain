package com.projectbrain.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.utility.MountableFile;

class RoleBootstrapScriptTest {

	@TempDir
	Path directory;

	@Test
	void rejectsMissingScript() {
		Path missing = directory.resolve("missing.sql");

		assertThatThrownBy(() -> RoleBootstrapScript.mountable(missing))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining(missing.toString());
	}

	@Test
	void rejectsEmptyScript() throws IOException {
		Path empty = Files.createFile(directory.resolve("empty.sql"));

		assertThatThrownBy(() -> RoleBootstrapScript.mountable(empty))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining(empty.toString());
	}

	@Test
	void rejectsDirectory() {
		assertThatThrownBy(() -> RoleBootstrapScript.mountable(directory))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining(directory.toString());
	}

	@Test
	void acceptsScriptWithContent() throws IOException {
		Path script = Files.writeString(directory.resolve("roles.sql"), "SELECT 1;");

		MountableFile mountable = RoleBootstrapScript.mountable(script);

		assertThat(Path.of(mountable.getResolvedPath()).getFileName().toString()).isEqualTo("roles.sql");
	}

}
