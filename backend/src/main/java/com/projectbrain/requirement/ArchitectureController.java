package com.projectbrain.requirement;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organizations/{orgId}")
class ArchitectureController {

	private final AiRunService runs;

	ArchitectureController(AiRunService runs) {
		this.runs = runs;
	}

	@PostMapping("/projects/{projectId}/architecture/generate")
	@ResponseStatus(HttpStatus.ACCEPTED)
	AiRunService.RunAccepted generate(@PathVariable UUID orgId, @PathVariable UUID projectId) {
		return runs.startArchitecture(orgId, projectId);
	}

}
