package com.projectbrain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.projectbrain.support.TeamApiTestBase;
import org.junit.jupiter.api.Test;

/** Tenant cases connect as brain_app, so row-level security and grants apply. */
class AccessTokenSchemaTest extends TeamApiTestBase {

	private static final String INSUFFICIENT_PRIVILEGE = "42501";

	@Test
	void accessTokensForceRowLevelSecurity() throws Exception {
		assertThat(countRows("""
				SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
				WHERE n.nspname = 'brain' AND c.relname = 'access_tokens' AND c.relrowsecurity AND c.relforcerowsecurity
				""")).isEqualTo(1);
	}

	@Test
	void userSeesOnlyTheirOwnTokensInTheCurrentOrganization() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner);
		UUID colleague = seedMember(org, "ADMIN");
		UUID otherOrg = seedOrg(owner);
		UUID mine = seedToken(owner, org, hash('a'), "now() + interval '1 day'", null);
		seedToken(colleague, org, hash('b'), "now() + interval '1 day'", null);
		seedToken(owner, otherOrg, hash('c'), "now() + interval '1 day'", null);

		List<UUID> visible = new ArrayList<>();
		try (Connection app = tenant(owner, org);
				PreparedStatement statement = app.prepareStatement("SELECT id FROM brain.access_tokens");
				ResultSet rows = statement.executeQuery()) {
			while (rows.next()) {
				visible.add(rows.getObject(1, UUID.class));
			}
		}

		assertThat(visible).containsExactly(mine);
	}

	@Test
	void appCannotDeleteTokens() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner);
		seedToken(owner, org, hash('d'), "now() + interval '1 day'", null);

		try (Connection app = tenant(owner, org)) {
			assertThatThrownBy(() -> app.prepareStatement("DELETE FROM brain.access_tokens").executeUpdate())
				.isInstanceOfSatisfying(SQLException.class,
						ex -> assertThat(ex.getSQLState()).isEqualTo(INSUFFICIENT_PRIVILEGE));
		}
	}

	@Test
	void resolveReturnsOnlyActiveTokens() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner);
		UUID active = seedToken(owner, org, hash('e'), "now() + interval '1 day'", null);
		seedToken(owner, org, hash('f'), "now() - interval '1 second'", null);
		seedToken(owner, org, hash('1'), "now() + interval '1 day'", "now()");

		assertThat(resolve(hash('e'))).containsExactly(active + " " + owner + " " + org + " READ");
		assertThat(resolve(hash('f'))).isEmpty();
		assertThat(resolve(hash('1'))).isEmpty();
		assertThat(resolve(hash('9'))).isEmpty();
	}

	@Test
	void lastUsedTimeChangesAtMostOnceAMinute() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner);
		UUID token = seedToken(owner, org, hash('2'), "now() + interval '1 day'", null);

		resolve(hash('2'));
		OffsetDateTime first = lastUsed(token);
		resolve(hash('2'));
		OffsetDateTime second = lastUsed(token);
		execute("UPDATE brain.access_tokens SET last_used_at = now() - interval '2 minutes' WHERE id = ?", token);
		resolve(hash('2'));

		assertThat(first).isNotNull();
		assertThat(second).isEqualTo(first);
		assertThat(lastUsed(token)).isAfter(OffsetDateTime.now().minusSeconds(30));
	}

	private static String hash(char fill) {
		return String.valueOf(fill).repeat(64);
	}

	private static UUID seedToken(UUID user, UUID org, String hash, String expires, String revoked) throws Exception {
		UUID id = UUID.randomUUID();
		execute("INSERT INTO brain.access_tokens (id, user_id, organization_id, name, token_hash, display_prefix, scope, created_at, expires_at, revoked_at) "
				+ "VALUES (?, ?, ?, 'Laptop', ?, 'pb_abcde', 'READ', now() - interval '2 days', " + expires + ", " + (revoked == null ? "NULL" : revoked)
				+ ")", id, user, org, hash);
		return id;
	}

	private static List<String> resolve(String hash) throws SQLException {
		List<String> rows = new ArrayList<>();
		try (Connection app = appConnection();
				PreparedStatement statement = app.prepareStatement(
						"SELECT token_id, user_id, org_id, scope FROM brain.resolve_access_token(?)")) {
			statement.setString(1, hash);
			try (ResultSet result = statement.executeQuery()) {
				while (result.next()) {
					rows.add(result.getString(1) + " " + result.getString(2) + " " + result.getString(3) + " "
							+ result.getString(4));
				}
			}
		}
		return rows;
	}

	private static OffsetDateTime lastUsed(UUID token) throws SQLException {
		try (Connection admin = adminConnection();
				PreparedStatement statement = admin
					.prepareStatement("SELECT last_used_at FROM brain.access_tokens WHERE id = ?")) {
			statement.setObject(1, token);
			try (ResultSet result = statement.executeQuery()) {
				result.next();
				return result.getObject(1, OffsetDateTime.class);
			}
		}
	}

	private static Connection tenant(UUID user, UUID org) throws SQLException {
		Connection app = appConnection();
		try (PreparedStatement statement = app.prepareStatement(
				"SELECT set_config('app.current_user_id', ?, false), set_config('app.current_org_id', ?, false)")) {
			statement.setString(1, user.toString());
			statement.setString(2, org.toString());
			statement.execute();
		}
		return app;
	}

}
