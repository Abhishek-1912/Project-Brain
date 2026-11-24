package com.projectbrain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.jayway.jsonpath.JsonPath;
import com.projectbrain.requirement.AiServiceClient;
import com.projectbrain.requirement.AiServiceException;
import com.projectbrain.support.TeamApiTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import(RequirementStoryApiTest.FakeAiConfig.class)
@TestPropertySource(properties = { "SUPABASE_URL=http://127.0.0.1:1", "APP_CORS_ALLOWED_ORIGINS=http://localhost:5173" })
class RequirementStoryApiTest extends TeamApiTestBase {

	private static final String DOCUMENT_TEXT = "Users can book a table.";

	private static final String STORED_ANALYSIS = """
			{"summary":"Dummy app","features":[{"text":"Book","sourceQuote":"Users can book a table","sourceVerified":true}],
			 "users":[],"gaps":[{"text":"Refunds","sourceQuote":null}],"risks":[]}
			""";

	private static final String ANALYSIS_RESULT = """
			{"summary":"Updated","features":[],"users":[],"gaps":[],"risks":[],"questions":[]}
			""";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private FakeAi ai;

	@BeforeEach
	void resetAi() {
		ai.storyRequests.clear();
		ai.analyzeAnswers.clear();
		ai.stories = stories(story("Book a table", "Project Manager", "Users can book a table"));
		ai.failure = null;
		ai.fatal = false;
		ai.started = null;
		ai.release = null;
	}

