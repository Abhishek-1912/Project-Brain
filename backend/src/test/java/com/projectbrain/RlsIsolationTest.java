package com.projectbrain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.projectbrain.support.PostgresTestBase;

/** Every case connects as brain_app, so row-level security and grants apply. */
class RlsIsolationTest extends PostgresTestBase {

	private static final String SQLSTATE_INSUFFICIENT_PRIVILEGE = "42501";
	private static final String SQLSTATE_FOREIGN_KEY_VIOLATION = "23503";
	private static final String SQLSTATE_UNIQUE_VIOLATION = "23505";
	private static final String SQLSTATE_CHECK_VIOLATION = "23514";

	private static final UUID USER_A = UUID.randomUUID();
	private static final UUID USER_A2 = UUID.randomUUID();
	private static final UUID USER_B = UUID.randomUUID();
	private static final UUID ORG_A = UUID.randomUUID();
	private static final UUID ORG_B = UUID.randomUUID();
	private static final UUID PROJECT_A = UUID.randomUUID();
	private static final UUID PROJECT_B = UUID.randomUUID();
	private static final UUID TASK_A = UUID.randomUUID();
	private static final UUID TASK_B = UUID.randomUUID();
	private static final UUID AUDIT_A = UUID.randomUUID();

	@FunctionalInterface
	private interface SqlWork {

		void run(Connection connection) throws SQLException;

	}

	@BeforeAll
	static void seed() throws SQLException {
		try (Connection admin = adminConnection()) {
			for (UUID user : new UUID[] { USER_A, USER_A2, USER_B }) {
				update(admin, "INSERT INTO brain.profiles (id, email, display_name) VALUES (?, ?, 'User')", user,
						user + "@example.test");
			}
			update(admin, "INSERT INTO brain.organizations (id, name, slug, created_by) VALUES (?, 'Org A', ?, ?)",
					ORG_A, "org-a-" + ORG_A, USER_A);
			update(admin, "INSERT INTO brain.organizations (id, name, slug, created_by) VALUES (?, 'Org B', ?, ?)",
					ORG_B, "org-b-" + ORG_B, USER_B);
			update(admin, "INSERT INTO brain.organization_members (organization_id, user_id, role) VALUES (?, ?, 'OWNER')",
					ORG_A, USER_A);
			update(admin, "INSERT INTO brain.organization_members (organization_id, user_id, role) VALUES (?, ?, 'MEMBER')",
					ORG_A, USER_A2);
			update(admin, "INSERT INTO brain.organization_members (organization_id, user_id, role) VALUES (?, ?, 'OWNER')",
					ORG_B, USER_B);
			update(admin, "INSERT INTO brain.projects (id, organization_id, name, status, created_by) VALUES (?, ?, 'Alpha', 'ACTIVE', ?)",
					PROJECT_A, ORG_A, USER_A);
			update(admin, "INSERT INTO brain.projects (id, organization_id, name, status, created_by) VALUES (?, ?, 'Beta', 'ACTIVE', ?)",
					PROJECT_B, ORG_B, USER_B);
			update(admin, "INSERT INTO brain.tasks (id, organization_id, project_id, title, status, priority, created_by) VALUES (?, ?, ?, 'Task A', 'TODO', 'LOW', ?)",
					TASK_A, ORG_A, PROJECT_A, USER_A);
			update(admin, "INSERT INTO brain.tasks (id, organization_id, project_id, title, status, priority, created_by) VALUES (?, ?, ?, 'Task B', 'TODO', 'LOW', ?)",
					TASK_B, ORG_B, PROJECT_B, USER_B);
			update(admin, "INSERT INTO brain.audit_events (id, organization_id, actor_id, action, entity_type, entity_id, metadata) VALUES (?, ?, ?, 'CREATE', 'TASK', ?, '{}'::jsonb)",
					AUDIT_A, ORG_A, USER_A, TASK_A);
			update(admin, "INSERT INTO brain.audit_events (id, organization_id, actor_id, action, entity_type, entity_id, metadata) VALUES (?, ?, ?, 'CREATE', 'TASK', ?, '{}'::jsonb)",
					UUID.randomUUID(), ORG_B, USER_B, TASK_B);
		}
	}

