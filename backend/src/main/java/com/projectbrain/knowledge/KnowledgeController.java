package com.projectbrain.knowledge;

import java.util.UUID;

import com.projectbrain.knowledge.KnowledgeSourceStore.RebuildResult;
import com.projectbrain.knowledge.KnowledgeSourceStore.SourceView;
import com.projectbrain.shared.web.PageParams;
import com.projectbrain.shared.web.PageResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
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
class KnowledgeController {

	private final KnowledgeSourceStore sources;

	private final KnowledgeAskService asking;

	private final KnowledgeNoteService notes;

	KnowledgeController(KnowledgeSourceStore sources, KnowledgeAskService asking, KnowledgeNoteService notes) {
		this.sources = sources;
		this.asking = asking;
		this.notes = notes;
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

	@GetMapping("/projects/{projectId}/knowledge/notes")
	PageResponse<KnowledgeNoteService.NoteView> notes(@PathVariable UUID orgId, @PathVariable UUID projectId,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		return notes.list(orgId, projectId, PageParams.of(page, size));
	}

	@PostMapping("/projects/{projectId}/knowledge/notes")
	@ResponseStatus(HttpStatus.CREATED)
	KnowledgeNoteService.NoteView createNote(@PathVariable UUID orgId, @PathVariable UUID projectId,
			@RequestBody KnowledgeNoteService.NoteRequest request) {
		return notes.create(orgId, projectId, request);
	}

	@GetMapping("/knowledge/notes/{noteId}")
	KnowledgeNoteService.NoteView note(@PathVariable UUID orgId, @PathVariable UUID noteId) {
		return notes.get(orgId, noteId);
	}

	@PatchMapping("/knowledge/notes/{noteId}")
	KnowledgeNoteService.NoteView updateNote(@PathVariable UUID orgId, @PathVariable UUID noteId,
			@RequestBody KnowledgeNoteService.NotePatch patch) {
		return notes.update(orgId, noteId, patch);
	}

	@DeleteMapping("/knowledge/notes/{noteId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void deleteNote(@PathVariable UUID orgId, @PathVariable UUID noteId) {
		notes.delete(orgId, noteId);
	}

}
