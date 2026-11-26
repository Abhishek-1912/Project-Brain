package com.projectbrain.knowledge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.projectbrain.organization.api.OrgTx;
import com.projectbrain.requirement.api.KnowledgeContent;
import com.projectbrain.requirement.api.KnowledgeSourceLookup;
import com.projectbrain.requirement.api.KnowledgeSourceType;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * Indexes one source at a time in the background. Holds no database transaction while embedding, and replaces a
 * source's chunks in one transaction, so search always sees a complete set.
 */
@Component
class KnowledgeIndexer {

	private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexer.class);

	private static final int BATCH = 64;

	private final OrgTx orgTx;

	private final JdbcClient jdbc;

	private final KnowledgeSourceLookup lookup;

	private final KnowledgeAiClient ai;

	private final ThreadPoolTaskExecutor executor;

	KnowledgeIndexer(OrgTx orgTx, JdbcClient jdbc, KnowledgeSourceLookup lookup, KnowledgeAiClient ai) {
		this.orgTx = orgTx;
		this.jdbc = jdbc;
		this.lookup = lookup;
		this.ai = ai;
		this.executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(2);
		executor.setMaxPoolSize(2);
		executor.setQueueCapacity(100);
		executor.setThreadNamePrefix("knowledge-");
		executor.initialize();
	}

	@PreDestroy
	void stop() {
		executor.shutdown();
	}

	/** Queues a change. A source that no longer qualifies is removed instead of indexed. */
	void submit(UUID orgId, UUID projectId, KnowledgeSourceType type, UUID sourceId, UUID actorId) {
		run(() -> enqueue(orgId, projectId, type, sourceId, actorId)
			.ifPresent(rowId -> index(orgId, rowId, actorId)));
	}

	/** Indexes an existing PENDING source, as restart recovery does. */
	void submitIndex(UUID orgId, UUID rowId, UUID queuedBy) {
		run(() -> index(orgId, rowId, queuedBy));
	}

	private void run(Runnable work) {
		try {
			executor.execute(() -> {
				try {
					work.run();
				}
				catch (RuntimeException ex) {
					log.error("Knowledge indexing failed: {}", ex.getClass().getSimpleName());
				}
			});
		}
		catch (TaskRejectedException ex) {
			// The source stays PENDING, so restart recovery or a rebuild picks it up.
			log.warn("Knowledge indexing queue is full");
		}
	}

	Optional<UUID> enqueue(UUID orgId, UUID projectId, KnowledgeSourceType type, UUID sourceId, UUID actorId) {
		return orgTx.withOrgContext(actorId, orgId, () -> {
			Optional<KnowledgeContent> content = content(orgId, projectId, type, sourceId);
			if (content.isEmpty()) {
				jdbc.sql("""
						DELETE FROM brain.knowledge_sources
						WHERE organization_id = :orgId AND project_id = :projectId AND source_type = :type
						  AND source_id = :sourceId
						""")
					.param("orgId", orgId)
					.param("projectId", projectId)
					.param("type", type.name())
					.param("sourceId", sourceId)
					.update();
				return Optional.<UUID>empty();
			}
			String hash = hash(content.get());
			boolean unchanged = jdbc.sql("""
					SELECT EXISTS (
					    SELECT 1 FROM brain.knowledge_sources
					    WHERE organization_id = :orgId AND project_id = :projectId AND source_type = :type
					      AND source_id = :sourceId AND status = 'READY' AND content_hash = :hash
					      AND embedding_model = :model
					)
					""")
				.param("orgId", orgId)
				.param("projectId", projectId)
				.param("type", type.name())
				.param("sourceId", sourceId)
				.param("hash", hash)
				.param("model", KnowledgeAiClient.EMBEDDING_MODEL)
				.query(Boolean.class)
				.single();
			if (unchanged) {
				return Optional.<UUID>empty();
			}
			return Optional.of(jdbc.sql("""
					INSERT INTO brain.knowledge_sources
					    (id, organization_id, project_id, source_type, source_id, title, status, queued_by)
					VALUES (:id, :orgId, :projectId, :type, :sourceId, :title, 'PENDING', :actor)
					ON CONFLICT (organization_id, project_id, source_type, source_id) DO UPDATE
					SET title = EXCLUDED.title, status = 'PENDING', error_code = NULL, queued_by = EXCLUDED.queued_by,
					    updated_at = clock_timestamp()
					RETURNING id
					""")
				.param("id", UUID.randomUUID())
				.param("orgId", orgId)
				.param("projectId", projectId)
				.param("type", type.name())
				.param("sourceId", sourceId)
				.param("title", content.get().title())
				.param("actor", actorId)
				.query(UUID.class)
				.single());
		});
	}

	private void index(UUID orgId, UUID rowId, UUID queuedBy) {
		Work work = orgTx.withOrgContext(queuedBy, orgId, () -> claim(orgId, rowId));
		if (work == null) {
			return;
		}
		try {
			List<String> chunks = Chunker.split(work.content().text());
			if (chunks.isEmpty()) {
				chunks = List.of(work.content().title());
			}
			List<float[]> vectors = new ArrayList<>();
			for (int start = 0; start < chunks.size(); start += BATCH) {
				vectors.addAll(ai.embed(chunks.subList(start, Math.min(start + BATCH, chunks.size()))));
			}
			List<String> finalChunks = chunks;
			orgTx.withOrgContext(queuedBy, orgId, () -> {
				save(orgId, rowId, work, finalChunks, vectors);
				return null;
			});
		}
		catch (KnowledgeAiException ex) {
			fail(orgId, rowId, queuedBy, work.token(), ex.code());
		}
		catch (RuntimeException ex) {
			log.error("Knowledge source {} failed: {}", rowId, ex.getClass().getSimpleName());
			fail(orgId, rowId, queuedBy, work.token(), "INTERNAL_ERROR");
		}
	}

	/** Marks a PENDING source INDEXING and returns its content, or null when there is nothing to do. */
	private Work claim(UUID orgId, UUID rowId) {
		Optional<Row> row = jdbc.sql("""
				SELECT project_id, source_type, source_id FROM brain.knowledge_sources
				WHERE organization_id = :orgId AND id = :id AND status = 'PENDING'
				FOR UPDATE
				""")
			.param("orgId", orgId)
			.param("id", rowId)
			.query((rs, rowNumber) -> new Row(rs.getObject("project_id", UUID.class),
					KnowledgeSourceType.valueOf(rs.getString("source_type")), rs.getObject("source_id", UUID.class)))
			.optional();
		if (row.isEmpty()) {
			return null;
		}
		Optional<KnowledgeContent> content = content(orgId, row.get().projectId(), row.get().type(),
				row.get().sourceId());
		if (content.isEmpty()) {
			jdbc.sql("DELETE FROM brain.knowledge_sources WHERE organization_id = :orgId AND id = :id")
				.param("orgId", orgId)
				.param("id", rowId)
				.update();
			return null;
		}
		// The new timestamp is this attempt's token: a later change or deletion makes the save step a no-op.
		OffsetDateTime token = jdbc.sql("""
				UPDATE brain.knowledge_sources SET status = 'INDEXING', updated_at = clock_timestamp()
				WHERE organization_id = :orgId AND id = :id
				RETURNING updated_at
				""")
			.param("orgId", orgId)
			.param("id", rowId)
			.query(OffsetDateTime.class)
			.single();
		return new Work(content.get(), token);
	}

	private void save(UUID orgId, UUID rowId, Work work, List<String> chunks, List<float[]> vectors) {
		Optional<UUID> project = jdbc.sql("""
				SELECT project_id FROM brain.knowledge_sources
				WHERE organization_id = :orgId AND id = :id AND status = 'INDEXING' AND updated_at = :token
				FOR UPDATE
				""")
			.param("orgId", orgId)
			.param("id", rowId)
			.param("token", work.token())
			.query(UUID.class)
			.optional();
		if (project.isEmpty()) {
			return;
		}
		jdbc.sql("DELETE FROM brain.knowledge_chunks WHERE organization_id = :orgId AND source_row_id = :id")
			.param("orgId", orgId)
			.param("id", rowId)
			.update();
		for (int position = 0; position < chunks.size(); position++) {
			jdbc.sql("""
					INSERT INTO brain.knowledge_chunks
					    (id, organization_id, project_id, source_row_id, position, content, embedding)
					VALUES (:id, :orgId, :projectId, :sourceRowId, :position, :content, CAST(:embedding AS extensions.vector))
					""")
				.param("id", UUID.randomUUID())
				.param("orgId", orgId)
				.param("projectId", project.get())
				.param("sourceRowId", rowId)
				.param("position", position)
				.param("content", chunks.get(position))
				.param("embedding", VectorText.of(vectors.get(position)))
				.update();
		}
		jdbc.sql("""
				UPDATE brain.knowledge_sources
				SET status = 'READY', error_code = NULL, content_hash = :hash, embedding_model = :model,
				    indexed_at = now(), updated_at = clock_timestamp()
				WHERE organization_id = :orgId AND id = :id
				""")
			.param("hash", hash(work.content()))
			.param("model", KnowledgeAiClient.EMBEDDING_MODEL)
			.param("orgId", orgId)
			.param("id", rowId)
			.update();
	}

	private void fail(UUID orgId, UUID rowId, UUID queuedBy, OffsetDateTime token, String code) {
		try {
			orgTx.withOrgContext(queuedBy, orgId, () -> jdbc.sql("""
					UPDATE brain.knowledge_sources SET status = 'FAILED', error_code = :code, updated_at = clock_timestamp()
					WHERE organization_id = :orgId AND id = :id AND status = 'INDEXING' AND updated_at = :token
					""")
				.param("code", code)
				.param("orgId", orgId)
				.param("id", rowId)
				.param("token", token)
				.update());
		}
		catch (RuntimeException ex) {
			log.error("Could not mark knowledge source {} failed: {}", rowId, ex.getClass().getSimpleName());
		}
	}

	private Optional<KnowledgeContent> content(UUID orgId, UUID projectId, KnowledgeSourceType type, UUID sourceId) {
		if (type != KnowledgeSourceType.NOTE) {
			return lookup.load(orgId, projectId, type, sourceId);
		}
		return jdbc.sql("""
				SELECT title, body FROM brain.knowledge_notes
				WHERE organization_id = :orgId AND project_id = :projectId AND id = :id
				""")
			.param("orgId", orgId)
			.param("projectId", projectId)
			.param("id", sourceId)
			.query((rs, rowNumber) -> new KnowledgeContent(rs.getString("title"),
					rs.getString("title") + "\n\n" + rs.getString("body")))
			.optional();
	}

	private static String hash(KnowledgeContent content) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			String value = KnowledgeAiClient.EMBEDDING_MODEL + "\n" + content.title() + "\n" + content.text();
			return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is unavailable", ex);
		}
	}

	private record Row(UUID projectId, KnowledgeSourceType type, UUID sourceId) {
	}

	private record Work(KnowledgeContent content, OffsetDateTime token) {
	}

}
