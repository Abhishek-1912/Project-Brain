package com.projectbrain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import com.jayway.jsonpath.JsonPath;
import com.projectbrain.knowledge.KnowledgeAiClient;
import com.projectbrain.support.TeamApiTestBase;
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
@Import(KnowledgeNotesApiTest.Config.class)
@TestPropertySource(properties = { "SUPABASE_URL=http://127.0.0.1:1", "APP_CORS_ALLOWED_ORIGINS=http://localhost:5173" })
class KnowledgeNotesApiTest extends TeamApiTestBase {

	@Autowired
	private MockMvc mvc;

	@Test
	void managerCreatesNoteAndAnyMemberCanRead() throws Exception {
		Fixture f = fixture();

		UUID note = create(f, f.owner, "Release checklist", "  Deploy on Fridays only.  ");

		mvc.perform(get(noteUrl(f, note)).with(as(f.viewer)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.projectId").value(f.project.toString()))
			.andExpect(jsonPath("$.title").value("Release checklist"))
			.andExpect(jsonPath("$.body").value("Deploy on Fridays only."))
			.andExpect(jsonPath("$.createdBy").value(f.owner.toString()))
			.andExpect(jsonPath("$.version").value(0));
		mvc.perform(get(notesUrl(f)).with(as(f.viewer)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(1)))
			.andExpect(jsonPath("$.totalItems").value(1));
	}

	@Test
	void onlyManagersCanWriteNotes() throws Exception {
		Fixture f = fixture();
		UUID member = seedMember(f.org, "MEMBER");
		UUID admin = seedMember(f.org, "ADMIN");
		UUID note = create(f, admin, "Title", "Body");

		mvc.perform(createRequest(f, member, "Title", "Body")).andExpect(status().isForbidden());
		mvc.perform(createRequest(f, f.viewer, "Title", "Body")).andExpect(status().isForbidden());
		mvc.perform(patchRequest(f, note, member, "{\"title\":\"New\",\"version\":0}")).andExpect(status().isForbidden());
		mvc.perform(delete(noteUrl(f, note)).with(as(f.viewer))).andExpect(status().isForbidden());
	}

	@Test
	void titleAndBodyLimitsApplyAfterTrimming() throws Exception {
		Fixture f = fixture();

		mvc.perform(createRequest(f, f.owner, "   ", "Body")).andExpect(status().isUnprocessableContent());
		mvc.perform(createRequest(f, f.owner, "x".repeat(201), "Body")).andExpect(status().isUnprocessableContent());
		mvc.perform(createRequest(f, f.owner, "Title", " ")).andExpect(status().isUnprocessableContent());
		mvc.perform(createRequest(f, f.owner, "Title", "x".repeat(20001))).andExpect(status().isUnprocessableContent());
		mvc.perform(createRequest(f, f.owner, "x".repeat(200), "x".repeat(20000))).andExpect(status().isCreated());
	}

	@Test
	void staleEditIsRejected() throws Exception {
		Fixture f = fixture();
		UUID note = create(f, f.owner, "Title", "Body");

		mvc.perform(patchRequest(f, note, f.owner, "{\"body\":\"Changed\",\"version\":0}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.body").value("Changed"))
			.andExpect(jsonPath("$.title").value("Title"))
			.andExpect(jsonPath("$.version").value(1));
		mvc.perform(patchRequest(f, note, f.owner, "{\"body\":\"Again\",\"version\":0}"))
			.andExpect(status().isConflict());
		mvc.perform(patchRequest(f, note, f.owner, "{\"body\":\"Again\"}")).andExpect(status().isConflict());
	}

	@Test
	void notesFollowSearchThroughCreateEditAndDelete() throws Exception {
		Fixture f = fixture();
		UUID note = create(f, f.owner, "Wildlife", "Pelicans nest near the harbour.");
		await(() -> hits(f, "pelicans") == 1);

		mvc.perform(patchRequest(f, note, f.owner, "{\"body\":\"Flamingos nest near the lake.\",\"version\":0}"))
			.andExpect(status().isOk());
		await(() -> hits(f, "flamingos") == 1);
		assertThat(hits(f, "pelicans")).isZero();

		mvc.perform(delete(noteUrl(f, note)).with(as(f.owner))).andExpect(status().isNoContent());

		assertThat(hits(f, "flamingos")).isZero();
		mvc.perform(get(noteUrl(f, note)).with(as(f.owner))).andExpect(status().isNotFound());
		assertThat(countRows("SELECT count(*) FROM brain.knowledge_sources WHERE project_id = ?", f.project)).isZero();
	}

	@Test
	void archivedProjectRefusesNoteChanges() throws Exception {
		Fixture f = fixture();
		UUID note = create(f, f.owner, "Title", "Body");
		execute("UPDATE brain.projects SET status = 'ARCHIVED' WHERE id = ?", f.project);

		mvc.perform(createRequest(f, f.owner, "Title", "Body")).andExpect(status().isConflict());
		mvc.perform(patchRequest(f, note, f.owner, "{\"body\":\"Changed\",\"version\":0}"))
			.andExpect(status().isConflict());
		mvc.perform(delete(noteUrl(f, note)).with(as(f.owner))).andExpect(status().isConflict());
		mvc.perform(get(noteUrl(f, note)).with(as(f.viewer))).andExpect(status().isOk());
	}

	@Test
	void anotherOrganizationGets404() throws Exception {
		Fixture f = fixture();
		Fixture other = fixture();
		UUID note = create(f, f.owner, "Title", "Body");

		mvc.perform(get(noteUrl(f, note)).with(as(other.owner))).andExpect(status().isNotFound());
		mvc.perform(get(ORGS + "/" + other.org + "/knowledge/notes/" + note).with(as(other.owner)))
			.andExpect(status().isNotFound());
		mvc.perform(patch(ORGS + "/" + other.org + "/knowledge/notes/" + note).with(as(other.owner))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"Taken\",\"version\":0}")).andExpect(status().isNotFound());
		mvc.perform(delete(ORGS + "/" + other.org + "/knowledge/notes/" + note).with(as(other.owner)))
			.andExpect(status().isNotFound());
		mvc.perform(get(ORGS + "/" + other.org + "/projects/" + f.project + "/knowledge/notes").with(as(other.owner)))
			.andExpect(status().isNotFound());
	}

	@Test
	void auditHasNoNoteBody() throws Exception {
		Fixture f = fixture();
		UUID note = create(f, f.owner, "Title", "Secret body text");
		mvc.perform(patchRequest(f, note, f.owner, "{\"body\":\"Secret edited text\",\"version\":0}"))
			.andExpect(status().isOk());
		mvc.perform(delete(noteUrl(f, note)).with(as(f.owner))).andExpect(status().isNoContent());

		for (String action : List.of("KNOWLEDGE_NOTE_CREATED", "KNOWLEDGE_NOTE_UPDATED", "KNOWLEDGE_NOTE_DELETED")) {
			assertThat(countRows("SELECT count(*) FROM brain.audit_events WHERE entity_id = ? AND action = ?", note,
					action)).as(action).isEqualTo(1);
		}
		assertThat(countRows("SELECT count(*) FROM brain.audit_events WHERE entity_id = ? AND metadata::text LIKE ?",
				note, "%Secret%")).isZero();
	}

	private UUID create(Fixture f, UUID user, String title, String body) throws Exception {
		String response = mvc.perform(createRequest(f, user, title, body))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return UUID.fromString(JsonPath.read(response, "$.id"));
	}

	private int hits(Fixture f, String query) {
		try {
			String response = mvc.perform(get(ORGS + "/" + f.org + "/projects/" + f.project + "/knowledge/search")
				.param("q", query)
				.with(as(f.owner))).andReturn().getResponse().getContentAsString();
			List<Object> items = JsonPath.read(response, "$.items");
			return items.size();
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
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

	private static MockHttpServletRequestBuilder createRequest(Fixture f, UUID user, String title, String body) {
		return post(notesUrl(f)).with(as(user))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"" + title + "\",\"body\":\"" + body + "\"}");
	}

	private static MockHttpServletRequestBuilder patchRequest(Fixture f, UUID note, UUID user, String body) {
		return patch(noteUrl(f, note)).with(as(user)).contentType(MediaType.APPLICATION_JSON).content(body);
	}

	private static String notesUrl(Fixture f) {
		return ORGS + "/" + f.org + "/projects/" + f.project + "/knowledge/notes";
	}

	private static String noteUrl(Fixture f, UUID note) {
		return ORGS + "/" + f.org + "/knowledge/notes/" + note;
	}

	private static Fixture fixture() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner);
		UUID viewer = seedMember(org, "VIEWER");
		return new Fixture(owner, org, viewer, seedProject(org, owner, "ACTIVE"));
	}

	private record Fixture(UUID owner, UUID org, UUID viewer, UUID project) {
	}

	/** Gives every text a vector unrelated to the others, so these tests match by keyword only. */
	static class FakeKnowledgeAi implements KnowledgeAiClient {

		@Override
		public List<float[]> embed(List<String> texts) {
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
