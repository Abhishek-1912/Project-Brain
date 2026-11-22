package com.projectbrain.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import org.flywaydb.core.Flyway;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Container;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/** Shared PostgreSQL with real roles: Flyway runs as brain_migrator, the app as brain_app. */
public abstract class PostgresTestBase {

	protected static final String APP_USER = "brain_app";
	protected static final String MIGRATOR_USER = "brain_migrator";
	private static final String APP_PASSWORD = "test_app_password";
	private static final String MIGRATOR_PASSWORD = "test_migrator_password";

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
		.withCopyFileToContainer(MountableFile.forHostPath("db/bootstrap/create_roles.sql"), "/tmp/create_roles.sql");

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
		try {
			Container.ExecResult result = POSTGRES.execInContainer("psql", "-v", "ON_ERROR_STOP=1",
					"-U", POSTGRES.getUsername(), "-d", POSTGRES.getDatabaseName(),
					"-v", "migrator_password=" + MIGRATOR_PASSWORD,
					"-v", "app_password=" + APP_PASSWORD,
					"-f", "/tmp/create_roles.sql");
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

	protected static Connection appConnection() throws SQLException {
		return DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_USER, APP_PASSWORD);
	}

	/** Superuser connection for seeding test data past RLS. */
	protected static Connection adminConnection() throws SQLException {
		return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
	}

}
