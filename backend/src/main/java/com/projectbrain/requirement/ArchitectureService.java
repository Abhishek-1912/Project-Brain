package com.projectbrain.requirement;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.projectbrain.audit.api.AuditLog;
import com.projectbrain.identity.api.CurrentUserProvider;
import com.projectbrain.organization.api.ApprovalSettings;
import com.projectbrain.organization.api.OrgTx;
import com.projectbrain.organization.api.Permission;
import com.projectbrain.project.api.ProjectLookup;
import com.projectbrain.project.api.ProjectStatus;
import com.projectbrain.requirement.AiRunStore.RunView;
import com.projectbrain.shared.web.ConflictException;
import com.projectbrain.shared.web.ForbiddenException;
import com.projectbrain.shared.web.NotFoundException;
import com.projectbrain.shared.web.PageParams;
import com.projectbrain.shared.web.PageResponse;
import com.projectbrain.shared.web.UnprocessableException;
import com.projectbrain.team.api.Approvers;
import com.projectbrain.team.api.ProjectTeamLookup;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
class ArchitectureService {

	private static final Map<String, String> SECTION_COLUMNS = Map.of("overview", "overview", "data-model", "data_model",
			"api-design", "api_design");

	private static final String COLUMNS = """
			id, project_id, version_number, status, based_on_version_id, approver_job_role_id, overview::text AS overview,
			data_model::text AS data_model, api_design::text AS api_design, story_ids::text AS story_ids, generated_by,
			created_at, updated_at, version
			""";

	private static final RowMapper<VersionSummary> SUMMARY = (row, rowNumber) -> new VersionSummary(
			row.getObject("id", UUID.class), row.getInt("version_number"), row.getString("status"),
			row.getObject("created_at", OffsetDateTime.class), row.getObject("generated_by", UUID.class));

	private final OrgTx orgTx;

	private final JdbcClient jdbc;

	private final JsonMapper json;

	private final AiRunStore runs;

	private final ArchitectureVersionStore versions;

	private final ArchitectureValidator validator;

	private final RequirementAccess access;

	private final ProjectTeamLookup team;

	private final ApprovalSettings approvalSettings;

	private final ProjectLookup projects;

	private final CurrentUserProvider currentUser;

	private final AuditLog auditLog;

	private final RowMapper<VersionRow> mapper;

	ArchitectureService(OrgTx orgTx, JdbcClient jdbc, JsonMapper json, AiRunStore runs, ArchitectureVersionStore versions,
			ArchitectureValidator validator, RequirementAccess access, ProjectTeamLookup team,
			ApprovalSettings approvalSettings, ProjectLookup projects, CurrentUserProvider currentUser,
			AuditLog auditLog) {
		this.orgTx = orgTx;
		this.jdbc = jdbc;
		this.json = json;
		this.runs = runs;
		this.versions = versions;
		this.validator = validator;
		this.access = access;
		this.team = team;
		this.approvalSettings = approvalSettings;
		this.projects = projects;
		this.currentUser = currentUser;
		this.auditLog = auditLog;
		this.mapper = (row, rowNumber) -> {
			List<UUID> storyIds = new ArrayList<>();
			json.readTree(row.getString("story_ids")).forEach(id -> storyIds.add(UUID.fromString(id.asString())));
			return new VersionRow(row.getObject("id", UUID.class), row.getObject("project_id", UUID.class),
					row.getInt("version_number"), row.getString("status"), row.getObject("based_on_version_id", UUID.class),
					row.getObject("approver_job_role_id", UUID.class), json.readTree(row.getString("overview")),
					json.readTree(row.getString("data_model")), json.readTree(row.getString("api_design")), storyIds,
					row.getObject("generated_by", UUID.class), row.getObject("created_at", OffsetDateTime.class),
					row.getObject("updated_at", OffsetDateTime.class), row.getLong("version"));
		};
	}

	ArchitectureState state(UUID orgId, UUID projectId) {
		return orgTx.inOrg(orgId, Permission.VIEW, () -> {
			projects.requireStatus(orgId, projectId);
			return new ArchitectureState(summary(orgId, projectId, "APPROVED").orElse(null),
					summary(orgId, projectId, "DRAFT").orElse(null), runs.latestArchitecture(orgId, projectId).orElse(null));
		});
	}

