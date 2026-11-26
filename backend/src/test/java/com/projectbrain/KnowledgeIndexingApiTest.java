package com.projectbrain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import com.projectbrain.knowledge.KnowledgeAiClient;
import com.projectbrain.knowledge.KnowledgeAiException;
import com.projectbrain.knowledge.KnowledgeRecovery;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@Import(KnowledgeIndexingApiTest.Config.class)
@TestPropertySource(properties = { "SUPABASE_URL=http://127.0.0.1:1", "APP_CORS_ALLOWED_ORIGINS=http://localhost:5173" })
class KnowledgeIndexingApiTest extends TeamApiTestBase {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private FakeKnowledgeAi ai;

	@Autowired
	private KnowledgeRecovery recovery;

	@BeforeEach
	void resetAi() {
		ai.failure = null;
		ai.started = null;
		ai.release = null;
		ai.embedded.set(0);
	}

	@Test
	void approvedStoryBecomesReadyWithChunks() throws Exception {
		Fixture f = fixture();
		UUID story = seedStory(f, "Book a table", "DRAFT");

		mvc.perform(post(ORGS + "/" + f.org + "/stories/" + story + "/approve").with(as(f.owner))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"version\":0}")).andExpect(status().isOk());

		await(() -> sourceStatus(f, "STORY", story).equals("READY"));
		assertThat(chunkText(f, "STORY", story)).contains("Book a table").contains("As a diner");
		mvc.perform(get(sourcesUrl(f)).with(as(f.viewer)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].sourceType").value("STORY"))
			.andExpect(jsonPath("$.items[0].sourceId").value(story.toString()))
			.andExpect(jsonPath("$.items[0].title").value("Book a table"))
			.andExpect(jsonPath("$.items[0].status").value("READY"))
			.andExpect(jsonPath("$.items[0].chunkCount").value(1))
			.andExpect(jsonPath("$.totalItems").value(1));
	}

	@Test
	void newerArchitectureReplacesOlderInTheIndex() throws Exception {
		Fixture f = fixture();
		approveVersion(f, seedVersion(f, 1, "Version one summary"));
		await(() -> chunkText(f, "ARCHITECTURE", f.project).contains("Version one summary"));

		approveVersion(f, seedVersion(f, 2, "Version two summary"));

		await(() -> chunkText(f, "ARCHITECTURE", f.project).contains("Version two summary"));
		assertThat(chunkText(f, "ARCHITECTURE", f.project)).doesNotContain("Version one summary");
		assertThat(countRows("SELECT count(*) FROM brain.knowledge_sources WHERE project_id = ?", f.project))
			.isEqualTo(1);
	}

	@Test
	void dismissingAnsweredQuestionRemovesItsSource() throws Exception {
		Fixture f = fixture();
		UUID question = seedQuestion(f);
		clarify(f, question, "{\"status\":\"ANSWERED\",\"answer\":\"Up to 2 hours before.\",\"version\":0}");
		await(() -> sourceStatus(f, "CLARIFICATION", question).equals("READY"));

		clarify(f, question, "{\"status\":\"DISMISSED\",\"version\":1}");

		await(() -> sourceStatus(f, "CLARIFICATION", question).equals("NONE"));
		assertThat(countRows("SELECT count(*) FROM brain.knowledge_chunks WHERE project_id = ?", f.project)).isZero();
	}

	@Test
	void unchangedContentIsNotEmbeddedAgain() throws Exception {
		Fixture f = fixture();
		rebuild(f, f.owner);
		await(() -> sourceStatus(f, "DOCUMENT", f.document).equals("READY"));
		int afterFirst = ai.embedded.get();

		rebuild(f, f.owner);
		Thread.sleep(300);

		assertThat(ai.embedded.get()).isEqualTo(afterFirst).isPositive();
	}

	@Test
	void embedFailureMarksNewSourceFailedWithoutChunks() throws Exception {
		Fixture f = fixture();
		ai.failure = "EMBEDDING_UNAVAILABLE";

		rebuild(f, f.owner);

		await(() -> sourceStatus(f, "DOCUMENT", f.document).equals("FAILED"));
		mvc.perform(get(sourcesUrl(f)).with(as(f.owner)))
			.andExpect(jsonPath("$.items[0].errorCode").value("EMBEDDING_UNAVAILABLE"))
			.andExpect(jsonPath("$.items[0].chunkCount").value(0));
	}

