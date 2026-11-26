package com.projectbrain.project.api;

import java.util.List;
import java.util.UUID;

/** Lists projects as the current user. Checks membership itself, so it runs outside any transaction. */
public interface ProjectDirectory {

	List<ProjectSummary> list(UUID orgId);

	record ProjectSummary(UUID id, String name, String description, String status) {
	}

}
