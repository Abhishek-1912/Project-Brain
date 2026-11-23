package com.projectbrain.task;

import java.sql.Types;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.projectbrain.audit.api.AuditLog;
import com.projectbrain.identity.api.CurrentUserProvider;
import com.projectbrain.organization.api.ApprovalSettings;
import com.projectbrain.organization.api.OrgTx;
import com.projectbrain.organization.api.Permission;
import com.projectbrain.project.api.ProjectLookup;
import com.projectbrain.project.api.ProjectStatus;
import com.projectbrain.shared.web.ConflictException;
import com.projectbrain.shared.web.ForbiddenException;
import com.projectbrain.shared.web.NotFoundException;
import com.projectbrain.shared.web.UnprocessableException;
import com.projectbrain.task.TaskService.TaskView;
import com.projectbrain.team.api.ApprovalBasis;
import com.projectbrain.team.api.Approvers;
import com.projectbrain.team.api.ProjectTeamLookup;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
class TaskReviewService {

	private static final Set<TaskStatus> SUBMITTABLE = Set.of(TaskStatus.BACKLOG, TaskStatus.TODO,
			TaskStatus.IN_PROGRESS);

	private final TaskRepository tasks;

	private final OrgTx orgTx;

	private final JdbcClient jdbc;

	private final ProjectLookup projects;

	private final ProjectTeamLookup team;

	private final ApprovalSettings approvalSettings;

	private final CurrentUserProvider currentUser;

	private final AuditLog auditLog;

	TaskReviewService(TaskRepository tasks, OrgTx orgTx, JdbcClient jdbc, ProjectLookup projects,
			ProjectTeamLookup team, ApprovalSettings approvalSettings, CurrentUserProvider currentUser,
			AuditLog auditLog) {
		this.tasks = tasks;
		this.orgTx = orgTx;
		this.jdbc = jdbc;
		this.projects = projects;
		this.team = team;
		this.approvalSettings = approvalSettings;
		this.currentUser = currentUser;
		this.auditLog = auditLog;
	}

	TaskView apply(UUID orgId, UUID taskId, TaskReviewAction action, long version, String requestedComment) {
		UUID actor = currentUser.get().id();
		String comment = clean(requestedComment);
		return orgTx.inOrg(orgId, Permission.EDIT_TASKS, () -> {
			Task task = tasks.findByOrganizationIdAndId(orgId, taskId)
				.orElseThrow(() -> new NotFoundException("Task not found."));
			if (task.getResponsibleJobRoleId() == null) {
				throw new ConflictException("Set a responsible job role before using review actions.");
			}
			if (projects.requireStatus(orgId, task.getProjectId()) == ProjectStatus.ARCHIVED) {
				throw new ConflictException("The project is archived.");
			}
			if (task.getVersion() != version) {
				throw new ConflictException("The task was changed by someone else. Reload and try again.");
			}
			requireStatusFor(action, task.getStatus());
			Decision decision = decide(orgId, task, action, actor);
			if (action == TaskReviewAction.REJECTED && comment == null) {
				throw new UnprocessableException("A rejection needs a comment.");
			}
			task.setStatus(nextStatus(action));
			// Hibernate checks the version here, so concurrent decisions cannot both succeed.
			tasks.saveAndFlush(task);
			long reviewedVersion = action == TaskReviewAction.SUBMITTED ? task.getVersion() : version;
			record(task, action, actor, reviewedVersion, decision, comment);
			audit(task, action, reviewedVersion, decision);
			return TaskView.from(task);
		});
	}

	private Decision decide(UUID orgId, Task task, TaskReviewAction action, UUID actor) {
		if (action != TaskReviewAction.APPROVED && action != TaskReviewAction.REJECTED) {
			return new Decision(null, false);
		}
		UUID submitter = latestSubmitter(orgId, task.getId());
		Approvers approvers = team.approvers(orgId, task.getProjectId(), task.getResponsibleJobRoleId(), submitter,
				approvalSettings.allowsSelfApproval(orgId));
		if (!approvers.userIds().contains(actor)) {
			throw new ForbiddenException("You are not an approver for this task.");
		}
		return new Decision(approvers.basis(), action == TaskReviewAction.APPROVED && actor.equals(submitter));
	}

	private UUID latestSubmitter(UUID orgId, UUID taskId) {
		return jdbc.sql("""
				SELECT actor_id FROM brain.task_reviews
				WHERE organization_id = :orgId AND task_id = :taskId AND action = 'SUBMITTED'
				ORDER BY task_version DESC, created_at DESC, id DESC
				LIMIT 1
				""")
			.param("orgId", orgId)
			.param("taskId", taskId)
			.query(UUID.class)
			.optional()
			.orElse(null);
	}

	private void record(Task task, TaskReviewAction action, UUID actor, long reviewedVersion, Decision decision,
			String comment) {
		jdbc.sql("""
				INSERT INTO brain.task_reviews
				    (id, organization_id, project_id, task_id, task_version, action, actor_id, job_role_id,
				     approval_basis, self_approved, comment)
				VALUES (:id, :orgId, :projectId, :taskId, :taskVersion, :action, :actor, :jobRoleId,
				        :basis, :selfApproved, :comment)
				""")
			.param("id", UUID.randomUUID())
			.param("orgId", task.getOrganizationId())
			.param("projectId", task.getProjectId())
			.param("taskId", task.getId())
			.param("taskVersion", reviewedVersion)
			.param("action", action.name())
			.param("actor", actor)
			.param("jobRoleId", task.getResponsibleJobRoleId())
			.param("basis", decision.basis() == null ? null : decision.basis().name(), Types.VARCHAR)
			.param("selfApproved", decision.selfApproved())
			.param("comment", comment, Types.VARCHAR)
			.update();
	}

	private void audit(Task task, TaskReviewAction action, long reviewedVersion, Decision decision) {
		Map<String, Object> metadata = new HashMap<>();
		metadata.put("title", task.getTitle());
		metadata.put("projectId", task.getProjectId().toString());
		metadata.put("taskVersion", reviewedVersion);
		if (decision.basis() != null) {
			metadata.put("approvalBasis", decision.basis().name());
			metadata.put("selfApproved", decision.selfApproved());
		}
		auditLog.record(task.getOrganizationId(), "TASK_" + action.name(), "TASK", task.getId(), metadata);
	}

	private static void requireStatusFor(TaskReviewAction action, TaskStatus status) {
		boolean allowed = switch (action) {
			case SUBMITTED -> SUBMITTABLE.contains(status);
			case APPROVED, REJECTED, WITHDRAWN -> status == TaskStatus.IN_REVIEW;
			case REOPENED -> status == TaskStatus.DONE;
		};
		if (!allowed) {
			throw new ConflictException("This review action is not available for the task's current status.");
		}
	}

	private static TaskStatus nextStatus(TaskReviewAction action) {
		return switch (action) {
			case SUBMITTED -> TaskStatus.IN_REVIEW;
			case APPROVED -> TaskStatus.DONE;
			case REJECTED, WITHDRAWN, REOPENED -> TaskStatus.IN_PROGRESS;
		};
	}

	private static String clean(String comment) {
		if (comment == null || comment.isBlank()) {
			return null;
		}
		return comment.trim();
	}

	private record Decision(ApprovalBasis basis, boolean selfApproved) {
	}

}
