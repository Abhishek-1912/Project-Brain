package com.projectbrain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import com.projectbrain.organization.api.OrgTx;
import com.projectbrain.requirement.AiServiceClient;
import com.projectbrain.requirement.api.KnowledgeChanged;
import com.projectbrain.requirement.api.KnowledgeContent;
import com.projectbrain.requirement.api.KnowledgeRef;
import com.projectbrain.requirement.api.KnowledgeSourceLookup;
import com.projectbrain.requirement.api.KnowledgeSourceType;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.event.TransactionalEventListener;

@SpringBootTest
@AutoConfigureMockMvc
@Import(RequirementKnowledgeEventsTest.Config.class)
@TestPropertySource(properties = { "SUPABASE_URL=http://127.0.0.1:1", "APP_CORS_ALLOWED_ORIGINS=http://localhost:5173" })
class RequirementKnowledgeEventsTest extends TeamApiTestBase {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private EventRecorder events;

	@Autowired
	private KnowledgeSourceLookup lookup;

	@Autowired
	private OrgTx orgTx;

	@BeforeEach
	void clearEvents() {
		events.received.clear();
	}

	@Test
	void uploadPublishesDocumentChange() throws Exception {
		Fixture f = fixture();
		MockMultipartFile file = new MockMultipartFile("file", "dummy.md", "text/markdown",
				"Users can book a table.".getBytes(StandardCharsets.UTF_8));

		mvc.perform(multipart(ORGS + "/" + f.org + "/projects/" + f.project + "/requirements").file(file)
			.param("dummyDataConfirmed", "true")
			.with(as(f.owner))).andExpect(status().isCreated());

		assertThat(events.received).singleElement().satisfies(event -> {
			assertThat(event.type()).isEqualTo(KnowledgeSourceType.DOCUMENT);
			assertThat(event.orgId()).isEqualTo(f.org);
			assertThat(event.projectId()).isEqualTo(f.project);
			assertThat(event.actorId()).isEqualTo(f.owner);
		});
	}

	@Test
	void answeringAndDismissingPublishClarificationChanges() throws Exception {
		Fixture f = fixture();
		UUID answered = seedQuestion(f, 0, "OPEN", null);
		UUID dismissed = seedQuestion(f, 1, "OPEN", null);

		mvc.perform(clarify(f, answered, "{\"status\":\"ANSWERED\",\"answer\":\"Yes\",\"version\":0}"))
			.andExpect(status().isOk());
		mvc.perform(clarify(f, dismissed, "{\"status\":\"DISMISSED\",\"version\":0}")).andExpect(status().isOk());

		assertThat(events.received).extracting(KnowledgeChanged::type, KnowledgeChanged::sourceId)
			.containsExactly(org.assertj.core.groups.Tuple.tuple(KnowledgeSourceType.CLARIFICATION, answered),
					org.assertj.core.groups.Tuple.tuple(KnowledgeSourceType.CLARIFICATION, dismissed));
	}

	@Test
	void approvingStoryPublishesOnceAndRejectingOrStaleApprovalPublishesNothing() throws Exception {
		Fixture f = fixture();
		UUID approved = seedStory(f, "Book a table", "DRAFT");
		UUID rejected = seedStory(f, "Cancel a table", "DRAFT");

		mvc.perform(storyDecision(f, approved, "approve", "{\"version\":5}")).andExpect(status().isConflict());
		mvc.perform(storyDecision(f, rejected, "reject", "{\"version\":0,\"comment\":\"No\"}"))
			.andExpect(status().isOk());
		assertThat(events.received).isEmpty();

		mvc.perform(storyDecision(f, approved, "approve", "{\"version\":0}")).andExpect(status().isOk());

		assertThat(events.received).extracting(KnowledgeChanged::type, KnowledgeChanged::sourceId)
			.containsExactly(org.assertj.core.groups.Tuple.tuple(KnowledgeSourceType.STORY, approved));
	}

	@Test
	void editingApprovedStoryPublishesStoryChange() throws Exception {
		Fixture f = fixture();
		UUID story = seedStory(f, "Book a table", "DRAFT");
		mvc.perform(storyDecision(f, story, "approve", "{\"version\":0}")).andExpect(status().isOk());
		events.received.clear();

		mvc.perform(patch(ORGS + "/" + f.org + "/stories/" + story).with(as(f.owner))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"Book a table\",\"asA\":\"diner\",\"iWant\":\"to book later\",\"soThat\":\"I get a seat\","
				+ "\"acceptanceCriteria\":[\"Booking is saved\"],\"priority\":\"HIGH\",\"jobRoleId\":\"" + f.role
				+ "\",\"version\":1}")).andExpect(status().isOk());

