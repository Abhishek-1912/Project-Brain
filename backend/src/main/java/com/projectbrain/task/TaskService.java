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
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

@Service
class TaskService {

	private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id"));

	private final TaskRepository tasks;

	private final OrgTx orgTx;

	private final ProjectLookup projects;

	private final CurrentUserProvider currentUser;

	private final AuditLog auditLog;

	TaskService(TaskRepository tasks, OrgTx orgTx, ProjectLookup projects, CurrentUserProvider currentUser,
			AuditLog auditLog) {
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
		return orgTx.inOrg(orgId, Permission.EDIT_TASKS, () -> {
			requireWritableProject(orgId, projectId);
			if (request.assigneeId() != null) {
				requireMember(orgId, request.assigneeId());
			}
			Task task = new Task(UUID.randomUUID(), orgId, projectId, request.title(), request.description(),
					request.status() == null ? TaskStatus.BACKLOG : request.status(),
					request.priority() == null ? TaskPriority.MEDIUM : request.priority(), request.assigneeId(),
					request.dueDate(), userId);
			tasks.saveAndFlush(task);
			return TaskView.from(task);
		});
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
			LocalDate dueDate) {
	}

	record TaskView(UUID id, UUID organizationId, UUID projectId, String title, String description, TaskStatus status,
			TaskPriority priority, UUID assigneeId, LocalDate dueDate, UUID createdBy, OffsetDateTime createdAt,
			OffsetDateTime updatedAt, long version) {

		static TaskView from(Task task) {
			return new TaskView(task.getId(), task.getOrganizationId(), task.getProjectId(), task.getTitle(),
					task.getDescription(), task.getStatus(), task.getPriority(), task.getAssigneeId(),
					task.getDueDate(), task.getCreatedBy(), task.getCreatedAt(), task.getUpdatedAt(),
					task.getVersion());
		}

	}

}
