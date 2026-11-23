package com.projectbrain.team;

import java.util.UUID;

import com.projectbrain.shared.web.PageParams;
import com.projectbrain.shared.web.PageResponse;
import com.projectbrain.team.JobRoleService.JobRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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
@RequestMapping("/api/v1/organizations/{orgId}/job-roles")
class JobRoleController {

	private final JobRoleService jobRoles;

	JobRoleController(JobRoleService jobRoles) {
		this.jobRoles = jobRoles;
	}

	@GetMapping
	PageResponse<JobRole> list(@PathVariable UUID orgId, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return jobRoles.list(orgId, PageParams.of(page, size));
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	JobRole create(@PathVariable UUID orgId, @Valid @RequestBody CreateRequest request) {
		return jobRoles.create(orgId, request.name());
	}

	@PatchMapping("/{jobRoleId}")
	JobRole update(@PathVariable UUID orgId, @PathVariable UUID jobRoleId, @Valid @RequestBody PatchRequest request) {
		return jobRoles.update(orgId, jobRoleId, request.name(), request.active(), request.version());
	}

	record CreateRequest(@NotBlank String name) {
	}

	record PatchRequest(@Pattern(regexp = "(?s).*\\S.*") String name, Boolean active, @NotNull Long version) {
	}

}
