package com.projectbrain.task;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import com.projectbrain.shared.web.PageParams;
import com.projectbrain.shared.web.PageResponse;
import com.projectbrain.task.TaskService.NewTask;
import com.projectbrain.task.TaskService.TaskView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organizations/{orgId}")
class TaskController {

	private static final String STATUS_VALUES = "BACKLOG|TODO|IN_PROGRESS|IN_REVIEW|DONE";

	private static final String PRIORITY_VALUES = "LOW|MEDIUM|HIGH|URGENT";

	private final TaskService tasks;

	TaskController(TaskService tasks) {
		this.tasks = tasks;
	}

	@GetMapping("/projects/{projectId}/tasks")
	PageResponse<TaskView> list(@PathVariable UUID orgId, @PathVariable UUID projectId,
			@RequestParam(required = false) TaskStatus status, @RequestParam(required = false) UUID assigneeId,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		return tasks.list(orgId, projectId, status, assigneeId, PageParams.of(page, size));
	}

	@PostMapping("/projects/{projectId}/tasks")
	@ResponseStatus(HttpStatus.CREATED)
	TaskView create(@PathVariable UUID orgId, @PathVariable UUID projectId, @Valid @RequestBody CreateRequest request) {
		return tasks.create(orgId, projectId, request.toNewTask());
	}

	@GetMapping("/tasks/{taskId}")
	TaskView get(@PathVariable UUID orgId, @PathVariable UUID taskId) {
		return tasks.get(orgId, taskId);
	}

	@PatchMapping("/tasks/{taskId}")
	TaskView update(@PathVariable UUID orgId, @PathVariable UUID taskId, @Valid @RequestBody PatchRequest request) {
		return tasks.update(orgId, taskId, request.toPatch());
	}

	@DeleteMapping("/tasks/{taskId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void delete(@PathVariable UUID orgId, @PathVariable UUID taskId) {
		tasks.delete(orgId, taskId);
	}

	record CreateRequest(@NotBlank @Size(max = 200) String title, @Size(max = 10000) String description,
			@Pattern(regexp = STATUS_VALUES) String status, @Pattern(regexp = PRIORITY_VALUES) String priority,
			UUID assigneeId, LocalDate dueDate, @NotNull UUID responsibleJobRoleId) {

		NewTask toNewTask() {
			return new NewTask(title, description, status == null ? null : TaskStatus.valueOf(status),
					priority == null ? null : TaskPriority.valueOf(priority), assigneeId, dueDate,
					responsibleJobRoleId);
		}

	}

	/** Setters record presence: an untouched field stays null, and a JSON null becomes Optional.empty(). */
	static class PatchRequest {

		private Optional<@Size(min = 1, max = 200) @Pattern(regexp = "(?s).*\\S.*") String> title;

		private Optional<@Size(max = 10000) String> description;

		private Optional<@Pattern(regexp = STATUS_VALUES) String> status;

		private Optional<@Pattern(regexp = PRIORITY_VALUES) String> priority;

		private Optional<UUID> assigneeId;

		private Optional<LocalDate> dueDate;

		private Optional<UUID> responsibleJobRoleId;

		@NotNull
		private Long version;

		public void setTitle(String title) {
			this.title = Optional.ofNullable(title);
		}

		public void setDescription(String description) {
			this.description = Optional.ofNullable(description);
		}

		public void setStatus(String status) {
			this.status = Optional.ofNullable(status);
		}

		public void setPriority(String priority) {
			this.priority = Optional.ofNullable(priority);
		}

		public void setAssigneeId(UUID assigneeId) {
			this.assigneeId = Optional.ofNullable(assigneeId);
		}

		public void setDueDate(LocalDate dueDate) {
			this.dueDate = Optional.ofNullable(dueDate);
		}

		public void setResponsibleJobRoleId(UUID responsibleJobRoleId) {
			this.responsibleJobRoleId = Optional.ofNullable(responsibleJobRoleId);
		}

		public void setVersion(Long version) {
			this.version = version;
		}

		TaskPatch toPatch() {
			return new TaskPatch(title, description, status == null ? null : status.map(TaskStatus::valueOf),
					priority == null ? null : priority.map(TaskPriority::valueOf), assigneeId, dueDate,
					responsibleJobRoleId, version);
		}

	}

}
