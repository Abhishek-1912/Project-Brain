package com.projectbrain.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Seeds organization data past RLS for API tests of team and approval features. */
public abstract class TeamApiTestBase extends PostgresTestBase {

	protected static final String ORGS = "/api/v1/organizations";

	protected static RequestPostProcessor as(UUID user) {
		return jwt().jwt(token -> token.subject(user.toString()).claim("email", emailOf(user)));
	}

	protected static String emailOf(UUID user) {
		return "user-" + user + "@example.test";
	}

	protected static UUID seedProfile() throws SQLException {
		UUID user = UUID.randomUUID();
		execute("INSERT INTO brain.profiles (id, email, display_name) VALUES (?, ?, 'User')", user, emailOf(user));
		return user;
	}

	protected static UUID seedOrg(UUID owner) throws SQLException {
		UUID org = UUID.randomUUID();
		execute("INSERT INTO brain.organizations (id, name, slug, created_by) VALUES (?, 'Seeded', ?, ?)", org,
				"seed-" + org, owner);
		addMember(org, owner, "OWNER");
		return org;
	}

	protected static UUID seedMember(UUID org, String role) throws SQLException {
		UUID user = seedProfile();
		addMember(org, user, role);
		return user;
	}

	protected static void addMember(UUID org, UUID user, String role) throws SQLException {
		execute("INSERT INTO brain.organization_members (organization_id, user_id, role) VALUES (?, ?, ?)", org, user,
				role);
	}

	protected static UUID seedProject(UUID org, UUID creator, String status) throws SQLException {
		UUID project = UUID.randomUUID();
		execute("INSERT INTO brain.projects (id, organization_id, name, status, created_by) VALUES (?, ?, ?, ?, ?)",
				project, org, "Project " + project, status, creator);
		return project;
	}

	protected static UUID seedJobRole(UUID org, String name, boolean active) throws SQLException {
		UUID role = UUID.randomUUID();
		execute("INSERT INTO brain.job_roles (id, organization_id, name, active) VALUES (?, ?, ?, ?)", role, org, name,
				active);
		return role;
	}

	protected static void assignJobRole(UUID org, UUID project, UUID user, UUID role, UUID creator)
			throws SQLException {
		execute("INSERT INTO brain.project_job_roles (organization_id, project_id, user_id, job_role_id, created_by) VALUES (?, ?, ?, ?, ?)",
				org, project, user, role, creator);
	}

	protected static void execute(String sql, Object... params) throws SQLException {
		try (Connection admin = adminConnection(); PreparedStatement statement = admin.prepareStatement(sql)) {
			bind(statement, params);
			statement.executeUpdate();
		}
	}

	protected static long countRows(String sql, Object... params) throws SQLException {
		try (Connection admin = adminConnection(); PreparedStatement statement = admin.prepareStatement(sql)) {
			bind(statement, params);
			try (ResultSet rows = statement.executeQuery()) {
				rows.next();
				return rows.getLong(1);
			}
		}
	}

	private static void bind(PreparedStatement statement, Object... params) throws SQLException {
		for (int i = 0; i < params.length; i++) {
			statement.setObject(i + 1, params[i]);
		}
	}

}
