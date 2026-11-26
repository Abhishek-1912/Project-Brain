package com.projectbrain.knowledge;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.projectbrain.audit.api.AuditLog;
import com.projectbrain.identity.api.CurrentUserProvider;
import com.projectbrain.organization.api.OrgTx;
import com.projectbrain.organization.api.Permission;
import com.projectbrain.project.api.ProjectLookup;
import com.projectbrain.project.api.ProjectStatus;
import com.projectbrain.requirement.api.KnowledgeSourceType;
import com.projectbrain.shared.web.ConflictException;
import com.projectbrain.shared.web.NotFoundException;
import com.projectbrain.shared.web.PageParams;
import com.projectbrain.shared.web.PageResponse;
import com.projectbrain.shared.web.UnprocessableException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Notes are written by people, so they are indexed without a separate approval step. */
@Service
class KnowledgeNoteService {

	private static final int MAX_TITLE_CHARS = 200;

	private static final int MAX_BODY_CHARS = 20000;

	private static final String COLUMNS = """
			id, project_id, title, body, created_by, updated_by, created_at, updated_at, version
			""";

	private static final RowMapper<NoteView> MAPPER = (row, rowNumber) -> new NoteView(row.getObject("id", UUID.class),
			row.getObject("project_id", UUID.class), row.getString("title"), row.getString("body"),
			row.getObject("created_by", UUID.class), row.getObject("updated_by", UUID.class),
			row.getObject("created_at", OffsetDateTime.class), row.getObject("updated_at", OffsetDateTime.class),
			row.getLong("version"));

	private final OrgTx orgTx;

	private final JdbcClient jdbc;

	private final ProjectLookup projects;

	private final KnowledgeIndexer indexer;

	private final CurrentUserProvider currentUser;

	private final AuditLog auditLog;

	KnowledgeNoteService(OrgTx orgTx, JdbcClient jdbc, ProjectLookup projects, KnowledgeIndexer indexer,
			CurrentUserProvider currentUser, AuditLog auditLog) {
		this.orgTx = orgTx;
		this.jdbc = jdbc;
		this.projects = projects;
		this.indexer = indexer;
		this.currentUser = currentUser;
		this.auditLog = auditLog;
	}

	PageResponse<NoteView> list(UUID orgId, UUID projectId, PageParams paging) {
		return orgTx.inOrg(orgId, Permission.VIEW, () -> {
			projects.requireStatus(orgId, projectId);
			long total = jdbc.sql("""
					SELECT count(*) FROM brain.knowledge_notes WHERE organization_id = :orgId AND project_id = :projectId
					""")
				.param("orgId", orgId)
				.param("projectId", projectId)
				.query(Long.class)
				.single();
			List<NoteView> items = jdbc.sql("SELECT " + COLUMNS + """
					FROM brain.knowledge_notes
					WHERE organization_id = :orgId AND project_id = :projectId
					ORDER BY updated_at DESC, id
					LIMIT :limit OFFSET :offset
					""")
				.param("orgId", orgId)
				.param("projectId", projectId)
				.param("limit", paging.size())
				.param("offset", paging.offset())
				.query(MAPPER)
				.list();
			return new PageResponse<>(items, paging.page(), paging.size(), total);
		});
	}

	NoteView get(UUID orgId, UUID noteId) {
		return orgTx.inOrg(orgId, Permission.VIEW, () -> load(orgId, noteId, false));
	}

	NoteView create(UUID orgId, UUID projectId, NoteRequest request) {
		UUID actor = currentUser.get().id();
		String title = clean(request.title(), MAX_TITLE_CHARS, "Title");
		String body = clean(request.body(), MAX_BODY_CHARS, "Body");
		NoteView note = orgTx.inOrg(orgId, Permission.MANAGE_PROJECTS, () -> {
			requireWritable(orgId, projectId);
			UUID id = UUID.randomUUID();
			jdbc.sql("""
					INSERT INTO brain.knowledge_notes (id, organization_id, project_id, title, body, created_by, updated_by)
					VALUES (:id, :orgId, :projectId, :title, :body, :actor, :actor)
					""")
				.param("id", id)
				.param("orgId", orgId)
				.param("projectId", projectId)
				.param("title", title)
				.param("body", body)
				.param("actor", actor)
				.update();
			auditLog.record(orgId, "KNOWLEDGE_NOTE_CREATED", "KNOWLEDGE_NOTE", id,
					Map.of("titleLength", title.length()));
			return load(orgId, id, false);
		});
		indexer.submit(orgId, projectId, KnowledgeSourceType.NOTE, note.id(), actor);
		return note;
	}

