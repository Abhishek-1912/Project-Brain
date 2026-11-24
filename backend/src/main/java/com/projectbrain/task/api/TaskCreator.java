package com.projectbrain.task.api;

import java.util.UUID;

public interface TaskCreator {

	/** Must run inside an organization transaction. The database allows only one task per story. */
	UUID createFromStory(UUID orgId, UUID projectId, UUID storyId, String title, String description, String priority,
			UUID jobRoleId);

}
