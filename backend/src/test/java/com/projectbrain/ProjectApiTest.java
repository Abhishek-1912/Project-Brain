package com.projectbrain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
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
import java.util.UUID;
import java.util.function.Supplier;

import com.projectbrain.organization.api.OrgTx;
import com.projectbrain.organization.api.Permission;
import com.projectbrain.project.api.ProjectLookup;
import com.projectbrain.project.api.ProjectStatus;
import com.projectbrain.shared.web.NotFoundException;
import com.projectbrain.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Token verification is covered by MeApiTest, so these cases supply an authenticated JWT directly. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = { "SUPABASE_URL=http://127.0.0.1:1", "APP_CORS_ALLOWED_ORIGINS=http://localhost:5173" })
class ProjectApiTest extends PostgresTestBase {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private OrgTx orgTx;

	@Autowired
	private ProjectLookup projectLookup;

	@Test
	void adminCreatesProjectAndAudits() throws Exception {
		UUID admin = seedProfile();
		UUID org = seedOrg(admin, "ADMIN");

		String body = create(admin, org, "{\"name\":\"  Launch  \",\"description\":\"Ship it\"}")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.organizationId").value(org.toString()))
			.andExpect(jsonPath("$.name").value("Launch"))
			.andExpect(jsonPath("$.description").value("Ship it"))
			.andExpect(jsonPath("$.status").value("ACTIVE"))
			.andExpect(jsonPath("$.createdBy").value(admin.toString()))
			.andExpect(jsonPath("$.version").value(0))
			.andExpect(jsonPath("$.createdAt").isNotEmpty())
			.andExpect(jsonPath("$.updatedAt").isNotEmpty())
			.andReturn()
			.getResponse()
			.getContentAsString();
		UUID project = UUID.fromString(body.replaceAll("^\\{\"id\":\"([^\"]+)\".*", "$1"));

		assertThat(projectName(project)).isEqualTo("Launch");
		assertThat(auditCount(org, admin, "PROJECT_CREATED", project)).isEqualTo(1);
	}

