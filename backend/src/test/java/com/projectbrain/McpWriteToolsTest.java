package com.projectbrain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import com.projectbrain.knowledge.KnowledgeAiClient;
import com.projectbrain.support.McpTestClient;
import com.projectbrain.support.TeamApiTestBase;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(McpWriteToolsTest.Config.class)
@TestPropertySource(properties = { "SUPABASE_URL=http://127.0.0.1:1", "APP_CORS_ALLOWED_ORIGINS=http://localhost:5173" })
class McpWriteToolsTest extends TeamApiTestBase {

	@LocalServerPort
	private int port;

	@Test
	void createTaskMakesAnAuditedBacklogTask() throws Exception {
		Fixture f = fixture();
		String token = McpTestClient.seedToken(f.owner, f.org, "READ_WRITE");

		try (McpTestClient mcp = McpTestClient.connect(port, token)) {
			assertThat(text(mcp.call("list_job_roles", Map.of()))).contains(f.roleName);
			McpSchema.CallToolResult result = mcp.call("create_task", task(f, "Add booking reminders"));

			assertThat(result.isError()).as(text(result)).isFalse();
			String id = JsonPath.read(text(result), "$.id");
			assertThat((String) JsonPath.read(text(result), "$.path")).contains(f.project.toString());
			assertThat(countRows("""
					SELECT count(*) FROM brain.tasks
					WHERE id = ? AND project_id = ? AND status = 'BACKLOG' AND created_by = ? AND title = 'Add booking reminders'
					""", UUID.fromString(id), f.project, f.owner)).isEqualTo(1);
			assertThat(countRows("""
					SELECT count(*) FROM brain.audit_events
					WHERE entity_id = ? AND action = 'TASK_CREATED' AND actor_id = ?
					  AND metadata->>'via' = 'MCP' AND metadata->>'tokenId' IS NOT NULL
					""", UUID.fromString(id), f.owner)).isEqualTo(1);
		}
	}

	@Test
	void addNoteCreatesAnIndexedNote() throws Exception {
		Fixture f = fixture();
		String token = McpTestClient.seedToken(f.owner, f.org, "READ_WRITE");

		try (McpTestClient mcp = McpTestClient.connect(port, token)) {
			McpSchema.CallToolResult result = mcp.call("add_note", Map.of("projectId", f.project.toString(), "title",
					"On-call", "body", "Page the lead after 10 pm."));

			assertThat(result.isError()).as(text(result)).isFalse();
			UUID note = UUID.fromString(JsonPath.read(text(result), "$.id"));
			assertThat(countRows("SELECT count(*) FROM brain.knowledge_notes WHERE id = ? AND created_by = ?", note,
					f.owner)).isEqualTo(1);
			awaitReady(note);
			assertThat(countRows("""
					SELECT count(*) FROM brain.audit_events
					WHERE entity_id = ? AND action = 'KNOWLEDGE_NOTE_CREATED' AND metadata->>'via' = 'MCP'
					""", note)).isEqualTo(1);
		}
	}

	@Test
	void readOnlyTokenCannotWrite() throws Exception {
		Fixture f = fixture();
		String token = McpTestClient.seedToken(f.owner, f.org, "READ");
		long tasks = countRows("SELECT count(*) FROM brain.tasks WHERE project_id = ?", f.project);

		try (McpTestClient mcp = McpTestClient.connect(port, token)) {
			McpSchema.CallToolResult task = mcp.call("create_task", task(f, "Should not exist"));
			McpSchema.CallToolResult note = mcp.call("add_note",
					Map.of("projectId", f.project.toString(), "title", "Nope", "body", "Nope"));

			assertThat(task.isError()).isTrue();
			assertThat(text(task)).contains("read-only");
			assertThat(note.isError()).isTrue();
		}
		assertThat(countRows("SELECT count(*) FROM brain.tasks WHERE project_id = ?", f.project)).isEqualTo(tasks);
		assertThat(countRows("SELECT count(*) FROM brain.knowledge_notes WHERE project_id = ?", f.project)).isZero();
	}

	@Test
	void memberCanCreateTasksButNotNotesAndViewerCanDoNeither() throws Exception {
		Fixture f = fixture();
		String member = McpTestClient.seedToken(seedMember(f.org, "MEMBER"), f.org, "READ_WRITE");
		String viewer = McpTestClient.seedToken(seedMember(f.org, "VIEWER"), f.org, "READ_WRITE");
		Map<String, Object> note = Map.of("projectId", f.project.toString(), "title", "T", "body", "B");

		try (McpTestClient mcp = McpTestClient.connect(port, member)) {
			assertThat(mcp.call("create_task", task(f, "Member task")).isError()).isFalse();
			assertThat(mcp.call("add_note", note).isError()).isTrue();
		}
		try (McpTestClient mcp = McpTestClient.connect(port, viewer)) {
			assertThat(mcp.call("create_task", task(f, "Viewer task")).isError()).isTrue();
			assertThat(mcp.call("add_note", note).isError()).isTrue();
		}
	}