	PageResponse<VersionSummary> list(UUID orgId, UUID projectId, PageParams paging) {
		return orgTx.inOrg(orgId, Permission.VIEW, () -> {
			projects.requireStatus(orgId, projectId);
			long total = jdbc.sql("""
					SELECT count(*) FROM brain.architecture_versions WHERE organization_id = :orgId AND project_id = :projectId
					""")
				.param("orgId", orgId)
				.param("projectId", projectId)
				.query(Long.class)
				.single();
			List<VersionSummary> items = jdbc.sql("""
					SELECT id, version_number, status, created_at, generated_by FROM brain.architecture_versions
					WHERE organization_id = :orgId AND project_id = :projectId
					ORDER BY version_number DESC
					LIMIT :limit OFFSET :offset
					""")
				.param("orgId", orgId)
				.param("projectId", projectId)
				.param("limit", paging.size())
				.param("offset", paging.offset())
				.query(SUMMARY)
				.list();
			return new PageResponse<>(items, paging.page(), paging.size(), total);
		});
	}

	ArchitectureVersion get(UUID orgId, UUID versionId) {
		UUID actor = currentUser.get().id();
		return orgTx.inOrg(orgId, Permission.VIEW, () -> view(orgId, load(orgId, versionId, false), actor));
	}

	ArchitectureVersion updateSection(UUID orgId, UUID versionId, String section, JsonNode content, Long expected) {
		UUID actor = currentUser.get().id();
		return orgTx.inOrg(orgId, Permission.VIEW, () -> {
			VersionRow row = lockAndLoad(orgId, versionId);
			access.requireEditor(orgId, row.projectId(), row.approverJobRoleId(), actor);
			requireChangeable(orgId, row, expected);
			String column = SECTION_COLUMNS.get(section);
			if (column == null) {
				throw new UnprocessableException("Section must be overview, data-model, or api-design.");
			}
			JsonNode validated;
			try {
				validated = switch (section) {
					case "overview" -> validator.overview(content);
					case "data-model" -> validator.editedDataModel(content, new HashSet<>(row.storyIds()));
					default -> validator.editedApiDesign(content, new HashSet<>(row.storyIds()));
				};
			}
			catch (InvalidAiOutputException ex) {
				throw new UnprocessableException(
						"The edited content isn't valid. Check names, types, duplicates, and relations.");
			}
			// The column name comes from a fixed allow-list, never from the request.
			jdbc.sql("UPDATE brain.architecture_versions SET " + column + """
					 = CAST(:content AS jsonb), version = version + 1, updated_at = now()
					WHERE organization_id = :orgId AND id = :id
					""")
				.param("content", json.writeValueAsString(validated))
				.param("orgId", orgId)
				.param("id", versionId)
				.update();
			auditLog.record(orgId, "ARCHITECTURE_EDITED", "ARCHITECTURE_VERSION", versionId,
					Map.of("section", section, "version", row.version() + 1));
			return view(orgId, load(orgId, versionId, false), actor);
		});
	}

	/** Must run inside an organization transaction. */
	MarkdownFile markdown(UUID orgId, UUID versionId, List<ArchitectureReviewService.ReviewView> reviews) {
		UUID actor = currentUser.get().id();
		ArchitectureVersion version = view(orgId, load(orgId, versionId, false), actor);
		Map<UUID, String> titles = new LinkedHashMap<>();
		version.stories().forEach(story -> titles.put(story.id(), story.title()));
		return new MarkdownFile("architecture-v" + version.versionNumber() + ".md",
				ArchitectureMarkdown.render(version, reviews, titles));
	}

	/** Must run inside an organization transaction. Takes the project lock, then reads the version for update. */
	VersionRow lockAndLoad(UUID orgId, UUID versionId) {
		VersionRow found = load(orgId, versionId, false);
		versions.lockProject(found.projectId());
		return load(orgId, versionId, true);
	}

	/** Must run inside an organization transaction. */
	void requireChangeable(UUID orgId, VersionRow row, Long expected) {
		access.requireWritable(orgId, row.projectId());
		if (!"DRAFT".equals(row.status())) {
			throw new ConflictException("Only a draft version can be changed.");
		}
		if (runs.hasActiveArchitectureRun(orgId, row.projectId())) {
			throw new ConflictException("A newer architecture version is being generated. Wait for it to finish.");
		}
		if (expected == null || expected != row.version()) {
			throw new ConflictException("This version was changed by someone else. Reload and try again.");
		}
	}

