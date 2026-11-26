package com.projectbrain.requirement;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.projectbrain.audit.api.AuditLog;
import com.projectbrain.requirement.api.KnowledgeChanged;
import com.projectbrain.requirement.api.KnowledgeSourceType;
import com.projectbrain.identity.api.CurrentUserProvider;
import com.projectbrain.organization.api.OrgTx;
import com.projectbrain.organization.api.Permission;
import com.projectbrain.project.api.ProjectLookup;
import com.projectbrain.project.api.ProjectStatus;
import com.projectbrain.shared.web.ConflictException;
import com.projectbrain.shared.web.NotFoundException;
import com.projectbrain.shared.web.PageParams;
import com.projectbrain.shared.web.PageResponse;
import com.projectbrain.shared.web.ServiceUnavailableException;
import com.projectbrain.shared.web.UnprocessableException;
import com.projectbrain.team.api.JobRoleLookup;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
class RequirementService {

	private static final long MAX_FILE_BYTES = 10L * 1024 * 1024;

	private static final String DEFAULT_APPROVER_ROLE = "Project Manager";

	private static final Map<String, String> MEDIA_TYPES = Map.of("pdf", "application/pdf", "docx",
			"application/vnd.openxmlformats-officedocument.wordprocessingml.document", "md", "text/markdown", "markdown",
			"text/markdown", "txt", "text/plain");

	private static final Map<String, String> FILE_ERRORS = Map.of("UNSUPPORTED_TYPE",
			"Upload a PDF, Word, Markdown, or plain text file.", "FILE_TOO_LARGE", "The file must be 10 MB or smaller.",
			"NO_TEXT", "No readable text was found. Scanned PDFs aren't supported.", "TEXT_TOO_LONG",
			"The document must have 200,000 characters or fewer.", "CORRUPT_FILE",
			"The file couldn't be read. It may be damaged or password protected.");

	private static final String VIEW_COLUMNS = """
			id, organization_id, project_id, name, media_type, size_bytes, char_length(extracted_text) AS characters,
			approver_job_role_id, uploaded_by, created_at, updated_at, version
			""";

	private static final RowMapper<DocumentView> VIEW_MAPPER = (row, rowNumber) -> new DocumentView(
			row.getObject("id", UUID.class), row.getObject("organization_id", UUID.class),
			row.getObject("project_id", UUID.class), row.getString("name"), row.getString("media_type"),
			row.getInt("size_bytes"), row.getInt("characters"), row.getObject("approver_job_role_id", UUID.class),
			row.getObject("uploaded_by", UUID.class), row.getObject("created_at", OffsetDateTime.class),
			row.getObject("updated_at", OffsetDateTime.class), row.getLong("version"));

	private final OrgTx orgTx;

	private final JdbcClient jdbc;

	private final ProjectLookup projects;

	private final JobRoleLookup jobRoles;

	private final AiServiceClient ai;

	private final CurrentUserProvider currentUser;

	private final AuditLog auditLog;

	private final ApplicationEventPublisher events;

	RequirementService(OrgTx orgTx, JdbcClient jdbc, ProjectLookup projects, JobRoleLookup jobRoles, AiServiceClient ai,
			CurrentUserProvider currentUser, AuditLog auditLog,
			ApplicationEventPublisher events) {
		this.orgTx = orgTx;
		this.jdbc = jdbc;
		this.projects = projects;
		this.jobRoles = jobRoles;
		this.ai = ai;
		this.currentUser = currentUser;
		this.auditLog = auditLog;
		this.events = events;
	}

	PageResponse<DocumentView> list(UUID orgId, UUID projectId, PageParams paging) {
		return orgTx.inOrg(orgId, Permission.VIEW, () -> {
			projects.requireStatus(orgId, projectId);
			long total = jdbc.sql("""
					SELECT count(*) FROM brain.requirement_documents
					WHERE organization_id = :orgId AND project_id = :projectId
					""")
				.param("orgId", orgId)
				.param("projectId", projectId)
				.query(Long.class)
				.single();
			List<DocumentView> items = jdbc.sql("SELECT " + VIEW_COLUMNS + """
					FROM brain.requirement_documents
					WHERE organization_id = :orgId AND project_id = :projectId
					ORDER BY created_at DESC, id
					LIMIT :limit OFFSET :offset
					""")
				.param("orgId", orgId)
				.param("projectId", projectId)
				.param("limit", paging.size())
				.param("offset", paging.offset())
				.query(VIEW_MAPPER)
				.list();
			return new PageResponse<>(items, paging.page(), paging.size(), total);
		});
	}

	DocumentView get(UUID orgId, UUID documentId) {
		return orgTx.inOrg(orgId, Permission.VIEW, () -> load(orgId, documentId));
	}