	@Test
	void failedReindexKeepsThePreviousChunks() throws Exception {
		Fixture f = fixture();
		UUID question = seedQuestion(f);
		clarify(f, question, "{\"status\":\"ANSWERED\",\"answer\":\"Old answer.\",\"version\":0}");
		await(() -> sourceStatus(f, "CLARIFICATION", question).equals("READY"));
		ai.failure = "RATE_LIMITED";

		clarify(f, question, "{\"status\":\"ANSWERED\",\"answer\":\"New answer.\",\"version\":1}");

		await(() -> sourceStatus(f, "CLARIFICATION", question).equals("FAILED"));
		assertThat(chunkText(f, "CLARIFICATION", question)).contains("Old answer.");
	}

	@Test
	void sourceRemovedDuringEmbeddingLeavesNoChunks() throws Exception {
		Fixture f = fixture();
		UUID question = seedQuestion(f);
		ai.started = new CountDownLatch(1);
		ai.release = new CountDownLatch(1);
		clarify(f, question, "{\"status\":\"ANSWERED\",\"answer\":\"Yes.\",\"version\":0}");
		assertThat(ai.started.await(5, TimeUnit.SECONDS)).isTrue();

		clarify(f, question, "{\"status\":\"DISMISSED\",\"version\":1}");
		await(() -> sourceStatus(f, "CLARIFICATION", question).equals("NONE"));
		ai.release.countDown();
		Thread.sleep(300);

		assertThat(sourceStatus(f, "CLARIFICATION", question)).isEqualTo("NONE");
		assertThat(countRows("SELECT count(*) FROM brain.knowledge_chunks WHERE project_id = ?", f.project)).isZero();
	}

	@Test
	void startupRecoveryFinishesInterruptedIndexing() throws Exception {
		Fixture f = fixture();
		UUID story = seedStory(f, "Book a table", "APPROVED");
		execute("""
				INSERT INTO brain.knowledge_sources (id, organization_id, project_id, source_type, source_id, title, status, queued_by)
				VALUES (?, ?, ?, 'STORY', ?, 'Book a table', 'INDEXING', ?)
				""", UUID.randomUUID(), f.org, f.project, story, f.owner);

		assertThat(recovery.recover()).isGreaterThanOrEqualTo(1);

		await(() -> sourceStatus(f, "STORY", story).equals("READY"));
		assertThat(chunkText(f, "STORY", story)).contains("Book a table");
	}

	@Test
	void rebuildIndexesKnowledgeApprovedEarlier() throws Exception {
		Fixture f = fixture();
		UUID story = seedStory(f, "Book a table", "APPROVED");
		seedStory(f, "Draft story", "DRAFT");
		execute("UPDATE brain.architecture_versions SET status = 'APPROVED' WHERE id = ?",
				seedVersion(f, 1, "Approved summary"));

		mvc.perform(post(rebuildUrl(f)).with(as(f.owner)))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.queued").value(3));

		await(() -> sourceStatus(f, "DOCUMENT", f.document).equals("READY")
				&& sourceStatus(f, "STORY", story).equals("READY")
				&& sourceStatus(f, "ARCHITECTURE", f.project).equals("READY"));
		assertThat(countRows("SELECT count(*) FROM brain.audit_events WHERE organization_id = ? AND action = ?", f.org,
				"KNOWLEDGE_REBUILT")).isEqualTo(1);
	}

	@Test
	void rebuildRemovesSourcesThatNoLongerQualify() throws Exception {
		Fixture f = fixture();
		UUID story = seedStory(f, "Book a table", "APPROVED");
		rebuild(f, f.owner);
		await(() -> sourceStatus(f, "STORY", story).equals("READY"));
		execute("UPDATE brain.story_drafts SET status = 'REJECTED' WHERE id = ?", story);

		rebuild(f, f.owner);

		await(() -> sourceStatus(f, "STORY", story).equals("NONE"));
	}