	@Test
	void noContextSeesNoTenantRows() throws SQLException {
		try (Connection app = appConnection()) {
			assertThat(count(app, "brain.projects")).isZero();
			assertThat(count(app, "brain.tasks")).isZero();
			assertThat(count(app, "brain.audit_events")).isZero();
		}
	}

	@Test
	void orgASeesOnlyOrgAProjects() throws SQLException {
		asTenant(USER_A, ORG_A, app -> assertThat(uuids(app, "SELECT organization_id FROM brain.projects"))
			.containsExactly(ORG_A));
	}

	@Test
	void orgACannotUpdateOrgBTask() throws SQLException {
		asTenant(USER_A, ORG_A, app -> assertThat(
				update(app, "UPDATE brain.tasks SET title = 'hijacked' WHERE id = ?", TASK_B)).isZero());
	}

	@Test
	void orgACannotDeleteOrgBTask() throws SQLException {
		asTenant(USER_A, ORG_A,
				app -> assertThat(update(app, "DELETE FROM brain.tasks WHERE id = ?", TASK_B)).isZero());
	}

	@Test
	void cannotInsertRowForAnotherOrg() {
		assertSqlState(SQLSTATE_INSUFFICIENT_PRIVILEGE, USER_A, ORG_A,
				app -> update(app, "INSERT INTO brain.projects (id, organization_id, name, status, created_by) VALUES (?, ?, 'Intruder', 'ACTIVE', ?)",
						UUID.randomUUID(), ORG_B, USER_A));
	}

	@Test
	void emptyContextDoesNotRaiseCastError() throws SQLException {
		try (Connection app = appConnection()) {
			app.setAutoCommit(false);
			setContext(app, USER_A, ORG_A);
			app.commit();
			// The transaction-local settings now read as empty strings on this connection.
			assertThat(count(app, "brain.projects")).isZero();
			app.rollback();
		}
	}

	@Test
	void taskCannotReferenceOtherOrgProject() {
		assertSqlState(SQLSTATE_FOREIGN_KEY_VIOLATION, USER_A, ORG_A,
				app -> update(app, "INSERT INTO brain.tasks (id, organization_id, project_id, title, status, priority, created_by) VALUES (?, ?, ?, 'Bad', 'TODO', 'LOW', ?)",
						UUID.randomUUID(), ORG_A, PROJECT_B, USER_A));
	}

	@Test
	void assigneeMustBelongToOrg() {
		assertSqlState(SQLSTATE_FOREIGN_KEY_VIOLATION, USER_A, ORG_A,
				app -> update(app, "UPDATE brain.tasks SET assignee_id = ? WHERE id = ?", USER_B, TASK_A));
	}

	@Test
	void auditEventsCannotBeUpdatedOrDeleted() {
		assertSqlState(SQLSTATE_INSUFFICIENT_PRIVILEGE, USER_A, ORG_A,
				app -> update(app, "UPDATE brain.audit_events SET action = 'TAMPERED' WHERE id = ?", AUDIT_A));
		assertSqlState(SQLSTATE_INSUFFICIENT_PRIVILEGE, USER_A, ORG_A,
				app -> update(app, "DELETE FROM brain.audit_events WHERE id = ?", AUDIT_A));
	}

	@Test
	void contextDoesNotSurviveTransaction() throws SQLException {
		try (Connection app = appConnection()) {
			app.setAutoCommit(false);
			setContext(app, USER_A, ORG_A);
			assertThat(count(app, "brain.projects")).isEqualTo(1);
			app.commit();
			assertThat(count(app, "brain.projects")).isZero();
			app.rollback();
		}
	}

