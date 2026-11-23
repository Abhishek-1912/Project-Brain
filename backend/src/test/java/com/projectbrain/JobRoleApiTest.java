package com.projectbrain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import com.projectbrain.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = { "SUPABASE_URL=http://127.0.0.1:1", "APP_CORS_ALLOWED_ORIGINS=http://localhost:5173" })
class JobRoleApiTest extends PostgresTestBase {

	private static final String ORGS = "/api/v1/organizations";

	@Autowired
	private MockMvc mvc;

	@Test
	void newOrganizationGetsDefaultJobRoles() throws Exception {
		UUID owner = UUID.randomUUID();
		String body = mvc.perform(post(ORGS).with(as(owner)).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"Acme\",\"slug\":\"org-" + UUID.randomUUID() + "\"}"))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		UUID org = UUID.fromString(body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"));

		mvc.perform(get(roles(org)).with(as(owner)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalItems").value(6))
			.andExpect(jsonPath("$.items[*].name",
					containsInAnyOrder("Project Manager", "Architect", "Backend", "Frontend", "QA", "DevOps")))
			.andExpect(jsonPath("$.items[0].active").value(true));
	}

	@Test
	void ownerCanCreateRenameAndDeactivateJobRole() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner, "OWNER");

		UUID role = createRole(org, owner, "  Security  ");

		mvc.perform(get(roles(org)).with(as(owner)))
			.andExpect(jsonPath("$.items[*].name", hasItem("Security")));
		mvc.perform(patch(roles(org) + "/" + role).with(as(owner)).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"AppSec\",\"version\":0}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("AppSec"))
			.andExpect(jsonPath("$.version").value(1));
		mvc.perform(patch(roles(org) + "/" + role).with(as(owner)).contentType(MediaType.APPLICATION_JSON)
			.content("{\"active\":false,\"version\":1}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("AppSec"))
			.andExpect(jsonPath("$.active").value(false));
		assertThat(auditCount(org, owner, "JOB_ROLE_CREATED", role)).isEqualTo(1);
		assertThat(auditCount(org, owner, "JOB_ROLE_UPDATED", role)).isEqualTo(2);
	}

	@Test
	void adminCannotManageJobRoles() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner, "OWNER");
		UUID admin = seedMember(org, "ADMIN");
		UUID role = createRole(org, owner, "Security");

		mvc.perform(get(roles(org)).with(as(admin))).andExpect(status().isOk());
		mvc.perform(post(roles(org)).with(as(admin)).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"Data\"}"))
			.andExpect(status().isForbidden());
		mvc.perform(patch(roles(org) + "/" + role).with(as(admin)).contentType(MediaType.APPLICATION_JSON)
			.content("{\"active\":false,\"version\":0}"))
			.andExpect(status().isForbidden());
	}

	@Test
	void duplicateJobRoleNameIgnoringCaseReturns409() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner, "OWNER");
		UUID first = createRole(org, owner, "Security");
		UUID second = createRole(org, owner, "Data");

		mvc.perform(post(roles(org)).with(as(owner)).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"  SECURITY \"}"))
			.andExpect(status().isConflict());
		mvc.perform(patch(roles(org) + "/" + second).with(as(owner)).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"security\",\"version\":0}"))
			.andExpect(status().isConflict());
		assertThat(first).isNotEqualTo(second);
	}

	@Test
	void staleVersionReturns409() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner, "OWNER");
		UUID role = createRole(org, owner, "Security");

		mvc.perform(patch(roles(org) + "/" + role).with(as(owner)).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"AppSec\",\"version\":7}"))
			.andExpect(status().isConflict());
	}

	@Test
	void blankOrTooLongNameReturns422() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner, "OWNER");

		mvc.perform(post(roles(org)).with(as(owner)).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"   \"}"))
			.andExpect(status().isUnprocessableContent());
		mvc.perform(post(roles(org)).with(as(owner)).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"" + "x".repeat(61) + "\"}"))
			.andExpect(status().isUnprocessableContent());
	}

	@Test
	void jobRoleFromAnotherOrganizationReturns404() throws Exception {
		UUID ownerA = seedProfile();
		UUID orgA = seedOrg(ownerA, "OWNER");
		UUID ownerB = seedProfile();
		UUID orgB = seedOrg(ownerB, "OWNER");
		UUID roleB = createRole(orgB, ownerB, "Security");

		mvc.perform(patch(roles(orgA) + "/" + roleB).with(as(ownerA)).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"Hijacked\",\"version\":0}"))
			.andExpect(status().isNotFound());
		mvc.perform(get(roles(orgB)).with(as(ownerA))).andExpect(status().isNotFound());
	}

	private UUID createRole(UUID org, UUID owner, String name) throws Exception {
		String body = mvc.perform(post(roles(org)).with(as(owner)).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"" + name + "\"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.name").value(name.trim()))
			.andExpect(jsonPath("$.active").value(true))
			.andReturn()
			.getResponse()
			.getContentAsString();
		return UUID.fromString(body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"));
	}

	private static String roles(UUID org) {
		return ORGS + "/" + org + "/job-roles";
	}

	private static RequestPostProcessor as(UUID user) {
		return jwt().jwt(token -> token.subject(user.toString()).claim("email", "user-" + user + "@example.test"));
	}

	private static UUID seedProfile() throws SQLException {
		UUID user = UUID.randomUUID();
		try (Connection admin = adminConnection();
				PreparedStatement statement = admin
					.prepareStatement("INSERT INTO brain.profiles (id, email, display_name) VALUES (?, ?, 'User')")) {
			statement.setObject(1, user);
			statement.setString(2, user + "@example.test");
			statement.executeUpdate();
		}
		return user;
	}

	private static UUID seedOrg(UUID user, String role) throws SQLException {
		UUID org = UUID.randomUUID();
		try (Connection admin = adminConnection();
				PreparedStatement insertOrg = admin.prepareStatement(
						"INSERT INTO brain.organizations (id, name, slug, created_by) VALUES (?, 'Seeded', ?, ?)")) {
			insertOrg.setObject(1, org);
			insertOrg.setString(2, "seed-" + org);
			insertOrg.setObject(3, user);
			insertOrg.executeUpdate();
		}
		addMember(org, user, role);
		return org;
	}

	private static UUID seedMember(UUID org, String role) throws SQLException {
		UUID user = seedProfile();
		addMember(org, user, role);
		return user;
	}

	private static void addMember(UUID org, UUID user, String role) throws SQLException {
		try (Connection admin = adminConnection();
				PreparedStatement statement = admin.prepareStatement(
						"INSERT INTO brain.organization_members (organization_id, user_id, role) VALUES (?, ?, ?)")) {
			statement.setObject(1, org);
			statement.setObject(2, user);
			statement.setString(3, role);
			statement.executeUpdate();
		}
	}

	private static long auditCount(UUID org, UUID actor, String action, UUID entity) throws SQLException {
		try (Connection admin = adminConnection();
				PreparedStatement statement = admin.prepareStatement("""
						SELECT count(*) FROM brain.audit_events
						WHERE organization_id = ? AND actor_id = ? AND action = ?
						AND entity_type = 'JOB_ROLE' AND entity_id = ?
						""")) {
			statement.setObject(1, org);
			statement.setObject(2, actor);
			statement.setString(3, action);
			statement.setObject(4, entity);
			try (ResultSet rows = statement.executeQuery()) {
				rows.next();
				return rows.getLong(1);
			}
		}
	}

}
