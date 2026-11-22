package com.projectbrain;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.OffsetDateTime;
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
class MemberAuditApiTest extends PostgresTestBase {

	private static final OffsetDateTime BASE_TIME = OffsetDateTime.parse("2026-01-01T00:00:00Z");

	@Autowired
	private MockMvc mvc;

	@Test
	void viewerCanListMembers() throws Exception {
		UUID viewer = seedProfile("Vera");
		UUID org = seedOrg(viewer, "VIEWER");
		UUID owner = seedProfile("Omar");
		addMember(org, owner, "OWNER");
		UUID outsider = seedProfile("Zed");
		seedOrg(outsider, "OWNER");

		mvc.perform(get(url(org, "members")).with(as(viewer)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(2)))
			.andExpect(jsonPath("$.items[*].userId", containsInAnyOrder(viewer.toString(), owner.toString())))
			.andExpect(jsonPath("$.items[0].userId").value(owner.toString()))
			.andExpect(jsonPath("$.items[0].email").value(owner + "@example.test"))
			.andExpect(jsonPath("$.items[0].displayName").value("Omar"))
			.andExpect(jsonPath("$.items[0].role").value("OWNER"))
			.andExpect(jsonPath("$.items[1].role").value("VIEWER"))
			.andExpect(jsonPath("$.page").value(0))
			.andExpect(jsonPath("$.size").value(20))
			.andExpect(jsonPath("$.totalItems").value(2));
	}

