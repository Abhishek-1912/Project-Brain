package com.projectbrain.task.api;

import java.util.UUID;

/** Creates Backlog tasks as the current user, with the app's permission and project checks. */
public interface TaskWriter {

	UUID create(UUID orgId, UUID projectId, String title, String description, String priority, String jobRoleName);

}
