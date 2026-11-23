package com.projectbrain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.jayway.jsonpath.JsonPath;
import com.projectbrain.requirement.AiRunRecovery;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import(RequirementAnalysisApiTest.FakeAiConfig.class)
@TestPropertySource(properties = { "SUPABASE_URL=http://127.0.0.1:1", "APP_CORS_ALLOWED_ORIGINS=http://localhost:5173" })
class RequirementAnalysisApiTest extends TeamApiTestBase {

	private static final String DOCUMENT_TEXT = "Users can book a table.";

	private static final String RESULT = """
			{
			  "summary": "A dummy booking app.",
			  "features": [
			    {"text": "Book a table", "sourceQuote": "users can  BOOK a table"},
			    {"text": "Pay online", "sourceQuote": "Payment is required"}
			  ],
			  "users": [{"text": "Diners", "sourceQuote": null}],
			  "gaps": [{"text": "Refund rules are missing", "sourceQuote": null}],
			  "risks": [],
			  "questions": [{"question": "Can bookings be cancelled?", "reason": "Not stated."}]
			}
			""";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private FakeAi ai;

	@Autowired
	private AiRunRecovery recovery;

	@BeforeEach
	void resetAi() {
		ai.failure = null;
		ai.result = RESULT;
		ai.started = null;
		ai.release = null;
		ai.calls.clear();
	}

