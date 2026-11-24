package com.projectbrain.task;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.projectbrain.audit.api.AuditLog;
import com.projectbrain.identity.api.CurrentUserProvider;
import com.projectbrain.organization.api.OrgTx;
import com.projectbrain.organization.api.Permission;
import com.projectbrain.project.api.ProjectLookup;
import com.projectbrain.project.api.ProjectStatus;
import com.projectbrain.shared.web.ConflictException;
import com.projectbrain.shared.web.NotFoundException;
import com.projectbrain.shared.web.PageParams;
import com.projectbrain.shared.web.PageResponse;
import com.projectbrain.shared.web.UnprocessableException;
import com.projectbrain.task.api.TaskCreator;
import com.projectbrain.team.api.JobRoleLookup;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class TaskService implements TaskCreator {

	private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id"));

	private final TaskRepository tasks;

	private final OrgTx orgTx;

	private final ProjectLookup projects;

	private final CurrentUserProvider currentUser;

	private final AuditLog auditLog;

	private final JobRoleLookup jobRoles;

	TaskService(TaskRepository tasks, OrgTx orgTx, ProjectLookup projects, CurrentUserProvider currentUser,
			AuditLog auditLog, JobRoleLookup jobRoles) {
		this.jobRoles = jobRoles;
		this.tasks = tasks;
		this.orgTx = orgTx;
		this.projects = projects;
		this.currentUser = currentUser;
		this.auditLog = auditLog;
	}

	PageResponse<TaskView> list(UUID orgId, UUID projectId, TaskStatus status, UUID assigneeId, PageParams paging) {
		return orgTx.inOrg(orgId, Permission.VIEW, () -> {
			projects.requireStatus(orgId, projectId);
			Specification<Task> filter = (root, query, builder) -> {
				List<Predicate> predicates = new ArrayList<>();
				predicates.add(builder.equal(root.get("organizationId"), orgId));
				predicates.add(builder.equal(root.get("projectId"), projectId));
				if (status != null) {
					predicates.add(builder.equal(root.get("status"), status));
				}
				if (assigneeId != null) {
					predicates.add(builder.equal(root.get("assigneeId"), assigneeId));
				}
				return builder.and(predicates.toArray(Predicate[]::new));
			};
			long total = tasks.count(filter);
			// Checked first because the query layer cannot express an offset beyond the int range.
			List<TaskView> items = paging.offset() >= total ? List.of()
					: tasks.findAll(filter, PageRequest.of(paging.page(), paging.size(), NEWEST_FIRST))
						.map(TaskView::from)
						.getContent();
			return new PageResponse<>(items, paging.page(), paging.size(), total);
		});
	}

	TaskView create(UUID orgId, UUID projectId, NewTask request) {
		UUID userId = currentUser.get().id();
		if (isReviewControlled(request.status())) {
			throw new UnprocessableException("A new task cannot start in review or done.");
		}
		return orgTx.inOrg(orgId, Permission.EDIT_TASKS, () -> {
			requireWritableProject(orgId, projectId);
			if (request.assigneeId() != null) {
				requireMember(orgId, request.assigneeId());
			}
			jobRoles.requireActive(orgId, request.responsibleJobRoleId());
			Task task = new Task(UUID.randomUUID(), orgId, projectId, request.title(), request.description(),
					request.status() == null ? TaskStatus.BACKLOG : request.status(),
					request.priority() == null ? TaskPriority.MEDIUM : request.priority(), request.assigneeId(),
					request.dueDate(), request.responsibleJobRoleId(), userId);
			tasks.saveAndFlush(task);
			return TaskView.from(task);
		});
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public UUID createFromStory(UUID orgId, UUID projectId, UUID storyId, String title, String description,
			String priority, UUID jobRoleId) {
		Task task = new Task(UUID.randomUUID(), orgId, projectId, title, description, TaskStatus.BACKLOG,
				TaskPriority.valueOf(priority), null, null, jobRoleId, currentUser.get().id());
		task.setSourceStoryId(storyId);
		tasks.saveAndFlush(task);
		return task.getId();
	}

	TaskView get(UUID orgId, UUID taskId) {
		return orgTx.inOrg(orgId, Permission.VIEW, () -> TaskView.from(require(orgId, taskId)));
	}

	TaskView update(UUID orgId, UUID taskId, TaskPatch patch) {
		return orgTx.inOrg(orgId, Permission.EDIT_TASKS, () -> {
			Task task = require(orgId, taskId);
			requireWritableProject(orgId, task.getProjectId());
			if (task.getVersion() != patch.version()) {
				throw new ConflictException("The task was changed by someone else. Reload and try again.");
			}
			rejectExplicitNull(patch.title(), "title");
			rejectExplicitNull(patch.status(), "status");
			rejectExplicitNull(patch.priority(), "priority");
			UUID nextRole = requireAllowedReviewChange(orgId, task, patch);
			task.setResponsibleJobRoleId(nextRole);
			if (patch.assigneeId() != null) {
				UUID assignee = patch.assigneeId().orElse(null);
				if (assignee != null && !Objects.equals(assignee, task.getAssigneeId())) {
					requireMember(orgId, assignee);
				}
				task.setAssigneeId(assignee);
			}
			if (patch.title() != null) {
				task.setTitle(patch.title().get());
			}
			if (patch.description() != null) {
				task.setDescription(patch.description().orElse(null));
			}
			if (patch.status() != null) {
				task.setStatus(patch.status().get());
			}
			if (patch.priority() != null) {
				task.setPriority(patch.priority().get());
			}
			if (patch.dueDate() != null) {
				task.setDueDate(patch.dueDate().orElse(null));
			}
			// Hibernate writes and bumps the version only when a value actually changed.
			tasks.saveAndFlush(task);
			return TaskView.from(task);
		});
	}

	void delete(UUID orgId, UUID taskId) {
		orgTx.inOrg(orgId, Permission.EDIT_TASKS, () -> {
			Task task = require(orgId, taskId);
			requireWritableProject(orgId, task.getProjectId());
			tasks.delete(task);
			tasks.flush();
			auditLog.record(orgId, "TASK_DELETED", "TASK", taskId,
					Map.of("title", task.getTitle(), "projectId", task.getProjectId().toString()));
			return null;
		});
	}

	/** Enforces that a task with a responsible role reaches review and done only through the review flow. */
	private UUID requireAllowedReviewChange(UUID orgId, Task task, TaskPatch patch) {
		UUID currentRole = task.getResponsibleJobRoleId();
		UUID nextRole = currentRole;
		if (patch.responsibleJobRoleId() != null) {
			if (patch.responsibleJobRoleId().isEmpty()) {
				if (currentRole != null) {
					throw new UnprocessableException("A responsible job role cannot be cleared.");
				}
			}
			else {
				nextRole = patch.responsibleJobRoleId().get();
			}
		}
		boolean roleChanged = !Objects.equals(currentRole, nextRole);
		if (roleChanged) {
			if (task.getStatus() == TaskStatus.IN_REVIEW) {
				throw new ConflictException("Move the task out of review before changing its job role.");
			}
			if (currentRole != null && task.getStatus() == TaskStatus.DONE) {
				throw new ConflictException("Reopen the task before changing it.");
			}
			jobRoles.requireActive(orgId, nextRole);
		}
		if (nextRole == null) {
			return null;
		}
		if (patch.status() != null && patch.status().get() != task.getStatus()
				&& (isReviewControlled(task.getStatus()) || isReviewControlled(patch.status().get()))) {
			throw new ConflictException("Use the review actions to move this task in or out of review.");
		}
		if (task.getStatus() == TaskStatus.DONE && changesContent(task, patch)) {
			throw new ConflictException("Reopen the task before changing it.");
		}
		return nextRole;
	}

	private static boolean isReviewControlled(TaskStatus status) {
		return status == TaskStatus.IN_REVIEW || status == TaskStatus.DONE;
	}

	private static boolean changesContent(Task task, TaskPatch patch) {
		return changes(patch.title(), task.getTitle()) || changes(patch.description(), task.getDescription())
				|| changes(patch.priority(), task.getPriority()) || changes(patch.assigneeId(), task.getAssigneeId())
				|| changes(patch.dueDate(), task.getDueDate());
	}

	private static <T> boolean changes(Optional<T> requested, T current) {
		return requested != null && !Objects.equals(requested.orElse(null), current);
	}

	private Task require(UUID orgId, UUID taskId) {
		return tasks.findByOrganizationIdAndId(orgId, taskId)
			.orElseThrow(() -> new NotFoundException("Task not found."));
	}

	private void requireWritableProject(UUID orgId, UUID projectId) {
		if (projects.requireStatus(orgId, projectId) == ProjectStatus.ARCHIVED) {
			throw new ConflictException("The project is archived.");
		}
	}

	private void requireMember(UUID orgId, UUID userId) {
		if (!orgTx.isMember(orgId, userId)) {
			throw new UnprocessableException("The assignee is not a member of this organization.");
		}
	}

	private static void rejectExplicitNull(Optional<?> value, String field) {
		if (value != null && value.isEmpty()) {
			throw new UnprocessableException(field + " cannot be null.");
		}
	}

	record NewTask(String title, String description, TaskStatus status, TaskPriority priority, UUID assigneeId,
			LocalDate dueDate, UUID responsibleJobRoleId) {
	}

	record TaskView(UUID id, UUID organizationId, UUID projectId, String title, String description, TaskStatus status,
			TaskPriority priority, UUID assigneeId, LocalDate dueDate, UUID responsibleJobRoleId, UUID createdBy,
			OffsetDateTime createdAt, OffsetDateTime updatedAt, long version) {

		static TaskView from(Task task) {
			return new TaskView(task.getId(), task.getOrganizationId(), task.getProjectId(), task.getTitle(),
					task.getDescription(), task.getStatus(), task.getPriority(), task.getAssigneeId(),
					task.getDueDate(), task.getResponsibleJobRoleId(), task.getCreatedBy(), task.getCreatedAt(),
					task.getUpdatedAt(), task.getVersion());
		}

	}

}