	@Test
	void onlyManagersCanRebuildActiveProjects() throws Exception {
		Fixture f = fixture();
		UUID member = seedMember(f.org, "MEMBER");

		mvc.perform(post(rebuildUrl(f)).with(as(member))).andExpect(status().isForbidden());
		mvc.perform(post(rebuildUrl(f)).with(as(f.viewer))).andExpect(status().isForbidden());
		execute("UPDATE brain.projects SET status = 'ARCHIVED' WHERE id = ?", f.project);
		mvc.perform(post(rebuildUrl(f)).with(as(f.owner))).andExpect(status().isConflict());
	}

	@Test
	void anotherOrganizationGets404() throws Exception {
		Fixture f = fixture();
		Fixture other = fixture();

		mvc.perform(get(ORGS + "/" + other.org + "/projects/" + f.project + "/knowledge/sources").with(as(other.owner)))
			.andExpect(status().isNotFound());
		mvc.perform(post(ORGS + "/" + other.org + "/projects/" + f.project + "/knowledge/rebuild").with(as(other.owner)))
			.andExpect(status().isNotFound());
		mvc.perform(get(sourcesUrl(f)).with(as(other.owner))).andExpect(status().isNotFound());
	}

	private void rebuild(Fixture f, UUID user) throws Exception {
		mvc.perform(post(rebuildUrl(f)).with(as(user))).andExpect(status().isAccepted());
	}

