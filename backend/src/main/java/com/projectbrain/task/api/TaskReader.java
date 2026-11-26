package com.projectbrain.task.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Lists a project's tasks as the current user, newest first, at most 100. Checks access itself. */
public interface TaskReader {

	List<TaskSummary> list(UUID orgId, UUID projectId, String status);

	record TaskSummary(UUID id, String title, String description, String status, String priority, UUID assigneeId,
			LocalDate dueDate) {
	}

}