		assertThat(events.received).extracting(KnowledgeChanged::type, KnowledgeChanged::sourceId)
			.containsExactly(org.assertj.core.groups.Tuple.tuple(KnowledgeSourceType.STORY, story));
	}

	@Test
	void approvingArchitecturePublishesProjectArchitectureChange() throws Exception {
		Fixture f = fixture();
		UUID rejected = seedVersion(f, 1, "DRAFT");

		mvc.perform(versionDecision(f, rejected, "reject", "{\"version\":0,\"comment\":\"No\"}"))
			.andExpect(status().isOk());
		assertThat(events.received).isEmpty();

		UUID approved = seedVersion(f, 2, "DRAFT");
		mvc.perform(versionDecision(f, approved, "approve", "{\"version\":0}")).andExpect(status().isOk());

		assertThat(events.received).extracting(KnowledgeChanged::type, KnowledgeChanged::sourceId)
			.containsExactly(org.assertj.core.groups.Tuple.tuple(KnowledgeSourceType.ARCHITECTURE, f.project));
	}

	@Test
	void lookupFormatsEachQualifyingSource() throws Exception {
		Fixture f = fixture();
		UUID question = seedQuestion(f, 0, "ANSWERED", "Yes, until 2 hours before.");
		UUID story = seedStory(f, "Book a table", "CONVERTED");
		seedVersion(f, 1, "SUPERSEDED");
		seedVersion(f, 2, "APPROVED");

		assertThat(load(f, KnowledgeSourceType.DOCUMENT, f.document))
			.contains(new KnowledgeContent("dummy.md", "Users can book a table."));
		assertThat(load(f, KnowledgeSourceType.CLARIFICATION, question)).contains(new KnowledgeContent(
				"Clarification: Can bookings be cancelled?",
				"Question: Can bookings be cancelled?\nAnswer: Yes, until 2 hours before."));
		assertThat(load(f, KnowledgeSourceType.STORY, story)).contains(new KnowledgeContent("Book a table", """
				Book a table
				As a diner
				I want to book
				So that I get a seat
				Acceptance criteria:
				- Booking is saved"""));
		assertThat(load(f, KnowledgeSourceType.ARCHITECTURE, f.project)).hasValueSatisfying(content -> {
			assertThat(content.title()).isEqualTo("Architecture version 2");
			assertThat(content.text()).contains("Architecture version 2").contains("A booking app").contains("bookings");
		});
	}

	@Test
	void lookupIsEmptyWhenItemNoLongerQualifies() throws Exception {
		Fixture f = fixture();
		UUID open = seedQuestion(f, 0, "OPEN", null);
		UUID dismissed = seedQuestion(f, 1, "DISMISSED", null);
		UUID draft = seedStory(f, "Draft", "DRAFT");
		UUID rejected = seedStory(f, "Rejected", "REJECTED");
		seedVersion(f, 1, "DRAFT");

		assertThat(load(f, KnowledgeSourceType.CLARIFICATION, open)).isEmpty();
		assertThat(load(f, KnowledgeSourceType.CLARIFICATION, dismissed)).isEmpty();
		assertThat(load(f, KnowledgeSourceType.STORY, draft)).isEmpty();
		assertThat(load(f, KnowledgeSourceType.STORY, rejected)).isEmpty();
		assertThat(load(f, KnowledgeSourceType.ARCHITECTURE, f.project)).isEmpty();
		assertThat(load(f, KnowledgeSourceType.DOCUMENT, UUID.randomUUID())).isEmpty();
	}

	@Test
	void lookupNeverReturnsAnotherProjectsItem() throws Exception {
		Fixture f = fixture();
		Fixture other = fixture();
		UUID story = seedStory(other, "Other story", "APPROVED");

		assertThat(load(f, KnowledgeSourceType.STORY, story)).isEmpty();
		assertThat(load(f, KnowledgeSourceType.DOCUMENT, other.document)).isEmpty();
	}

	@Test
	void qualifyingListsReviewedItemsAndOneArchitecture() throws Exception {
		Fixture f = fixture();
		UUID answered = seedQuestion(f, 0, "ANSWERED", "Yes");
		seedQuestion(f, 1, "OPEN", null);
		UUID approved = seedStory(f, "Approved", "APPROVED");
		UUID converted = seedStory(f, "Converted", "CONVERTED");
		seedStory(f, "Draft", "DRAFT");
		seedVersion(f, 1, "SUPERSEDED");
		seedVersion(f, 2, "APPROVED");

		List<KnowledgeRef> refs = orgTx.withOrgContext(f.owner, f.org, () -> lookup.qualifying(f.org, f.project));

		assertThat(refs).containsExactlyInAnyOrder(new KnowledgeRef(KnowledgeSourceType.DOCUMENT, f.document),
				new KnowledgeRef(KnowledgeSourceType.CLARIFICATION, answered),
				new KnowledgeRef(KnowledgeSourceType.STORY, approved),
				new KnowledgeRef(KnowledgeSourceType.STORY, converted),
				new KnowledgeRef(KnowledgeSourceType.ARCHITECTURE, f.project));
	}

	private Optional<KnowledgeContent> load(Fixture f, KnowledgeSourceType type, UUID sourceId) {
		return orgTx.withOrgContext(f.owner, f.org, () -> lookup.load(f.org, f.project, type, sourceId));
	}

	private static Fixture fixture() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner);
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
		return new Fixture(owner, org, author, project, document, analysis, role);
	}

	private static UUID seedQuestion(Fixture f, int position, String status, String answer) throws Exception {
		UUID id = UUID.randomUUID();
		boolean decided = !"OPEN".equals(status);
		execute("""
				INSERT INTO brain.clarification_questions
				    (id, organization_id, analysis_id, position, question, reason, status, answer, answered_by, answered_at)
				VALUES (?, ?, ?, ?, 'Can bookings be cancelled?', 'Not stated.', ?, ?, ?, CASE WHEN ? THEN now() END)
				""", id, f.org, f.analysis, position, status, answer, decided ? f.owner : null, decided);
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

	private static UUID seedVersion(Fixture f, int number, String status) throws Exception {
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
				VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), CAST(? AS jsonb), '[]'::jsonb, ?)
				""", id, f.org, f.project, number, status, run, """
				{"summary":"A booking app","components":[],"techStack":[],"decisions":[],"risks":[]}
				""", """
				{"tables":[{"name":"bookings","description":"Reservations","columns":[
				  {"name":"id","type":"uuid","nullable":false,"description":"Key"}],"relations":[],"storyIds":[]}]}
				""", "{\"endpoints\":[]}", f.author);
		return id;
	}

	private static MockHttpServletRequestBuilder clarify(Fixture f, UUID question, String body) {
		return patch(ORGS + "/" + f.org + "/clarifications/" + question).with(as(f.owner))
			.contentType(MediaType.APPLICATION_JSON)
			.content(body);
	}

	private static MockHttpServletRequestBuilder storyDecision(Fixture f, UUID story, String action, String body) {
		return post(ORGS + "/" + f.org + "/stories/" + story + "/" + action).with(as(f.owner))
			.contentType(MediaType.APPLICATION_JSON)
			.content(body);
	}

	private static MockHttpServletRequestBuilder versionDecision(Fixture f, UUID version, String action, String body) {
		return post(ORGS + "/" + f.org + "/architecture-versions/" + version + "/" + action).with(as(f.owner))
			.contentType(MediaType.APPLICATION_JSON)
			.content(body);
	}

	private record Fixture(UUID owner, UUID org, UUID author, UUID project, UUID document, UUID analysis, UUID role) {
	}

	static class EventRecorder {

		final List<KnowledgeChanged> received = new CopyOnWriteArrayList<>();

		@TransactionalEventListener
		void on(KnowledgeChanged event) {
			received.add(event);
		}

	}

	static class FakeAi implements AiServiceClient {

		@Override
		public ExtractedText extract(String filename, String contentType, byte[] content) {
			String text = new String(content, StandardCharsets.UTF_8);
			return new ExtractedText(text, text.length());
		}

		@Override
		public GeneratedResult analyze(String text, List<ClarificationAnswer> answers) {
			throw new UnsupportedOperationException();
		}

		@Override
		public GeneratedResult generateStories(StoriesRequest request) {
			throw new UnsupportedOperationException();
		}

	}

	@TestConfiguration
	static class Config {

		@Bean
		EventRecorder eventRecorder() {
			return new EventRecorder();
		}

		@Bean
		@Primary
		FakeAi fakeAi() {
			return new FakeAi();
		}

	}

}
