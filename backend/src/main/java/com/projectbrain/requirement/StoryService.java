package com.projectbrain.requirement;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.projectbrain.audit.api.AuditLog;
import com.projectbrain.identity.api.CurrentUserProvider;
import com.projectbrain.organization.api.OrgTx;
import com.projectbrain.organization.api.Permission;
import com.projectbrain.requirement.AiServiceClient.StoriesRequest;
import com.projectbrain.requirement.AnalysisService.AnalysisInput;
import com.projectbrain.requirement.AnalysisService.StoryBasis;
import com.projectbrain.shared.web.ConflictException;
import com.projectbrain.shared.web.NotFoundException;
import com.projectbrain.shared.web.PageParams;
import com.projectbrain.shared.web.PageResponse;
import com.projectbrain.shared.web.UnprocessableException;
import com.projectbrain.team.api.JobRoleLookup;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

@Service
class StoryService {

	private static final Set<String> PRIORITIES = Set.of("LOW", "MEDIUM", "HIGH", "URGENT");

	private static final int MAX_CRITERIA = 20;

	private static final int MAX_CRITERION_CHARS = 1000;

	private static final int MAX_ROLES = 100;

	private static final int MAX_COMMENTS = 100;

	private static final int MAX_KEPT_TITLES = 200;

	private static final String COLUMNS = """
			id, document_id, analysis_id, title, as_a, i_want, so_that, acceptance_criteria::text AS acceptance_criteria,
			priority, job_role_id, source_quotes::text AS source_quotes, status, task_id, created_at, updated_at, version
			""";

	private final OrgTx orgTx;

	private final JdbcClient jdbc;

	private final AnalysisService analyses;

	private final RequirementAccess access;

	private final JobRoleLookup jobRoles;

	private final CurrentUserProvider currentUser;

	private final AuditLog auditLog;

	private final JsonMapper json;

	private final RowMapper<StoryView> mapper;

	StoryService(OrgTx orgTx, JdbcClient jdbc, AnalysisService analyses, RequirementAccess access, JobRoleLookup jobRoles,
			CurrentUserProvider currentUser, AuditLog auditLog, JsonMapper json) {
		this.orgTx = orgTx;
		this.jdbc = jdbc;
		this.analyses = analyses;
		this.access = access;
		this.jobRoles = jobRoles;
		this.currentUser = currentUser;
		this.auditLog = auditLog;
		this.json = json;
		this.mapper = (row, rowNumber) -> new StoryView(row.getObject("id", UUID.class),
				row.getObject("document_id", UUID.class), row.getObject("analysis_id", UUID.class),
				row.getString("title"), row.getString("as_a"), row.getString("i_want"), row.getString("so_that"),
				json.readTree(row.getString("acceptance_criteria")), row.getString("priority"),
				row.getObject("job_role_id", UUID.class), json.readTree(row.getString("source_quotes")),
				row.getString("status"), row.getObject("task_id", UUID.class),
				row.getObject("created_at", OffsetDateTime.class), row.getObject("updated_at", OffsetDateTime.class),
				row.getLong("version"));
	}

	PageResponse<StoryView> list(UUID orgId, UUID documentId, PageParams paging) {
		return orgTx.inOrg(orgId, Permission.VIEW, () -> {
			analyses.requireDocument(orgId, documentId);
			long total = jdbc.sql("""
					SELECT count(*) FROM brain.story_drafts
					WHERE organization_id = :orgId AND document_id = :documentId
					""")
				.param("orgId", orgId)
				.param("documentId", documentId)
				.query(Long.class)
				.single();
			List<StoryView> items = jdbc.sql("SELECT " + COLUMNS + """
					FROM brain.story_drafts
					WHERE organization_id = :orgId AND document_id = :documentId
					ORDER BY created_at, id
					LIMIT :limit OFFSET :offset
					""")
				.param("orgId", orgId)
				.param("documentId", documentId)
				.param("limit", paging.size())
				.param("offset", paging.offset())
				.query(mapper)
				.list();
			return new PageResponse<>(items, paging.page(), paging.size(), total);
		});
	}

