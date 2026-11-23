package com.projectbrain.team;

import java.util.UUID;

import com.projectbrain.team.ProjectTeamService.ProjectTeam;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organizations/{orgId}/projects/{projectId}/team")
class ProjectTeamController {

	private final ProjectTeamService team;

	ProjectTeamController(ProjectTeamService team) {
		this.team = team;
	}

	@GetMapping
	ProjectTeam get(@PathVariable UUID orgId, @PathVariable UUID projectId) {
		return team.get(orgId, projectId);
	}

	@PutMapping("/{userId}/job-roles/{jobRoleId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void assign(@PathVariable UUID orgId, @PathVariable UUID projectId, @PathVariable UUID userId,
			@PathVariable UUID jobRoleId) {
		team.assign(orgId, projectId, userId, jobRoleId);
	}

	@DeleteMapping("/{userId}/job-roles/{jobRoleId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void remove(@PathVariable UUID orgId, @PathVariable UUID projectId, @PathVariable UUID userId,
			@PathVariable UUID jobRoleId) {
		team.remove(orgId, projectId, userId, jobRoleId);
	}

}
