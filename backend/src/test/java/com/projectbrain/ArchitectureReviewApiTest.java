package com.projectbrain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.projectbrain.support.TeamApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = { "SUPABASE_URL=http://127.0.0.1:1", "APP_CORS_ALLOWED_ORIGINS=http://localhost:5173" })
class ArchitectureReviewApiTest extends TeamApiTestBase {

	@Autowired
	private MockMvc mvc;

	@Test
	void stateShowsCurrentDraftAndLatestRun() throws Exception {
		Fixture f = fixture();
		version(f, 1, "APPROVED", f.architectRole, f.owner);
		UUID draft = version(f, 2, "DRAFT", f.architectRole, f.owner);

		mvc.perform(get(projectUrl(f) + "/architecture").with(as(f.viewer)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.current.versionNumber").value(1))
			.andExpect(jsonPath("$.draft.versionNumber").value(2))
			.andExpect(jsonPath("$.draft.id").value(draft.toString()))
			.andExpect(jsonPath("$.latestRun.runType").value("ARCHITECTURE"));
		mvc.perform(get(projectUrl(f) + "/architecture/versions").with(as(f.viewer)))
			.andExpect(jsonPath("$.totalItems").value(2))
			.andExpect(jsonPath("$.items[0].versionNumber").value(2));
	}

	@Test
	void versionIncludesSectionsAndStoryTitles() throws Exception {
		Fixture f = fixture();
		UUID draft = version(f, 1, "DRAFT", f.architectRole, f.owner);

		mvc.perform(get(versionUrl(f, draft)).with(as(f.viewer)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.overview.summary").value("A booking app"))
			.andExpect(jsonPath("$.dataModel.tables[0].storyIds[0]").value(f.story.toString()))
			.andExpect(jsonPath("$.apiDesign.endpoints[0].path").value("/bookings"))
			.andExpect(jsonPath("$.stories[0].title").value("Book a table"));
	}

	@Test
	void architectEditsDraftSectionWithVersion() throws Exception {
		Fixture f = fixture();
		UUID draft = version(f, 1, "DRAFT", f.architectRole, f.owner);
		String edited = dataModel(f.story).replace("Reservations", "Table reservations")
			.replace("\"storyIds\":[\"" + f.story + "\"]", "\"storyIds\":[\"" + f.story + "\",\"" + UUID.randomUUID() + "\"]");

		mvc.perform(patchSection(f, draft, "data-model", f.architect, edited, 0))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.version").value(1))
			.andExpect(jsonPath("$.dataModel.tables[0].description").value("Table reservations"))
			.andExpect(jsonPath("$.dataModel.tables[0].storyIds.length()").value(1));
		assertThat(countRows("SELECT count(*) FROM brain.audit_events WHERE entity_id = ? AND action = 'ARCHITECTURE_EDITED'",
				draft))
			.isEqualTo(1);
	}

	@Test
	void staleEditOrApprovalReturns409() throws Exception {
		Fixture f = fixture();
		UUID draft = version(f, 1, "DRAFT", f.architectRole, f.owner);
		mvc.perform(patchSection(f, draft, "overview", f.architect, overview(), 0)).andExpect(status().isOk());

		mvc.perform(patchSection(f, draft, "overview", f.architect, overview(), 0)).andExpect(status().isConflict());
		mvc.perform(decide(f, draft, "approve", f.architect, "{\"version\":0}")).andExpect(status().isConflict());
	}

	@Test
	void editedContentPassesSameChecks() throws Exception {
		Fixture f = fixture();
		UUID draft = version(f, 1, "DRAFT", f.architectRole, f.owner);
		String broken = dataModel(f.story).replace("\"relations\":[]",
				"\"relations\":[{\"column\":\"id\",\"referencesTable\":\"ghosts\",\"referencesColumn\":\"id\"}]");

		mvc.perform(patchSection(f, draft, "data-model", f.architect, broken, 0))
			.andExpect(status().isUnprocessableContent());
		mvc.perform(patchSection(f, draft, "diagrams", f.architect, overview(), 0))
			.andExpect(status().isUnprocessableContent());
	}

	@Test
	void reviewedOrSupersededVersionIsReadOnly() throws Exception {
		Fixture f = fixture();
		UUID approved = version(f, 1, "APPROVED", f.architectRole, f.owner);
		UUID superseded = version(f, 2, "SUPERSEDED", f.architectRole, f.owner);

		mvc.perform(patchSection(f, approved, "overview", f.owner, overview(), 0)).andExpect(status().isConflict());
		mvc.perform(patchSection(f, superseded, "overview", f.owner, overview(), 0)).andExpect(status().isConflict());
		mvc.perform(decide(f, approved, "reject", f.architect, "{\"version\":0,\"comment\":\"No\"}"))
			.andExpect(status().isConflict());
	}

	@Test
	void draftCannotBeChangedWhileRunIsActive() throws Exception {
		Fixture f = fixture();
		UUID draft = version(f, 1, "DRAFT", f.architectRole, f.owner);
		execute("""
				INSERT INTO brain.ai_runs (id, organization_id, project_id, run_type, status, requested_by)
				VALUES (?, ?, ?, 'ARCHITECTURE', 'RUNNING', ?)
				""", UUID.randomUUID(), f.org, f.project, f.owner);

		mvc.perform(patchSection(f, draft, "overview", f.architect, overview(), 0)).andExpect(status().isConflict());
		mvc.perform(decide(f, draft, "approve", f.architect, "{\"version\":0}")).andExpect(status().isConflict());
		mvc.perform(get(versionUrl(f, draft)).with(as(f.architect)))
			.andExpect(jsonPath("$.canEdit").value(false))
			.andExpect(jsonPath("$.canReview").value(false));
	}

	@Test
	void eligibleArchitectApprovesAndOldApprovedIsSuperseded() throws Exception {
		Fixture f = fixture();
		UUID old = version(f, 1, "APPROVED", f.architectRole, f.owner);
		UUID draft = version(f, 2, "DRAFT", f.architectRole, f.owner);

		mvc.perform(decide(f, draft, "approve", f.architect, "{\"version\":0}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("APPROVED"))
			.andExpect(jsonPath("$.version").value(1));

		assertThat(statusOf(old)).isEqualTo("SUPERSEDED");
		assertThat(countRows("""
				SELECT count(*) FROM brain.architecture_reviews
				WHERE version_id = ? AND action = 'APPROVED' AND approval_basis = 'ROLE_HOLDER' AND content_version = 0
				  AND actor_id = ? AND NOT self_approved
				""", draft, f.architect)).isEqualTo(1);
		mvc.perform(decide(f, draft, "approve", f.owner, "{\"version\":1}")).andExpect(status().isForbidden());
	}

	@Test
	void ownerFallbackAndSelfApprovalAreRecorded() throws Exception {
		Fixture f = fixture();
		UUID draft = version(f, 1, "DRAFT", null, f.owner);

		mvc.perform(decide(f, draft, "approve", f.owner, "{\"version\":0}")).andExpect(status().isOk());

		assertThat(countRows("""
				SELECT count(*) FROM brain.architecture_reviews
				WHERE version_id = ? AND approval_basis = 'OWNER_FALLBACK' AND self_approved
				""", draft)).isEqualTo(1);
	}

	@Test
	void selfApprovalOffBlocksGenerator() throws Exception {
		Fixture f = fixture();
		execute("UPDATE brain.organizations SET allow_self_approval = false WHERE id = ?", f.org);
		UUID draft = version(f, 1, "DRAFT", f.architectRole, f.architect);

		mvc.perform(decide(f, draft, "approve", f.architect, "{\"version\":0}")).andExpect(status().isForbidden());
		mvc.perform(decide(f, draft, "approve", f.owner, "{\"version\":0}")).andExpect(status().isOk());
	}

	@Test
	void rejectRequiresComment() throws Exception {
		Fixture f = fixture();
		UUID draft = version(f, 1, "DRAFT", f.architectRole, f.owner);

		mvc.perform(decide(f, draft, "reject", f.architect, "{\"version\":0,\"comment\":\"  \"}"))
			.andExpect(status().isUnprocessableContent());
		mvc.perform(decide(f, draft, "reject", f.architect, "{\"version\":0,\"comment\":\"" + "x".repeat(2001) + "\"}"))
			.andExpect(status().isUnprocessableContent());
		mvc.perform(decide(f, draft, "reject", f.architect, "{\"version\":0,\"comment\":\"Rename bookings\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("REJECTED"));

		mvc.perform(get(versionUrl(f, draft) + "/reviews").with(as(f.viewer)))
			.andExpect(jsonPath("$.items[0].action").value("REJECTED"))
			.andExpect(jsonPath("$.items[0].comment").value("Rename bookings"));
		assertThat(countRows("SELECT count(*) FROM brain.audit_events WHERE entity_id = ? AND metadata::text LIKE '%Rename%'",
				draft))
			.isZero();
	}

	@Test
	void canEditAndCanReviewMatchTheRules() throws Exception {
		Fixture f = fixture();
		UUID draft = version(f, 1, "DRAFT", f.architectRole, f.owner);
		UUID member = seedMember(f.org, "MEMBER");

		expectAccess(f, draft, f.architect, true, true);
		expectAccess(f, draft, f.owner, true, false);
		expectAccess(f, draft, member, false, false);
		expectAccess(f, draft, f.viewer, false, false);
		execute("UPDATE brain.projects SET status = 'ARCHIVED' WHERE id = ?", f.project);
		expectAccess(f, draft, f.architect, false, false);
	}

	@Test
	void markdownMatchesStoredVersion() throws Exception {
		Fixture f = fixture();
		UUID approved = version(f, 1, "DRAFT", f.architectRole, f.owner);
		mvc.perform(decide(f, approved, "approve", f.architect, "{\"version\":0}")).andExpect(status().isOk());

		mvc.perform(get(versionUrl(f, approved) + "/markdown").with(as(f.viewer)))
			.andExpect(status().isOk())
			.andExpect(header().string("Content-Type", containsString("text/markdown")))
			.andExpect(header().string("Content-Disposition", containsString("architecture-v1.md")))
			.andExpect(content().string(containsString("# Architecture version 1")))
			.andExpect(content().string(containsString("Approved")))
			.andExpect(content().string(containsString("A booking app")))
			.andExpect(content().string(containsString("bookings")))
			.andExpect(content().string(containsString("/bookings")))
			.andExpect(content().string(containsString("Book a table")))
			.andExpect(content().string(containsString("Not linked to a story")))
			.andExpect(content().string(not(containsString(f.story.toString()))));
	}

	@Test
	void anotherOrganizationGets404() throws Exception {
		Fixture f = fixture();
		Fixture other = fixture();
		UUID draft = version(f, 1, "DRAFT", f.architectRole, f.owner);
		String base = ORGS + "/" + other.org + "/architecture-versions/" + draft;

		mvc.perform(get(base).with(as(other.owner))).andExpect(status().isNotFound());
		mvc.perform(get(base + "/markdown").with(as(other.owner))).andExpect(status().isNotFound());
		mvc.perform(get(base + "/reviews").with(as(other.owner))).andExpect(status().isNotFound());
		mvc.perform(post(base + "/approve").with(as(other.owner))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"version\":0}")).andExpect(status().isNotFound());
		mvc.perform(patch(base + "/overview").with(as(other.owner))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"content\":" + overview() + ",\"version\":0}")).andExpect(status().isNotFound());
		mvc.perform(get(ORGS + "/" + other.org + "/projects/" + f.project + "/architecture").with(as(other.owner)))
			.andExpect(status().isNotFound());
		assertThat(statusOf(draft)).isEqualTo("DRAFT");
	}

	private void expectAccess(Fixture f, UUID version, UUID user, boolean canEdit, boolean canReview) throws Exception {
		mvc.perform(get(versionUrl(f, version)).with(as(user)))
			.andExpect(jsonPath("$.canEdit").value(canEdit))
			.andExpect(jsonPath("$.canReview").value(canReview));
	}

	private record Fixture(UUID owner, UUID org, UUID viewer, UUID architect, UUID project, UUID story,
			UUID architectRole) {
	}

	private static Fixture fixture() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner);
		UUID viewer = seedMember(org, "VIEWER");
		UUID architect = seedMember(org, "MEMBER");
		UUID project = seedProject(org, owner, "ACTIVE");
		UUID role = seedJobRole(org, "Architect " + UUID.randomUUID(), true);
		assignJobRole(org, project, architect, role, owner);
		UUID document = UUID.randomUUID();
		execute("""
				INSERT INTO brain.requirement_documents
				    (id, organization_id, project_id, name, media_type, size_bytes, sha256, extracted_text,
				     dummy_data_confirmed, uploaded_by)
				VALUES (?, ?, ?, 'dummy.md', 'text/markdown', 10, ?, 'Dummy', true, ?)
				""", document, org, project, "a".repeat(64), owner);
		UUID run = UUID.randomUUID();
		execute("""
				INSERT INTO brain.ai_runs (id, organization_id, project_id, document_id, run_type, status, requested_by)
				VALUES (?, ?, ?, ?, 'ANALYSIS', 'SUCCEEDED', ?)
				""", run, org, project, document, owner);
		UUID analysis = UUID.randomUUID();
		execute("""
				INSERT INTO brain.requirement_analyses (id, organization_id, document_id, analysis_version, ai_run_id, result, created_by)
				VALUES (?, ?, ?, 1, ?, '{}'::jsonb, ?)
				""", analysis, org, document, run, owner);
		UUID story = UUID.randomUUID();
		execute("""
				INSERT INTO brain.story_drafts
				    (id, organization_id, project_id, document_id, analysis_id, title, as_a, i_want, so_that,
				     acceptance_criteria, priority, status, generated_by)
				VALUES (?, ?, ?, ?, ?, 'Book a table', 'diner', 'to book', 'I get a seat', '["Saved"]'::jsonb, 'HIGH', 'APPROVED', ?)
				""", story, org, project, document, analysis, owner);
		return new Fixture(owner, org, viewer, architect, project, story, role);
	}

	private static UUID version(Fixture f, int number, String status, UUID approverRole, UUID generatedBy)
			throws Exception {
		UUID run = UUID.randomUUID();
		execute("""
				INSERT INTO brain.ai_runs (id, organization_id, project_id, run_type, status, requested_by, created_at)
				VALUES (?, ?, ?, 'ARCHITECTURE', 'SUCCEEDED', ?, clock_timestamp())
				""", run, f.org, f.project, generatedBy);
		UUID id = UUID.randomUUID();
		execute("""
				INSERT INTO brain.architecture_versions
				    (id, organization_id, project_id, version_number, status, ai_run_id, approver_job_role_id, overview,
				     data_model, api_design, story_ids, generated_by)
				VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), CAST(? AS jsonb), CAST(? AS jsonb), ?)
				""", id, f.org, f.project, number, status, run, approverRole, overview(), dataModel(f.story),
				apiDesign(f.story), "[\"" + f.story + "\"]", generatedBy);
		return id;
	}

	private static String overview() {
		return """
				{"summary":"A booking app","components":[{"name":"Web","responsibility":"Screens","technology":null}],
				 "techStack":[],"decisions":[],"risks":[]}
				""";
	}

	private static String dataModel(UUID story) {
		return """
				{"tables":[
				  {"name":"bookings","description":"Reservations","columns":[
				    {"name":"id","type":"uuid","nullable":false,"description":"Key"}],"relations":[],"storyIds":["%s"]},
				  {"name":"audit_log","description":"History","columns":[
				    {"name":"id","type":"uuid","nullable":false,"description":"Key"}],"relations":[],"storyIds":[]}]}
				""".formatted(story);
	}

	private static String apiDesign(UUID story) {
		return """
				{"endpoints":[{"method":"POST","path":"/bookings","summary":"Book","requestFields":[],"responseFields":[],
				  "roles":["Diner"],"storyIds":["%s"]}]}
				""".formatted(story);
	}

	private static String projectUrl(Fixture f) {
		return ORGS + "/" + f.org + "/projects/" + f.project;
	}

	private static String versionUrl(Fixture f, UUID version) {
		return ORGS + "/" + f.org + "/architecture-versions/" + version;
	}

	private static MockHttpServletRequestBuilder patchSection(Fixture f, UUID version, String section, UUID user,
			String content, long expectedVersion) {
		return patch(versionUrl(f, version) + "/" + section).with(as(user))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"content\":" + content + ",\"version\":" + expectedVersion + "}");
	}

	private static MockHttpServletRequestBuilder decide(Fixture f, UUID version, String action, UUID user, String body) {
		return post(versionUrl(f, version) + "/" + action).with(as(user))
			.contentType(MediaType.APPLICATION_JSON)
			.content(body);
	}

	private static String statusOf(UUID version) throws Exception {
		try (var admin = adminConnection();
				var statement = admin.prepareStatement("SELECT status FROM brain.architecture_versions WHERE id = ?")) {
			statement.setObject(1, version);
			try (var rows = statement.executeQuery()) {
				rows.next();
				return rows.getString(1);
			}
		}
	}

}
