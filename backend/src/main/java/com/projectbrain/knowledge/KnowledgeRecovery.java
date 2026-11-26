package com.projectbrain.knowledge;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Indexing is safe to repeat, so sources left waiting or interrupted are queued again at startup. */
@Component
public class KnowledgeRecovery {

	private static final Logger log = LoggerFactory.getLogger(KnowledgeRecovery.class);

	private final JdbcClient jdbc;

	private final KnowledgeIndexer indexer;

	KnowledgeRecovery(JdbcClient jdbc, KnowledgeIndexer indexer) {
		this.jdbc = jdbc;
		this.indexer = indexer;
	}

	@EventListener(ApplicationReadyEvent.class)
	void onStartup() {
		int queued = recover();
		if (queued > 0) {
			log.info("Queued {} knowledge sources for indexing", queued);
		}
	}

	public int recover() {
		List<Waiting> waiting = jdbc.sql("SELECT org_id, source_row_id, queued_by_user FROM brain.requeue_interrupted_knowledge()")
			.query((row, rowNumber) -> new Waiting(row.getObject("org_id", UUID.class),
					row.getObject("source_row_id", UUID.class), row.getObject("queued_by_user", UUID.class)))
			.list();
		waiting.forEach(item -> indexer.submitIndex(item.orgId(), item.rowId(), item.queuedBy()));
		return waiting.size();
	}

	private record Waiting(UUID orgId, UUID rowId, UUID queuedBy) {
	}

}
