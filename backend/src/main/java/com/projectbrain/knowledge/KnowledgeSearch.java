package com.projectbrain.knowledge;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Merges the closest chunks by meaning with the best keyword matches, using reciprocal rank fusion, and returns only
 * relevant ones: a keyword match, or a meaning match at least MIN_SIMILARITY close. Every query filters
 * by organization and project, on top of row-level security. Chunks are always a complete set for their source, so
 * every source's chunks are searched whatever its indexing status.
 */
@Component
class KnowledgeSearch {

	static final int CANDIDATES = 20;

	static final int RRF_K = 60;

	/** A result is relevant when it matched by keyword or is at least this close in meaning. Tuned in acceptance. */
	static final double MIN_SIMILARITY = 0.6;

	private final JdbcClient jdbc;

	KnowledgeSearch(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Must run inside an organization transaction. */
	List<Hit> search(UUID orgId, UUID projectId, String query, float[] queryVector, int limit) {
		return jdbc.sql("""
				WITH vector_hits AS (
				    SELECT c.id,
				           row_number() OVER (ORDER BY c.embedding OPERATOR(extensions.<=>) CAST(:vector AS extensions.vector), c.id) AS rank,
				           1 - (c.embedding OPERATOR(extensions.<=>) CAST(:vector AS extensions.vector)) AS similarity
				    FROM brain.knowledge_chunks c
				    WHERE c.organization_id = :orgId AND c.project_id = :projectId
				    ORDER BY c.embedding OPERATOR(extensions.<=>) CAST(:vector AS extensions.vector), c.id
				    LIMIT :candidates
				),
				keyword_hits AS (
				    SELECT c.id, row_number() OVER (ORDER BY ts_rank(c.search_vector, q) DESC, c.id) AS rank
				    FROM brain.knowledge_chunks c, websearch_to_tsquery('english'::regconfig, :query) q
				    WHERE c.organization_id = :orgId AND c.project_id = :projectId AND c.search_vector @@ q
				    ORDER BY ts_rank(c.search_vector, q) DESC, c.id
				    LIMIT :candidates
				),
				merged AS (
				    SELECT id FROM vector_hits UNION SELECT id FROM keyword_hits
				)
				SELECT c.id, c.content, s.source_type, s.source_id, s.title,
				       coalesce(1.0 / (:k + v.rank), 0) + coalesce(1.0 / (:k + kw.rank), 0) AS score,
				       coalesce(v.similarity, 0) AS similarity, kw.id IS NOT NULL AS keyword
				FROM merged m
				JOIN brain.knowledge_chunks c ON c.id = m.id
				JOIN brain.knowledge_sources s ON s.organization_id = c.organization_id AND s.id = c.source_row_id
				LEFT JOIN vector_hits v ON v.id = c.id
				LEFT JOIN keyword_hits kw ON kw.id = c.id
				WHERE c.organization_id = :orgId AND c.project_id = :projectId
				  AND (kw.id IS NOT NULL OR v.similarity >= :minSimilarity)
				ORDER BY score DESC, c.id
				LIMIT :limit
				""")
			.param("vector", VectorText.of(queryVector))
			.param("query", query)
			.param("orgId", orgId)
			.param("projectId", projectId)
			.param("candidates", CANDIDATES)
			.param("k", RRF_K)
			.param("minSimilarity", MIN_SIMILARITY)
			.param("limit", limit)
			.query((row, rowNumber) -> new Hit(row.getObject("id", UUID.class), row.getString("content"),
					row.getString("source_type"), row.getObject("source_id", UUID.class), row.getString("title"),
					row.getDouble("score"), row.getDouble("similarity"), row.getBoolean("keyword")))
			.list();
	}

	record Hit(UUID chunkId, String content, String sourceType, UUID sourceId, String title, double score,
			double similarity, boolean keyword) {

		boolean relevant() {
			return keyword || similarity >= MIN_SIMILARITY;
		}

	}

}
