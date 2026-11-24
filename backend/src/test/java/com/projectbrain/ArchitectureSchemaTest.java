package com.projectbrain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import com.projectbrain.support.TeamApiTestBase;
import org.junit.jupiter.api.Test;

/** Tenant cases connect as brain_app, so row-level security and grants apply. */
class ArchitectureSchemaTest extends TeamApiTestBase {

	private static final String INSUFFICIENT_PRIVILEGE = "42501";

	private static final String UNIQUE_VIOLATION = "23505";

	private static final String CHECK_VIOLATION = "23514";

	@FunctionalInterface
	private interface SqlWork {

		void run(Connection connection) throws SQLException;

	}

	private record Tenant(UUID user, UUID org, UUID project) {
	}

	@Test
	void architectureRunNeedsNoDocumentButOthersDo() throws Exception {
		Tenant t = tenant();
		UUID document = document(t);

		asTenant(t, app -> insertRun(app, t, null, "ARCHITECTURE", "QUEUED"));
		assertState(CHECK_VIOLATION, t, app -> insertRun(app, t, null, "ANALYSIS", "QUEUED"));
		assertState(CHECK_VIOLATION, t, app -> insertRun(app, t, document, "ARCHITECTURE", "QUEUED"));
		assertState(CHECK_VIOLATION, t, app -> update(app, """
				INSERT INTO brain.ai_runs (id, organization_id, project_id, run_type, status, requested_by, current_step)
				VALUES (?, ?, ?, 'ARCHITECTURE', 'RUNNING', ?, 'DIAGRAMS')
				""", UUID.randomUUID(), t.org(), t.project(), t.user()));
	}

	@Test
	void projectCanHaveOnlyOneActiveArchitectureRun() throws Exception {
		Tenant t = tenant();

		assertState(UNIQUE_VIOLATION, t, app -> {
			insertRun(app, t, null, "ARCHITECTURE", "QUEUED");
			insertRun(app, t, null, "ARCHITECTURE", "RUNNING");
		});
		asTenant(t, app -> {
			insertRun(app, t, null, "ARCHITECTURE", "FAILED");
			insertRun(app, t, null, "ARCHITECTURE", "SUCCEEDED");
			insertRun(app, t, null, "ARCHITECTURE", "QUEUED");
		});
	}

	@Test
	void projectCanHaveOnlyOneDraftAndOneApprovedVersion() throws Exception {
		Tenant t = tenant();

		assertState(UNIQUE_VIOLATION, t, app -> {
			insertVersion(app, t, 1, "DRAFT");
			insertVersion(app, t, 2, "DRAFT");
		});
		assertState(UNIQUE_VIOLATION, t, app -> {
			insertVersion(app, t, 1, "APPROVED");
			insertVersion(app, t, 2, "APPROVED");
		});
		assertState(UNIQUE_VIOLATION, t, app -> {
			insertVersion(app, t, 1, "SUPERSEDED");
			insertVersion(app, t, 1, "REJECTED");
		});
		asTenant(t, app -> {
			insertVersion(app, t, 1, "SUPERSEDED");
			insertVersion(app, t, 2, "APPROVED");
			insertVersion(app, t, 3, "REJECTED");
			insertVersion(app, t, 4, "DRAFT");
		});
	}

