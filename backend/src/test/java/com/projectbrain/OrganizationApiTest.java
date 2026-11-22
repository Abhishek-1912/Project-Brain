package com.projectbrain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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

/** Token verification is covered by MeApiTest, so these cases supply an authenticated JWT directly. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = { "SUPABASE_URL=http://127.0.0.1:1", "APP_CORS_ALLOWED_ORIGINS=http://localhost:5173" })
class OrganizationApiTest extends PostgresTestBase {

	private static final String ORGS = "/api/v1/organizations";

	@Autowired
	private MockMvc mvc;

	@Test
	void createOrganizationMakesCreatorOwnerAndAudits() throws Exception {
		// No profile is seeded: creating an organization must not depend on a prior /me call.
		UUID user = UUID.randomUUID();
		String slug = uniqueSlug();

		String body = mvc.perform(post(ORGS).with(as(user)).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"Acme\",\"slug\":\"" + slug + "\"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.name").value("Acme"))
			.andExpect(jsonPath("$.slug").value(slug))
			.andExpect(jsonPath("$.role").value("OWNER"))
			.andExpect(jsonPath("$.createdBy").value(user.toString()))
			.andExpect(jsonPath("$.version").value(0))
			.andExpect(jsonPath("$.createdAt").isNotEmpty())
			.andExpect(jsonPath("$.updatedAt").isNotEmpty())
			.andReturn()
			.getResponse()
			.getContentAsString();
		UUID org = UUID.fromString(body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"));

		assertThat(memberRole(org, user)).isEqualTo("OWNER");
		assertThat(auditCount(org, user, "ORGANIZATION_CREATED", org)).isEqualTo(1);
	}

	@Test
	void listReturnsOnlyMyOrganizations() throws Exception {
		UUID me = seedProfile();
		UUID other = seedProfile();
		UUID mine = seedOrg(me, "OWNER");
		seedOrg(other, "OWNER");

		mvc.perform(get(ORGS).with(as(me)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(1)))
			.andExpect(jsonPath("$.items[0].id").value(mine.toString()))
			.andExpect(jsonPath("$.items[0].role").value("OWNER"))
			.andExpect(jsonPath("$.page").value(0))
			.andExpect(jsonPath("$.size").value(20))
			.andExpect(jsonPath("$.totalItems").value(1));
	}

	@Test
	void listPaginates() throws Exception {
		UUID me = seedProfile();
		seedOrg(me, "OWNER");
		seedOrg(me, "MEMBER");
		seedOrg(me, "VIEWER");

		mvc.perform(get(ORGS).with(as(me)).param("page", "1").param("size", "2"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(1)))
			.andExpect(jsonPath("$.page").value(1))
			.andExpect(jsonPath("$.size").value(2))
			.andExpect(jsonPath("$.totalItems").value(3));
	}

	@Test
	void sizeAbove100IsCapped() throws Exception {
		mvc.perform(get(ORGS).with(as(seedProfile())).param("size", "101"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.size").value(100));
	}

	@Test
	void invalidPagingReturns422() throws Exception {
		UUID me = seedProfile();
		mvc.perform(get(ORGS).with(as(me)).param("page", "-1")).andExpect(status().isUnprocessableContent());
		mvc.perform(get(ORGS).with(as(me)).param("size", "0")).andExpect(status().isUnprocessableContent());
		mvc.perform(get(ORGS).with(as(me)).param("page", "abc"))
			.andExpect(status().isUnprocessableContent())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
	}

	@Test
	void memberCanReadOrganizationWithOwnRole() throws Exception {
		UUID viewer = seedProfile();
		UUID org = seedOrg(viewer, "VIEWER");

		mvc.perform(get(ORGS + "/" + org).with(as(viewer)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(org.toString()))
			.andExpect(jsonPath("$.role").value("VIEWER"));
	}

	@Test
	void nonMemberGets404() throws Exception {
		UUID org = seedOrg(seedProfile(), "OWNER");
		UUID outsider = seedProfile();

		mvc.perform(get(ORGS + "/" + org).with(as(outsider)))
			.andExpect(status().isNotFound())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
		mvc.perform(patch(ORGS + "/" + org).with(as(outsider)).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"Hijacked\",\"version\":0}"))
			.andExpect(status().isNotFound());
		assertThat(orgName(org)).isEqualTo("Seeded");
	}

	@Test
	void nonMemberAndMissingOrganizationLookIdentical() throws Exception {
		UUID org = seedOrg(seedProfile(), "OWNER");
		UUID outsider = seedProfile();

		String existing = mvc.perform(get(ORGS + "/" + org).with(as(outsider))).andReturn().getResponse().getContentAsString();
		UUID absent = UUID.randomUUID();
		String missing = mvc.perform(get(ORGS + "/" + absent).with(as(outsider))).andReturn().getResponse().getContentAsString();

		// The problem body echoes the request path, so compare with the IDs removed.
		assertThat(existing).contains("\"status\":404");
		assertThat(existing.replace(org.toString(), "ID")).isEqualTo(missing.replace(absent.toString(), "ID"));
	}

	@Test
	void nonUuidOrganizationIdReturns404() throws Exception {
		mvc.perform(get(ORGS + "/not-a-uuid").with(as(seedProfile()))).andExpect(status().isNotFound());
	}

	@Test
	void adminCannotEditOrganization() throws Exception {
		UUID admin = seedProfile();
		UUID org = seedOrg(admin, "ADMIN");

		mvc.perform(patch(ORGS + "/" + org).with(as(admin)).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"Renamed\",\"version\":0}"))
			.andExpect(status().isForbidden())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

		assertThat(orgName(org)).isEqualTo("Seeded");
	}

	@Test
	void ownerPatchRenamesKeepsSlugAndAudits() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner, "OWNER");
		String slug = orgSlug(org);

		mvc.perform(patch(ORGS + "/" + org).with(as(owner)).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"Renamed\",\"version\":0}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Renamed"))
			.andExpect(jsonPath("$.slug").value(slug))
			.andExpect(jsonPath("$.role").value("OWNER"))
			.andExpect(jsonPath("$.version").value(1));

		assertThat(orgName(org)).isEqualTo("Renamed");
		assertThat(auditCount(org, owner, "ORGANIZATION_UPDATED", org)).isEqualTo(1);
	}

	@Test
	void stalePatchReturns409() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner, "OWNER");
		String rename = "{\"name\":\"First\",\"version\":0}";
		mvc.perform(patch(ORGS + "/" + org).with(as(owner)).contentType(MediaType.APPLICATION_JSON).content(rename))
			.andExpect(status().isOk());

		mvc.perform(patch(ORGS + "/" + org).with(as(owner)).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"Second\",\"version\":0}"))
			.andExpect(status().isConflict())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

		assertThat(orgName(org)).isEqualTo("First");
	}

	@Test
	void stalePatchWithoutNameReturns409() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner, "OWNER");

		mvc.perform(patch(ORGS + "/" + org).with(as(owner)).contentType(MediaType.APPLICATION_JSON)
			.content("{\"version\":7}"))
			.andExpect(status().isConflict());
	}

	@Test
	void patchCannotChangeSlug() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner, "OWNER");
		String slug = orgSlug(org);

		mvc.perform(patch(ORGS + "/" + org).with(as(owner)).contentType(MediaType.APPLICATION_JSON)
			.content("{\"slug\":\"changed-slug\",\"version\":0}"))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

		assertThat(orgSlug(org)).isEqualTo(slug);
	}

	@Test
	void patchWithoutVersionReturns422() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner, "OWNER");

		mvc.perform(patch(ORGS + "/" + org).with(as(owner)).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"Renamed\"}"))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.errors.version").isArray());
	}

	@Test
	void duplicateSlugReturns409() throws Exception {
		UUID user = UUID.randomUUID();
		String slug = uniqueSlug();
		String request = "{\"name\":\"Acme\",\"slug\":\"" + slug + "\"}";
		mvc.perform(post(ORGS).with(as(user)).contentType(MediaType.APPLICATION_JSON).content(request))
			.andExpect(status().isCreated());

		mvc.perform(post(ORGS).with(as(user)).contentType(MediaType.APPLICATION_JSON).content(request))
			.andExpect(status().isConflict())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

		assertThat(orgCountBySlug(slug)).isEqualTo(1);
	}

	@Test
	void invalidSlugReturns422() throws Exception {
		mvc.perform(post(ORGS).with(as(UUID.randomUUID())).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"Acme\",\"slug\":\"Not Valid\"}"))
			.andExpect(status().isUnprocessableContent())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.errors.slug").isArray());
	}

	@Test
	void malformedJsonReturns400() throws Exception {
		mvc.perform(post(ORGS).with(as(UUID.randomUUID())).contentType(MediaType.APPLICATION_JSON).content("{nope"))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
	}

	@Test
	void failedAuditRollsBackOrganization() throws Exception {
		String slug = uniqueSlug();
		try (Connection admin = adminConnection(); Statement statement = admin.createStatement()) {
			statement.execute("""
					CREATE FUNCTION brain.test_fail_audit() RETURNS trigger LANGUAGE plpgsql AS $$
					BEGIN
					    IF NEW.metadata->>'slug' = '%s' THEN RAISE EXCEPTION 'audit failed'; END IF;
					    RETURN NEW;
					END $$
					""".formatted(slug));
			statement.execute("""
					CREATE TRIGGER test_fail_audit BEFORE INSERT ON brain.audit_events
					FOR EACH ROW EXECUTE FUNCTION brain.test_fail_audit()
					""");
			try {
				mvc.perform(post(ORGS).with(as(UUID.randomUUID())).contentType(MediaType.APPLICATION_JSON)
					.content("{\"name\":\"Acme\",\"slug\":\"" + slug + "\"}"))
					.andExpect(status().isInternalServerError())
					.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
			}
			finally {
				statement.execute("DROP TRIGGER test_fail_audit ON brain.audit_events");
				statement.execute("DROP FUNCTION brain.test_fail_audit()");
			}
		}

		assertThat(orgCountBySlug(slug)).isZero();
	}

	private static RequestPostProcessor as(UUID user) {
		return jwt().jwt(token -> token.subject(user.toString()).claim("email", "user-" + user + "@example.test"));
	}

	private static String uniqueSlug() {
		return "org-" + UUID.randomUUID();
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
						"INSERT INTO brain.organizations (id, name, slug, created_by) VALUES (?, 'Seeded', ?, ?)");
				PreparedStatement insertMember = admin.prepareStatement(
						"INSERT INTO brain.organization_members (organization_id, user_id, role) VALUES (?, ?, ?)")) {
			insertOrg.setObject(1, org);
			insertOrg.setString(2, "seed-" + org);
			insertOrg.setObject(3, user);
			insertOrg.executeUpdate();
			insertMember.setObject(1, org);
			insertMember.setObject(2, user);
			insertMember.setString(3, role);
			insertMember.executeUpdate();
		}
		return org;
	}

	private static String orgName(UUID org) throws SQLException {
		return queryString("SELECT name FROM brain.organizations WHERE id = ?", org);
	}

	private static String orgSlug(UUID org) throws SQLException {
		return queryString("SELECT slug FROM brain.organizations WHERE id = ?", org);
	}

	private static String queryString(String sql, UUID id) throws SQLException {
		try (Connection admin = adminConnection(); PreparedStatement statement = admin.prepareStatement(sql)) {
			statement.setObject(1, id);
			try (ResultSet rows = statement.executeQuery()) {
				rows.next();
				return rows.getString(1);
			}
		}
	}

	private static String memberRole(UUID org, UUID user) throws SQLException {
		try (Connection admin = adminConnection();
				PreparedStatement statement = admin.prepareStatement(
						"SELECT role FROM brain.organization_members WHERE organization_id = ? AND user_id = ?")) {
			statement.setObject(1, org);
			statement.setObject(2, user);
			try (ResultSet rows = statement.executeQuery()) {
				return rows.next() ? rows.getString(1) : null;
			}
		}
	}

	private static long auditCount(UUID org, UUID actor, String action, UUID entity) throws SQLException {
		try (Connection admin = adminConnection();
				PreparedStatement statement = admin.prepareStatement("""
						SELECT count(*) FROM brain.audit_events
						WHERE organization_id = ? AND actor_id = ? AND action = ?
						AND entity_type = 'ORGANIZATION' AND entity_id = ?
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

	private static long orgCountBySlug(String slug) throws SQLException {
		try (Connection admin = adminConnection();
				PreparedStatement statement = admin
					.prepareStatement("SELECT count(*) FROM brain.organizations WHERE slug = ?")) {
			statement.setString(1, slug);
			try (ResultSet rows = statement.executeQuery()) {
				rows.next();
				return rows.getLong(1);
			}
		}
	}

}
