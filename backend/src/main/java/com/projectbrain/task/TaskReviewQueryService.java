package com.projectbrain.task;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.projectbrain.identity.api.CurrentUserProvider;
import com.projectbrain.organization.api.ApprovalSettings;
import com.projectbrain.organization.api.OrgTx;
import com.projectbrain.organization.api.Permission;
import com.projectbrain.shared.web.NotFoundException;
import com.projectbrain.shared.web.PageParams;
import com.projectbrain.shared.web.PageResponse;
import com.projectbrain.task.TaskService.TaskView;
import com.projectbrain.team.api.ApprovalBasis;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
class TaskReviewQueryService {

	/** Applies the same eligibility rules as ApproverPolicy, so the queue can paginate in SQL. */
	private static final String MY_QUEUE = """
			WITH latest AS (
			    SELECT DISTINCT ON (task_id) task_id, actor_id AS submitter, created_at AS submitted_at
			    FROM brain.task_reviews
			    WHERE organization_id = :orgId AND action = 'SUBMITTED'
			    ORDER BY task_id, task_version DESC, created_at DESC, id DESC
			), queue AS (
			    SELECT t.*, p.name AS project_name, r.name AS job_role_name, l.submitter, l.submitted_at,
			        EXISTS (
			            SELECT 1 FROM brain.project_job_roles a
			            JOIN brain.organization_members m
			              ON m.organization_id = a.organization_id AND m.user_id = a.user_id
			            WHERE a.organization_id = t.organization_id AND a.project_id = t.project_id
			              AND a.job_role_id = t.responsible_job_role_id
			              AND m.role IN ('OWNER', 'ADMIN', 'MEMBER')
			              AND (:allowSelf OR a.user_id <> l.submitter)
			        ) AS has_eligible_holder,
			        EXISTS (
			            SELECT 1 FROM brain.project_job_roles a
			            JOIN brain.organization_members m
			              ON m.organization_id = a.organization_id AND m.user_id = a.user_id
			            WHERE a.organization_id = t.organization_id AND a.project_id = t.project_id
			              AND a.job_role_id = t.responsible_job_role_id
			              AND m.role IN ('OWNER', 'ADMIN', 'MEMBER')
			              AND a.user_id = :me
			        ) AS me_holder
			    FROM brain.tasks t
			    JOIN latest l ON l.task_id = t.id
			    JOIN brain.projects p ON p.organization_id = t.organization_id AND p.id = t.project_id
			    JOIN brain.job_roles r ON r.organization_id = t.organization_id AND r.id = t.responsible_job_role_id
			    WHERE t.organization_id = :orgId AND t.status = 'IN_REVIEW' AND p.status = 'ACTIVE'
			), mine AS (
			    SELECT q.*
			    FROM queue q
			    WHERE (:allowSelf OR q.submitter <> :me)
			      AND ((q.has_eligible_holder AND q.me_holder) OR (NOT q.has_eligible_holder AND :isOwner))
			)
			""";

	private final OrgTx orgTx;

	private final JdbcClient jdbc;

	private final ApprovalSettings approvalSettings;

	private final CurrentUserProvider currentUser;

	TaskReviewQueryService(OrgTx orgTx, JdbcClient jdbc, ApprovalSettings approvalSettings,
			CurrentUserProvider currentUser) {
		this.orgTx = orgTx;
		this.jdbc = jdbc;
		this.approvalSettings = approvalSettings;
		this.currentUser = currentUser;
	}

