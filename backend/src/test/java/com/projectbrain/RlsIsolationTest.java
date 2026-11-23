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

	@Test
	void organizationBCannotReadJobRolesInvitesTeamsOrReviews() throws SQLException {
		UUID jobRole = UUID.randomUUID();
		try (Connection admin = adminConnection()) {
			insertJobRole(admin, jobRole, ORG_B, "Isolation " + jobRole);
			update(admin, "INSERT INTO brain.project_job_roles (organization_id, project_id, user_id, job_role_id, created_by) VALUES (?, ?, ?, ?, ?)",
					ORG_B, PROJECT_B, USER_B, jobRole, USER_B);
			insertInvite(admin, ORG_B, "hidden-" + jobRole + "@example.test", "MEMBER", tokenHash(), "now() + interval '7 days'");
			insertReview(admin, ORG_B, PROJECT_B, TASK_B, jobRole, USER_B);
		}
		asTenant(USER_A, ORG_A, app -> {
			assertThat(countForOrg(app, "brain.job_roles", ORG_B)).isZero();
			assertThat(countForOrg(app, "brain.project_job_roles", ORG_B)).isZero();
			assertThat(countForOrg(app, "brain.organization_invites", ORG_B)).isZero();
			assertThat(countForOrg(app, "brain.task_reviews", ORG_B)).isZero();
		});
	}

	@Test
	void appRoleCannotUpdateOrDeleteTaskReviews() throws SQLException {
		UUID jobRole = UUID.randomUUID();
		try (Connection admin = adminConnection()) {
			insertJobRole(admin, jobRole, ORG_A, "Append only " + jobRole);
			insertReview(admin, ORG_A, PROJECT_A, TASK_A, jobRole, USER_A);
		}
		assertSqlState(SQLSTATE_INSUFFICIENT_PRIVILEGE, USER_A, ORG_A,
				app -> update(app, "UPDATE brain.task_reviews SET comment = 'changed' WHERE job_role_id = ?", jobRole));
		assertSqlState(SQLSTATE_INSUFFICIENT_PRIVILEGE, USER_A, ORG_A,
				app -> update(app, "DELETE FROM brain.task_reviews WHERE job_role_id = ?", jobRole));
	}

	@Test
	void memberInsertRequiresMatchingPendingInviteHash() throws SQLException {
		UUID invitee = UUID.randomUUID();
		String hash = tokenHash();
		try (Connection admin = adminConnection()) {
			update(admin, "INSERT INTO brain.profiles (id, email, display_name) VALUES (?, ?, 'Invitee')", invitee,
					"ravi-" + invitee + "@example.test");
			insertInvite(admin, ORG_A, "RAVI-" + invitee + "@Example.test", "MEMBER", hash, "now() + interval '7 days'");
		}
		asInvitee(invitee, hash, app -> assertThat(insertMember(app, ORG_A, invitee, "MEMBER")).isEqualTo(1));
		assertInviteRejected(invitee, tokenHash(), "MEMBER");
		assertInviteRejected(invitee, hash, "ADMIN");
		assertInviteRejected(invitee, hash, "OWNER");
	}

	@Test
	void expiredCancelledOrUsedInviteCannotCreateMember() throws SQLException {
		UUID invitee = UUID.randomUUID();
		String email = "late-" + invitee + "@example.test";
		String cancelled = tokenHash();
		String used = tokenHash();
		String expired = tokenHash();
		try (Connection admin = adminConnection()) {
			update(admin, "INSERT INTO brain.profiles (id, email, display_name) VALUES (?, ?, 'Invitee')", invitee, email);
			insertInvite(admin, ORG_A, email, "MEMBER", cancelled, "now() + interval '7 days'");
			update(admin, "UPDATE brain.organization_invites SET cancelled_by = ?, cancelled_at = now() WHERE token_hash = ?",
					USER_A, cancelled);
			insertInvite(admin, ORG_A, email, "MEMBER", used, "now() + interval '7 days'");
			update(admin, "UPDATE brain.organization_invites SET accepted_by = ?, accepted_at = now() WHERE token_hash = ?",
					USER_A2, used);
			insertInvite(admin, ORG_A, email, "MEMBER", expired, "now() - interval '1 second'");
		}
		assertInviteRejected(invitee, cancelled, "MEMBER");
		assertInviteRejected(invitee, used, "MEMBER");
		assertInviteRejected(invitee, expired, "MEMBER");
	}

	@Test
	void inviteForAnotherEmailCannotCreateMember() throws SQLException {
		UUID invitee = UUID.randomUUID();
		String hash = tokenHash();
		try (Connection admin = adminConnection()) {
			update(admin, "INSERT INTO brain.profiles (id, email, display_name) VALUES (?, ?, 'Invitee')", invitee,
					"attacker-" + invitee + "@example.test");
			insertInvite(admin, ORG_A, "victim-" + invitee + "@example.test", "MEMBER", hash, "now() + interval '7 days'");
		}
		assertInviteRejected(invitee, hash, "MEMBER");
	}

	@Test
	void existingOrganizationsReceiveSixDefaultJobRoles() throws SQLException {
		String url = createBootstrappedDatabase("backfill_" + UUID.randomUUID().toString().replace("-", ""));
		migrate(url, "2");
		UUID user = UUID.randomUUID();
		UUID org = UUID.randomUUID();
		try (Connection admin = adminConnection(url)) {
			update(admin, "INSERT INTO brain.profiles (id, email, display_name) VALUES (?, 'old@example.test', 'Old')", user);
			update(admin, "INSERT INTO brain.organizations (id, name, slug, created_by) VALUES (?, 'Old Org', 'old-org', ?)", org, user);
		}

		migrate(url, "latest");

		Set<String> names = new HashSet<>();
		try (Connection admin = adminConnection(url);
				PreparedStatement statement = admin
					.prepareStatement("SELECT name FROM brain.job_roles WHERE organization_id = ? AND active")) {
			statement.setObject(1, org);
			try (ResultSet rows = statement.executeQuery()) {
				while (rows.next()) {
					names.add(rows.getString(1));
				}
			}
		}
		assertThat(names).containsExactlyInAnyOrder("Project Manager", "Architect", "Backend", "Frontend", "QA", "DevOps");
	}

	@Test
	void organizationBCannotReadRequirementData() throws SQLException {
		UUID jobRole = UUID.randomUUID();
		try (Connection admin = adminConnection()) {
			insertJobRole(admin, jobRole, ORG_B, "Requirements " + jobRole);
			UUID document = insertRequirementDocument(admin, ORG_B, PROJECT_B, USER_B);
			update(admin, "INSERT INTO brain.requirement_document_files (document_id, organization_id, content) VALUES (?, ?, ?)",
					document, ORG_B, new byte[] { 1, 2, 3 });
			UUID run = insertAiRun(admin, ORG_B, PROJECT_B, document, USER_B, "SUCCEEDED");
			UUID analysis = UUID.randomUUID();
			update(admin, "INSERT INTO brain.requirement_analyses (id, organization_id, document_id, analysis_version, ai_run_id, result, created_by) VALUES (?, ?, ?, 1, ?, '{}'::jsonb, ?)",
					analysis, ORG_B, document, run, USER_B);
			update(admin, "INSERT INTO brain.clarification_questions (id, organization_id, analysis_id, position, question, reason) VALUES (?, ?, ?, 0, 'Question?', 'Reason')",
					UUID.randomUUID(), ORG_B, analysis);
			UUID story = insertStory(admin, ORG_B, PROJECT_B, document, analysis, jobRole);
			update(admin, "INSERT INTO brain.story_reviews (id, organization_id, project_id, document_id, story_id, story_version, action, actor_id, job_role_id) VALUES (?, ?, ?, ?, ?, 0, 'SUBMITTED', ?, ?)",
					UUID.randomUUID(), ORG_B, PROJECT_B, document, story, USER_B, jobRole);
		}
		asTenant(USER_A, ORG_A, app -> {
			for (String table : new String[] { "brain.requirement_documents", "brain.requirement_document_files",
					"brain.ai_runs", "brain.requirement_analyses", "brain.clarification_questions", "brain.story_drafts",
					"brain.story_reviews" }) {
				assertThat(countForOrg(app, table, ORG_B)).as(table).isZero();
			}
		});
	}

	@Test
	void requirementTablesForceRowLevelSecurity() throws SQLException {
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
		assertThat(forced).contains("requirement_documents", "requirement_document_files", "ai_runs",
				"requirement_analyses", "clarification_questions", "story_drafts", "story_reviews");
	}

	@Test
	void appRoleCannotUpdateOrDeleteStoryReviews() throws SQLException {
		UUID jobRole = UUID.randomUUID();
		UUID story;
		try (Connection admin = adminConnection()) {
			insertJobRole(admin, jobRole, ORG_A, "Review history " + jobRole);
			UUID document = insertRequirementDocument(admin, ORG_A, PROJECT_A, USER_A);
			UUID run = insertAiRun(admin, ORG_A, PROJECT_A, document, USER_A, "SUCCEEDED");
			UUID analysis = UUID.randomUUID();
			update(admin, "INSERT INTO brain.requirement_analyses (id, organization_id, document_id, analysis_version, ai_run_id, result, created_by) VALUES (?, ?, ?, 1, ?, '{}'::jsonb, ?)",
					analysis, ORG_A, document, run, USER_A);
			story = insertStory(admin, ORG_A, PROJECT_A, document, analysis, jobRole);
			update(admin, "INSERT INTO brain.story_reviews (id, organization_id, project_id, document_id, story_id, story_version, action, actor_id, job_role_id) VALUES (?, ?, ?, ?, ?, 0, 'SUBMITTED', ?, ?)",
					UUID.randomUUID(), ORG_A, PROJECT_A, document, story, USER_A, jobRole);
		}
		assertSqlState(SQLSTATE_INSUFFICIENT_PRIVILEGE, USER_A, ORG_A,
				app -> update(app, "UPDATE brain.story_reviews SET comment = 'changed' WHERE story_id = ?", story));
		assertSqlState(SQLSTATE_INSUFFICIENT_PRIVILEGE, USER_A, ORG_A,
				app -> update(app, "DELETE FROM brain.story_reviews WHERE story_id = ?", story));
	}

	@Test
	void documentCannotHaveTwoActiveRuns() throws SQLException {
		UUID document;
		try (Connection admin = adminConnection()) {
			document = insertRequirementDocument(admin, ORG_A, PROJECT_A, USER_A);
			insertAiRun(admin, ORG_A, PROJECT_A, document, USER_A, "FAILED");
			insertAiRun(admin, ORG_A, PROJECT_A, document, USER_A, "RUNNING");
		}
		assertSqlState(SQLSTATE_UNIQUE_VIOLATION, USER_A, ORG_A,
				app -> insertAiRun(app, ORG_A, PROJECT_A, document, USER_A, "QUEUED"));
	}

	@Test
	void oneStoryCannotCreateTwoTasks() throws SQLException {
		UUID jobRole = UUID.randomUUID();
		UUID story;
		try (Connection admin = adminConnection()) {
			insertJobRole(admin, jobRole, ORG_A, "One task " + jobRole);
			story = insertStoryWithParents(admin, ORG_A, PROJECT_A, USER_A, jobRole);
			insertStoryTask(admin, ORG_A, PROJECT_A, USER_A, story);
		}
		assertSqlState(SQLSTATE_UNIQUE_VIOLATION, USER_A, ORG_A,
				app -> insertStoryTask(app, ORG_A, PROJECT_A, USER_A, story));
	}

	@Test
	void storiesCannotReferenceAnotherOrganizationsRoleOrTask() throws SQLException {
		UUID roleA = UUID.randomUUID();
		UUID roleB = UUID.randomUUID();
		UUID story;
		UUID document;
		UUID analysis;
		try (Connection admin = adminConnection()) {
			insertJobRole(admin, roleA, ORG_A, "Boundary " + roleA);
			insertJobRole(admin, roleB, ORG_B, "Boundary " + roleB);
			document = insertRequirementDocument(admin, ORG_A, PROJECT_A, USER_A);
			UUID run = insertAiRun(admin, ORG_A, PROJECT_A, document, USER_A, "SUCCEEDED");
			analysis = UUID.randomUUID();
			update(admin, "INSERT INTO brain.requirement_analyses (id, organization_id, document_id, analysis_version, ai_run_id, result, created_by) VALUES (?, ?, ?, 1, ?, '{}'::jsonb, ?)",
					analysis, ORG_A, document, run, USER_A);
			story = insertStory(admin, ORG_A, PROJECT_A, document, analysis, roleA);
		}
		UUID finalDocument = document;
		UUID finalAnalysis = analysis;
		assertSqlState(SQLSTATE_FOREIGN_KEY_VIOLATION, USER_A, ORG_A,
				app -> insertStory(app, ORG_A, PROJECT_A, finalDocument, finalAnalysis, roleB));
		assertSqlState(SQLSTATE_FOREIGN_KEY_VIOLATION, USER_A, ORG_A,
				app -> update(app, "UPDATE brain.story_drafts SET task_id = ? WHERE id = ?", TASK_B, story));
	}

	@Test
	void deletingConvertedTaskKeepsStoryAndClearsLink() throws SQLException {
		UUID jobRole = UUID.randomUUID();
		UUID story;
		UUID task;
		try (Connection admin = adminConnection()) {
			insertJobRole(admin, jobRole, ORG_A, "Deleted task " + jobRole);
			story = insertStoryWithParents(admin, ORG_A, PROJECT_A, USER_A, jobRole);
			task = insertStoryTask(admin, ORG_A, PROJECT_A, USER_A, story);
			update(admin, "UPDATE brain.story_drafts SET status = 'CONVERTED', task_id = ? WHERE id = ?", task, story);
		}
		asTenant(USER_A, ORG_A, app -> {
			assertThat(update(app, "DELETE FROM brain.tasks WHERE id = ?", task)).isEqualTo(1);
			assertThat(uuids(app, "SELECT id FROM brain.story_drafts WHERE status = 'CONVERTED' AND task_id IS NULL"))
				.contains(story);
		});
	}

	@Test
	void uploadRowRequiresDummyDataConfirmation() {
		assertSqlState(SQLSTATE_CHECK_VIOLATION, USER_A, ORG_A,
				app -> update(app, """
						INSERT INTO brain.requirement_documents
						    (id, organization_id, project_id, name, media_type, size_bytes, sha256, extracted_text,
						     dummy_data_confirmed, uploaded_by)
						VALUES (?, ?, ?, 'real.pdf', 'application/pdf', 10, ?, 'Text', false, ?)
						""", UUID.randomUUID(), ORG_A, PROJECT_A, tokenHash(), USER_A));
	}

	private static UUID insertRequirementDocument(Connection connection, UUID orgId, UUID projectId, UUID user)
			throws SQLException {
		UUID id = UUID.randomUUID();
		update(connection, """
				INSERT INTO brain.requirement_documents
				    (id, organization_id, project_id, name, media_type, size_bytes, sha256, extracted_text,
				     dummy_data_confirmed, uploaded_by)
				VALUES (?, ?, ?, 'dummy.md', 'text/markdown', 10, ?, 'Dummy text', true, ?)
				""", id, orgId, projectId, tokenHash(), user);
		return id;
	}

	private static UUID insertAiRun(Connection connection, UUID orgId, UUID projectId, UUID document, UUID user,
			String runStatus) throws SQLException {
		UUID id = UUID.randomUUID();
		update(connection, """
				INSERT INTO brain.ai_runs (id, organization_id, project_id, document_id, run_type, status, requested_by)
				VALUES (?, ?, ?, ?, 'ANALYSIS', ?, ?)
				""", id, orgId, projectId, document, runStatus, user);
		return id;
	}

	private static UUID insertStory(Connection connection, UUID orgId, UUID projectId, UUID document, UUID analysis,
			UUID jobRole) throws SQLException {
		UUID id = UUID.randomUUID();
		update(connection, """
				INSERT INTO brain.story_drafts
				    (id, organization_id, project_id, document_id, analysis_id, title, as_a, i_want, so_that,
				     acceptance_criteria, priority, job_role_id)
				VALUES (?, ?, ?, ?, ?, 'Story', 'user', 'a feature', 'value', '["Works"]'::jsonb, 'MEDIUM', ?)
				""", id, orgId, projectId, document, analysis, jobRole);
		return id;
	}

	private static UUID insertStoryWithParents(Connection connection, UUID orgId, UUID projectId, UUID user, UUID jobRole)
			throws SQLException {
		UUID document = insertRequirementDocument(connection, orgId, projectId, user);
		UUID run = insertAiRun(connection, orgId, projectId, document, user, "SUCCEEDED");
		UUID analysis = UUID.randomUUID();
		update(connection, "INSERT INTO brain.requirement_analyses (id, organization_id, document_id, analysis_version, ai_run_id, result, created_by) VALUES (?, ?, ?, 1, ?, '{}'::jsonb, ?)",
				analysis, orgId, document, run, user);
		return insertStory(connection, orgId, projectId, document, analysis, jobRole);
	}

	private static UUID insertStoryTask(Connection connection, UUID orgId, UUID projectId, UUID user, UUID story)
			throws SQLException {
		UUID task = UUID.randomUUID();
		update(connection, """
				INSERT INTO brain.tasks (id, organization_id, project_id, title, status, priority, created_by, source_story_id)
				VALUES (?, ?, ?, 'From story', 'BACKLOG', 'MEDIUM', ?, ?)
				""", task, orgId, projectId, user, story);
		return task;
	}

	private static String tokenHash() {
		return (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "");
	}

	private static void insertJobRole(Connection connection, UUID id, UUID orgId, String name) throws SQLException {
		update(connection, "INSERT INTO brain.job_roles (id, organization_id, name) VALUES (?, ?, ?)", id, orgId, name);
	}

	private static void insertInvite(Connection connection, UUID orgId, String email, String role, String hash,
			String expiresAtSql) throws SQLException {
		UUID creator = orgId.equals(ORG_A) ? USER_A : USER_B;
		update(connection, "INSERT INTO brain.organization_invites (id, organization_id, email, role, token_hash, created_by, expires_at) VALUES (?, ?, ?, ?, ?, ?, "
				+ expiresAtSql + ")", UUID.randomUUID(), orgId, email, role, hash, creator);
	}

	private static void insertReview(Connection connection, UUID orgId, UUID projectId, UUID taskId, UUID jobRole,
			UUID actor) throws SQLException {
		update(connection, "INSERT INTO brain.task_reviews (id, organization_id, project_id, task_id, task_version, action, actor_id, job_role_id) VALUES (?, ?, ?, ?, 0, 'SUBMITTED', ?, ?)",
				UUID.randomUUID(), orgId, projectId, taskId, actor, jobRole);
	}

	private static int insertMember(Connection connection, UUID orgId, UUID userId, String role) throws SQLException {
		return update(connection, "INSERT INTO brain.organization_members (organization_id, user_id, role) VALUES (?, ?, ?)",
				orgId, userId, role);
	}

	private static long countForOrg(Connection connection, String table, UUID orgId) throws SQLException {
		try (PreparedStatement statement = connection
			.prepareStatement("SELECT count(*) FROM " + table + " WHERE organization_id = ?")) {
			statement.setObject(1, orgId);
			try (ResultSet rows = statement.executeQuery()) {
				rows.next();
				return rows.getLong(1);
			}
		}
	}

	private static void assertInviteRejected(UUID invitee, String hash, String role) {
		assertThatThrownBy(() -> asInvitee(invitee, hash, app -> insertMember(app, ORG_A, invitee, role)))
			.isInstanceOfSatisfying(SQLException.class,
					ex -> assertThat(ex.getSQLState()).isEqualTo(SQLSTATE_INSUFFICIENT_PRIVILEGE));
	}

	private static void asInvitee(UUID userId, String hash, SqlWork work) throws SQLException {
		try (Connection app = appConnection()) {
			app.setAutoCommit(false);
			setContext(app, userId, null);
			try (PreparedStatement statement = app
				.prepareStatement("SELECT set_config('app.current_invite_hash', ?, true)")) {
				statement.setString(1, hash);
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
