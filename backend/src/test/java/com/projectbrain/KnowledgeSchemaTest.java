package com.projectbrain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import com.projectbrain.support.TeamApiTestBase;
import org.junit.jupiter.api.Test;

/** Tenant cases connect as brain_app, so row-level security and grants apply. */
class KnowledgeSchemaTest extends TeamApiTestBase {

	private static final String INSUFFICIENT_PRIVILEGE = "42501";

	private static final String DATA_EXCEPTION = "22000";

	@FunctionalInterface
	private interface SqlWork {

		void run(Connection connection) throws SQLException;

	}

	private record Tenant(UUID user, UUID org, UUID project) {
	}

	@Test
	void knowledgeTablesForceRowLevelSecurity() throws Exception {
		assertThat(countRows("""
				SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
				WHERE n.nspname = 'brain' AND c.relrowsecurity AND c.relforcerowsecurity
				  AND c.relname IN ('knowledge_sources', 'knowledge_chunks', 'knowledge_notes', 'knowledge_answers')
				""")).isEqualTo(4);
	}

	@Test
	void chunkEmbeddingMustHave384Dimensions() throws Exception {
		Tenant t = tenant();

		asTenant(t, app -> insertChunk(app, t, insertSource(app, t, "READY"), 0, 384));
		assertState(DATA_EXCEPTION, t, app -> insertChunk(app, t, insertSource(app, t, "READY"), 0, 383));
	}

	@Test
	void tenantCannotSeeAnotherOrganizationsKnowledge() throws Exception {
		Tenant a = tenant();
		Tenant b = tenant();
		execute("""
				INSERT INTO brain.knowledge_sources (id, organization_id, project_id, source_type, source_id, title, status, queued_by)
				VALUES (?, ?, ?, 'NOTE', ?, 'Other', 'READY', ?)
				""", UUID.randomUUID(), b.org(), b.project(), UUID.randomUUID(), b.user());

		asTenant(a, app -> assertThat(count(app, "SELECT count(*) FROM brain.knowledge_sources")).isZero());
		assertState(INSUFFICIENT_PRIVILEGE, a, app -> insertSource(app, b, "PENDING"));
	}

	@Test
	void deletingSourceDeletesItsChunks() throws Exception {
		Tenant t = tenant();

		asTenant(t, app -> {
			UUID source = insertSource(app, t, "READY");
			insertChunk(app, t, source, 0, 384);
			insertChunk(app, t, source, 1, 384);
			update(app, "DELETE FROM brain.knowledge_sources WHERE id = ?", source);
			assertThat(count(app, "SELECT count(*) FROM brain.knowledge_chunks")).isZero();
		});
	}

	@Test
	void answersAreAppendOnly() throws Exception {
		Tenant t = tenant();

		asTenant(t, app -> update(app, """
				INSERT INTO brain.knowledge_answers (id, organization_id, project_id, asked_by, question, found)
				VALUES (?, ?, ?, ?, 'Dummy?', false)
				""", UUID.randomUUID(), t.org(), t.project(), t.user()));
		assertState(INSUFFICIENT_PRIVILEGE, t, app -> update(app, "UPDATE brain.knowledge_answers SET found = true"));
		assertState(INSUFFICIENT_PRIVILEGE, t, app -> update(app, "DELETE FROM brain.knowledge_answers"));
	}

	@Test
	void requeueReturnsInterruptedSourcesAcrossOrganizations() throws Exception {
		Tenant a = tenant();
		Tenant b = tenant();
		UUID indexing = seedSource(a, "INDEXING");
		UUID pending = seedSource(b, "PENDING");
		UUID ready = seedSource(b, "READY");

		List<UUID> returned = new ArrayList<>();
		try (Connection app = appConnection();
				PreparedStatement statement = app.prepareStatement(
						"SELECT source_row_id FROM brain.requeue_interrupted_knowledge()");
				ResultSet rows = statement.executeQuery()) {
			while (rows.next()) {
				returned.add(rows.getObject(1, UUID.class));
			}
		}

		assertThat(returned).contains(indexing, pending).doesNotContain(ready);
		assertThat(countRows("SELECT count(*) FROM brain.knowledge_sources WHERE id = ? AND status = 'PENDING'",
				indexing)).isEqualTo(1);
		assertThat(countRows("SELECT count(*) FROM brain.knowledge_sources WHERE id = ? AND status = 'READY'", ready))
			.isEqualTo(1);
	}

	private static Tenant tenant() throws Exception {
		UUID user = seedProfile();
		UUID org = seedOrg(user);
		return new Tenant(user, org, seedProject(org, user, "ACTIVE"));
	}

	private static UUID seedSource(Tenant t, String status) throws Exception {
		UUID id = UUID.randomUUID();
		execute("""
				INSERT INTO brain.knowledge_sources (id, organization_id, project_id, source_type, source_id, title, status, queued_by)
				VALUES (?, ?, ?, 'NOTE', ?, 'Dummy', ?, ?)
				""", id, t.org(), t.project(), UUID.randomUUID(), status, t.user());
		return id;
	}

	private static UUID insertSource(Connection app, Tenant t, String status) throws SQLException {
		UUID id = UUID.randomUUID();
		update(app, """
				INSERT INTO brain.knowledge_sources (id, organization_id, project_id, source_type, source_id, title, status, queued_by)
				VALUES (?, ?, ?, 'NOTE', ?, 'Dummy', ?, ?)
				""", id, t.org(), t.project(), UUID.randomUUID(), status, t.user());
		return id;
	}

	private static void insertChunk(Connection app, Tenant t, UUID source, int position, int dimensions)
			throws SQLException {
		update(app, """
				INSERT INTO brain.knowledge_chunks (id, organization_id, project_id, source_row_id, position, content, embedding)
				VALUES (?, ?, ?, ?, ?, 'Users can book a table.', CAST(? AS extensions.vector))
				""", UUID.randomUUID(), t.org(), t.project(), source, position,
				"[" + String.join(",", Collections.nCopies(dimensions, "0.1")) + "]");
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
		try (PreparedStatement statement = connection.prepareStatement(sql);
				ResultSet rows = statement.executeQuery()) {
			rows.next();
			return rows.getLong(1);
		}
	}

}
