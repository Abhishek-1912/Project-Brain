package com.projectbrain.requirement;

import java.util.UUID;

import com.projectbrain.requirement.AnalysisService.QuestionView;
import com.projectbrain.requirement.ClarificationService.ClarificationUpdate;
import com.projectbrain.requirement.StoryService.StoryUpdate;
import com.projectbrain.requirement.StoryService.StoryView;
import com.projectbrain.shared.web.PageParams;
import com.projectbrain.shared.web.PageResponse;
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
@RequestMapping("/api/v1/organizations/{orgId}")
class StoryController {

	private final ClarificationService clarifications;

	private final StoryService stories;

	private final AiRunService runs;

	StoryController(ClarificationService clarifications, StoryService stories, AiRunService runs) {
		this.clarifications = clarifications;
		this.stories = stories;
		this.runs = runs;
	}

	@PatchMapping("/clarifications/{questionId}")
	QuestionView updateClarification(@PathVariable UUID orgId, @PathVariable UUID questionId,
			@RequestBody ClarificationUpdate update) {
		return clarifications.update(orgId, questionId, update);
	}

	@PostMapping("/requirements/{documentId}/stories/generate")
	@ResponseStatus(HttpStatus.ACCEPTED)
	AiRunService.RunAccepted generate(@PathVariable UUID orgId, @PathVariable UUID documentId) {
		return runs.startStories(orgId, documentId);
	}

	@GetMapping("/requirements/{documentId}/stories")
	PageResponse<StoryView> list(@PathVariable UUID orgId, @PathVariable UUID documentId,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "100") int size) {
		return stories.list(orgId, documentId, PageParams.of(page, size));
	}

	@PatchMapping("/stories/{storyId}")
	StoryView update(@PathVariable UUID orgId, @PathVariable UUID storyId, @RequestBody StoryUpdate update) {
		return stories.update(orgId, storyId, update);
	}

}