	@Test
	void approverCanAnswerOrDismissQuestion() throws Exception {
		Fixture f = fixture();

		mvc.perform(patchQuestion(f, f.question, f.approver, "{\"status\":\"ANSWERED\",\"answer\":\"Yes\",\"version\":0}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ANSWERED"))
			.andExpect(jsonPath("$.answer").value("Yes"))
			.andExpect(jsonPath("$.version").value(1));
		mvc.perform(patchQuestion(f, f.question, f.owner, "{\"status\":\"DISMISSED\",\"version\":1}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("DISMISSED"))
			.andExpect(jsonPath("$.answer").doesNotExist());
		assertThat(countRows("SELECT count(*) FROM brain.audit_events WHERE organization_id = ? AND action = ?", f.org,
				"CLARIFICATION_UPDATED"))
			.isEqualTo(2);
	}

	@Test
	void viewerCannotAnswerQuestion() throws Exception {
		Fixture f = fixture();
		UUID member = seedMember(f.org, "MEMBER");
		String body = "{\"status\":\"ANSWERED\",\"answer\":\"Yes\",\"version\":0}";

		mvc.perform(patchQuestion(f, f.question, f.viewer, body)).andExpect(status().isForbidden());
		mvc.perform(patchQuestion(f, f.question, member, body)).andExpect(status().isForbidden());
	}

	@Test
	void answerRequiresText() throws Exception {
		Fixture f = fixture();

		mvc.perform(patchQuestion(f, f.question, f.owner, "{\"status\":\"ANSWERED\",\"answer\":\"  \",\"version\":0}"))
			.andExpect(status().isUnprocessableContent());
	}

	@Test
	void updateAnalysisCreatesNewVersionUsingAnswers() throws Exception {
		Fixture f = fixture();
		mvc.perform(patchQuestion(f, f.question, f.owner, "{\"status\":\"ANSWERED\",\"answer\":\"Yes\",\"version\":0}"))
			.andExpect(status().isOk());

		UUID run = runId(mvc.perform(post(docUrl(f) + "/analyze").with(as(f.owner))).andExpect(status().isAccepted())
			.andReturn().getResponse().getContentAsString());
		awaitRun(f, run, "SUCCEEDED");

		mvc.perform(get(docUrl(f) + "/analysis").with(as(f.owner)))
			.andExpect(jsonPath("$.analysis.version").value(2));
		assertThat(ai.analyzeAnswers).containsExactly(new AiServiceClient.ClarificationAnswer("Can it cancel?", "Yes"));
		assertThat(countRows("SELECT count(*) FROM brain.requirement_analyses WHERE document_id = ?", f.document))
			.isEqualTo(2);
	}

	@Test
	void generationSavesDraftStories() throws Exception {
		Fixture f = fixture();

		awaitRun(f, generate(f), "SUCCEEDED");

		mvc.perform(get(docUrl(f) + "/stories").with(as(f.viewer)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalItems").value(1))
			.andExpect(jsonPath("$.items[0].title").value("Book a table"))
			.andExpect(jsonPath("$.items[0].asA").value("diner"))
			.andExpect(jsonPath("$.items[0].iWant").value("to book"))
			.andExpect(jsonPath("$.items[0].soThat").value("I get a seat"))
			.andExpect(jsonPath("$.items[0].acceptanceCriteria[0]").value("Booking is saved"))
			.andExpect(jsonPath("$.items[0].priority").value("HIGH"))
			.andExpect(jsonPath("$.items[0].jobRoleId").value(f.approverRole.toString()))
			.andExpect(jsonPath("$.items[0].sourceQuotes[0].quote").value("Users can book a table"))
			.andExpect(jsonPath("$.items[0].sourceQuotes[0].verified").value(true))
			.andExpect(jsonPath("$.items[0].status").value("DRAFT"));
		AiServiceClient.StoriesRequest sent = ai.storyRequests.get(0);
		assertThat(sent.text()).isEqualTo(DOCUMENT_TEXT);
		assertThat(sent.activeJobRoles()).contains("Project Manager");
		assertThat(sent.analysis().get("features").get(0).has("sourceVerified")).isFalse();
		assertThat(sent.analysis().get("questions").get(0).get("question").asString()).isEqualTo("Can it cancel?");
	}

	@Test
	void unknownAiRoleIsNotAssigned() throws Exception {
		Fixture f = fixture();
		ai.stories = stories(story("Book a table", "Wizard", "Users can book a table"));

		awaitRun(f, generate(f), "SUCCEEDED");

		mvc.perform(get(docUrl(f) + "/stories").with(as(f.owner)))
			.andExpect(jsonPath("$.items[0].jobRoleId").doesNotExist());
	}

	@Test
	void madeUpQuoteIsMarkedUnverified() throws Exception {
		Fixture f = fixture();
		ai.stories = stories(story("Book a table", "Project Manager", "This sentence is not in the document"));

		awaitRun(f, generate(f), "SUCCEEDED");

		mvc.perform(get(docUrl(f) + "/stories").with(as(f.owner)))
			.andExpect(jsonPath("$.items[0].sourceQuotes[0].verified").value(false));
	}

	@Test
	void malformedStoriesSaveNothing() throws Exception {
		Fixture f = fixture();
		ai.stories = "{\"stories\":[{\"title\":\"Missing fields\"}]}";

		UUID run = generate(f);
		awaitRun(f, run, "FAILED");

		mvc.perform(get(ORGS + "/" + f.org + "/ai-runs/" + run).with(as(f.owner)))
			.andExpect(jsonPath("$.errorCode").value("INVALID_OUTPUT"))
			.andExpect(jsonPath("$.runType").value("STORIES"));
		assertThat(countRows("SELECT count(*) FROM brain.story_drafts WHERE document_id = ?", f.document)).isZero();
	}

	@Test
	void regenerationKeepsApprovedAndConvertedStories() throws Exception {
		Fixture f = fixture();
		ai.stories = stories(story("Approved one", "Project Manager", "x"), story("Converted one", "Project Manager", "x"),
				story("Old draft", "Project Manager", "x"));
		awaitRun(f, generate(f), "SUCCEEDED");
		execute("UPDATE brain.story_drafts SET status = 'APPROVED' WHERE document_id = ? AND title = 'Approved one'",
				f.document);
		execute("UPDATE brain.story_drafts SET status = 'CONVERTED' WHERE document_id = ? AND title = 'Converted one'",
				f.document);
		ai.stories = stories(story("New draft", "Project Manager", "x"), story("approved  ONE", "Project Manager", "x"));

		awaitRun(f, generate(f), "SUCCEEDED");

		assertThat(titles(f)).containsExactlyInAnyOrder("Approved one", "Converted one", "New draft");
		assertThat(ai.storyRequests.get(1).keptStoryTitles()).containsExactlyInAnyOrder("Approved one", "Converted one");
	}

	@Test
	void editSavedDuringRegenerationIsKept() throws Exception {
		Fixture f = fixture();
		ai.stories = stories(story("Edit me", "Project Manager", "x"), story("Replace me", "Project Manager", "x"));
		awaitRun(f, generate(f), "SUCCEEDED");
		UUID story = storyId(f);
		ai.stories = stories(story("Fresh", "Project Manager", "x"), story("edited during RUN", "Project Manager", "x"));
		ai.started = new CountDownLatch(1);
		ai.release = new CountDownLatch(1);

		UUID run = generate(f);
		assertThat(ai.started.await(5, TimeUnit.SECONDS)).isTrue();
		mvc.perform(patchStory(f, story, f.owner, storyBody("Edited during run", f.approverRole, 0)))
			.andExpect(status().isOk());
		ai.release.countDown();
		awaitRun(f, run, "SUCCEEDED");

		assertThat(titles(f)).containsExactlyInAnyOrder("Edited during run", "Fresh");
	}

	@Test
	void emptyAiResultLeavesDraftsAndFails() throws Exception {
		Fixture f = fixture();
		awaitRun(f, generate(f), "SUCCEEDED");
		ai.stories = "{\"stories\":[]}";

		UUID run = generate(f);
		awaitRun(f, run, "FAILED");

		mvc.perform(get(ORGS + "/" + f.org + "/ai-runs/" + run).with(as(f.owner)))
			.andExpect(jsonPath("$.errorCode").value("INVALID_OUTPUT"));
		assertThat(titles(f)).containsExactly("Book a table");
	}

	@Test
	void resultWithOnlyKeptTitlesLeavesDraftsAndFails() throws Exception {
		Fixture f = fixture();
		ai.stories = stories(story("Approved one", "Project Manager", "x"), story("Draft one", "Project Manager", "x"));
		awaitRun(f, generate(f), "SUCCEEDED");
		execute("UPDATE brain.story_drafts SET status = 'APPROVED' WHERE document_id = ? AND title = 'Approved one'",
				f.document);
		ai.stories = stories(story("approved ONE", "Project Manager", "x"));

		awaitRun(f, generate(f), "FAILED");

		assertThat(titles(f)).containsExactlyInAnyOrder("Approved one", "Draft one");
	}

	@Test
	void fatalWorkerErrorDoesNotLeaveRunActive() throws Exception {
		Fixture f = fixture();
		ai.fatal = true;

		UUID run = generate(f);
		awaitRun(f, run, "FAILED");

		mvc.perform(get(ORGS + "/" + f.org + "/ai-runs/" + run).with(as(f.owner)))
			.andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"));
	}

	@Test
	void failedRunRecordsUsageFromTheAiService() throws Exception {
		Fixture f = fixture();
		ai.failure = new AiServiceException("INVALID_OUTPUT",
				new AiServiceClient.AiUsage("gemini", "gemini-test", "stories-v1", 70, 30));

		UUID run = generate(f);
		awaitRun(f, run, "FAILED");

		mvc.perform(get(ORGS + "/" + f.org + "/ai-runs/" + run).with(as(f.owner)))
			.andExpect(jsonPath("$.provider").value("gemini"))
			.andExpect(jsonPath("$.model").value("gemini-test"))
			.andExpect(jsonPath("$.promptVersion").value("stories-v1"))
			.andExpect(jsonPath("$.inputTokens").value(70))
			.andExpect(jsonPath("$.outputTokens").value(30));
	}

	@Test
	void rejectionCommentsAreSentToRegeneration() throws Exception {
		Fixture f = fixture();
		awaitRun(f, generate(f), "SUCCEEDED");
		UUID story = storyId(f);
		execute("UPDATE brain.story_drafts SET status = 'REJECTED' WHERE id = ?", story);
		execute("""
				INSERT INTO brain.story_reviews
				    (id, organization_id, project_id, document_id, story_id, story_version, action, actor_id,
				     approval_basis, comment)
				VALUES (?, ?, ?, ?, ?, 0, 'REJECTED', ?, 'OWNER_FALLBACK', 'Too vague')
				""", UUID.randomUUID(), f.org, f.project, f.document, story, f.owner);

		awaitRun(f, generate(f), "SUCCEEDED");

		assertThat(ai.storyRequests.get(1).rejectionComments()).containsExactly("Too vague");
	}

	@Test
	void editingApprovedStoryReturnsItToDraft() throws Exception {
		Fixture f = fixture();
		awaitRun(f, generate(f), "SUCCEEDED");
		UUID story = storyId(f);
		execute("UPDATE brain.story_drafts SET status = 'APPROVED' WHERE id = ?", story);

		mvc.perform(patchStory(f, story, f.approver, storyBody("Edited title", f.approverRole, 0)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.title").value("Edited title"))
			.andExpect(jsonPath("$.status").value("DRAFT"))
			.andExpect(jsonPath("$.version").value(1));
		assertThat(countRows("SELECT count(*) FROM brain.story_reviews WHERE story_id = ? AND action = 'REOPENED'", story))
			.isEqualTo(1);
	}

	@Test
	void staleStoryEditReturns409() throws Exception {
		Fixture f = fixture();
		awaitRun(f, generate(f), "SUCCEEDED");
		UUID story = storyId(f);
		mvc.perform(patchStory(f, story, f.owner, storyBody("First", f.approverRole, 0))).andExpect(status().isOk());

		mvc.perform(patchStory(f, story, f.owner, storyBody("Second", f.approverRole, 0)))
			.andExpect(status().isConflict());
	}

	@Test
	void convertedStoryCannotBeEdited() throws Exception {
		Fixture f = fixture();
		awaitRun(f, generate(f), "SUCCEEDED");
		UUID story = storyId(f);
		execute("UPDATE brain.story_drafts SET status = 'CONVERTED' WHERE id = ?", story);

		mvc.perform(patchStory(f, story, f.owner, storyBody("Edited", f.approverRole, 0)))
			.andExpect(status().isConflict());
	}

	@Test
	void archivedProjectCannotChangeStoriesOrQuestions() throws Exception {
		Fixture f = fixture();
		awaitRun(f, generate(f), "SUCCEEDED");
		UUID story = storyId(f);
		execute("UPDATE brain.projects SET status = 'ARCHIVED' WHERE id = ?", f.project);

		mvc.perform(patchStory(f, story, f.owner, storyBody("Edited", f.approverRole, 0)))
			.andExpect(status().isConflict());
		mvc.perform(patchQuestion(f, f.question, f.owner, "{\"status\":\"DISMISSED\",\"version\":0}"))
			.andExpect(status().isConflict());
		mvc.perform(post(docUrl(f) + "/stories/generate").with(as(f.owner))).andExpect(status().isConflict());
	}

	@Test
	void generationNeedsAnAnalysis() throws Exception {
		Fixture f = fixture();
		execute("DELETE FROM brain.clarification_questions WHERE organization_id = ?", f.org);
		execute("DELETE FROM brain.requirement_analyses WHERE organization_id = ?", f.org);

		mvc.perform(post(docUrl(f) + "/stories/generate").with(as(f.owner))).andExpect(status().isConflict());
	}

	@Test
	void memberCannotGenerateStories() throws Exception {
		Fixture f = fixture();
		UUID member = seedMember(f.org, "MEMBER");

		mvc.perform(post(docUrl(f) + "/stories/generate").with(as(member))).andExpect(status().isForbidden());
	}

	@Test
	void anotherOrganizationCannotReachStoriesOrQuestions() throws Exception {
		Fixture f = fixture();
		Fixture other = fixture();
		awaitRun(f, generate(f), "SUCCEEDED");
		UUID story = storyId(f);

		mvc.perform(get(ORGS + "/" + other.org + "/requirements/" + f.document + "/stories").with(as(other.owner)))
			.andExpect(status().isNotFound());
		mvc.perform(patch(ORGS + "/" + other.org + "/stories/" + story).with(as(other.owner))
			.contentType(MediaType.APPLICATION_JSON)
			.content(storyBody("Edited", null, 0))).andExpect(status().isNotFound());
		mvc.perform(patch(ORGS + "/" + other.org + "/clarifications/" + f.question).with(as(other.owner))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"status\":\"DISMISSED\",\"version\":0}")).andExpect(status().isNotFound());
	}

	private Fixture fixture() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner);
		UUID viewer = seedMember(org, "VIEWER");
		UUID approver = seedMember(org, "MEMBER");
		UUID project = seedProject(org, owner, "ACTIVE");
		UUID role = UUID.randomUUID();
		execute("""
				INSERT INTO brain.job_roles (id, organization_id, name, active)
				VALUES (?, ?, 'Project Manager', true)
				ON CONFLICT (organization_id, lower(name)) DO NOTHING
				""", role, org);
		role = jobRole(org);
		assignJobRole(org, project, approver, role, owner);
		UUID document = UUID.randomUUID();
		execute("""
				INSERT INTO brain.requirement_documents
				    (id, organization_id, project_id, name, media_type, size_bytes, sha256, extracted_text,
				     dummy_data_confirmed, approver_job_role_id, uploaded_by)
				VALUES (?, ?, ?, 'dummy.md', 'text/markdown', 23, ?, ?, true, ?, ?)
				""", document, org, project, "a".repeat(64), DOCUMENT_TEXT, role, owner);
		UUID run = UUID.randomUUID();
		execute("""
				INSERT INTO brain.ai_runs (id, organization_id, project_id, document_id, run_type, status, requested_by)
				VALUES (?, ?, ?, ?, 'ANALYSIS', 'SUCCEEDED', ?)
				""", run, org, project, document, owner);
		UUID analysis = UUID.randomUUID();
		execute("""
				INSERT INTO brain.requirement_analyses
				    (id, organization_id, document_id, analysis_version, ai_run_id, result, created_by)
				VALUES (?, ?, ?, 1, ?, CAST(? AS jsonb), ?)
				""", analysis, org, document, run, STORED_ANALYSIS, owner);
		UUID question = UUID.randomUUID();
		execute("""
				INSERT INTO brain.clarification_questions (id, organization_id, analysis_id, position, question, reason)
				VALUES (?, ?, ?, 0, 'Can it cancel?', 'Not stated.')
				""", question, org, analysis);
		return new Fixture(owner, org, viewer, approver, project, document, role, question);
	}

	private static UUID jobRole(UUID org) throws Exception {
		try (var admin = adminConnection();
				var statement = admin.prepareStatement(
						"SELECT id FROM brain.job_roles WHERE organization_id = ? AND lower(name) = 'project manager'")) {
			statement.setObject(1, org);
			try (var rows = statement.executeQuery()) {
				rows.next();
				return rows.getObject(1, UUID.class);
			}
		}
	}

	private UUID generate(Fixture f) throws Exception {
		return runId(mvc.perform(post(docUrl(f) + "/stories/generate").with(as(f.owner)))
			.andExpect(status().isAccepted())
			.andReturn()
			.getResponse()
			.getContentAsString());
	}

	private static UUID runId(String body) {
		return UUID.fromString(JsonPath.read(body, "$.runId"));
	}

	private void awaitRun(Fixture f, UUID run, String expected) throws Exception {
		String current = "";
		for (int i = 0; i < 250; i++) {
			String body = mvc.perform(get(ORGS + "/" + f.org + "/ai-runs/" + run).with(as(f.owner)))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();
			current = JsonPath.read(body, "$.status");
			if (current.equals("SUCCEEDED") || current.equals("FAILED")) {
				break;
			}
			Thread.sleep(20);
		}
		assertThat(current).isEqualTo(expected);
	}

	private UUID storyId(Fixture f) throws Exception {
		String body = mvc.perform(get(docUrl(f) + "/stories").with(as(f.owner)))
			.andReturn()
			.getResponse()
			.getContentAsString();
		return UUID.fromString(JsonPath.read(body, "$.items[0].id"));
	}

	private List<String> titles(Fixture f) throws Exception {
		String body = mvc.perform(get(docUrl(f) + "/stories").with(as(f.owner)))
			.andReturn()
			.getResponse()
			.getContentAsString();
		return JsonPath.read(body, "$.items[*].title");
	}

	private static String docUrl(Fixture f) {
		return ORGS + "/" + f.org + "/requirements/" + f.document;
	}

	private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder patchQuestion(Fixture f,
			UUID question, UUID user, String body) {
		return patch(ORGS + "/" + f.org + "/clarifications/" + question).with(as(user))
			.contentType(MediaType.APPLICATION_JSON)
			.content(body);
	}

	private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder patchStory(Fixture f,
			UUID story, UUID user, String body) {
		return patch(ORGS + "/" + f.org + "/stories/" + story).with(as(user))
			.contentType(MediaType.APPLICATION_JSON)
			.content(body);
	}

	private static String storyBody(String title, UUID role, long version) {
		String roleJson = role == null ? "null" : "\"" + role + "\"";
		return """
				{"title":"%s","asA":"diner","iWant":"to book","soThat":"I get a seat",
				 "acceptanceCriteria":["Booking is saved"],"priority":"MEDIUM","jobRoleId":%s,"version":%d}
				""".formatted(title, roleJson, version);
	}

	private static String story(String title, String role, String quote) {
		return """
				{"title":"%s","asA":"diner","iWant":"to book","soThat":"I get a seat",
				 "acceptanceCriteria":["Booking is saved"],"priority":"HIGH","jobRole":"%s","sourceQuotes":["%s"]}
				""".formatted(title, role, quote);
	}

	private static String stories(String... items) {
		return "{\"stories\":[" + String.join(",", items) + "]}";
	}

	private record Fixture(UUID owner, UUID org, UUID viewer, UUID approver, UUID project, UUID document,
			UUID approverRole, UUID question) {
	}

	static class FakeAi implements AiServiceClient {

		final List<StoriesRequest> storyRequests = new CopyOnWriteArrayList<>();

		final List<ClarificationAnswer> analyzeAnswers = new CopyOnWriteArrayList<>();

		volatile String stories;

		volatile AiServiceException failure;

		volatile boolean fatal;

		volatile CountDownLatch started;

		volatile CountDownLatch release;

		@Override
		public ExtractedText extract(String filename, String contentType, byte[] content) {
			throw new UnsupportedOperationException();
		}

		@Override
		public GeneratedResult analyze(String text, List<ClarificationAnswer> answers) {
			analyzeAnswers.addAll(answers);
			return new GeneratedResult(JsonMapper.builder().build().readTree(ANALYSIS_RESULT),
					new AiUsage("gemini", "gemini-test", "analysis-v1", 10, 5));
		}

		@Override
		public GeneratedResult generateStories(StoriesRequest request) {
			storyRequests.add(request);
			if (started != null) {
				started.countDown();
			}
			try {
				if (release != null && !release.await(5, TimeUnit.SECONDS)) {
					throw new AiServiceException("PROVIDER_TIMEOUT");
				}
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new AiServiceException("PROVIDER_TIMEOUT");
			}
			if (fatal) {
				throw new StackOverflowError();
			}
			if (failure != null) {
				throw failure;
			}
			return new GeneratedResult(JsonMapper.builder().build().readTree(stories),
					new AiUsage("gemini", "gemini-test", "stories-v1", 10, 5));
		}

	}

	@TestConfiguration
	static class FakeAiConfig {

		@Bean
		@Primary
		FakeAi fakeAi() {
			return new FakeAi();
		}

	}

}
