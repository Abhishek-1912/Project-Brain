package com.projectbrain.knowledge;

import java.util.List;
import java.util.UUID;

import com.projectbrain.knowledge.api.KnowledgeQueries;
import com.projectbrain.knowledge.api.NoteReader;
import com.projectbrain.shared.web.PageParams;
import org.springframework.stereotype.Component;

@Component
class KnowledgeApiAdapter implements KnowledgeQueries, NoteReader {

	private final KnowledgeAskService asking;

	private final KnowledgeNoteService notes;

	KnowledgeApiAdapter(KnowledgeAskService asking, KnowledgeNoteService notes) {
		this.asking = asking;
		this.notes = notes;
	}

	@Override
	public List<SearchHit> search(UUID orgId, UUID projectId, String query, Integer limit) {
		return asking.search(orgId, projectId, query, limit)
			.items()
			.stream()
			.map(item -> new SearchHit(item.chunkId(), item.sourceId(), item.sourceType(), item.title(), item.content(),
					item.score()))
			.toList();
	}

	@Override
	public Answer ask(UUID orgId, UUID projectId, String question) {
		KnowledgeAskService.AnswerView view = asking.ask(orgId, projectId, question);
		return new Answer(view.id(), view.question(), view.answer(), view.found(),
				view.citations()
					.stream()
					.map(c -> new Citation(c.number(), c.sourceId(), c.sourceType(), c.title(), c.excerpt()))
					.toList());
	}

	@Override
	public List<NoteSummary> list(UUID orgId, UUID projectId) {
		return notes.list(orgId, projectId, PageParams.of(0, 100))
			.items()
			.stream()
			.map(note -> new NoteSummary(note.id(), note.title(), note.body(), note.updatedAt()))
			.toList();
	}

}