	@Test
	void archivedAndForeignProjectsRefuseWrites() throws Exception {
		Fixture f = fixture();
		Fixture other = fixture();
		String token = McpTestClient.seedToken(f.owner, f.org, "READ_WRITE");

		try (McpTestClient mcp = McpTestClient.connect(port, token)) {
			McpSchema.CallToolResult foreign = mcp.call("create_task", task(other, "Foreign"));
			assertThat(foreign.isError()).isTrue();
			assertThat(text(foreign)).containsIgnoringCase("not found");

			execute("UPDATE brain.projects SET status = 'ARCHIVED' WHERE id = ?", f.project);
			assertThat(mcp.call("create_task", task(f, "Archived")).isError()).isTrue();
			assertThat(mcp.call("add_note", Map.of("projectId", f.project.toString(), "title", "T", "body", "B"))
				.isError()).isTrue();
		}
	}

	@Test
	void unknownJobRoleIsExplained() throws Exception {
		Fixture f = fixture();
		String token = McpTestClient.seedToken(f.owner, f.org, "READ_WRITE");

		try (McpTestClient mcp = McpTestClient.connect(port, token)) {
			McpSchema.CallToolResult result = mcp.call("create_task", Map.of("projectId", f.project.toString(),
					"title", "T", "jobRole", "Astronaut"));

			assertThat(result.isError()).isTrue();
			assertThat(text(result)).contains("list_job_roles");
		}
	}

	@Test
	void resourcesReturnMarkdownForTheTokensOrganizationOnly() throws Exception {
		Fixture f = fixture();
		Fixture other = fixture();
		String token = McpTestClient.seedToken(f.owner, f.org, "READ");

		try (McpTestClient mcp = McpTestClient.connect(port, token)) {
			assertThat(mcp.client().listResourceTemplates().resourceTemplates())
				.extracting(McpSchema.ResourceTemplate::uriTemplate)
				.containsExactlyInAnyOrder("projectbrain://projects/{projectId}/architecture",
						"projectbrain://projects/{projectId}/stories");
			McpSchema.TextResourceContents architecture = read(mcp,
					"projectbrain://projects/" + f.project + "/architecture");
			assertThat(architecture.mimeType()).isEqualTo("text/markdown");
			assertThat(architecture.text()).contains("Architecture version 1").contains("A booking app");
			assertThat(read(mcp, "projectbrain://projects/" + f.project + "/stories").text()).contains("Book a table")
				.contains("As a diner");

			assertThatThrownBy(() -> read(mcp, "projectbrain://projects/" + other.project + "/architecture"))
				.hasMessageContaining("not found");
		}
	}

	private static McpSchema.TextResourceContents read(McpTestClient mcp, String uri) {
		return (McpSchema.TextResourceContents) mcp.client()
			.readResource(new McpSchema.ReadResourceRequest(uri))
			.contents()
			.get(0);
	}

	private static Map<String, Object> task(Fixture f, String title) {
		return Map.of("projectId", f.project.toString(), "title", title, "description", "From MCP", "priority", "HIGH",
				"jobRole", f.roleName);
	}

	private static String text(McpSchema.CallToolResult result) {
		return ((McpSchema.TextContent) result.content().get(0)).text();
	}

	private static void awaitReady(UUID note) throws Exception {
		for (int i = 0; i < 250; i++) {
			if (countRows("SELECT count(*) FROM brain.knowledge_sources WHERE source_id = ? AND status = 'READY'",
					note) == 1) {
				return;
			}
			Thread.sleep(20);
		}
		throw new AssertionError("note was not indexed in time");
	}

	private static Fixture fixture() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner);
		UUID project = seedProject(org, owner, "ACTIVE");
		String roleName = "Developer " + UUID.randomUUID().toString().substring(0, 8);
		seedJobRole(org, roleName, true);
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
				INSERT INTO brain.requirement_analyses (id, organization_id, document_id, analysis_version, ai_run_id, result, created_by)
				VALUES (?, ?, ?, 1, ?, '{}'::jsonb, ?)
				""", analysis, org, document, run, owner);
		execute("""
				INSERT INTO brain.story_drafts
				    (id, organization_id, project_id, document_id, analysis_id, title, as_a, i_want, so_that,
				     acceptance_criteria, priority, status, generated_by)
				VALUES (?, ?, ?, ?, ?, 'Book a table', 'diner', 'to book', 'I get a seat', '["Saved"]'::jsonb, 'HIGH', 'APPROVED', ?)
				""", UUID.randomUUID(), org, project, document, analysis, owner);
		UUID archRun = UUID.randomUUID();
		execute("""
				INSERT INTO brain.ai_runs (id, organization_id, project_id, run_type, status, requested_by)
				VALUES (?, ?, ?, 'ARCHITECTURE', 'SUCCEEDED', ?)
				""", archRun, org, project, owner);
		execute("""
				INSERT INTO brain.architecture_versions
				    (id, organization_id, project_id, version_number, status, ai_run_id, overview, data_model, api_design,
				     story_ids, generated_by)
				VALUES (?, ?, ?, 1, 'APPROVED', ?, CAST(? AS jsonb), '{"tables":[]}'::jsonb, '{"endpoints":[]}'::jsonb,
				        '[]'::jsonb, ?)
				""", UUID.randomUUID(), org, project, archRun,
				"{\"summary\":\"A booking app\",\"components\":[],\"techStack\":[],\"decisions\":[],\"risks\":[]}",
				owner);
		return new Fixture(owner, org, project, roleName);
	}

	private record Fixture(UUID owner, UUID org, UUID project, String roleName) {
	}

	static class FakeKnowledgeAi implements KnowledgeAiClient {

		@Override
		public List<float[]> embed(List<String> texts) {
			return texts.stream().map(text -> {
				float[] vector = new float[384];
				vector[2] = 1f;
				return vector;
			}).toList();
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
