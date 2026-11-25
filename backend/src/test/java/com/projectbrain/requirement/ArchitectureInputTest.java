package com.projectbrain.requirement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import com.projectbrain.organization.api.OrgTx;
import com.projectbrain.organization.api.Permission;
import com.projectbrain.shared.web.ConflictException;
import com.projectbrain.shared.web.UnprocessableException;
import com.projectbrain.support.TeamApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = { "SUPABASE_URL=http://127.0.0.1:1", "APP_CORS_ALLOWED_ORIGINS=http://localhost:5173" })
class ArchitectureInputTest extends TeamApiTestBase {

	@Autowired
	private OrgTx orgTx;

	@Autowired
	private ArchitectureInputService inputs;

	private record Fixture(UUID owner, UUID org, UUID project, UUID document, UUID analysis) {
	}

	@Test
	void inputUsesApprovedAndConvertedStoriesOnly() throws Exception {
		Fixture f = fixture();
		UUID approved = story(f, "Approved", "APPROVED");
		UUID converted = story(f, "Converted", "CONVERTED");
		story(f, "Draft", "DRAFT");
		story(f, "Rejected", "REJECTED");

		ArchitectureInput input = load(f);

		assertThat(input.stories()).extracting(AiServiceClient.ArchitectureStoryInput::title)
			.containsExactly("Approved", "Converted");
		assertThat(input.stories()).extracting(AiServiceClient.ArchitectureStoryInput::key).containsExactly("S1", "S2");
		assertThat(input.storyIdsByKey()).containsEntry("S1", approved).containsEntry("S2", converted);
		assertThat(input.startingPoint()).isNull();
		assertThat(input.fingerprint()).matches("[0-9a-f]{64}");
	}

	@Test
	void rejectedVersionAndCommentsBecomeTheStartingPoint() throws Exception {
		Fixture f = fixture();
		UUID story = story(f, "Approved", "APPROVED");
		version(f, 1, "APPROVED", "{\"tables\":[]}");
		UUID rejected = version(f, 2, "REJECTED", """
				{"tables":[{"name":"bookings","description":"D","columns":[],"relations":[],"storyIds":["%s"]}]}
				""".formatted(story));
		execute("""
				INSERT INTO brain.architecture_reviews
				    (id, organization_id, project_id, version_id, content_version, action, actor_id, approval_basis, comment)
				VALUES (?, ?, ?, ?, 0, 'REJECTED', ?, 'OWNER_FALLBACK', 'Rename the bookings table')
				""", UUID.randomUUID(), f.org(), f.project(), rejected, f.owner());

		ArchitectureInput input = load(f);

		assertThat(input.startingVersionId()).isEqualTo(rejected);
		assertThat(input.startingPoint().get("status").asString()).isEqualTo("REJECTED");
		assertThat(input.startingPoint().get("rejectionComments").get(0).asString()).isEqualTo("Rename the bookings table");
		assertThat(input.startingPoint().get("dataModel").get("tables").get(0).get("storyRefs").get(0).asString())
			.isEqualTo("S1");
	}

	@Test
	void approvedVersionIsTheStartingPointWhenNewestReviewIsApproval() throws Exception {
		Fixture f = fixture();
		story(f, "Approved", "APPROVED");
		version(f, 1, "REJECTED", "{\"tables\":[]}");
		UUID approved = version(f, 2, "APPROVED", "{\"tables\":[]}");
		version(f, 3, "DRAFT", "{\"tables\":[]}");

		ArchitectureInput input = load(f);

		assertThat(input.startingVersionId()).isEqualTo(approved);
		assertThat(input.startingPoint().get("status").asString()).isEqualTo("APPROVED");
		assertThat(input.startingPoint().get("rejectionComments")).isEmpty();
	}

	@Test
	void noStoriesReturns409() throws Exception {
		Fixture f = fixture();
		story(f, "Draft", "DRAFT");

		assertThatThrownBy(() -> load(f)).isInstanceOf(ConflictException.class);
	}

	@Test
	void over200StoriesOrCharacterLimitReturns422() throws Exception {
		Fixture many = fixture();
		for (int i = 0; i < 201; i++) {
			story(many, "Story " + i, "APPROVED");
		}
		Fixture large = fixture();
		String criteria = "[" + String.join(",", java.util.Collections.nCopies(20, "\"" + "x".repeat(1000) + "\"")) + "]";
		for (int i = 0; i < 11; i++) {
			UUID id = story(large, "Large " + i, "APPROVED");
			execute("UPDATE brain.story_drafts SET acceptance_criteria = CAST(? AS jsonb) WHERE id = ?", criteria, id);
		}

		assertThatThrownBy(() -> load(many)).isInstanceOf(UnprocessableException.class);
		assertThatThrownBy(() -> load(large)).isInstanceOf(UnprocessableException.class);
	}

