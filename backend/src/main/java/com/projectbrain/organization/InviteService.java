package com.projectbrain.organization;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
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
import com.projectbrain.shared.web.ForbiddenException;
import com.projectbrain.shared.web.NotFoundException;
import com.projectbrain.shared.web.PageParams;
import com.projectbrain.shared.web.PageResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
class InviteService {

	private static final String ENTITY_TYPE = "INVITE";

	private static final int MAX_TOKEN_LENGTH = 128;

	private static final String INVALID_INVITE = "This invite is not valid.";

	private final OrgTx orgTx;

	private final JdbcClient jdbc;

	private final CurrentUserProvider currentUser;

	private final ProfileProvisioner profiles;

	private final AuditLog auditLog;

	InviteService(OrgTx orgTx, JdbcClient jdbc, CurrentUserProvider currentUser, ProfileProvisioner profiles,
			AuditLog auditLog) {
		this.orgTx = orgTx;
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.profiles = profiles;
		this.auditLog = auditLog;
	}

	PageResponse<Invite> listPending(UUID orgId, PageParams paging) {
		return orgTx.inOrg(orgId, Permission.INVITE_MEMBERS, () -> {
			long total = jdbc.sql("""
					SELECT count(*) FROM brain.organization_invites
					WHERE organization_id = :orgId AND accepted_at IS NULL AND cancelled_at IS NULL
					""")
				.param("orgId", orgId)
				.query(Long.class)
				.single();
			List<Invite> items = jdbc.sql("""
					SELECT id, email, role, expires_at, created_at, expires_at <= now() AS expired
					FROM brain.organization_invites
					WHERE organization_id = :orgId AND accepted_at IS NULL AND cancelled_at IS NULL
					ORDER BY created_at DESC, id
					LIMIT :limit OFFSET :offset
					""")
				.param("orgId", orgId)
				.param("limit", paging.size())
				.param("offset", paging.offset())
				.query((row, rowNumber) -> new Invite(row.getObject("id", UUID.class), row.getString("email"),
						Role.valueOf(row.getString("role")), row.getBoolean("expired") ? "EXPIRED" : "PENDING",
						row.getObject("expires_at", OffsetDateTime.class),
						row.getObject("created_at", OffsetDateTime.class)))
				.list();
			return new PageResponse<>(items, paging.page(), paging.size(), total);
		});
	}

	CreatedInvite create(UUID orgId, String requestedEmail, Role role) {
		String email = requestedEmail.trim().toLowerCase(Locale.ROOT);
		UUID actor = currentUser.get().id();
		String token = InviteToken.generate();
		return orgTx.inOrg(orgId, Permission.INVITE_MEMBERS, () -> {
			requireCanManageInviteRole(orgId, actor, role);
			boolean alreadyMember = jdbc.sql("""
					SELECT EXISTS (
					    SELECT 1 FROM brain.organization_members m
					    JOIN brain.profiles p ON p.id = m.user_id
					    WHERE m.organization_id = :orgId AND lower(p.email) = :email
					)
					""")
				.param("orgId", orgId)
				.param("email", email)
				.query(Boolean.class)
				.single();
			if (alreadyMember) {
				throw new ConflictException("That person is already a member.");
			}
			// An expired invite still holds the pending slot for this email.
			jdbc.sql("""
					UPDATE brain.organization_invites
					SET cancelled_by = :actor, cancelled_at = now()
					WHERE organization_id = :orgId AND lower(email) = :email
					  AND accepted_at IS NULL AND cancelled_at IS NULL AND expires_at <= now()
					""")
				.param("actor", actor)
				.param("orgId", orgId)
				.param("email", email)
				.update();
			UUID id = UUID.randomUUID();
			try {
				jdbc.sql("""
						INSERT INTO brain.organization_invites
						    (id, organization_id, email, role, token_hash, created_by, expires_at)
						VALUES (:id, :orgId, :email, :role, :tokenHash, :actor, now() + interval '7 days')
						""")
					.param("id", id)
					.param("orgId", orgId)
					.param("email", email)
					.param("role", role.name())
					.param("tokenHash", InviteToken.hash(token))
					.param("actor", actor)
					.update();
			}
			catch (DuplicateKeyException ex) {
				throw new ConflictException("That email already has a pending invite.");
			}
			auditLog.record(orgId, "INVITE_CREATED", ENTITY_TYPE, id, Map.of("name", email, "role", role.name()));
			return jdbc.sql("SELECT email, role, expires_at, created_at FROM brain.organization_invites WHERE id = :id")
				.param("id", id)
				.query((row, rowNumber) -> new CreatedInvite(id, row.getString("email"),
						Role.valueOf(row.getString("role")), "PENDING", row.getObject("expires_at", OffsetDateTime.class),
						row.getObject("created_at", OffsetDateTime.class), token))
				.single();
		});
	}