	DocumentView upload(UUID orgId, UUID projectId, MultipartFile file, boolean dummyDataConfirmed,
			UUID requestedApprover) {
		UUID actor = currentUser.get().id();
		String name = cleanName(file.getOriginalFilename());
		String mediaType = mediaType(name);
		orgTx.inOrg(orgId, Permission.MANAGE_PROJECTS, () -> {
			requireWritable(orgId, projectId);
			if (!dummyDataConfirmed) {
				throw new UnprocessableException("Confirm that this document contains only dummy test data.");
			}
			if (file.isEmpty() || file.getSize() > MAX_FILE_BYTES) {
				throw new UnprocessableException(FILE_ERRORS.get("FILE_TOO_LARGE"));
			}
			if (mediaType == null) {
				throw new UnprocessableException(FILE_ERRORS.get("UNSUPPORTED_TYPE"));
			}
			return resolveApprover(orgId, requestedApprover);
		});

		byte[] content = bytes(file);
		// PostgreSQL text cannot store NUL characters, which some PDFs produce.
		String text = extract(name, mediaType, content).replace("\u0000", "").strip();
		if (text.isEmpty()) {
			throw new UnprocessableException(FILE_ERRORS.get("NO_TEXT"));
		}

		return orgTx.inOrg(orgId, Permission.MANAGE_PROJECTS, () -> {
			requireWritable(orgId, projectId);
			UUID approver = resolveApprover(orgId, requestedApprover);
			UUID id = UUID.randomUUID();
			jdbc.sql("""
					INSERT INTO brain.requirement_documents
					    (id, organization_id, project_id, name, media_type, size_bytes, sha256, extracted_text,
					     dummy_data_confirmed, approver_job_role_id, uploaded_by)
					VALUES (:id, :orgId, :projectId, :name, :mediaType, :size, :sha256, :text, true, :approver, :actor)
					""")
				.param("id", id)
				.param("orgId", orgId)
				.param("projectId", projectId)
				.param("name", name)
				.param("mediaType", mediaType)
				.param("size", content.length)
				.param("sha256", sha256(content))
				.param("text", text)
				.param("approver", approver, java.sql.Types.OTHER)
				.param("actor", actor)
				.update();
			jdbc.sql("""
					INSERT INTO brain.requirement_document_files (document_id, organization_id, content)
					VALUES (:id, :orgId, :content)
					""")
				.param("id", id)
				.param("orgId", orgId)
				.param("content", content)
				.update();
			auditLog.record(orgId, "REQUIREMENT_UPLOADED", "REQUIREMENT_DOCUMENT", id,
					Map.of("name", name, "sizeBytes", content.length, "dummyDataConfirmed", true));
			events.publishEvent(new KnowledgeChanged(orgId, projectId, KnowledgeSourceType.DOCUMENT, id, actor));
			return load(orgId, id);
		});
	}

	private String extract(String name, String mediaType, byte[] content) {
		try {
			return ai.extract(name, mediaType, content).text();
		}
		catch (AiServiceException ex) {
			String message = FILE_ERRORS.get(ex.code());
			if (message != null) {
				throw new UnprocessableException(message);
			}
			throw new ServiceUnavailableException("The AI service is unavailable. Try again shortly.");
		}
	}

	private UUID resolveApprover(UUID orgId, UUID requested) {
		if (requested != null) {
			jobRoles.requireActive(orgId, requested);
			return requested;
		}
		return jobRoles.findActiveByName(orgId, DEFAULT_APPROVER_ROLE).orElse(null);
	}

	private void requireWritable(UUID orgId, UUID projectId) {
		if (projects.requireStatus(orgId, projectId) == ProjectStatus.ARCHIVED) {
			throw new ConflictException("The project is archived.");
		}
	}

	private DocumentView load(UUID orgId, UUID documentId) {
		return jdbc.sql("SELECT " + VIEW_COLUMNS + """
				FROM brain.requirement_documents
				WHERE organization_id = :orgId AND id = :id
				""")
			.param("orgId", orgId)
			.param("id", documentId)
			.query(VIEW_MAPPER)
			.optional()
			.orElseThrow(() -> new NotFoundException("Requirement document not found."));
	}

	private static byte[] bytes(MultipartFile file) {
		try {
			return file.getBytes();
		}
		catch (IOException ex) {
			throw new UnprocessableException(FILE_ERRORS.get("CORRUPT_FILE"));
		}
	}

	private static String cleanName(String original) {
		String value = original == null ? "" : original.replace('\\', '/');
		value = value.substring(value.lastIndexOf('/') + 1).replaceAll("\\p{Cntrl}", "").strip();
		if (value.isEmpty()) {
			return "document";
		}
		return value.length() <= 255 ? value : value.substring(value.length() - 255);
	}

	private static String mediaType(String name) {
		int dot = name.lastIndexOf('.');
		return dot < 0 ? null : MEDIA_TYPES.get(name.substring(dot + 1).toLowerCase(Locale.ROOT));
	}

	private static String sha256(byte[] content) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is unavailable", ex);
		}
	}

	record DocumentView(UUID id, UUID organizationId, UUID projectId, String name, String mediaType, int sizeBytes,
			int characters, UUID approverJobRoleId, UUID uploadedBy, OffsetDateTime createdAt, OffsetDateTime updatedAt,
			long version) {
	}

}