	@Test
	void creatorCanCreateOrganizationWithOwnerMembership() throws SQLException {
		UUID newOrg = UUID.randomUUID();
		asTenant(USER_A, newOrg, app -> {
			assertThat(update(app, "INSERT INTO brain.organizations (id, name, slug, created_by) VALUES (?, 'New', ?, ?)",
					newOrg, "new-" + newOrg, USER_A)).isEqualTo(1);
			assertThat(update(app, "INSERT INTO brain.organization_members (organization_id, user_id, role) VALUES (?, ?, 'OWNER')",
					newOrg, USER_A)).isEqualTo(1);
		});
	}

	@Test
	void cannotCreateOrganizationOnBehalfOfAnotherUser() {
		UUID newOrg = UUID.randomUUID();
		assertSqlState(SQLSTATE_INSUFFICIENT_PRIVILEGE, USER_A, newOrg,
				app -> update(app, "INSERT INTO brain.organizations (id, name, slug, created_by) VALUES (?, 'New', ?, ?)",
						newOrg, "new-" + newOrg, USER_B));
	}

	@Test
	void cannotJoinOrganizationCreatedBySomeoneElse() {
		assertSqlState(SQLSTATE_INSUFFICIENT_PRIVILEGE, USER_A, ORG_B,
				app -> update(app, "INSERT INTO brain.organization_members (organization_id, user_id, role) VALUES (?, ?, 'OWNER')",
						ORG_B, USER_A));
	}

	@Test
	void creatorCannotInsertMembershipForAnotherUser() {
		assertSqlState(SQLSTATE_INSUFFICIENT_PRIVILEGE, USER_A, ORG_A,
				app -> update(app, "INSERT INTO brain.organization_members (organization_id, user_id, role) VALUES (?, ?, 'OWNER')",
						ORG_A, USER_B));
	}

	@Test
	void userSeesOwnOrganizationsWithoutOrgContext() throws SQLException {
		asTenant(USER_A, null,
				app -> assertThat(uuids(app, "SELECT id FROM brain.organizations")).containsExactly(ORG_A));
	}

	@Test
	void userWithoutOrgContextSeesOnlyOwnMembership() throws SQLException {
		asTenant(USER_A, null, app -> assertThat(uuids(app, "SELECT user_id FROM brain.organization_members"))
			.containsExactly(USER_A));
	}

	@Test
	void orgContextSeesAllMembersOfThatOrgOnly() throws SQLException {
		asTenant(USER_A, ORG_A, app -> assertThat(uuids(app, "SELECT user_id FROM brain.organization_members"))
			.containsExactlyInAnyOrder(USER_A, USER_A2));
	}

	@Test
	void profilesVisibleOnlyForSelfAndSameOrg() throws SQLException {
		asTenant(USER_A, ORG_A, app -> assertThat(uuids(app, "SELECT id FROM brain.profiles"))
			.containsExactlyInAnyOrder(USER_A, USER_A2));
	}

	@Test
	void cannotUpdateAnotherUsersProfile() throws SQLException {
		asTenant(USER_A, ORG_A, app -> assertThat(
				update(app, "UPDATE brain.profiles SET display_name = 'hijacked' WHERE id = ?", USER_A2)).isZero());
	}

	@Test
	void projectNameIsUniqueIgnoringCase() {
		assertSqlState(SQLSTATE_UNIQUE_VIOLATION, USER_A, ORG_A,
				app -> update(app, "INSERT INTO brain.projects (id, organization_id, name, status, created_by) VALUES (?, ?, 'ALPHA', 'ACTIVE', ?)",
						UUID.randomUUID(), ORG_A, USER_A));
	}

	@Test
	void sameProjectNameAllowedInDifferentOrg() throws SQLException {
		asTenant(USER_A, ORG_A, app -> assertThat(
				update(app, "INSERT INTO brain.projects (id, organization_id, name, status, created_by) VALUES (?, ?, 'Beta', 'ACTIVE', ?)",
						UUID.randomUUID(), ORG_A, USER_A)).isEqualTo(1));
	}