	@Test
	void createWithoutDescriptionReturnsNull() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner, "OWNER");

		create(owner, org, "{\"name\":\"Plain\"}")
			.andExpect(status().isCreated())
			.andExpect(content().string(containsString("\"description\":null")));
	}

	@Test
	void memberCannotCreateProject() throws Exception {
		UUID member = seedProfile();
		UUID org = seedOrg(member, "MEMBER");

		create(member, org, "{\"name\":\"Nope\"}")
			.andExpect(status().isForbidden())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

		assertThat(projectCount(org)).isZero();
	}

	@Test
	void blankNameReturns422() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner, "OWNER");

		create(owner, org, "{\"name\":\"   \"}")
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.errors.name").isArray());
	}

	@Test
	void viewerCanList() throws Exception {
		UUID viewer = seedProfile();
		UUID org = seedOrg(viewer, "VIEWER");
		UUID project = seedProject(org, viewer, "Visible", "ACTIVE");
		UUID otherOwner = seedProfile();
		seedProject(seedOrg(otherOwner, "OWNER"), otherOwner, "Hidden", "ACTIVE");

		mvc.perform(get(projects(org)).with(as(viewer)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(1)))
			.andExpect(jsonPath("$.items[0].id").value(project.toString()))
			.andExpect(jsonPath("$.page").value(0))
			.andExpect(jsonPath("$.size").value(20))
			.andExpect(jsonPath("$.totalItems").value(1));
		mvc.perform(get(projects(org) + "/" + project).with(as(viewer)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Visible"));
	}

	@Test
	void nonMemberGets404() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner, "OWNER");

		mvc.perform(get(projects(org)).with(as(seedProfile()))).andExpect(status().isNotFound());
	}

	@Test
	void otherOrgProjectIdReturns404() throws Exception {
		UUID me = seedProfile();
		UUID myOrg = seedOrg(me, "OWNER");
		UUID other = seedProfile();
		UUID otherProject = seedProject(seedOrg(other, "OWNER"), other, "Secret", "ACTIVE");

		mvc.perform(get(projects(myOrg) + "/" + otherProject).with(as(me))).andExpect(status().isNotFound());
		patchProject(me, myOrg, otherProject, "{\"name\":\"Stolen\",\"version\":0}").andExpect(status().isNotFound());

		assertThat(projectName(otherProject)).isEqualTo("Secret");
	}

	@Test
	void duplicateNameIgnoringCaseAndSpacesReturns409() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner, "OWNER");
		seedProject(org, owner, "Launch", "ACTIVE");

		create(owner, org, "{\"name\":\"  lAUNCH \"}")
			.andExpect(status().isConflict())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

		assertThat(projectCount(org)).isEqualTo(1);
	}

	@Test
	void renameToExistingNameReturns409() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner, "OWNER");
		seedProject(org, owner, "Launch", "ACTIVE");
		UUID second = seedProject(org, owner, "Second", "ACTIVE");

		patchProject(owner, org, second, "{\"name\":\" launch \",\"version\":0}").andExpect(status().isConflict());

		assertThat(projectName(second)).isEqualTo("Second");
	}

	@Test
	void memberCannotPatchProject() throws Exception {
		UUID member = seedProfile();
		UUID org = seedOrg(member, "MEMBER");
		UUID project = seedProject(org, member, "Original", "ACTIVE");

		patchProject(member, org, project, "{\"name\":\"Changed\",\"version\":0}").andExpect(status().isForbidden());

		assertThat(projectName(project)).isEqualTo("Original");
	}

	@Test
	void renameBumpsVersionAndAudits() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner, "OWNER");
		UUID project = seedProject(org, owner, "Original", "ACTIVE");

		patchProject(owner, org, project, "{\"name\":\"Renamed\",\"version\":0}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Renamed"))
			.andExpect(jsonPath("$.description").value("Seed description"))
			.andExpect(jsonPath("$.version").value(1));

		assertThat(auditCount(org, owner, "PROJECT_UPDATED", project)).isEqualTo(1);
		assertThat(auditCount(org, owner, "PROJECT_ARCHIVED", project)).isZero();
	}

	@Test
	void archiveThroughPatchAudits() throws Exception {
		UUID admin = seedProfile();
		UUID org = seedOrg(admin, "ADMIN");
		UUID project = seedProject(org, admin, "Old", "ACTIVE");

		patchProject(admin, org, project, "{\"status\":\"ARCHIVED\",\"version\":0}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ARCHIVED"))
			.andExpect(jsonPath("$.version").value(1));

		assertThat(auditCount(org, admin, "PROJECT_ARCHIVED", project)).isEqualTo(1);
		assertThat(auditCount(org, admin, "PROJECT_UPDATED", project)).isZero();
	}

	@Test
	void restoringArchivedProjectAuditsAsUpdate() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner, "OWNER");
		UUID project = seedProject(org, owner, "Old", "ARCHIVED");

		patchProject(owner, org, project, "{\"status\":\"ACTIVE\",\"version\":0}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ACTIVE"));

		assertThat(auditCount(org, owner, "PROJECT_UPDATED", project)).isEqualTo(1);
	}

	@Test
	void explicitNullDescriptionClearsIt() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner, "OWNER");
		UUID project = seedProject(org, owner, "Described", "ACTIVE");

		patchProject(owner, org, project, "{\"description\":null,\"version\":0}")
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("\"description\":null")))
			.andExpect(jsonPath("$.version").value(1));
	}

	@Test
	void unchangedPatchDoesNotBumpVersionOrAudit() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner, "OWNER");
		UUID project = seedProject(org, owner, "Same", "ACTIVE");

		patchProject(owner, org, project, "{\"name\":\"Same\",\"status\":\"ACTIVE\",\"version\":0}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.version").value(0));

		assertThat(auditCount(org, owner, "PROJECT_UPDATED", project)).isZero();
	}

	@Test
	void stalePatchReturns409() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner, "OWNER");
		UUID project = seedProject(org, owner, "Original", "ACTIVE");
		patchProject(owner, org, project, "{\"name\":\"First\",\"version\":0}").andExpect(status().isOk());

		patchProject(owner, org, project, "{\"name\":\"Second\",\"version\":0}")
			.andExpect(status().isConflict())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

		assertThat(projectName(project)).isEqualTo("First");
	}

	@Test
	void invalidStatusReturns422() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner, "OWNER");
		UUID project = seedProject(org, owner, "Original", "ACTIVE");

		patchProject(owner, org, project, "{\"status\":\"DELETED\",\"version\":0}")
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.errors.status").isArray());
	}

	@Test
	void paginationCapsSizeAt100() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner, "OWNER");
		for (int i = 0; i < 3; i++) {
			seedProject(org, owner, "Project " + i, "ACTIVE");
		}

		mvc.perform(get(projects(org)).with(as(owner)).param("size", "101"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.size").value(100))
			.andExpect(jsonPath("$.items", hasSize(3)));
		mvc.perform(get(projects(org)).with(as(owner)).param("page", "1").param("size", "2"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(1)))
			.andExpect(jsonPath("$.items[0].name").value("Project 2"))
			.andExpect(jsonPath("$.totalItems").value(3));
	}

	@Test
	void pageBeyondIntegerOffsetReturnsEmpty() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner, "OWNER");
		seedProject(org, owner, "Only", "ACTIVE");

		mvc.perform(get(projects(org)).with(as(owner)).param("page", "2147483647").param("size", "100"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(0)))
			.andExpect(jsonPath("$.totalItems").value(1));
	}

	@Test
	void lookupReturnsProjectStatus() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner, "OWNER");
		UUID project = seedProject(org, owner, "Archived", "ARCHIVED");

		ProjectStatus status = authenticatedAs(owner,
				() -> orgTx.inOrg(org, Permission.VIEW, () -> projectLookup.requireStatus(org, project)));

		assertThat(status).isEqualTo(ProjectStatus.ARCHIVED);
	}

	@Test
	void lookupThrowsNotFoundForOtherOrgProject() throws Exception {
		UUID me = seedProfile();
		UUID myOrg = seedOrg(me, "OWNER");
		UUID other = seedProfile();
		UUID otherProject = seedProject(seedOrg(other, "OWNER"), other, "Secret", "ACTIVE");

		assertThatThrownBy(() -> authenticatedAs(me,
				() -> orgTx.inOrg(myOrg, Permission.VIEW, () -> projectLookup.requireStatus(myOrg, otherProject))))
			.isInstanceOf(NotFoundException.class);
	}

	private ResultActions create(UUID user, UUID org, String json) throws Exception {
		return mvc.perform(post(projects(org)).with(as(user)).contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private ResultActions patchProject(UUID user, UUID org, UUID project, String json) throws Exception {
		return mvc.perform(patch(projects(org) + "/" + project).with(as(user))
			.contentType(MediaType.APPLICATION_JSON)
			.content(json));
	}

	private static String projects(UUID org) {
		return "/api/v1/organizations/" + org + "/projects";
	}

	private static RequestPostProcessor as(UUID user) {
		return jwt().jwt(token -> token.subject(user.toString()).claim("email", "user-" + user + "@example.test"));
	}

	private static <T> T authenticatedAs(UUID user, Supplier<T> work) {
		Jwt token = Jwt.withTokenValue("test")
			.header("alg", "none")
			.subject(user.toString())
			.claim("email", "user-" + user + "@example.test")
			.build();
		SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(token));
		try {
			return work.get();
		}
		finally {
			SecurityContextHolder.clearContext();
		}
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

	private static UUID seedProject(UUID org, UUID creator, String name, String projectStatus) throws SQLException {
		UUID project = UUID.randomUUID();
		try (Connection admin = adminConnection();
				PreparedStatement statement = admin.prepareStatement("""
						INSERT INTO brain.projects (id, organization_id, name, description, status, created_by)
						VALUES (?, ?, ?, 'Seed description', ?, ?)
						""")) {
			statement.setObject(1, project);
			statement.setObject(2, org);
			statement.setString(3, name);
			statement.setString(4, projectStatus);
			statement.setObject(5, creator);
			statement.executeUpdate();
		}
		return project;
	}

	private static String projectName(UUID project) throws SQLException {
		try (Connection admin = adminConnection();
				PreparedStatement statement = admin.prepareStatement("SELECT name FROM brain.projects WHERE id = ?")) {
			statement.setObject(1, project);
			try (ResultSet rows = statement.executeQuery()) {
				rows.next();
				return rows.getString(1);
			}
		}
	}

	private static long projectCount(UUID org) throws SQLException {
		try (Connection admin = adminConnection();
				PreparedStatement statement = admin
					.prepareStatement("SELECT count(*) FROM brain.projects WHERE organization_id = ?")) {
			statement.setObject(1, org);
			try (ResultSet rows = statement.executeQuery()) {
				rows.next();
				return rows.getLong(1);
			}
		}
	}

	private static long auditCount(UUID org, UUID actor, String action, UUID entity) throws SQLException {
		try (Connection admin = adminConnection();
				PreparedStatement statement = admin.prepareStatement("""
						SELECT count(*) FROM brain.audit_events
						WHERE organization_id = ? AND actor_id = ? AND action = ?
						AND entity_type = 'PROJECT' AND entity_id = ?
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