	@Test
	void analyzeCreatesRunAndSavesValidatedResult() throws Exception {
		Fixture f = fixture();

		UUID run = analyze(f, f.owner);
		awaitRun(f, run, "SUCCEEDED");

		mvc.perform(get(runUrl(f.org, run)).with(as(f.owner)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.runType").value("ANALYSIS"))
			.andExpect(jsonPath("$.errorCode").doesNotExist());
		mvc.perform(get(analysisUrl(f)).with(as(f.viewer)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.analysis.version").value(1))
			.andExpect(jsonPath("$.analysis.result.summary").value("A dummy booking app."))
			.andExpect(jsonPath("$.analysis.result.features[0].sourceVerified").value(true))
			.andExpect(jsonPath("$.analysis.result.features[1].sourceVerified").value(false))
			.andExpect(jsonPath("$.analysis.result.users[0].sourceVerified").value(false))
			.andExpect(jsonPath("$.analysis.result.gaps[0].sourceVerified").doesNotExist())
			.andExpect(jsonPath("$.analysis.result.questions").doesNotExist())
			.andExpect(jsonPath("$.questions[0].question").value("Can bookings be cancelled?"))
			.andExpect(jsonPath("$.questions[0].status").value("OPEN"))
			.andExpect(jsonPath("$.latestRun.id").value(run.toString()));
		assertThat(ai.calls).containsExactly(DOCUMENT_TEXT);
		assertThat(audits(f.org, "AI_RUN_STARTED")).isEqualTo(1);
		assertThat(audits(f.org, "AI_RUN_SUCCEEDED")).isEqualTo(1);
		assertThat(countRows("""
				SELECT count(*) FROM brain.audit_events
				WHERE organization_id = ? AND metadata::text LIKE ?
				""", f.org, "%" + DOCUMENT_TEXT + "%")).isZero();
	}

	@Test
	void memberAndViewerCannotStartAnalysis() throws Exception {
		Fixture f = fixture();
		UUID member = seedMember(f.org, "MEMBER");

		mvc.perform(post(analyzeUrl(f)).with(as(member))).andExpect(status().isForbidden());
		mvc.perform(post(analyzeUrl(f)).with(as(f.viewer))).andExpect(status().isForbidden());

		assertThat(ai.calls).isEmpty();
	}

	@Test
	void documentCannotStartTwoActiveRuns() throws Exception {
		Fixture f = fixture();
		ai.started = new CountDownLatch(1);
		ai.release = new CountDownLatch(1);

		UUID run = analyze(f, f.owner);
		assertThat(ai.started.await(5, TimeUnit.SECONDS)).isTrue();
		mvc.perform(post(analyzeUrl(f)).with(as(f.owner))).andExpect(status().isConflict());
		ai.release.countDown();

		awaitRun(f, run, "SUCCEEDED");
		assertThat(ai.calls).hasSize(1);
	}

	@Test
	void providerFailureSavesSafeErrorAndNoPartialAnalysis() throws Exception {
		Fixture f = fixture();
		ai.failure = "RATE_LIMITED";

		UUID run = analyze(f, f.owner);
		awaitRun(f, run, "FAILED");

		mvc.perform(get(runUrl(f.org, run)).with(as(f.owner)))
			.andExpect(jsonPath("$.errorCode").value("RATE_LIMITED"));
		assertThat(analyses(f)).isZero();
		assertThat(audits(f.org, "AI_RUN_FAILED")).isEqualTo(1);
	}

	@Test
	void malformedResultSavesNoPartialAnalysis() throws Exception {
		Fixture f = fixture();
		ai.result = "{\"summary\":\"Missing sections\"}";

		UUID run = analyze(f, f.owner);
		awaitRun(f, run, "FAILED");

		mvc.perform(get(runUrl(f.org, run)).with(as(f.owner)))
			.andExpect(jsonPath("$.errorCode").value("INVALID_OUTPUT"));
		assertThat(analyses(f)).isZero();
	}

	@Test
	void userWhoLosesAccessDuringRunCannotSaveResult() throws Exception {
		Fixture f = fixture();
		UUID admin = seedMember(f.org, "ADMIN");
		ai.started = new CountDownLatch(1);
		ai.release = new CountDownLatch(1);

		UUID run = analyze(f, admin);
		assertThat(ai.started.await(5, TimeUnit.SECONDS)).isTrue();
		mvc.perform(delete(ORGS + "/" + f.org + "/members/" + admin).with(as(f.owner)))
			.andExpect(status().isNoContent());
		ai.release.countDown();

		awaitRun(f, run, "FAILED");
		mvc.perform(get(runUrl(f.org, run)).with(as(f.owner)))
			.andExpect(jsonPath("$.errorCode").value("ACCESS_REVOKED"));
		assertThat(analyses(f)).isZero();
	}

	@Test
	void projectArchivedDuringRunSavesNoResult() throws Exception {
		Fixture f = fixture();
		ai.started = new CountDownLatch(1);
		ai.release = new CountDownLatch(1);

		UUID run = analyze(f, f.owner);
		assertThat(ai.started.await(5, TimeUnit.SECONDS)).isTrue();
		execute("UPDATE brain.projects SET status = 'ARCHIVED' WHERE id = ?", f.project);
		ai.release.countDown();

		awaitRun(f, run, "FAILED");
		mvc.perform(get(runUrl(f.org, run)).with(as(f.owner)))
			.andExpect(jsonPath("$.errorCode").value("PROJECT_ARCHIVED"));
		assertThat(analyses(f)).isZero();
	}

	@Test
	void archivedProjectCannotStartRun() throws Exception {
		Fixture f = fixture();
		execute("UPDATE brain.projects SET status = 'ARCHIVED' WHERE id = ?", f.project);

		mvc.perform(post(analyzeUrl(f)).with(as(f.owner))).andExpect(status().isConflict());
	}

	@Test
	void startupMarksAbandonedRunsFailed() throws Exception {
		Fixture f = fixture();
		UUID run = UUID.randomUUID();
		execute("""
				INSERT INTO brain.ai_runs (id, organization_id, project_id, document_id, run_type, status, requested_by)
				VALUES (?, ?, ?, ?, 'ANALYSIS', 'RUNNING', ?)
				""", run, f.org, f.project, f.document, f.owner);

		assertThat(recovery.recover()).isGreaterThanOrEqualTo(1);

		mvc.perform(get(runUrl(f.org, run)).with(as(f.owner)))
			.andExpect(jsonPath("$.status").value("FAILED"))
			.andExpect(jsonPath("$.errorCode").value("ABANDONED"));
		UUID retry = analyze(f, f.owner);
		awaitRun(f, retry, "SUCCEEDED");
	}

	@Test
	void failedRunCanBeRetried() throws Exception {
		Fixture f = fixture();
		ai.failure = "PROVIDER_TIMEOUT";
		awaitRun(f, analyze(f, f.owner), "FAILED");
		ai.failure = null;

		awaitRun(f, analyze(f, f.owner), "SUCCEEDED");

		assertThat(analyses(f)).isEqualTo(1);
	}

	@Test
	void usageIsRecordedAsFreeTierWithZeroCost() throws Exception {
		Fixture f = fixture();

		UUID run = analyze(f, f.owner);
		awaitRun(f, run, "SUCCEEDED");

		mvc.perform(get(runUrl(f.org, run)).with(as(f.viewer)))
			.andExpect(jsonPath("$.provider").value("gemini"))
			.andExpect(jsonPath("$.model").value("gemini-test"))
			.andExpect(jsonPath("$.promptVersion").value("analysis-v1"))
			.andExpect(jsonPath("$.inputTokens").value(100))
			.andExpect(jsonPath("$.outputTokens").value(25))
			.andExpect(jsonPath("$.billingTier").value("FREE"))
			.andExpect(jsonPath("$.costUsd").value(0));
	}

	@Test
	void anotherOrganizationsRunAndAnalysisReturn404() throws Exception {
		Fixture f = fixture();
		Fixture other = fixture();
		UUID run = analyze(f, f.owner);
		awaitRun(f, run, "SUCCEEDED");

		mvc.perform(get(runUrl(other.org, run)).with(as(other.owner))).andExpect(status().isNotFound());
		mvc.perform(get(ORGS + "/" + other.org + "/requirements/" + f.document + "/analysis").with(as(other.owner)))
			.andExpect(status().isNotFound());
		mvc.perform(post(ORGS + "/" + other.org + "/requirements/" + f.document + "/analyze").with(as(other.owner)))
			.andExpect(status().isNotFound());
	}

	private Fixture fixture() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner);
		UUID viewer = seedMember(org, "VIEWER");
		UUID project = seedProject(org, owner, "ACTIVE");
		UUID document = UUID.randomUUID();
		execute("""
				INSERT INTO brain.requirement_documents
				    (id, organization_id, project_id, name, media_type, size_bytes, sha256, extracted_text,
				     dummy_data_confirmed, uploaded_by)
				VALUES (?, ?, ?, 'dummy.md', 'text/markdown', 23, ?, ?, true, ?)
				""", document, org, project, "a".repeat(64), DOCUMENT_TEXT, owner);
		return new Fixture(owner, org, viewer, project, document);
	}

	private UUID analyze(Fixture f, UUID user) throws Exception {
		String body = mvc.perform(post(analyzeUrl(f)).with(as(user)))
			.andExpect(status().isAccepted())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return UUID.fromString(JsonPath.read(body, "$.runId"));
	}

	private void awaitRun(Fixture f, UUID run, String expected) throws Exception {
		String current = "";
		for (int i = 0; i < 250; i++) {
			String body = mvc.perform(get(runUrl(f.org, run)).with(as(f.owner)))
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

	private static String analyzeUrl(Fixture f) {
		return ORGS + "/" + f.org + "/requirements/" + f.document + "/analyze";
	}

	private static String analysisUrl(Fixture f) {
		return ORGS + "/" + f.org + "/requirements/" + f.document + "/analysis";
	}

	private static String runUrl(UUID org, UUID run) {
		return ORGS + "/" + org + "/ai-runs/" + run;
	}

	private static long analyses(Fixture f) throws Exception {
		return countRows("SELECT count(*) FROM brain.requirement_analyses WHERE document_id = ?", f.document);
	}

	private static long audits(UUID org, String action) throws Exception {
		return countRows("SELECT count(*) FROM brain.audit_events WHERE organization_id = ? AND action = ?", org, action);
	}

	private record Fixture(UUID owner, UUID org, UUID viewer, UUID project, UUID document) {
	}

	static class FakeAi implements AiServiceClient {

		final List<String> calls = new ArrayList<>();

		volatile String failure;

		volatile String result;

		volatile CountDownLatch started;

		volatile CountDownLatch release;

		@Override
		public ExtractedText extract(String filename, String contentType, byte[] content) {
			throw new UnsupportedOperationException();
		}

		@Override
		public GeneratedResult analyze(String text, List<ClarificationAnswer> answers) {
			calls.add(text);
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
			if (failure != null) {
				throw new AiServiceException(failure);
			}
			return new GeneratedResult(JsonMapper.builder().build().readTree(result),
					new AiUsage("gemini", "gemini-test", "analysis-v1", 100, 25));
		}

		@Override
		public GeneratedResult generateStories(StoriesRequest request) {
			throw new UnsupportedOperationException();
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
