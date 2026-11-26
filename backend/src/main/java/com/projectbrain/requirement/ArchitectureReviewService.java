package com.projectbrain.requirement;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.projectbrain.audit.api.AuditLog;
import com.projectbrain.requirement.api.KnowledgeChanged;
import com.projectbrain.requirement.api.KnowledgeSourceType;
import com.projectbrain.identity.api.CurrentUserProvider;
import com.projectbrain.organization.api.OrgTx;
import com.projectbrain.organization.api.Permission;
import com.projectbrain.requirement.ArchitectureService.ArchitectureVersion;
import com.projectbrain.requirement.ArchitectureService.MarkdownFile;
import com.projectbrain.requirement.ArchitectureService.VersionRow;
import com.projectbrain.shared.web.ForbiddenException;
import com.projectbrain.shared.web.PageParams;
import com.projectbrain.shared.web.PageResponse;
import com.projectbrain.shared.web.UnprocessableException;
import com.projectbrain.team.api.Approvers;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Human decisions on architecture versions. Nothing the AI returns can reach these methods. */
@Service
class ArchitectureReviewService {

	private static final int MAX_COMMENT_CHARS = 2000;

	private final OrgTx orgTx;

	private final JdbcClient jdbc;

	private final ArchitectureService architecture;

	private final CurrentUserProvider currentUser;

	private final AuditLog auditLog;

	private final ApplicationEventPublisher events;

	ArchitectureReviewService(OrgTx orgTx, JdbcClient jdbc, ArchitectureService architecture,
			CurrentUserProvider currentUser, AuditLog auditLog,
			ApplicationEventPublisher events) {
		this.orgTx = orgTx;
		this.jdbc = jdbc;
		this.architecture = architecture;
		this.currentUser = currentUser;
		this.auditLog = auditLog;
		this.events = events;
	}

	ArchitectureVersion approve(UUID orgId, UUID versionId, Long expected) {
		UUID actor = currentUser.get().id();
		return orgTx.inOrg(orgId, Permission.VIEW, () -> {
			VersionRow row = architecture.lockAndLoad(orgId, versionId);
			Approvers approvers = requireApprover(orgId, row, actor);
			architecture.requireChangeable(orgId, row, expected);
			jdbc.sql("""
					UPDATE brain.architecture_versions SET status = 'SUPERSEDED', updated_at = now(), version = version + 1
					WHERE organization_id = :orgId AND project_id = :projectId AND status = 'APPROVED'
					""")
				.param("orgId", orgId)
				.param("projectId", row.projectId())
				.update();
			boolean selfApproved = actor.equals(row.generatedBy());
			decide(orgId, row, "APPROVED", actor, approvers, selfApproved, null);
			auditLog.record(orgId, "ARCHITECTURE_APPROVED", "ARCHITECTURE_VERSION", versionId,
					Map.of("versionNumber", row.versionNumber(), "basis", approvers.basis().name(), "selfApproved",
							selfApproved));
			// The architecture source is keyed by project, so the new version replaces the old one in the index.
			events.publishEvent(new KnowledgeChanged(orgId, row.projectId(), KnowledgeSourceType.ARCHITECTURE,
					row.projectId(), actor));
			return architecture.view(orgId, architecture.load(orgId, versionId, false), actor);
		});
	}

	ArchitectureVersion reject(UUID orgId, UUID versionId, Long expected, String comment) {
		UUID actor = currentUser.get().id();
		return orgTx.inOrg(orgId, Permission.VIEW, () -> {
			VersionRow row = architecture.lockAndLoad(orgId, versionId);
			Approvers approvers = requireApprover(orgId, row, actor);
			architecture.requireChangeable(orgId, row, expected);
			String cleaned = comment == null ? "" : comment.strip();
			if (cleaned.isEmpty() || cleaned.length() > MAX_COMMENT_CHARS) {
				throw new UnprocessableException("Add a rejection comment of up to 2000 characters.");
			}
			decide(orgId, row, "REJECTED", actor, approvers, false, cleaned);
			auditLog.record(orgId, "ARCHITECTURE_REJECTED", "ARCHITECTURE_VERSION", versionId,
					Map.of("versionNumber", row.versionNumber(), "basis", approvers.basis().name()));
			return architecture.view(orgId, architecture.load(orgId, versionId, false), actor);
		});
	}