	@Test
	void membersPaginate() throws Exception {
		UUID owner = seedProfile("Anna");
		UUID org = seedOrg(owner, "OWNER");
		UUID second = seedProfile("Bob");
		addMember(org, second, "MEMBER");

		mvc.perform(get(url(org, "members")).with(as(owner)).param("page", "1").param("size", "1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(1)))
			.andExpect(jsonPath("$.items[0].userId").value(second.toString()))
			.andExpect(jsonPath("$.totalItems").value(2));
		mvc.perform(get(url(org, "members")).with(as(owner)).param("size", "0"))
			.andExpect(status().isUnprocessableContent());
	}

	@Test
	void nonMemberCannotListMembers() throws Exception {
		UUID org = seedOrg(seedProfile("Owner"), "OWNER");

		mvc.perform(get(url(org, "members")).with(as(seedProfile("Outsider"))))
			.andExpect(status().isNotFound())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
	}

	@Test
	void memberCannotViewAudit() throws Exception {
		UUID member = seedProfile("Member");
		UUID memberOrg = seedOrg(member, "MEMBER");
		UUID viewer = seedProfile("Viewer");
		UUID viewerOrg = seedOrg(viewer, "VIEWER");

		mvc.perform(get(url(memberOrg, "audit-events")).with(as(member)))
			.andExpect(status().isForbidden())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
		mvc.perform(get(url(viewerOrg, "audit-events")).with(as(viewer))).andExpect(status().isForbidden());
	}

	@Test
	void nonMemberCannotViewAudit() throws Exception {
		UUID org = seedOrg(seedProfile("Owner"), "OWNER");

		mvc.perform(get(url(org, "audit-events")).with(as(seedProfile("Outsider")))).andExpect(status().isNotFound());
	}

	@Test
	void adminSeesOnlyOwnOrgAuditNewestFirst() throws Exception {
		UUID admin = seedProfile("Admin");
		UUID org = seedOrg(admin, "ADMIN");
		UUID other = seedProfile("Other");
		UUID otherOrg = seedOrg(other, "OWNER");
		UUID oldest = seedAudit(org, admin, "PROJECT_CREATED", BASE_TIME);
		UUID newest = seedAudit(org, admin, "TASK_DELETED", BASE_TIME.plusHours(2));
		UUID middle = seedAudit(org, admin, "PROJECT_UPDATED", BASE_TIME.plusHours(1));
		seedAudit(otherOrg, other, "PROJECT_CREATED", BASE_TIME.plusHours(3));

		mvc.perform(get(url(org, "audit-events")).with(as(admin)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(3)))
			.andExpect(jsonPath("$.items[0].id").value(newest.toString()))
			.andExpect(jsonPath("$.items[1].id").value(middle.toString()))
			.andExpect(jsonPath("$.items[2].id").value(oldest.toString()))
			.andExpect(jsonPath("$.items[0].organizationId").value(org.toString()))
			.andExpect(jsonPath("$.items[0].actorId").value(admin.toString()))
			.andExpect(jsonPath("$.items[0].action").value("TASK_DELETED"))
			.andExpect(jsonPath("$.items[0].entityType").value("TASK"))
			.andExpect(jsonPath("$.items[0].entityId").isNotEmpty())
			.andExpect(jsonPath("$.items[0].metadata.title").value("Seeded"))
			.andExpect(jsonPath("$.items[0].metadata.count").value(2))
			.andExpect(jsonPath("$.items[0].createdAt").isNotEmpty())
			.andExpect(jsonPath("$.totalItems").value(3));
	}

	@Test
	void auditPaginatesAndCapsSize() throws Exception {
		UUID owner = seedProfile("Owner");
		UUID org = seedOrg(owner, "OWNER");
		seedAudit(org, owner, "FIRST", BASE_TIME);
		UUID older = seedAudit(org, owner, "SECOND", BASE_TIME.minusHours(1));

		mvc.perform(get(url(org, "audit-events")).with(as(owner)).param("page", "1").param("size", "1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(1)))
			.andExpect(jsonPath("$.items[0].id").value(older.toString()))
			.andExpect(jsonPath("$.totalItems").value(2));
		mvc.perform(get(url(org, "audit-events")).with(as(owner)).param("size", "101"))
			.andExpect(jsonPath("$.size").value(100));
		mvc.perform(get(url(org, "audit-events")).with(as(owner)).param("page", "-1"))
			.andExpect(status().isUnprocessableContent());
	}

	private static String url(UUID org, String resource) {
		return "/api/v1/organizations/" + org + "/" + resource;
	}

	private static RequestPostProcessor as(UUID user) {
		return jwt().jwt(token -> token.subject(user.toString()).claim("email", "user-" + user + "@example.test"));
	}

	private static UUID seedProfile(String displayName) throws SQLException {
		UUID user = UUID.randomUUID();
		try (Connection admin = adminConnection();
				PreparedStatement statement = admin
					.prepareStatement("INSERT INTO brain.profiles (id, email, display_name) VALUES (?, ?, ?)")) {
			statement.setObject(1, user);
			statement.setString(2, user + "@example.test");
			statement.setString(3, displayName);
			statement.executeUpdate();
		}
		return user;
	}

	private static UUID seedOrg(UUID user, String role) throws SQLException {
		UUID org = UUID.randomUUID();
		try (Connection admin = adminConnection();
				PreparedStatement statement = admin.prepareStatement(
						"INSERT INTO brain.organizations (id, name, slug, created_by) VALUES (?, 'Seeded', ?, ?)")) {
			statement.setObject(1, org);
			statement.setString(2, "seed-" + org);
			statement.setObject(3, user);
			statement.executeUpdate();
		}
		addMember(org, user, role);
		return org;
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

	private static UUID seedAudit(UUID org, UUID actor, String action, OffsetDateTime createdAt) throws SQLException {
		UUID event = UUID.randomUUID();
		try (Connection admin = adminConnection();
				PreparedStatement statement = admin.prepareStatement("""
						INSERT INTO brain.audit_events
						    (id, organization_id, actor_id, action, entity_type, entity_id, metadata, created_at)
						VALUES (?, ?, ?, ?, 'TASK', ?, '{"title":"Seeded","count":2}'::jsonb, ?)
						""")) {
			statement.setObject(1, event);
			statement.setObject(2, org);
			statement.setObject(3, actor);
			statement.setString(4, action);
			statement.setObject(5, UUID.randomUUID());
			statement.setObject(6, createdAt);
			statement.executeUpdate();
		}
		return event;
	}

}