	StoryView update(UUID orgId, UUID storyId, StoryUpdate update) {
		UUID actor = currentUser.get().id();
		return orgTx.inOrg(orgId, Permission.VIEW, () -> {
			Target target = lockTarget(orgId, storyId);
			access.requireEditor(orgId, target.projectId(), target.approverRole(), actor);
			access.requireWritable(orgId, target.projectId());
			if ("CONVERTED".equals(target.status())) {
				throw new ConflictException("This story is already a task and can't be edited.");
			}
			if (update.version() == null || update.version() != target.version()) {
				throw new ConflictException("This story was changed by someone else. Reload and try again.");
			}
			validate(update);
			if (update.jobRoleId() != null) {
				jobRoles.requireActive(orgId, update.jobRoleId());
			}
			long nextVersion = target.version() + 1;
			jdbc.sql("""
					UPDATE brain.story_drafts
					SET title = :title, as_a = :asA, i_want = :iWant, so_that = :soThat,
					    acceptance_criteria = CAST(:criteria AS jsonb), priority = :priority, job_role_id = :jobRoleId,
					    status = 'DRAFT', updated_at = now(), version = :nextVersion
					WHERE organization_id = :orgId AND id = :id
					""")
				.param("title", update.title().strip())
				.param("asA", update.asA().strip())
				.param("iWant", update.iWant().strip())
				.param("soThat", update.soThat().strip())
				.param("criteria", json.writeValueAsString(update.acceptanceCriteria().stream().map(String::strip).toList()))
				.param("priority", update.priority())
				.param("jobRoleId", update.jobRoleId(), Types.OTHER)
				.param("nextVersion", nextVersion)
				.param("orgId", orgId)
				.param("id", storyId)
				.update();
			boolean reopened = !"DRAFT".equals(target.status());
			if (reopened) {
				jdbc.sql("""
						INSERT INTO brain.story_reviews
						    (id, organization_id, project_id, document_id, story_id, story_version, action, actor_id)
						VALUES (:id, :orgId, :projectId, :documentId, :storyId, :version, 'REOPENED', :actor)
						""")
					.param("id", UUID.randomUUID())
					.param("orgId", orgId)
					.param("projectId", target.projectId())
					.param("documentId", target.documentId())
					.param("storyId", storyId)
					.param("version", nextVersion)
					.param("actor", actor)
					.update();
			}
			auditLog.record(orgId, "STORY_EDITED", "STORY", storyId, Map.of("version", nextVersion, "reopened", reopened));
			return load(orgId, storyId);
		});
	}

	/** Must run inside an organization transaction. */
	StoryInput loadInput(UUID orgId, UUID documentId) {
		AnalysisInput input = analyses.loadInput(orgId, documentId);
		StoryBasis basis = analyses.storyBasis(orgId, documentId);
		List<String> roles = jobRoles.activeNames(orgId).stream().limit(MAX_ROLES).toList();
		List<String> comments = jdbc.sql("""
				SELECT r.comment
				FROM brain.story_reviews r
				JOIN brain.story_drafts s ON s.organization_id = r.organization_id AND s.id = r.story_id
				WHERE r.organization_id = :orgId AND r.document_id = :documentId
				  AND r.action = 'REJECTED' AND s.status = 'REJECTED'
				ORDER BY r.created_at DESC, r.id
				LIMIT :limit
				""")
			.param("orgId", orgId)
			.param("documentId", documentId)
			.param("limit", MAX_COMMENTS)
			.query(String.class)
			.list();
		List<String> kept = keptTitles(orgId, documentId).stream().limit(MAX_KEPT_TITLES).toList();
		return new StoryInput(basis.analysisId(),
				new StoriesRequest(input.text(), basis.analysis(), input.answers(), roles, comments, kept));
	}