	NoteView update(UUID orgId, UUID noteId, NotePatch patch) {
		UUID actor = currentUser.get().id();
		NoteView note = orgTx.inOrg(orgId, Permission.MANAGE_PROJECTS, () -> {
			NoteView current = load(orgId, noteId, true);
			requireWritable(orgId, current.projectId());
			if (patch.version() == null || patch.version() != current.version()) {
				throw new ConflictException("This note changed. Reload to see the latest version.");
			}
			String title = patch.title() == null ? current.title() : clean(patch.title(), MAX_TITLE_CHARS, "Title");
			String body = patch.body() == null ? current.body() : clean(patch.body(), MAX_BODY_CHARS, "Body");
			jdbc.sql("""
					UPDATE brain.knowledge_notes
					SET title = :title, body = :body, updated_by = :actor, updated_at = now(), version = version + 1
					WHERE organization_id = :orgId AND id = :id
					""")
				.param("title", title)
				.param("body", body)
				.param("actor", actor)
				.param("orgId", orgId)
				.param("id", noteId)
				.update();
			auditLog.record(orgId, "KNOWLEDGE_NOTE_UPDATED", "KNOWLEDGE_NOTE", noteId,
					Map.of("titleLength", title.length()));
			return load(orgId, noteId, false);
		});
		indexer.submit(orgId, note.projectId(), KnowledgeSourceType.NOTE, noteId, actor);
		return note;
	}

	void delete(UUID orgId, UUID noteId) {
		orgTx.inOrg(orgId, Permission.MANAGE_PROJECTS, () -> {
			NoteView current = load(orgId, noteId, true);
			requireWritable(orgId, current.projectId());
			// Removing the source in the same transaction makes the note unsearchable the moment it is deleted.
			jdbc.sql("""
					DELETE FROM brain.knowledge_sources
					WHERE organization_id = :orgId AND project_id = :projectId AND source_type = 'NOTE' AND source_id = :id
					""")
				.param("orgId", orgId)
				.param("projectId", current.projectId())
				.param("id", noteId)
				.update();
			jdbc.sql("DELETE FROM brain.knowledge_notes WHERE organization_id = :orgId AND id = :id")
				.param("orgId", orgId)
				.param("id", noteId)
				.update();
			auditLog.record(orgId, "KNOWLEDGE_NOTE_DELETED", "KNOWLEDGE_NOTE", noteId,
					Map.of("titleLength", current.title().length()));
			return null;
		});
	}

	private void requireWritable(UUID orgId, UUID projectId) {
		if (projects.requireStatus(orgId, projectId) == ProjectStatus.ARCHIVED) {
			throw new ConflictException("The project is archived.");
		}
	}

	private NoteView load(UUID orgId, UUID noteId, boolean forUpdate) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM brain.knowledge_notes WHERE organization_id = :orgId AND id = :id"
				+ (forUpdate ? " FOR UPDATE" : ""))
			.param("orgId", orgId)
			.param("id", noteId)
			.query(MAPPER)
			.optional()
			.orElseThrow(() -> new NotFoundException("Note not found."));
	}

	private static String clean(String value, int max, String label) {
		String cleaned = value == null ? "" : value.strip();
		if (cleaned.isEmpty() || cleaned.length() > max) {
			throw new UnprocessableException(label + " must be between 1 and " + String.format("%,d", max)
					+ " characters.");
		}
		return cleaned;
	}

	record NoteView(UUID id, UUID projectId, String title, String body, UUID createdBy, UUID updatedBy,
			OffsetDateTime createdAt, OffsetDateTime updatedAt, long version) {
	}

	record NoteRequest(String title, String body) {
	}

	record NotePatch(String title, String body, Long version) {
	}

}
