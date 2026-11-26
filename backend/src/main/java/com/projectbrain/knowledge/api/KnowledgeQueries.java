package com.projectbrain.knowledge.api;

import java.util.List;
import java.util.UUID;

/** Phase 4 search and ask, as the current user. Checks access itself. */
public interface KnowledgeQueries {

	List<SearchHit> search(UUID orgId, UUID projectId, String query, Integer limit);

	Answer ask(UUID orgId, UUID projectId, String question);

	record SearchHit(UUID chunkId, UUID sourceId, String sourceType, String title, String content, double score) {
	}

	record Citation(int number, UUID sourceId, String sourceType, String title, String excerpt) {
	}

	record Answer(UUID id, String question, String answer, boolean found, List<Citation> citations) {
	}

}