	void cancel(UUID orgId, UUID inviteId) {
		UUID actor = currentUser.get().id();
		orgTx.inOrg(orgId, Permission.INVITE_MEMBERS, () -> {
			InviteState invite = jdbc.sql("""
					SELECT email, role, accepted_at IS NULL AND cancelled_at IS NULL AS pending
					FROM brain.organization_invites
					WHERE organization_id = :orgId AND id = :id
					FOR UPDATE
					""")
				.param("orgId", orgId)
				.param("id", inviteId)
				.query((row, rowNumber) -> new InviteState(row.getString("email"), Role.valueOf(row.getString("role")),
						row.getBoolean("pending")))
				.optional()
				.orElseThrow(() -> new NotFoundException("Invite not found."));
			requireCanManageInviteRole(orgId, actor, invite.role());
			if (!invite.pending()) {
				throw new ConflictException("This invite is no longer pending.");
			}
			jdbc.sql("""
					UPDATE brain.organization_invites
					SET cancelled_by = :actor, cancelled_at = now()
					WHERE organization_id = :orgId AND id = :id
					""")
				.param("actor", actor)
				.param("orgId", orgId)
				.param("id", inviteId)
				.update();
			auditLog.record(orgId, "INVITE_CANCELLED", ENTITY_TYPE, inviteId,
					Map.of("name", invite.email(), "role", invite.role().name()));
			return null;
		});
	}

	AcceptedInvite accept(String token) {
		if (token.length() > MAX_TOKEN_LENGTH) {
			throw invalidInvite();
		}
		CurrentUser user = currentUser.get();
		String tokenHash = InviteToken.hash(token);
		return orgTx.withInvite(tokenHash, () -> {
			profiles.ensureExists(user);
			// The token holds the current confirmed email, while the profile may predate an email change.
			jdbc.sql("""
					UPDATE brain.profiles
					SET email = :email, updated_at = now(), version = version + 1
					WHERE id = :id AND email <> :email
					""")
				.param("email", user.email())
				.param("id", user.id())
				.update();
			PendingInvite invite = jdbc.sql("""
					SELECT id, organization_id, email, role
					FROM brain.organization_invites
					WHERE token_hash = :tokenHash
					  AND accepted_at IS NULL AND cancelled_at IS NULL AND expires_at > now()
					FOR UPDATE
					""")
				.param("tokenHash", tokenHash)
				.query((row, rowNumber) -> new PendingInvite(row.getObject("id", UUID.class),
						row.getObject("organization_id", UUID.class), row.getString("email"),
						Role.valueOf(row.getString("role"))))
				.optional()
				.orElseThrow(InviteService::invalidInvite);
			if (!invite.email().equalsIgnoreCase(user.email()) || isMember(invite.organizationId(), user.id())) {
				throw invalidInvite();
			}
			jdbc.sql("""
					INSERT INTO brain.organization_members (organization_id, user_id, role)
					VALUES (:orgId, :userId, :role)
					""")
				.param("orgId", invite.organizationId())
				.param("userId", user.id())
				.param("role", invite.role().name())
				.update();
			jdbc.sql("""
					UPDATE brain.organization_invites
					SET accepted_by = :userId, accepted_at = now()
					WHERE id = :id
					""")
				.param("userId", user.id())
				.param("id", invite.id())
				.update();
			jdbc.sql("SELECT set_config('app.current_org_id', :orgId, true)")
				.param("orgId", invite.organizationId().toString())
				.query(String.class)
				.single();
			auditLog.record(invite.organizationId(), "INVITE_ACCEPTED", ENTITY_TYPE, invite.id(),
					Map.of("name", invite.email(), "role", invite.role().name()));
			return new AcceptedInvite(invite.organizationId());
		});
	}

	private boolean isMember(UUID orgId, UUID userId) {
		return jdbc.sql("""
				SELECT EXISTS (
				    SELECT 1 FROM brain.organization_members
				    WHERE organization_id = :orgId AND user_id = :userId
				)
				""")
			.param("orgId", orgId)
			.param("userId", userId)
			.query(Boolean.class)
			.single();
	}

	private void requireCanManageInviteRole(UUID orgId, UUID actor, Role inviteRole) {
		if (inviteRole != Role.ADMIN) {
			return;
		}
		String actorRole = jdbc.sql("""
				SELECT role FROM brain.organization_members
				WHERE organization_id = :orgId AND user_id = :userId
				""")
			.param("orgId", orgId)
			.param("userId", actor)
			.query(String.class)
			.single();
		if (Role.valueOf(actorRole) != Role.OWNER) {
			throw new ForbiddenException("Only an owner can manage admin invites.");
		}
	}

	private static NotFoundException invalidInvite() {
		return new NotFoundException(INVALID_INVITE);
	}

	private record InviteState(String email, Role role, boolean pending) {
	}

	private record PendingInvite(UUID id, UUID organizationId, String email, Role role) {
	}

	record Invite(UUID id, String email, Role role, String status, OffsetDateTime expiresAt,
			OffsetDateTime createdAt) {
	}

	record CreatedInvite(UUID id, String email, Role role, String status, OffsetDateTime expiresAt,
			OffsetDateTime createdAt, String token) {
	}

	record AcceptedInvite(UUID organizationId) {
	}

}