	@Test
	void architectureTablesAreTenantIsolated() throws Exception {
		Tenant a = tenant();
		Tenant b = tenant();
		UUID run = UUID.randomUUID();
		execute("""
				INSERT INTO brain.ai_runs (id, organization_id, project_id, run_type, status, requested_by)
				VALUES (?, ?, ?, 'ARCHITECTURE', 'SUCCEEDED', ?)
				""", run, b.org(), b.project(), b.user());
		UUID version = UUID.randomUUID();
		execute("""
				INSERT INTO brain.architecture_versions
				    (id, organization_id, project_id, version_number, status, ai_run_id, overview, data_model, api_design,
				     story_ids, generated_by)
				VALUES (?, ?, ?, 1, 'DRAFT', ?, '{}', '{}', '{}', '[]', ?)
				""", version, b.org(), b.project(), run, b.user());
		execute("""
				INSERT INTO brain.architecture_run_steps (organization_id, ai_run_id, step, output, provider, model, prompt_version)
				VALUES (?, ?, 'OVERVIEW', '{}', 'gemini', 'm', 'architecture-overview-v1')
				""", b.org(), run);
		execute("""
				INSERT INTO brain.architecture_reviews
				    (id, organization_id, project_id, version_id, content_version, action, actor_id, approval_basis, comment)
				VALUES (?, ?, ?, ?, 0, 'REJECTED', ?, 'OWNER_FALLBACK', 'No')
				""", UUID.randomUUID(), b.org(), b.project(), version, b.user());

		asTenant(a, app -> {
			for (String table : new String[] { "architecture_versions", "architecture_run_steps", "architecture_reviews" }) {
				assertThat(count(app, "SELECT count(*) FROM brain." + table)).as(table).isZero();
			}
		});
		assertState(INSUFFICIENT_PRIVILEGE, a, app -> insertVersion(app, b, 1, "DRAFT"));
	}

	@Test
	void architectureTablesForceRowLevelSecurity() throws Exception {
		assertThat(countRows("""
				SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
				WHERE n.nspname = 'brain' AND c.relrowsecurity AND c.relforcerowsecurity
				  AND c.relname IN ('architecture_versions', 'architecture_run_steps', 'architecture_reviews')
				""")).isEqualTo(3);
	}

	@Test
	void architectureReviewsAndStepsAreAppendOnlyForBrainApp() throws Exception {
		Tenant t = tenant();
		UUID run = UUID.randomUUID();
		UUID version = UUID.randomUUID();
		execute("""
				INSERT INTO brain.ai_runs (id, organization_id, project_id, run_type, status, requested_by)
				VALUES (?, ?, ?, 'ARCHITECTURE', 'SUCCEEDED', ?)
				""", run, t.org(), t.project(), t.user());
		execute("""
				INSERT INTO brain.architecture_versions
				    (id, organization_id, project_id, version_number, status, ai_run_id, overview, data_model, api_design,
				     story_ids, generated_by)
				VALUES (?, ?, ?, 1, 'REJECTED', ?, '{}', '{}', '{}', '[]', ?)
				""", version, t.org(), t.project(), run, t.user());
		execute("""
				INSERT INTO brain.architecture_reviews
				    (id, organization_id, project_id, version_id, content_version, action, actor_id, approval_basis, comment)
				VALUES (?, ?, ?, ?, 0, 'REJECTED', ?, 'OWNER_FALLBACK', 'No')
				""", UUID.randomUUID(), t.org(), t.project(), version, t.user());
		execute("""
				INSERT INTO brain.architecture_run_steps (organization_id, ai_run_id, step, output, provider, model, prompt_version)
				VALUES (?, ?, 'OVERVIEW', '{}', 'gemini', 'm', 'architecture-overview-v1')
				""", t.org(), run);

		assertState(INSUFFICIENT_PRIVILEGE, t, app -> update(app, "UPDATE brain.architecture_reviews SET comment = 'x'"));
		assertState(INSUFFICIENT_PRIVILEGE, t, app -> update(app, "DELETE FROM brain.architecture_reviews"));
		assertState(INSUFFICIENT_PRIVILEGE, t, app -> update(app, "UPDATE brain.architecture_run_steps SET model = 'x'"));
		assertState(INSUFFICIENT_PRIVILEGE, t, app -> update(app, "DELETE FROM brain.architecture_versions"));
		assertState(CHECK_VIOLATION, t, app -> update(app, """
				INSERT INTO brain.architecture_reviews
				    (id, organization_id, project_id, version_id, content_version, action, actor_id, approval_basis, comment)
				VALUES (?, ?, ?, ?, 0, 'REJECTED', ?, 'OWNER_FALLBACK', '   ')
				""", UUID.randomUUID(), t.org(), t.project(), version, t.user()));
	}

