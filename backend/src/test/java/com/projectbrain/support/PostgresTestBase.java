package com.projectbrain.support;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Container;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Shared PostgreSQL with real roles: Flyway runs as brain_migrator, the app as brain_app. */
public abstract class PostgresTestBase {

	protected static final String APP_USER = "brain_app";
	protected static final String MIGRATOR_USER = "brain_migrator";
	private static final String APP_PASSWORD = "test_app_password";
	private static final String MIGRATOR_PASSWORD = "test_migrator_password";

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
			DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))
		.withCopyFileToContainer(RoleBootstrapScript.mountable(Path.of("db/bootstrap/create_roles.sql")),
				"/tmp/create_roles.sql")
		.withCopyFileToContainer(RoleBootstrapScript.mountable(Path.of("db/bootstrap/enable_vector.sql")),
				"/tmp/enable_vector.sql");

	static {
		POSTGRES.start();
		bootstrapRoles();
		Flyway.configure()
			.dataSource(POSTGRES.getJdbcUrl(), MIGRATOR_USER, MIGRATOR_PASSWORD)
			.defaultSchema("brain")
			.schemas("brain")
			.createSchemas(false)
			.load()
			.migrate();
	}

	private static void bootstrapRoles() {
		runBootstrap("create_roles.sql");
		runBootstrap("enable_vector.sql");
	}

	private static void runBootstrap(String script) {
		try {
			Container.ExecResult result = POSTGRES.execInContainer("psql", "-v", "ON_ERROR_STOP=1",
					"-U", POSTGRES.getUsername(), "-d", POSTGRES.getDatabaseName(),
					"-v", "migrator_password=" + MIGRATOR_PASSWORD,
					"-v", "app_password=" + APP_PASSWORD,
					"-f", "/tmp/" + script);
			if (result.getExitCode() != 0) {
				throw new IllegalStateException("Role bootstrap failed: " + result.getStderr());
			}
		}
		catch (java.io.IOException ex) {
			throw new IllegalStateException("Role bootstrap failed", ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Role bootstrap interrupted", ex);
		}
	}

	@DynamicPropertySource
	static void databaseProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", () -> APP_USER);
		registry.add("spring.datasource.password", () -> APP_PASSWORD);
		registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
		registry.add("spring.flyway.user", () -> MIGRATOR_USER);
		registry.add("spring.flyway.password", () -> MIGRATOR_PASSWORD);
	}

	/** Creates an empty, bootstrapped database so a test can migrate it step by step. */
	protected static String createBootstrappedDatabase(String name) throws SQLException {
		try (Connection admin = adminConnection(); Statement statement = admin.createStatement()) {
			statement.execute("CREATE DATABASE " + name);
		}
		String url = POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName() + "?", "/" + name + "?");
		try (Connection admin = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
				Statement statement = admin.createStatement()) {
			statement.execute("CREATE SCHEMA brain AUTHORIZATION " + MIGRATOR_USER);
			statement.execute("REVOKE ALL ON SCHEMA brain FROM PUBLIC");
			statement.execute("GRANT USAGE ON SCHEMA brain TO " + APP_USER);
			statement.execute("CREATE SCHEMA IF NOT EXISTS extensions");
			statement.execute("CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA extensions");
			statement.execute("GRANT USAGE ON SCHEMA extensions TO " + MIGRATOR_USER + ", " + APP_USER);
		}
		return url;
	}

	protected static void migrate(String url, String targetVersion) {
		Flyway.configure()
			.dataSource(url, MIGRATOR_USER, MIGRATOR_PASSWORD)
			.defaultSchema("brain")
			.schemas("brain")
			.createSchemas(false)
			.target(targetVersion)
			.load()
			.migrate();
	}

	protected static Connection adminConnection(String url) throws SQLException {
		return DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
	}

	protected static Connection appConnection() throws SQLException {
		return DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_USER, APP_PASSWORD);
	}

	/** Superuser connection for seeding test data past RLS. */
	protected static Connection adminConnection() throws SQLException {
		return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
	}

}