	@Test
	void stepOneLeavesRoomForLaterSteps() throws Exception {
		Fixture f = fixture();
		String criteria = "[" + String.join(",", java.util.Collections.nCopies(20, "\"" + "x".repeat(1000) + "\"")) + "]";
		for (int i = 0; i < 7; i++) {
			UUID id = story(f, "Large " + i, "APPROVED");
			execute("UPDATE brain.story_drafts SET acceptance_criteria = CAST(? AS jsonb) WHERE id = ?", criteria, id);
		}

		// About 140,000 characters fits Python's 200,000 for step 1 but leaves no room for steps 2 and 3.
		assertThatThrownBy(() -> load(f)).isInstanceOf(UnprocessableException.class);
	}

	@Test
	void largeStartingPointIsCompactedInsteadOfBlockingGeneration() throws Exception {
		Fixture f = fixture();
		story(f, "Approved", "APPROVED");
		StringBuilder tables = new StringBuilder("{\"tables\":[");
		for (int i = 0; i < 60; i++) {
			tables.append(i == 0 ? "" : ",").append("{\"name\":\"table_").append(i)
				.append("\",\"description\":\"").append("d".repeat(1000)).append("\",\"columns\":[");
			for (int c = 0; c < 20; c++) {
				tables.append(c == 0 ? "" : ",").append("{\"name\":\"col_").append(c)
					.append("\",\"type\":\"text\",\"nullable\":true,\"description\":\"").append("c".repeat(200))
					.append("\"}");
			}
			tables.append("],\"relations\":[],\"storyIds\":[]}");
		}
		tables.append("]}");
		version(f, 1, "REJECTED", tables.toString());

		ArchitectureInput input = load(f);

		assertThat(input.startingPoint().get("status").asString()).isEqualTo("REJECTED");
		assertThat(input.startingPoint().get("compacted").asBoolean()).isTrue();
		assertThat(input.startingPoint().get("dataModel").get("tables").get(0).get("name").asString()).isEqualTo("table_0");
		assertThat(input.startingPoint().get("dataModel").get("tables").get(0).has("columns")).isFalse();
	}

	@Test
	void fingerprintChangesWithStoriesStartingPointOrPromptVersions() throws Exception {
		Fixture f = fixture();
		UUID story = story(f, "Approved", "APPROVED");
		String first = load(f).fingerprint();

		assertThat(load(f).fingerprint()).isEqualTo(first);
		execute("UPDATE brain.story_drafts SET version = version + 1 WHERE id = ?", story);
		String afterEdit = load(f).fingerprint();
		assertThat(afterEdit).isNotEqualTo(first);
		version(f, 1, "APPROVED", "{\"tables\":[]}");
		assertThat(load(f).fingerprint()).isNotEqualTo(afterEdit);
		assertThat(ArchitectureInputService.fingerprint(java.util.List.of(), null, 0, java.util.List.of(),
				java.util.Map.of("OVERVIEW", "architecture-overview-v2")))
			.isNotEqualTo(ArchitectureInputService.fingerprint(java.util.List.of(), null, 0, java.util.List.of(),
					ArchitecturePrompts.VERSIONS));
	}

	private ArchitectureInput load(Fixture f) {
		return orgTx.inOrgAs(f.owner(), f.org(), Permission.VIEW, () -> inputs.load(f.org(), f.project()));
	}

	private static Fixture fixture() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner);
		UUID project = seedProject(org, owner, "ACTIVE");
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
		return new Fixture(owner, org, project, document, analysis);
	}

	private static UUID story(Fixture f, String title, String status) throws Exception {
		UUID id = UUID.randomUUID();
		execute("""
				INSERT INTO brain.story_drafts
				    (id, organization_id, project_id, document_id, analysis_id, title, as_a, i_want, so_that,
				     acceptance_criteria, priority, status, generated_by, created_at)
				VALUES (?, ?, ?, ?, ?, ?, 'diner', 'to book', 'I get a seat', '["Saved"]'::jsonb, 'HIGH', ?, ?, clock_timestamp())
				""", id, f.org(), f.project(), f.document(), f.analysis(), title, status, f.owner());
		return id;
	}

	private static UUID version(Fixture f, int number, String status, String dataModel) throws Exception {
		UUID run = UUID.randomUUID();
		execute("""
				INSERT INTO brain.ai_runs (id, organization_id, project_id, run_type, status, requested_by)
				VALUES (?, ?, ?, 'ARCHITECTURE', 'SUCCEEDED', ?)
				""", run, f.org(), f.project(), f.owner());
		UUID id = UUID.randomUUID();
		execute("""
				INSERT INTO brain.architecture_versions
				    (id, organization_id, project_id, version_number, status, ai_run_id, overview, data_model, api_design,
				     story_ids, generated_by)
				VALUES (?, ?, ?, ?, ?, ?, '{"summary":"S"}', CAST(? AS jsonb), '{"endpoints":[]}', '[]', ?)
				""", id, f.org(), f.project(), number, status, run, dataModel, f.owner());
		return id;
	}

}
