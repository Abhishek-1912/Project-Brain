package com.projectbrain.requirement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.projectbrain.requirement.ArchitectureService.ArchitectureVersion;
import com.projectbrain.requirement.api.KnowledgeContent;
import com.projectbrain.requirement.api.KnowledgeRef;
import com.projectbrain.requirement.api.KnowledgeSourceLookup;
import com.projectbrain.requirement.api.KnowledgeSourceType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Every query filters by organization and project, on top of row-level security. */
@Component
class RequirementKnowledgeLookup implements KnowledgeSourceLookup {

	private static final int MAX_TITLE_CHARS = 200;

	// The version view needs a viewer only for edit and review flags, which knowledge text never uses.
	private static final UUID NO_VIEWER = new UUID(0, 0);

	private final JdbcClient jdbc;

	private final ArchitectureService architecture;

	private final JsonMapper json;

	RequirementKnowledgeLookup(JdbcClient jdbc, ArchitectureService architecture, JsonMapper json) {
		this.jdbc = jdbc;
		this.architecture = architecture;
		this.json = json;
	}

	@Override
	public Optional<KnowledgeContent> load(UUID orgId, UUID projectId, KnowledgeSourceType type, UUID sourceId) {
		return switch (type) {
			case DOCUMENT -> document(orgId, projectId, sourceId);
			case CLARIFICATION -> clarification(orgId, projectId, sourceId);
			case STORY -> story(orgId, projectId, sourceId);
			case ARCHITECTURE -> projectId.equals(sourceId) ? architecture(orgId, projectId) : Optional.empty();
			case NOTE -> Optional.empty();
		};
	}

	@Override
	public List<KnowledgeRef> qualifying(UUID orgId, UUID projectId) {
		List<KnowledgeRef> refs = new ArrayList<>();
		ids("""
				SELECT id FROM brain.requirement_documents WHERE organization_id = :orgId AND project_id = :projectId
				""", orgId, projectId).forEach(id -> refs.add(new KnowledgeRef(KnowledgeSourceType.DOCUMENT, id)));
		ids("""
				SELECT q.id FROM brain.clarification_questions q
				JOIN brain.requirement_analyses a ON a.organization_id = q.organization_id AND a.id = q.analysis_id
				JOIN brain.requirement_documents d ON d.organization_id = a.organization_id AND d.id = a.document_id
				WHERE q.organization_id = :orgId AND d.project_id = :projectId AND q.status = 'ANSWERED'
				""", orgId, projectId).forEach(id -> refs.add(new KnowledgeRef(KnowledgeSourceType.CLARIFICATION, id)));
		ids("""
				SELECT id FROM brain.story_drafts
				WHERE organization_id = :orgId AND project_id = :projectId AND status IN ('APPROVED', 'CONVERTED')
				""", orgId, projectId).forEach(id -> refs.add(new KnowledgeRef(KnowledgeSourceType.STORY, id)));
		if (approvedVersion(orgId, projectId).isPresent()) {
			refs.add(new KnowledgeRef(KnowledgeSourceType.ARCHITECTURE, projectId));
		}
		return refs;
	}

	private Optional<KnowledgeContent> document(UUID orgId, UUID projectId, UUID id) {
		return jdbc.sql("""
				SELECT name, extracted_text FROM brain.requirement_documents
				WHERE organization_id = :orgId AND project_id = :projectId AND id = :id
				""")
			.param("orgId", orgId)
			.param("projectId", projectId)
			.param("id", id)
			.query((row, rowNumber) -> new KnowledgeContent(row.getString("name"), row.getString("extracted_text")))
			.optional();
	}

	private Optional<KnowledgeContent> clarification(UUID orgId, UUID projectId, UUID id) {
		return jdbc.sql("""
				SELECT q.question, q.answer FROM brain.clarification_questions q
				JOIN brain.requirement_analyses a ON a.organization_id = q.organization_id AND a.id = q.analysis_id
				JOIN brain.requirement_documents d ON d.organization_id = a.organization_id AND d.id = a.document_id
				WHERE q.organization_id = :orgId AND d.project_id = :projectId AND q.id = :id AND q.status = 'ANSWERED'
				""")
			.param("orgId", orgId)
			.param("projectId", projectId)
			.param("id", id)
			.query((row, rowNumber) -> new KnowledgeContent(title("Clarification: " + row.getString("question")),
					"Question: " + row.getString("question") + "\nAnswer: " + row.getString("answer")))
			.optional();
	}

	private Optional<KnowledgeContent> story(UUID orgId, UUID projectId, UUID id) {
		return jdbc.sql("""
				SELECT title, as_a, i_want, so_that, acceptance_criteria::text AS criteria FROM brain.story_drafts
				WHERE organization_id = :orgId AND project_id = :projectId AND id = :id
				  AND status IN ('APPROVED', 'CONVERTED')
				""")
			.param("orgId", orgId)
			.param("projectId", projectId)
			.param("id", id)
			.query((row, rowNumber) -> {
				StringBuilder text = new StringBuilder(row.getString("title"))
					.append("\nAs a ")
					.append(row.getString("as_a"))
					.append("\nI want ")
					.append(row.getString("i_want"))
					.append("\nSo that ")
					.append(row.getString("so_that"))
					.append("\nAcceptance criteria:");
				for (JsonNode criterion : json.readTree(row.getString("criteria"))) {
					text.append("\n- ").append(criterion.asString());
				}
				return new KnowledgeContent(title(row.getString("title")), text.toString());
			})
			.optional();
	}

	private Optional<KnowledgeContent> architecture(UUID orgId, UUID projectId) {
		return approvedVersion(orgId, projectId).map(versionId -> {
			ArchitectureVersion version = architecture.view(orgId, architecture.load(orgId, versionId, false),
					NO_VIEWER);
			Map<UUID, String> titles = new LinkedHashMap<>();
			version.stories().forEach(story -> titles.put(story.id(), story.title()));
			// Review history is left out, because rejection comments are not reviewed knowledge.
			return new KnowledgeContent("Architecture version " + version.versionNumber(),
					ArchitectureMarkdown.render(version, List.of(), titles));
		});
	}

	private Optional<UUID> approvedVersion(UUID orgId, UUID projectId) {
		return jdbc.sql("""
				SELECT id FROM brain.architecture_versions
				WHERE organization_id = :orgId AND project_id = :projectId AND status = 'APPROVED'
				""")
			.param("orgId", orgId)
			.param("projectId", projectId)
			.query(UUID.class)
			.optional();
	}

	private List<UUID> ids(String sql, UUID orgId, UUID projectId) {
		return jdbc.sql(sql).param("orgId", orgId).param("projectId", projectId).query(UUID.class).list();
	}

	private static String title(String value) {
		return value.length() <= MAX_TITLE_CHARS ? value : value.substring(0, MAX_TITLE_CHARS - 1) + "…";
	}

}
