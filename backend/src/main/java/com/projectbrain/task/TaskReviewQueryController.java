package com.projectbrain.task;

import java.util.UUID;

import com.projectbrain.shared.web.PageParams;
import com.projectbrain.shared.web.PageResponse;
import com.projectbrain.task.TaskReviewQueryService.ReviewEntry;
import com.projectbrain.task.TaskReviewQueryService.ReviewQueueItem;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organizations/{orgId}")
class TaskReviewQueryController {

	private final TaskReviewQueryService reviews;

	TaskReviewQueryController(TaskReviewQueryService reviews) {
		this.reviews = reviews;
	}

	@GetMapping("/reviews/mine")
	PageResponse<ReviewQueueItem> mine(@PathVariable UUID orgId, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return reviews.myQueue(orgId, PageParams.of(page, size));
	}

	@GetMapping("/tasks/{taskId}/reviews")
	PageResponse<ReviewEntry> history(@PathVariable UUID orgId, @PathVariable UUID taskId,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		return reviews.history(orgId, taskId, PageParams.of(page, size));
	}

}