	PageResponse<ReviewQueueItem> myQueue(UUID orgId, PageParams paging) {
		UUID me = currentUser.get().id();
		return orgTx.inOrg(orgId, Permission.VIEW, () -> {
			boolean allowSelf = approvalSettings.allowsSelfApproval(orgId);
			boolean isOwner = jdbc.sql("""
					SELECT role = 'OWNER' FROM brain.organization_members
					WHERE organization_id = :orgId AND user_id = :me
					""")
				.param("orgId", orgId)
				.param("me", me)
				.query(Boolean.class)
				.single();
			long total = jdbc.sql(MY_QUEUE + "SELECT count(*) FROM mine")
				.param("orgId", orgId)
				.param("me", me)
				.param("allowSelf", allowSelf)
				.param("isOwner", isOwner)
				.query(Long.class)
				.single();
			List<ReviewQueueItem> items = jdbc.sql(MY_QUEUE + """
					SELECT mine.*, COALESCE(s.display_name, s.email) AS submitter_name
					FROM mine
					LEFT JOIN brain.profiles s ON s.id = mine.submitter
					ORDER BY mine.submitted_at, mine.id
					LIMIT :limit OFFSET :offset
					""")
				.param("orgId", orgId)
				.param("me", me)
				.param("allowSelf", allowSelf)
				.param("isOwner", isOwner)
				.param("limit", paging.size())
				.param("offset", paging.offset())
				.query((row, rowNumber) -> {
					TaskView task = new TaskView(row.getObject("id", UUID.class),
							row.getObject("organization_id", UUID.class), row.getObject("project_id", UUID.class),
							row.getString("title"), row.getString("description"),
							TaskStatus.valueOf(row.getString("status")), TaskPriority.valueOf(row.getString("priority")),
							row.getObject("assignee_id", UUID.class), row.getObject("due_date", LocalDate.class),
							row.getObject("responsible_job_role_id", UUID.class), row.getObject("created_by", UUID.class),
							row.getObject("created_at", OffsetDateTime.class),
							row.getObject("updated_at", OffsetDateTime.class), row.getLong("version"));
					UUID submitter = row.getObject("submitter", UUID.class);
					ApprovalBasis basis = row.getBoolean("has_eligible_holder") ? ApprovalBasis.ROLE_HOLDER
							: ApprovalBasis.OWNER_FALLBACK;
					return new ReviewQueueItem(task, row.getString("project_name"), row.getString("job_role_name"),
							submitter, row.getString("submitter_name"),
							row.getObject("submitted_at", OffsetDateTime.class), basis, me.equals(submitter));
				})
				.list();
			return new PageResponse<>(items, paging.page(), paging.size(), total);
		});
	}

	PageResponse<ReviewEntry> history(UUID orgId, UUID taskId, PageParams paging) {
		return orgTx.inOrg(orgId, Permission.VIEW, () -> {
			boolean exists = jdbc.sql("""
					SELECT EXISTS (SELECT 1 FROM brain.tasks WHERE organization_id = :orgId AND id = :taskId)
					""")
				.param("orgId", orgId)
				.param("taskId", taskId)
				.query(Boolean.class)
				.single();
			if (!exists) {
				throw new NotFoundException("Task not found.");
			}
			long total = jdbc.sql("""
					SELECT count(*) FROM brain.task_reviews WHERE organization_id = :orgId AND task_id = :taskId
					""")
				.param("orgId", orgId)
				.param("taskId", taskId)
				.query(Long.class)
				.single();
			List<ReviewEntry> items = jdbc.sql("""
					SELECT v.id, v.task_id, v.task_version, v.action, v.actor_id, v.job_role_id, v.approval_basis,
					       v.self_approved, v.comment, v.created_at, r.name AS job_role_name,
					       COALESCE(p.display_name, p.email) AS actor_name
					FROM brain.task_reviews v
					JOIN brain.job_roles r ON r.organization_id = v.organization_id AND r.id = v.job_role_id
					LEFT JOIN brain.profiles p ON p.id = v.actor_id
					WHERE v.organization_id = :orgId AND v.task_id = :taskId
					ORDER BY v.created_at DESC, v.id DESC
					LIMIT :limit OFFSET :offset
					""")
				.param("orgId", orgId)
				.param("taskId", taskId)
				.param("limit", paging.size())
				.param("offset", paging.offset())
				.query((row, rowNumber) -> {
					String basis = row.getString("approval_basis");
					return new ReviewEntry(row.getObject("id", UUID.class), row.getObject("task_id", UUID.class),
							row.getLong("task_version"), TaskReviewAction.valueOf(row.getString("action")),
							row.getObject("actor_id", UUID.class), row.getString("actor_name"),
							row.getObject("job_role_id", UUID.class), row.getString("job_role_name"),
							basis == null ? null : ApprovalBasis.valueOf(basis), row.getBoolean("self_approved"),
							row.getString("comment"), row.getObject("created_at", OffsetDateTime.class));
				})
				.list();
			return new PageResponse<>(items, paging.page(), paging.size(), total);
		});
	}

	record ReviewQueueItem(TaskView task, String projectName, String jobRoleName, UUID submittedBy,
			String submittedByName, OffsetDateTime submittedAt, ApprovalBasis approvalBasis, boolean selfApproval) {
	}

	record ReviewEntry(UUID id, UUID taskId, long taskVersion, TaskReviewAction action, UUID actorId, String actorName,
			UUID jobRoleId, String jobRoleName, ApprovalBasis approvalBasis, boolean selfApproved, String comment,
			OffsetDateTime createdAt) {
	}

}
