package com.projectbrain.project;

import java.util.List;
import java.util.UUID;

import com.projectbrain.project.api.ProjectDirectory;
import com.projectbrain.shared.web.PageParams;
import org.springframework.stereotype.Component;

@Component
class ProjectDirectoryAdapter implements ProjectDirectory {

	private final ProjectService projects;

	ProjectDirectoryAdapter(ProjectService projects) {
		this.projects = projects;
	}

	@Override
	public List<ProjectSummary> list(UUID orgId) {
		return projects.list(orgId, PageParams.of(0, 100))
			.items()
			.stream()
			.map(project -> new ProjectSummary(project.id(), project.name(), project.description(),
					project.status().name()))
			.toList();
	}

}