	/** Must run inside an organization transaction, so a bad result leaves the current stories untouched. */
	void save(UUID orgId, UUID projectId, UUID documentId, UUID analysisId, UUID actor, JsonNode generated) {
		List<JsonNode> stories = validateGenerated(generated);
		String documentText = jdbc.sql("""
				SELECT extracted_text FROM brain.requirement_documents WHERE organization_id = :orgId AND id = :id
				""")
			.param("orgId", orgId)
			.param("id", documentId)
			.query(String.class)
			.single();
		jdbc.sql("""
				DELETE FROM brain.story_drafts
				WHERE organization_id = :orgId AND document_id = :documentId AND status = 'DRAFT'
				""")
			.param("orgId", orgId)
			.param("documentId", documentId)
			.update();
		Set<String> seen = new HashSet<>();
		keptTitles(orgId, documentId).forEach(title -> seen.add(titleKey(title)));
		for (JsonNode story : stories) {
			if (seen.add(titleKey(story.get("title").asString()))) {
				insert(orgId, projectId, documentId, analysisId, actor, story, documentText);
			}
		}
	}

	private void insert(UUID orgId, UUID projectId, UUID documentId, UUID analysisId, UUID actor, JsonNode story,
			String documentText) {
		JsonNode role = story.get("jobRole");
		UUID jobRoleId = role != null && role.isString() ? jobRoles.findActiveByName(orgId, role.asString().strip()).orElse(null)
				: null;
		List<String> criteria = new ArrayList<>();
		story.get("acceptanceCriteria").forEach(item -> criteria.add(item.asString().strip()));
		ArrayNode quotes = json.createArrayNode();
		JsonNode sourceQuotes = story.get("sourceQuotes");
		if (sourceQuotes != null) {
			for (JsonNode quote : sourceQuotes) {
				if (!quote.asString().isBlank()) {
					quotes.addObject()
						.put("quote", quote.asString().strip())
						.put("verified", QuoteVerifier.isVerified(quote.asString(), documentText));
				}
			}
		}
		// clock_timestamp keeps the generated order, because now() is the same for the whole transaction.
		jdbc.sql("""
				INSERT INTO brain.story_drafts
				    (id, organization_id, project_id, document_id, analysis_id, title, as_a, i_want, so_that,
				     acceptance_criteria, priority, job_role_id, source_quotes, generated_by, created_at)
				VALUES (:id, :orgId, :projectId, :documentId, :analysisId, :title, :asA, :iWant, :soThat,
				        CAST(:criteria AS jsonb), :priority, :jobRoleId, CAST(:quotes AS jsonb), :actor, clock_timestamp())
				""")
			.param("id", UUID.randomUUID())
			.param("orgId", orgId)
			.param("projectId", projectId)
			.param("documentId", documentId)
			.param("analysisId", analysisId)
			.param("title", story.get("title").asString().strip())
			.param("asA", story.get("asA").asString().strip())
			.param("iWant", story.get("iWant").asString().strip())
			.param("soThat", story.get("soThat").asString().strip())
			.param("criteria", json.writeValueAsString(criteria))
			.param("priority", story.get("priority").asString())
			.param("jobRoleId", jobRoleId, Types.OTHER)
			.param("quotes", json.writeValueAsString(quotes))
			.param("actor", actor)
			.update();
	}

	private List<String> keptTitles(UUID orgId, UUID documentId) {
		return jdbc.sql("""
				SELECT title FROM brain.story_drafts
				WHERE organization_id = :orgId AND document_id = :documentId AND status IN ('APPROVED', 'CONVERTED')
				ORDER BY created_at, id
				""")
			.param("orgId", orgId)
			.param("documentId", documentId)
			.query(String.class)
			.list();
	}

	private Target lockTarget(UUID orgId, UUID storyId) {
		return jdbc.sql("""
				SELECT s.status, s.version, s.project_id, s.document_id, d.approver_job_role_id
				FROM brain.story_drafts s
				JOIN brain.requirement_documents d ON d.organization_id = s.organization_id AND d.id = s.document_id
				WHERE s.organization_id = :orgId AND s.id = :id
				FOR UPDATE OF s
				""")
			.param("orgId", orgId)
			.param("id", storyId)
			.query((row, rowNumber) -> new Target(row.getString("status"), row.getLong("version"),
					row.getObject("project_id", UUID.class), row.getObject("document_id", UUID.class),
					row.getObject("approver_job_role_id", UUID.class)))
			.optional()
			.orElseThrow(() -> new NotFoundException("Story not found."));
	}

