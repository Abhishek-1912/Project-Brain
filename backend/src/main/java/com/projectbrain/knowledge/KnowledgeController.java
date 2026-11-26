package com.projectbrain.knowledge;

import java.util.UUID;

import com.projectbrain.knowledge.KnowledgeSourceStore.RebuildResult;
import com.projectbrain.knowledge.KnowledgeSourceStore.SourceView;
import com.projectbrain.shared.web.PageParams;
import com.projectbrain.shared.web.PageResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organizations/{orgId}")
class KnowledgeController {

	private final KnowledgeSourceStore sources;

	private final KnowledgeAskService asking;

	KnowledgeController(KnowledgeSourceStore sources, KnowledgeAskService asking) {
		this.sources = sources;
		this.asking = asking;
	}

	@GetMapping("/projects/{projectId}/knowledge/sources")
	PageResponse<SourceView> sources(@PathVariable UUID orgId, @PathVariable UUID projectId,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		return sources.list(orgId, projectId, PageParams.of(page, size));
	}

	@PostMapping("/projects/{projectId}/knowledge/rebuild")
	@ResponseStatus(HttpStatus.ACCEPTED)
	RebuildResult rebuild(@PathVariable UUID orgId, @PathVariable UUID projectId) {
		return sources.rebuild(orgId, projectId);
	}

	@GetMapping("/projects/{projectId}/knowledge/search")
	KnowledgeAskService.SearchResult search(@PathVariable UUID orgId, @PathVariable UUID projectId,
			@RequestParam(required = false) String q, @RequestParam(required = false) Integer limit) {
		return asking.search(orgId, projectId, q, limit);
	}

	@PostMapping("/projects/{projectId}/knowledge/ask")
	KnowledgeAskService.AnswerView ask(@PathVariable UUID orgId, @PathVariable UUID projectId,
			@RequestBody KnowledgeAskService.AskRequest request) {
		return asking.ask(orgId, projectId, request.question());
	}

}