	@Test
	void rejectsUnknownTaskStatus() {
		assertSqlState(SQLSTATE_CHECK_VIOLATION, USER_A, ORG_A,
				app -> update(app, "UPDATE brain.tasks SET status = 'WONTFIX' WHERE id = ?", TASK_A));
	}

	@Test
	void rejectsUnknownTaskPriority() {
		assertSqlState(SQLSTATE_CHECK_VIOLATION, USER_A, ORG_A,
				app -> update(app, "UPDATE brain.tasks SET priority = 'CRITICAL' WHERE id = ?", TASK_A));
	}

	@Test
	void rejectsUnknownProjectStatus() {
		assertSqlState(SQLSTATE_CHECK_VIOLATION, USER_A, ORG_A,
				app -> update(app, "UPDATE brain.projects SET status = 'DELETED' WHERE id = ?", PROJECT_A));
	}

	@Test
	void rejectsUnknownMemberRole() throws SQLException {
		try (Connection admin = adminConnection()) {
			assertThatThrownBy(() -> update(admin,
					"UPDATE brain.organization_members SET role = 'SUPERUSER' WHERE organization_id = ? AND user_id = ?",
					ORG_A, USER_A2))
				.isInstanceOfSatisfying(SQLException.class,
						ex -> assertThat(ex.getSQLState()).isEqualTo(SQLSTATE_CHECK_VIOLATION));
		}
	}

	@Test
	void everyTenantTableForcesRowLevelSecurity() throws SQLException {
		Set<String> forced = new HashSet<>();
		try (Connection app = appConnection();
				PreparedStatement statement = app.prepareStatement("""
						SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
						WHERE n.nspname = 'brain' AND c.relkind = 'r'
						AND c.relrowsecurity AND c.relforcerowsecurity
						""");
				ResultSet rows = statement.executeQuery()) {
			while (rows.next()) {
				forced.add(rows.getString(1));
			}
		}
		assertThat(forced).contains("profiles", "organizations", "organization_members", "projects", "tasks",
				"audit_events");
	}

	/** Runs work in a transaction with tenant context, then rolls back so cases stay independent. */
	private static void asTenant(UUID userId, UUID orgId, SqlWork work) throws SQLException {
		try (Connection app = appConnection()) {
			app.setAutoCommit(false);
			setContext(app, userId, orgId);
			try {
				work.run(app);
			}
			finally {
				app.rollback();
			}
		}
	}

	private static void assertSqlState(String sqlState, UUID userId, UUID orgId, SqlWork work) {
		assertThatThrownBy(() -> asTenant(userId, orgId, work)).isInstanceOfSatisfying(SQLException.class,
				ex -> assertThat(ex.getSQLState()).isEqualTo(sqlState));
	}

	private static void setContext(Connection connection, UUID userId, UUID orgId) throws SQLException {
		try (PreparedStatement statement = connection
			.prepareStatement("SELECT set_config('app.current_user_id', ?, true), set_config('app.current_org_id', ?, true)")) {
			statement.setString(1, userId == null ? "" : userId.toString());
			statement.setString(2, orgId == null ? "" : orgId.toString());
			statement.execute();
		}
	}

	private static int update(Connection connection, String sql, Object... params) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			for (int i = 0; i < params.length; i++) {
				statement.setObject(i + 1, params[i]);
			}
			return statement.executeUpdate();
		}
	}

	private static long count(Connection connection, String table) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("SELECT count(*) FROM " + table);
				ResultSet rows = statement.executeQuery()) {
			rows.next();
			return rows.getLong(1);
		}
	}

	private static Set<UUID> uuids(Connection connection, String sql) throws SQLException {
		Set<UUID> values = new HashSet<>();
		try (PreparedStatement statement = connection.prepareStatement(sql);
				ResultSet rows = statement.executeQuery()) {
			while (rows.next()) {
				values.add(rows.getObject(1, UUID.class));
			}
		}
		return values;
	}

}
