package com.projectbrain.requirement;

import java.util.UUID;

import com.projectbrain.requirement.RequirementService.DocumentView;
import com.projectbrain.shared.web.PageParams;
import com.projectbrain.shared.web.PageResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/organizations/{orgId}")
class RequirementController {

	private final RequirementService requirements;

	private final AiRunService runs;

	private final AnalysisService analyses;

	RequirementController(RequirementService requirements, AiRunService runs, AnalysisService analyses) {
		this.requirements = requirements;
		this.runs = runs;
		this.analyses = analyses;
	}

	@GetMapping("/projects/{projectId}/requirements")
	PageResponse<DocumentView> list(@PathVariable UUID orgId, @PathVariable UUID projectId,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		return requirements.list(orgId, projectId, PageParams.of(page, size));
	}

	@PostMapping(path = "/projects/{projectId}/requirements", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@ResponseStatus(HttpStatus.CREATED)
	DocumentView upload(@PathVariable UUID orgId, @PathVariable UUID projectId, @RequestPart("file") MultipartFile file,
			@RequestParam(defaultValue = "false") boolean dummyDataConfirmed,
			@RequestParam(required = false) UUID approverJobRoleId) {
		return requirements.upload(orgId, projectId, file, dummyDataConfirmed, approverJobRoleId);
	}

	@GetMapping("/requirements/{documentId}")
	DocumentView get(@PathVariable UUID orgId, @PathVariable UUID documentId) {
		return requirements.get(orgId, documentId);
	}

	@PostMapping("/requirements/{documentId}/analyze")
	@ResponseStatus(HttpStatus.ACCEPTED)
	AiRunService.RunAccepted analyze(@PathVariable UUID orgId, @PathVariable UUID documentId) {
		return runs.startAnalysis(orgId, documentId);
	}

	@GetMapping("/requirements/{documentId}/analysis")
	AnalysisService.AnalysisResponse analysis(@PathVariable UUID orgId, @PathVariable UUID documentId) {
		return analyses.latest(orgId, documentId);
	}

	@GetMapping("/ai-runs/{runId}")
	AiRunStore.RunView run(@PathVariable UUID orgId, @PathVariable UUID runId) {
		return runs.get(orgId, runId);
	}

}
