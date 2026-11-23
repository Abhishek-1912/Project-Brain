package com.projectbrain.task;

import java.util.UUID;

import com.projectbrain.task.TaskService.TaskView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organizations/{orgId}/tasks/{taskId}")
class TaskReviewController {

	private final TaskReviewService reviews;

	TaskReviewController(TaskReviewService reviews) {
		this.reviews = reviews;
	}

	@PostMapping("/submit")
	TaskView submit(@PathVariable UUID orgId, @PathVariable UUID taskId, @Valid @RequestBody ReviewRequest request) {
		return reviews.apply(orgId, taskId, TaskReviewAction.SUBMITTED, request.version(), request.comment());
	}

	@PostMapping("/approve")
	TaskView approve(@PathVariable UUID orgId, @PathVariable UUID taskId, @Valid @RequestBody ReviewRequest request) {
		return reviews.apply(orgId, taskId, TaskReviewAction.APPROVED, request.version(), request.comment());
	}

	@PostMapping("/reject")
	TaskView reject(@PathVariable UUID orgId, @PathVariable UUID taskId, @Valid @RequestBody ReviewRequest request) {
		return reviews.apply(orgId, taskId, TaskReviewAction.REJECTED, request.version(), request.comment());
	}

	@PostMapping("/withdraw")
	TaskView withdraw(@PathVariable UUID orgId, @PathVariable UUID taskId, @Valid @RequestBody ReviewRequest request) {
		return reviews.apply(orgId, taskId, TaskReviewAction.WITHDRAWN, request.version(), request.comment());
	}

	@PostMapping("/reopen")
	TaskView reopen(@PathVariable UUID orgId, @PathVariable UUID taskId, @Valid @RequestBody ReviewRequest request) {
		return reviews.apply(orgId, taskId, TaskReviewAction.REOPENED, request.version(), request.comment());
	}

	record ReviewRequest(@NotNull Long version, @Size(max = 2000) String comment) {
	}

}