	PageResponse<ReviewView> history(UUID orgId, UUID versionId, PageParams paging) {
		return orgTx.inOrg(orgId, Permission.VIEW, () -> {
			architecture.load(orgId, versionId, false);
			long total = jdbc.sql("""
					SELECT count(*) FROM brain.architecture_reviews WHERE organization_id = :orgId AND version_id = :versionId
					""")
				.param("orgId", orgId)
				.param("versionId", versionId)
				.query(Long.class)
				.single();
			return new PageResponse<>(reviews(orgId, versionId, paging.size(), paging.offset()), paging.page(),
					paging.size(), total);
		});
	}

	MarkdownFile markdown(UUID orgId, UUID versionId) {
		return orgTx.inOrg(orgId, Permission.VIEW, () -> {
			architecture.load(orgId, versionId, false);
			return architecture.markdown(orgId, versionId, reviews(orgId, versionId, 100, 0));
		});
	}

	private List<ReviewView> reviews(UUID orgId, UUID versionId, int limit, long offset) {
		return jdbc.sql("""
				SELECT id, content_version, action, actor_id, approval_basis, self_approved, comment, created_at
				FROM brain.architecture_reviews
				WHERE organization_id = :orgId AND version_id = :versionId
				ORDER BY created_at, id
				LIMIT :limit OFFSET :offset
				""")
			.param("orgId", orgId)
			.param("versionId", versionId)
			.param("limit", limit)
			.param("offset", offset)
			.query((row, rowNumber) -> new ReviewView(row.getObject("id", UUID.class), row.getLong("content_version"),
					row.getString("action"), row.getObject("actor_id", UUID.class), row.getString("approval_basis"),
					row.getBoolean("self_approved"), row.getString("comment"),
					row.getObject("created_at", OffsetDateTime.class)))
			.list();
	}

	private Approvers requireApprover(UUID orgId, VersionRow row, UUID actor) {
		Approvers approvers = architecture.approvers(orgId, row);
		if (!approvers.userIds().contains(actor)) {
			throw new ForbiddenException("You are not an approver for this architecture.");
		}
		return approvers;
	}

	private void decide(UUID orgId, VersionRow row, String decision, UUID actor, Approvers approvers,
			boolean selfApproved, String comment) {
		jdbc.sql("""
				INSERT INTO brain.architecture_reviews
				    (id, organization_id, project_id, version_id, content_version, action, actor_id, job_role_id,
				     approval_basis, self_approved, comment)
				VALUES (:id, :orgId, :projectId, :versionId, :contentVersion, :action, :actor, :jobRoleId, :basis,
				        :selfApproved, :comment)
				""")
			.param("id", UUID.randomUUID())
			.param("orgId", orgId)
			.param("projectId", row.projectId())
			.param("versionId", row.id())
			.param("contentVersion", row.version())
			.param("action", decision)
			.param("actor", actor)
			.param("jobRoleId", row.approverJobRoleId(), Types.OTHER)
			.param("basis", approvers.basis().name())
			.param("selfApproved", selfApproved)
			.param("comment", comment, Types.VARCHAR)
			.update();
		jdbc.sql("""
				UPDATE brain.architecture_versions SET status = :status, updated_at = now(), version = version + 1
				WHERE organization_id = :orgId AND id = :id
				""")
			.param("status", decision)
			.param("orgId", orgId)
			.param("id", row.id())
			.update();
	}

	record ReviewView(UUID id, long contentVersion, String action, UUID actorId, String approvalBasis,
			boolean selfApproved, String comment, OffsetDateTime createdAt) {
	}

}