	/** Must run inside an organization transaction. */
	Approvers approvers(UUID orgId, VersionRow row) {
		return team.approvers(orgId, row.projectId(), row.approverJobRoleId(), row.generatedBy(),
				approvalSettings.allowsSelfApproval(orgId));
	}

	/** Must run inside an organization transaction. */
	VersionRow load(UUID orgId, UUID versionId, boolean forUpdate) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM brain.architecture_versions WHERE organization_id = :orgId AND id = :id"
				+ (forUpdate ? " FOR UPDATE" : ""))
			.param("orgId", orgId)
			.param("id", versionId)
			.query(mapper)
			.optional()
			.orElseThrow(() -> new NotFoundException("Architecture version not found."));
	}

	/** Must run inside an organization transaction. */
	ArchitectureVersion view(UUID orgId, VersionRow row, UUID actor) {
		boolean open = "DRAFT".equals(row.status())
				&& projects.requireStatus(orgId, row.projectId()) != ProjectStatus.ARCHIVED
				&& !runs.hasActiveArchitectureRun(orgId, row.projectId());
		boolean canEdit = open && isEditor(orgId, row, actor);
		boolean canReview = open && approvers(orgId, row).userIds().contains(actor);
		return new ArchitectureVersion(row.id(), row.projectId(), row.versionNumber(), row.status(),
				row.basedOnVersionId(), row.approverJobRoleId(), row.overview(), row.dataModel(), row.apiDesign(),
				row.storyIds(), stories(orgId, row.storyIds()), row.generatedBy(), row.createdAt(), row.updatedAt(),
				row.version(), canEdit, canReview);
	}

	private boolean isEditor(UUID orgId, VersionRow row, UUID actor) {
		try {
			access.requireEditor(orgId, row.projectId(), row.approverJobRoleId(), actor);
			return true;
		}
		catch (ForbiddenException ex) {
			return false;
		}
	}

	private List<StoryLink> stories(UUID orgId, List<UUID> ids) {
		if (ids.isEmpty()) {
			return List.of();
		}
		return jdbc.sql("""
				SELECT id, title, status FROM brain.story_drafts
				WHERE organization_id = :orgId AND id IN (:ids)
				ORDER BY created_at, id
				""")
			.param("orgId", orgId)
			.param("ids", ids)
			.query((row, rowNumber) -> new StoryLink(row.getObject("id", UUID.class), row.getString("title"),
					row.getString("status")))
			.list();
	}

	private Optional<VersionSummary> summary(UUID orgId, UUID projectId, String status) {
		return jdbc.sql("""
				SELECT id, version_number, status, created_at, generated_by FROM brain.architecture_versions
				WHERE organization_id = :orgId AND project_id = :projectId AND status = :status
				""")
			.param("orgId", orgId)
			.param("projectId", projectId)
			.param("status", status)
			.query(SUMMARY)
			.optional();
	}

	record VersionRow(UUID id, UUID projectId, int versionNumber, String status, UUID basedOnVersionId,
			UUID approverJobRoleId, JsonNode overview, JsonNode dataModel, JsonNode apiDesign, List<UUID> storyIds,
			UUID generatedBy, OffsetDateTime createdAt, OffsetDateTime updatedAt, long version) {
	}

	record StoryLink(UUID id, String title, String status) {
	}

	record ArchitectureVersion(UUID id, UUID projectId, int versionNumber, String status, UUID basedOnVersionId,
			UUID approverJobRoleId, JsonNode overview, JsonNode dataModel, JsonNode apiDesign, List<UUID> storyIds,
			List<StoryLink> stories, UUID generatedBy, OffsetDateTime createdAt, OffsetDateTime updatedAt, long version,
			boolean canEdit, boolean canReview) {
	}

	record VersionSummary(UUID id, int versionNumber, String status, OffsetDateTime createdAt, UUID generatedBy) {
	}

	record ArchitectureState(VersionSummary current, VersionSummary draft, RunView latestRun) {
	}

	record SectionUpdate(JsonNode content, Long version) {
	}

	record MarkdownFile(String filename, String text) {
	}

}