	/** Must run inside an organization transaction. */
	StoryView load(UUID orgId, UUID storyId) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM brain.story_drafts WHERE organization_id = :orgId AND id = :id")
			.param("orgId", orgId)
			.param("id", storyId)
			.query(mapper)
			.single();
	}

	private static void validate(StoryUpdate update) {
		boolean valid = fits(update.title(), 200) && fits(update.asA(), 300) && fits(update.iWant(), 1000)
				&& fits(update.soThat(), 1000) && update.priority() != null && PRIORITIES.contains(update.priority())
				&& update.acceptanceCriteria() != null && !update.acceptanceCriteria().isEmpty()
				&& update.acceptanceCriteria().size() <= MAX_CRITERIA
				&& update.acceptanceCriteria().stream().allMatch(item -> fits(item, MAX_CRITERION_CHARS));
		if (!valid) {
			throw new UnprocessableException(
					"Fill in every story field, keep them within their limits, and add 1 to 20 acceptance criteria.");
		}
	}

	private static List<JsonNode> validateGenerated(JsonNode generated) {
		JsonNode stories = generated == null ? null : generated.get("stories");
		if (stories == null || !stories.isArray()) {
			throw new InvalidAiOutputException();
		}
		List<JsonNode> result = new ArrayList<>();
		for (JsonNode story : stories) {
			if (!story.isObject() || !fitsNode(story.get("title"), 200) || !fitsNode(story.get("asA"), 300)
					|| !fitsNode(story.get("iWant"), 1000) || !fitsNode(story.get("soThat"), 1000)
					|| !story.path("priority").isString() || !PRIORITIES.contains(story.path("priority").asString())
					|| !validStrings(story.get("acceptanceCriteria"), 1, MAX_CRITERIA)
					|| (story.has("sourceQuotes") && !story.get("sourceQuotes").isNull()
							&& !allStrings(story.get("sourceQuotes")))) {
				throw new InvalidAiOutputException();
			}
			result.add(story);
		}
		return result;
	}

	private static boolean validStrings(JsonNode node, int min, int max) {
		if (node == null || !node.isArray() || node.size() < min || node.size() > max) {
			return false;
		}
		for (JsonNode item : node) {
			if (!fitsNode(item, MAX_CRITERION_CHARS)) {
				return false;
			}
		}
		return true;
	}

	private static boolean allStrings(JsonNode node) {
		if (!node.isArray()) {
			return false;
		}
		for (JsonNode item : node) {
			if (!item.isString()) {
				return false;
			}
		}
		return true;
	}

	private static boolean fitsNode(JsonNode node, int max) {
		return node != null && node.isString() && fits(node.asString(), max);
	}

	private static boolean fits(String value, int max) {
		if (value == null) {
			return false;
		}
		String stripped = value.strip();
		return !stripped.isEmpty() && stripped.length() <= max;
	}

	private static String titleKey(String title) {
		return title.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
	}

	record StoryInput(UUID analysisId, StoriesRequest request) {
	}

	record StoryUpdate(String title, String asA, String iWant, String soThat, List<String> acceptanceCriteria,
			String priority, UUID jobRoleId, Long version) {
	}

	record StoryView(UUID id, UUID documentId, UUID analysisId, String title, String asA, String iWant, String soThat,
			JsonNode acceptanceCriteria, String priority, UUID jobRoleId, JsonNode sourceQuotes, String status,
			UUID taskId, OffsetDateTime createdAt, OffsetDateTime updatedAt, long version) {
	}

	private record Target(String status, long version, UUID projectId, UUID documentId, UUID approverRole) {
	}

}
