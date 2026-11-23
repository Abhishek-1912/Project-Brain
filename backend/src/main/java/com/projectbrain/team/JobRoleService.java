package com.projectbrain.team;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.projectbrain.audit.api.AuditLog;
import com.projectbrain.organization.api.OrganizationInitializer;
import com.projectbrain.organization.api.OrgTx;
import com.projectbrain.organization.api.Permission;
import com.projectbrain.shared.web.ConflictException;
import com.projectbrain.shared.web.NotFoundException;
import com.projectbrain.shared.web.PageParams;
import com.projectbrain.shared.web.PageResponse;
import com.projectbrain.shared.web.UnprocessableException;
import com.projectbrain.team.api.JobRoleLookup;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class JobRoleService implements JobRoleLookup, OrganizationInitializer {

	private static final String ENTITY_TYPE = "JOB_ROLE";

	private static final int MAX_NAME_LENGTH = 60;

	private static final List<String> DEFAULT_ROLES = List.of("Project Manager", "Architect", "Backend", "Frontend",
			"QA", "DevOps");

	private static final String COLUMNS = "id, organization_id, name, active, created_at, updated_at, version";

	private static final RowMapper<JobRole> ROW_MAPPER = (row, rowNumber) -> new JobRole(
			row.getObject("id", UUID.class), row.getObject("organization_id", UUID.class), row.getString("name"),
			row.getBoolean("active"), row.getObject("created_at", OffsetDateTime.class),
			row.getObject("updated_at", OffsetDateTime.class), row.getLong("version"));

	private final OrgTx orgTx;

	private final JdbcClient jdbc;

	private final AuditLog auditLog;

	JobRoleService(OrgTx orgTx, JdbcClient jdbc, AuditLog auditLog) {
		this.orgTx = orgTx;
		this.jdbc = jdbc;
		this.auditLog = auditLog;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void initialize(UUID orgId) {
		for (String name : DEFAULT_ROLES) {
			jdbc.sql("INSERT INTO brain.job_roles (id, organization_id, name) VALUES (:id, :orgId, :name)")
				.param("id", UUID.randomUUID())
				.param("orgId", orgId)
				.param("name", name)
				.update();
		}
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void requireActive(UUID orgId, UUID jobRoleId) {
		boolean active = jdbc.sql("""
				SELECT EXISTS (
				    SELECT 1 FROM brain.job_roles WHERE organization_id = :orgId AND id = :id AND active
				)
				""")
			.param("orgId", orgId)
			.param("id", jobRoleId)
			.query(Boolean.class)
			.single();
		if (!active) {
			throw new UnprocessableException("The job role is not active in this organization.");
		}
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public Optional<UUID> findActiveByName(UUID orgId, String name) {
		return jdbc.sql("""
				SELECT id FROM brain.job_roles
				WHERE organization_id = :orgId AND lower(name) = lower(:name) AND active
				""")
			.param("orgId", orgId)
			.param("name", name)
			.query(UUID.class)
			.optional();
	}

	PageResponse<JobRole> list(UUID orgId, PageParams paging) {
		return orgTx.inOrg(orgId, Permission.VIEW, () -> {
			long total = jdbc.sql("SELECT count(*) FROM brain.job_roles WHERE organization_id = :orgId")
				.param("orgId", orgId)
				.query(Long.class)
				.single();
			List<JobRole> items = jdbc.sql("SELECT " + COLUMNS + """
					 FROM brain.job_roles
					WHERE organization_id = :orgId
					ORDER BY lower(name), id
					LIMIT :limit OFFSET :offset
					""")
				.param("orgId", orgId)
				.param("limit", paging.size())
				.param("offset", paging.offset())
				.query(ROW_MAPPER)
				.list();
			return new PageResponse<>(items, paging.page(), paging.size(), total);
		});
	}

	JobRole create(UUID orgId, String requestedName) {
		String name = cleanName(requestedName);
		return orgTx.inOrg(orgId, Permission.MANAGE_JOB_ROLES, () -> {
			UUID id = UUID.randomUUID();
			try {
				jdbc.sql("INSERT INTO brain.job_roles (id, organization_id, name) VALUES (:id, :orgId, :name)")
					.param("id", id)
					.param("orgId", orgId)
					.param("name", name)
					.update();
			}
			catch (DuplicateKeyException ex) {
				throw duplicateName();
			}
			auditLog.record(orgId, "JOB_ROLE_CREATED", ENTITY_TYPE, id, Map.of("name", name));
			return load(orgId, id);
		});
	}

	JobRole update(UUID orgId, UUID id, String requestedName, Boolean requestedActive, long version) {
		String cleanName = requestedName == null ? null : cleanName(requestedName);
		return orgTx.inOrg(orgId, Permission.MANAGE_JOB_ROLES, () -> {
			JobRole existing = load(orgId, id);
			if (existing.version() != version) {
				throw staleVersion();
			}
			String name = cleanName == null ? existing.name() : cleanName;
			boolean active = requestedActive == null ? existing.active() : requestedActive;
			List<String> changed = new ArrayList<>();
			if (!name.equals(existing.name())) {
				changed.add("name");
			}
			if (active != existing.active()) {
				changed.add("active");
			}
			if (changed.isEmpty()) {
				return existing;
			}
			int updated;
			try {
				updated = jdbc.sql("""
						UPDATE brain.job_roles
						SET name = :name, active = :active, updated_at = now(), version = version + 1
						WHERE organization_id = :orgId AND id = :id AND version = :version
						""")
					.param("name", name)
					.param("active", active)
					.param("orgId", orgId)
					.param("id", id)
					.param("version", version)
					.update();
			}
			catch (DuplicateKeyException ex) {
				throw duplicateName();
			}
			if (updated == 0) {
				throw staleVersion();
			}
			auditLog.record(orgId, "JOB_ROLE_UPDATED", ENTITY_TYPE, id, Map.of("name", name, "changedFields", changed));
			return load(orgId, id);
		});
	}

	private JobRole load(UUID orgId, UUID id) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM brain.job_roles WHERE organization_id = :orgId AND id = :id")
			.param("orgId", orgId)
			.param("id", id)
			.query(ROW_MAPPER)
			.optional()
			.orElseThrow(() -> new NotFoundException("Job role not found."));
	}

	private static String cleanName(String name) {
		String trimmed = name.trim();
		if (trimmed.isEmpty() || trimmed.length() > MAX_NAME_LENGTH) {
			throw new UnprocessableException("Job role name must be 1 to 60 characters.");
		}
		return trimmed;
	}

	private static ConflictException duplicateName() {
		return new ConflictException("A job role with that name already exists.");
	}

	private static ConflictException staleVersion() {
		return new ConflictException("The job role was changed by someone else. Reload and try again.");
	}

	record JobRole(UUID id, UUID organizationId, String name, boolean active, OffsetDateTime createdAt,
			OffsetDateTime updatedAt, long version) {
	}

}