	private void approveVersion(Fixture f, UUID version) throws Exception {
		mvc.perform(post(ORGS + "/" + f.org + "/architecture-versions/" + version + "/approve").with(as(f.owner))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"version\":0}")).andExpect(status().isOk());
	}

	private void clarify(Fixture f, UUID question, String body) throws Exception {
		MockHttpServletRequestBuilder request = patch(ORGS + "/" + f.org + "/clarifications/" + question)
			.with(as(f.owner))
			.contentType(MediaType.APPLICATION_JSON)
			.content(body);
		mvc.perform(request).andExpect(status().isOk());
	}

	private static void await(BooleanSupplier condition) throws Exception {
		for (int i = 0; i < 250; i++) {
			if (condition.getAsBoolean()) {
				return;
			}
			Thread.sleep(20);
		}
		assertThat(condition.getAsBoolean()).as("condition reached in time").isTrue();
	}

	private static String sourceStatus(Fixture f, String type, UUID sourceId) {
		try (var admin = adminConnection(); var statement = admin.prepareStatement("""
				SELECT status FROM brain.knowledge_sources WHERE project_id = ? AND source_type = ? AND source_id = ?
				""")) {
			statement.setObject(1, f.project);
			statement.setString(2, type);
			statement.setObject(3, sourceId);
			try (var rows = statement.executeQuery()) {
				return rows.next() ? rows.getString(1) : "NONE";
			}
		}
		catch (java.sql.SQLException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static String chunkText(Fixture f, String type, UUID sourceId) {
		try (var admin = adminConnection(); var statement = admin.prepareStatement("""
				SELECT coalesce(string_agg(c.content, ' ' ORDER BY c.position), '')
				FROM brain.knowledge_chunks c JOIN brain.knowledge_sources s ON s.id = c.source_row_id
				WHERE s.project_id = ? AND s.source_type = ? AND s.source_id = ?
				""")) {
			statement.setObject(1, f.project);
			statement.setString(2, type);
			statement.setObject(3, sourceId);
			try (var rows = statement.executeQuery()) {
				rows.next();
				return rows.getString(1);
			}
		}
		catch (java.sql.SQLException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static String sourcesUrl(Fixture f) {
		return ORGS + "/" + f.org + "/projects/" + f.project + "/knowledge/sources";
	}

	private static String rebuildUrl(Fixture f) {
		return ORGS + "/" + f.org + "/projects/" + f.project + "/knowledge/rebuild";
	}

	private static Fixture fixture() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner);
		UUID viewer = seedMember(org, "VIEWER");
		UUID author = seedMember(org, "MEMBER");
		UUID project = seedProject(org, owner, "ACTIVE");
		UUID role = seedJobRole(org, "Developer " + UUID.randomUUID(), true);
		UUID document = UUID.randomUUID();
		execute("""
				INSERT INTO brain.requirement_documents
				    (id, organization_id, project_id, name, media_type, size_bytes, sha256, extracted_text,
				     dummy_data_confirmed, uploaded_by)
				VALUES (?, ?, ?, 'dummy.md', 'text/markdown', 23, ?, 'Users can book a table.', true, ?)
				""", document, org, project, "a".repeat(64), owner);
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
				""", analysis, org, document, run,
				"{\"summary\":\"S\",\"features\":[],\"users\":[],\"gaps\":[],\"risks\":[]}", owner);
		return new Fixture(owner, org, viewer, author, project, document, analysis, role);
	}

	private static UUID seedQuestion(Fixture f) throws Exception {
		UUID id = UUID.randomUUID();
		execute("""
				INSERT INTO brain.clarification_questions (id, organization_id, analysis_id, position, question, reason)
				VALUES (?, ?, ?, 0, 'Can bookings be cancelled?', 'Not stated.')
				""", id, f.org, f.analysis);
		return id;
	}

	private static UUID seedStory(Fixture f, String title, String status) throws Exception {
		UUID id = UUID.randomUUID();
		execute("""
				INSERT INTO brain.story_drafts
				    (id, organization_id, project_id, document_id, analysis_id, title, as_a, i_want, so_that,
				     acceptance_criteria, priority, job_role_id, status, generated_by)
				VALUES (?, ?, ?, ?, ?, ?, 'diner', 'to book', 'I get a seat', '["Booking is saved"]'::jsonb, 'HIGH', ?, ?, ?)
				""", id, f.org, f.project, f.document, f.analysis, title, f.role, status, f.author);
		return id;
	}

	private static UUID seedVersion(Fixture f, int number, String summary) throws Exception {
		UUID run = UUID.randomUUID();
		execute("""
				INSERT INTO brain.ai_runs (id, organization_id, project_id, run_type, status, requested_by, created_at)
				VALUES (?, ?, ?, 'ARCHITECTURE', 'SUCCEEDED', ?, clock_timestamp())
				""", run, f.org, f.project, f.author);
		UUID id = UUID.randomUUID();
		execute("""
				INSERT INTO brain.architecture_versions
				    (id, organization_id, project_id, version_number, status, ai_run_id, overview, data_model, api_design,
				     story_ids, generated_by)
				VALUES (?, ?, ?, ?, 'DRAFT', ?, CAST(? AS jsonb), '{"tables":[]}'::jsonb, '{"endpoints":[]}'::jsonb,
				        '[]'::jsonb, ?)
				""", id, f.org, f.project, number, run, """
				{"summary":"%s","components":[],"techStack":[],"decisions":[],"risks":[]}
				""".formatted(summary), f.author);
		return id;
	}

	private record Fixture(UUID owner, UUID org, UUID viewer, UUID author, UUID project, UUID document,
			UUID analysis, UUID role) {
	}

	static class FakeKnowledgeAi implements KnowledgeAiClient {

		final AtomicInteger embedded = new AtomicInteger();

		volatile String failure;

		volatile CountDownLatch started;

		volatile CountDownLatch release;

		@Override
		public List<float[]> embed(List<String> texts) {
			if (started != null) {
				started.countDown();
			}
			try {
				if (release != null && !release.await(5, TimeUnit.SECONDS)) {
					throw new KnowledgeAiException("PROVIDER_TIMEOUT");
				}
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new KnowledgeAiException("PROVIDER_TIMEOUT");
			}
			if (failure != null) {
				throw new KnowledgeAiException(failure);
			}
			embedded.addAndGet(texts.size());
			List<float[]> vectors = new ArrayList<>();
			for (String text : texts) {
				float[] vector = new float[384];
				vector[Math.floorMod(text.hashCode(), 384)] = 1f;
				vectors.add(vector);
			}
			return vectors;
		}

		@Override
		public GeneratedAnswer answer(String question, List<Passage> passages) {
			throw new UnsupportedOperationException();
		}

	}

	@TestConfiguration
	static class Config {

		@Bean
		@Primary
		FakeKnowledgeAi fakeKnowledgeAi() {
			return new FakeKnowledgeAi();
		}

	}

}
