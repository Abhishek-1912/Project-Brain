package com.projectbrain.knowledge;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.projectbrain.audit.api.AuditLog;
import com.projectbrain.identity.api.CurrentUserProvider;
import com.projectbrain.organization.api.OrgTx;
import com.projectbrain.organization.api.Permission;
import com.projectbrain.project.api.ProjectLookup;
import com.projectbrain.project.api.ProjectStatus;
import com.projectbrain.requirement.api.KnowledgeRef;
import com.projectbrain.requirement.api.KnowledgeSourceLookup;
import com.projectbrain.requirement.api.KnowledgeSourceType;
import com.projectbrain.shared.web.ConflictException;
import com.projectbrain.shared.web.PageParams;
import com.projectbrain.shared.web.PageResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
class KnowledgeSourceStore {

	private final OrgTx orgTx;

	private final JdbcClient jdbc;

	private final ProjectLookup projects;

	private final KnowledgeSourceLookup lookup;

	private final KnowledgeIndexer indexer;

	private final CurrentUserProvider currentUser;

	private final AuditLog auditLog;

	KnowledgeSourceStore(OrgTx orgTx, JdbcClient jdbc, ProjectLookup projects, KnowledgeSourceLookup lookup,
			KnowledgeIndexer indexer, CurrentUserProvider currentUser, AuditLog auditLog) {
		this.orgTx = orgTx;
		this.jdbc = jdbc;
		this.projects = projects;
		this.lookup = lookup;
		this.indexer = indexer;
		this.currentUser = currentUser;
		this.auditLog = auditLog;
	}

	PageResponse<SourceView> list(UUID orgId, UUID projectId, PageParams paging) {
		return orgTx.inOrg(orgId, Permission.VIEW, () -> {
			projects.requireStatus(orgId, projectId);
			long total = jdbc.sql("""
					SELECT count(*) FROM brain.knowledge_sources WHERE organization_id = :orgId AND project_id = :projectId
					""")
				.param("orgId", orgId)
				.param("projectId", projectId)
				.query(Long.class)
				.single();
			List<SourceView> items = jdbc.sql("""
					SELECT s.id, s.source_type, s.source_id, s.title, s.status, s.error_code, s.indexed_at, s.updated_at,
					       (SELECT count(*) FROM brain.knowledge_chunks c
					        WHERE c.organization_id = s.organization_id AND c.source_row_id = s.id) AS chunk_count
					FROM brain.knowledge_sources s
					WHERE s.organization_id = :orgId AND s.project_id = :projectId
					ORDER BY s.updated_at DESC, s.id
					LIMIT :limit OFFSET :offset
					""")
				.param("orgId", orgId)
				.param("projectId", projectId)
				.param("limit", paging.size())
				.param("offset", paging.offset())
				.query((row, rowNumber) -> new SourceView(row.getObject("id", UUID.class), row.getString("source_type"),
						row.getObject("source_id", UUID.class), row.getString("title"), row.getString("status"),
						row.getString("error_code"), row.getInt("chunk_count"),
						row.getObject("indexed_at", OffsetDateTime.class),
						row.getObject("updated_at", OffsetDateTime.class)))
				.list();
			return new PageResponse<>(items, paging.page(), paging.size(), total);
		});
	}

	RebuildResult rebuild(UUID orgId, UUID projectId) {
		UUID actor = currentUser.get().id();
		List<KnowledgeRef> refs = orgTx.inOrg(orgId, Permission.MANAGE_PROJECTS, () -> {
			if (projects.requireStatus(orgId, projectId) == ProjectStatus.ARCHIVED) {
				throw new ConflictException("The project is archived.");
			}
			List<KnowledgeRef> qualifying = new ArrayList<>(lookup.qualifying(orgId, projectId));
			jdbc.sql("SELECT id FROM brain.knowledge_notes WHERE organization_id = :orgId AND project_id = :projectId")
				.param("orgId", orgId)
				.param("projectId", projectId)
				.query(UUID.class)
				.list()
				.forEach(id -> qualifying.add(new KnowledgeRef(KnowledgeSourceType.NOTE, id)));
			removeOthers(orgId, projectId, new HashSet<>(qualifying));
			auditLog.record(orgId, "KNOWLEDGE_REBUILT", "PROJECT", projectId, Map.of("queued", qualifying.size()));
			return qualifying;
		});
		refs.forEach(ref -> indexer.submit(orgId, projectId, ref.type(), ref.sourceId(), actor));
		return new RebuildResult(refs.size());
	}

	private void removeOthers(UUID orgId, UUID projectId, Set<KnowledgeRef> keep) {
		jdbc.sql("""
				SELECT id, source_type, source_id FROM brain.knowledge_sources
				WHERE organization_id = :orgId AND project_id = :projectId
				""")
			.param("orgId", orgId)
			.param("projectId", projectId)
			.query((row, rowNumber) -> Map.entry(row.getObject("id", UUID.class),
					new KnowledgeRef(KnowledgeSourceType.valueOf(row.getString("source_type")),
							row.getObject("source_id", UUID.class))))
			.list()
			.stream()
			.filter(entry -> !keep.contains(entry.getValue()))
			.forEach(entry -> jdbc.sql("DELETE FROM brain.knowledge_sources WHERE organization_id = :orgId AND id = :id")
				.param("orgId", orgId)
				.param("id", entry.getKey())
				.update());
	}

	record SourceView(UUID id, String sourceType, UUID sourceId, String title, String status, String errorCode,
			int chunkCount, OffsetDateTime indexedAt, OffsetDateTime updatedAt) {
	}

	record RebuildResult(int queued) {
	}

}
