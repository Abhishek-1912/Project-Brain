package com.projectbrain.requirement;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.projectbrain.shared.web.PageParams;
import com.projectbrain.shared.web.PageResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
@RequestMapping("/api/v1/organizations/{orgId}")
class ArchitectureController {

	private final AiRunService runs;

	private final ArchitectureService architecture;

	private final ArchitectureReviewService reviews;

	ArchitectureController(AiRunService runs, ArchitectureService architecture, ArchitectureReviewService reviews) {
		this.runs = runs;
		this.architecture = architecture;
		this.reviews = reviews;
	}

	@PostMapping("/projects/{projectId}/architecture/generate")
	@ResponseStatus(HttpStatus.ACCEPTED)
	AiRunService.RunAccepted generate(@PathVariable UUID orgId, @PathVariable UUID projectId) {
		return runs.startArchitecture(orgId, projectId);
	}

	@GetMapping("/projects/{projectId}/architecture")
	ArchitectureService.ArchitectureState state(@PathVariable UUID orgId, @PathVariable UUID projectId) {
		return architecture.state(orgId, projectId);
	}

	@GetMapping("/projects/{projectId}/architecture/versions")
	PageResponse<ArchitectureService.VersionSummary> versions(@PathVariable UUID orgId, @PathVariable UUID projectId,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		return architecture.list(orgId, projectId, PageParams.of(page, size));
	}

	@GetMapping("/architecture-versions/{versionId}")
	ArchitectureService.ArchitectureVersion version(@PathVariable UUID orgId, @PathVariable UUID versionId) {
		return architecture.get(orgId, versionId);
	}

	@PatchMapping("/architecture-versions/{versionId}/{section}")
	ArchitectureService.ArchitectureVersion updateSection(@PathVariable UUID orgId, @PathVariable UUID versionId,
			@PathVariable String section, @RequestBody ArchitectureService.SectionUpdate update) {
		return architecture.updateSection(orgId, versionId, section, update.content(), update.version());
	}

	@PostMapping("/architecture-versions/{versionId}/approve")
	ArchitectureService.ArchitectureVersion approve(@PathVariable UUID orgId, @PathVariable UUID versionId,
			@RequestBody StoryController.ApproveRequest request) {
		return reviews.approve(orgId, versionId, request.version());
	}

	@PostMapping("/architecture-versions/{versionId}/reject")
	ArchitectureService.ArchitectureVersion reject(@PathVariable UUID orgId, @PathVariable UUID versionId,
			@RequestBody StoryController.RejectRequest request) {
		return reviews.reject(orgId, versionId, request.version(), request.comment());
	}

	@GetMapping("/architecture-versions/{versionId}/reviews")
	PageResponse<ArchitectureReviewService.ReviewView> reviews(@PathVariable UUID orgId, @PathVariable UUID versionId,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "100") int size) {
		return reviews.history(orgId, versionId, PageParams.of(page, size));
	}

	@GetMapping("/architecture-versions/{versionId}/markdown")
	ResponseEntity<String> markdown(@PathVariable UUID orgId, @PathVariable UUID versionId) {
		ArchitectureService.MarkdownFile file = reviews.markdown(orgId, versionId);
		return ResponseEntity.ok()
			.contentType(new MediaType("text", "markdown", StandardCharsets.UTF_8))
			.header(HttpHeaders.CONTENT_DISPOSITION,
					ContentDisposition.attachment().filename(file.filename()).build().toString())
			.body(file.text());
	}

}