	@Test
	void restartRecoveryFailsActiveArchitectureRuns() throws Exception {
		Tenant t = tenant();
		UUID run = UUID.randomUUID();
		execute("""
				INSERT INTO brain.ai_runs (id, organization_id, project_id, run_type, status, requested_by, current_step)
				VALUES (?, ?, ?, 'ARCHITECTURE', 'RUNNING', ?, 'DATA_MODEL')
				""", run, t.org(), t.project(), t.user());

		try (Connection app = appConnection(); PreparedStatement statement = app
			.prepareStatement("SELECT brain.fail_abandoned_ai_runs()")) {
			statement.execute();
		}

		assertThat(countRows("SELECT count(*) FROM brain.ai_runs WHERE id = ? AND status = 'FAILED' AND error_code = 'ABANDONED'",
				run))
			.isEqualTo(1);
	}

	private static Tenant tenant() throws Exception {
		UUID user = seedProfile();
		UUID org = seedOrg(user);
		return new Tenant(user, org, seedProject(org, user, "ACTIVE"));
	}

	private static UUID document(Tenant t) throws Exception {
		UUID id = UUID.randomUUID();
		execute("""
				INSERT INTO brain.requirement_documents
				    (id, organization_id, project_id, name, media_type, size_bytes, sha256, extracted_text,
				     dummy_data_confirmed, uploaded_by)
				VALUES (?, ?, ?, 'dummy.md', 'text/markdown', 10, ?, 'Dummy', true, ?)
				""", id, t.org(), t.project(), "a".repeat(64), t.user());
		return id;
	}

	private static void insertRun(Connection app, Tenant t, UUID document, String type, String status)
			throws SQLException {
		update(app, """
				INSERT INTO brain.ai_runs (id, organization_id, project_id, document_id, run_type, status, requested_by)
				VALUES (?, ?, ?, ?, ?, ?, ?)
				""", UUID.randomUUID(), t.org(), t.project(), document, type, status, t.user());
	}

	private static void insertVersion(Connection app, Tenant t, int number, String status) throws SQLException {
		UUID run = UUID.randomUUID();
		update(app, """
				INSERT INTO brain.ai_runs (id, organization_id, project_id, run_type, status, requested_by)
				VALUES (?, ?, ?, 'ARCHITECTURE', 'SUCCEEDED', ?)
				""", run, t.org(), t.project(), t.user());
		update(app, """
				INSERT INTO brain.architecture_versions
				    (id, organization_id, project_id, version_number, status, ai_run_id, overview, data_model, api_design,
				     story_ids, generated_by)
				VALUES (?, ?, ?, ?, ?, ?, '{}', '{}', '{}', '[]', ?)
				""", UUID.randomUUID(), t.org(), t.project(), number, status, run, t.user());
	}

	private static void asTenant(Tenant t, SqlWork work) throws SQLException {
		try (Connection app = appConnection()) {
			app.setAutoCommit(false);
			try (PreparedStatement statement = app.prepareStatement(
					"SELECT set_config('app.current_user_id', ?, true), set_config('app.current_org_id', ?, true)")) {
				statement.setString(1, t.user().toString());
				statement.setString(2, t.org().toString());
				statement.execute();
			}
			try {
				work.run(app);
			}
			finally {
				app.rollback();
			}
		}
	}

	private static void assertState(String sqlState, Tenant t, SqlWork work) {
		assertThatThrownBy(() -> asTenant(t, work)).isInstanceOfSatisfying(SQLException.class,
				ex -> assertThat(ex.getSQLState()).isEqualTo(sqlState));
	}

	private static int update(Connection connection, String sql, Object... params) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			for (int i = 0; i < params.length; i++) {
				statement.setObject(i + 1, params[i]);
			}
			return statement.executeUpdate();
		}
	}

	private static long count(Connection connection, String sql) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(sql); ResultSet rows = statement.executeQuery()) {
			rows.next();
			return rows.getLong(1);
		}
	}

}
