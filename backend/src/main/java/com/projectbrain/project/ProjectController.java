package com.projectbrain.project;

import java.util.UUID;

import com.projectbrain.project.ProjectService.ProjectChanges;
import com.projectbrain.project.ProjectService.ProjectView;
import com.projectbrain.project.api.ProjectStatus;
import com.projectbrain.shared.web.PageParams;
import com.projectbrain.shared.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
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
@RequestMapping("/api/v1/organizations/{orgId}/projects")
class ProjectController {

	private final ProjectService projects;

	ProjectController(ProjectService projects) {
		this.projects = projects;
	}

	@GetMapping
	PageResponse<ProjectView> list(@PathVariable UUID orgId, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return projects.list(orgId, PageParams.of(page, size));
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	ProjectView create(@PathVariable UUID orgId, @Valid @RequestBody CreateRequest request) {
		return projects.create(orgId, request.name(), request.description());
	}

	@GetMapping("/{projectId}")
	ProjectView get(@PathVariable UUID orgId, @PathVariable UUID projectId) {
		return projects.get(orgId, projectId);
	}

	@PatchMapping("/{projectId}")
	ProjectView update(@PathVariable UUID orgId, @PathVariable UUID projectId,
			@Valid @RequestBody PatchRequest request) {
		return projects.update(orgId, projectId, request.toChanges());
	}

	record CreateRequest(@NotBlank @Size(max = 120) String name, @Size(max = 10000) String description) {
	}

	/** A class with setters, because a PATCH must tell an absent description from an explicit null. */
	static class PatchRequest {

		@Size(min = 1, max = 120)
		@Pattern(regexp = "(?s).*\\S.*")
		private String name;

		@Size(max = 10000)
		private String description;

		private boolean descriptionProvided;

		@Pattern(regexp = "ACTIVE|ARCHIVED")
		private String status;

		@NotNull
		private Long version;

		public void setName(String name) {
			this.name = name;
		}

		public void setDescription(String description) {
			this.description = description;
			this.descriptionProvided = true;
		}

		public void setStatus(String status) {
			this.status = status;
		}

		public void setVersion(Long version) {
			this.version = version;
		}

		ProjectChanges toChanges() {
			return new ProjectChanges(name, descriptionProvided, description,
					status == null ? null : ProjectStatus.valueOf(status), version);
		}

	}

}
