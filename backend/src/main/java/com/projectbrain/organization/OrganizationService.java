package com.projectbrain.organization;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.projectbrain.audit.api.AuditLog;
import com.projectbrain.identity.api.CurrentUser;
import com.projectbrain.identity.api.CurrentUserProvider;
import com.projectbrain.identity.api.ProfileProvisioner;
import com.projectbrain.organization.api.OrgTx;
import com.projectbrain.organization.api.Permission;
import com.projectbrain.organization.api.Role;
import com.projectbrain.shared.web.ConflictException;
import com.projectbrain.shared.web.PageParams;
import com.projectbrain.shared.web.PageResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
class OrganizationService {

	private static final String ENTITY_TYPE = "ORGANIZATION";

	private static final String SELECT_FOR_USER = """
			SELECT o.id, o.name, o.slug, m.role, o.created_by, o.created_at, o.updated_at, o.version
			FROM brain.organizations o
			JOIN brain.organization_members m ON m.organization_id = o.id AND m.user_id = :userId
			""";

	private static final RowMapper<Organization> ROW_MAPPER = (row, rowNumber) -> new Organization(
			row.getObject("id", UUID.class), row.getString("name"), row.getString("slug"),
			Role.valueOf(row.getString("role")), row.getObject("created_by", UUID.class),
			row.getObject("created_at", OffsetDateTime.class), row.getObject("updated_at", OffsetDateTime.class),
			row.getLong("version"));

	private final OrgTx orgTx;

	private final JdbcClient jdbc;

	private final CurrentUserProvider currentUser;

	private final ProfileProvisioner profiles;

	private final AuditLog auditLog;

	OrganizationService(OrgTx orgTx, JdbcClient jdbc, CurrentUserProvider currentUser, ProfileProvisioner profiles,
			AuditLog auditLog) {
		this.orgTx = orgTx;
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.profiles = profiles;
		this.auditLog = auditLog;
	}

	PageResponse<Organization> list(PageParams paging) {
		UUID userId = currentUser.get().id();
		return orgTx.asUser(() -> {
			long total = jdbc.sql("SELECT count(*) FROM brain.organization_members WHERE user_id = :userId")
				.param("userId", userId)
				.query(Long.class)
				.single();
			List<Organization> items = jdbc.sql(SELECT_FOR_USER + " ORDER BY lower(o.name), o.id LIMIT :limit OFFSET :offset")
				.param("userId", userId)
				.param("limit", paging.size())
				.param("offset", paging.offset())
				.query(ROW_MAPPER)
				.list();
			return new PageResponse<>(items, paging.page(), paging.size(), total);
		});
	}

	Organization create(String name, String slug) {
		CurrentUser user = currentUser.get();
		return orgTx.asUser(() -> {
			profiles.ensureExists(user);
			UUID orgId = UUID.randomUUID();
			jdbc.sql("SELECT set_config('app.current_org_id', :orgId, true)")
				.param("orgId", orgId.toString())
				.query(String.class)
				.single();
			try {
				jdbc.sql("""
						INSERT INTO brain.organizations (id, name, slug, created_by)
						VALUES (:id, :name, :slug, :createdBy)
						""")
					.param("id", orgId)
					.param("name", name)
					.param("slug", slug)
					.param("createdBy", user.id())
					.update();
			}
			catch (DuplicateKeyException ex) {
				throw new ConflictException("That slug is already in use.");
			}
			jdbc.sql("""
					INSERT INTO brain.organization_members (organization_id, user_id, role)
					VALUES (:orgId, :userId, 'OWNER')
					""")
				.param("orgId", orgId)
				.param("userId", user.id())
				.update();
			auditLog.record(orgId, "ORGANIZATION_CREATED", ENTITY_TYPE, orgId, Map.of("name", name, "slug", slug));
			return load(orgId, user.id());
		});
	}

	Organization get(UUID orgId) {
		UUID userId = currentUser.get().id();
		return orgTx.inOrg(orgId, Permission.VIEW, () -> load(orgId, userId));
	}

	Organization update(UUID orgId, String name, long version) {
		UUID userId = currentUser.get().id();
		return orgTx.inOrg(orgId, Permission.EDIT_ORGANIZATION, () -> {
			Organization existing = load(orgId, userId);
			if (existing.version() != version) {
				throw staleVersion();
			}
			if (name == null || name.equals(existing.name())) {
				return existing;
			}
			int updated = jdbc.sql("""
					UPDATE brain.organizations
					SET name = :name, updated_at = now(), version = version + 1
					WHERE id = :id AND version = :version
					""")
				.param("name", name)
				.param("id", orgId)
				.param("version", version)
				.update();
			if (updated == 0) {
				throw staleVersion();
			}
			auditLog.record(orgId, "ORGANIZATION_UPDATED", ENTITY_TYPE, orgId,
					Map.of("name", name, "previousName", existing.name()));
			return load(orgId, userId);
		});
	}

	private Organization load(UUID orgId, UUID userId) {
		return jdbc.sql(SELECT_FOR_USER + " WHERE o.id = :orgId")
			.param("userId", userId)
			.param("orgId", orgId)
			.query(ROW_MAPPER)
			.single();
	}

	private static ConflictException staleVersion() {
		return new ConflictException("The organization was changed by someone else. Reload and try again.");
	}

	record Organization(UUID id, String name, String slug, Role role, UUID createdBy, OffsetDateTime createdAt,
			OffsetDateTime updatedAt, long version) {
	}

}
