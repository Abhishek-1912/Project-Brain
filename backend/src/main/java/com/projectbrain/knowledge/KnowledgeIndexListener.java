package com.projectbrain.knowledge;

import com.projectbrain.requirement.api.KnowledgeChanged;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/** Hands each committed change to the indexer's own threads, so no database work runs in the commit callback. */
@Component
class KnowledgeIndexListener {

	private final KnowledgeIndexer indexer;

	KnowledgeIndexListener(KnowledgeIndexer indexer) {
		this.indexer = indexer;
	}

	@TransactionalEventListener
	void on(KnowledgeChanged event) {
		indexer.submit(event.orgId(), event.projectId(), event.type(), event.sourceId(), event.actorId());
	}

}
