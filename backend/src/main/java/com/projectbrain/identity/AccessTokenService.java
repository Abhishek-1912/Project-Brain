package com.projectbrain.identity;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.projectbrain.audit.api.AuditLog;
import com.projectbrain.identity.api.AccessTokenResolver;
import com.projectbrain.identity.api.CurrentUserProvider;
import com.projectbrain.organization.api.OrgTx;
import com.projectbrain.organization.api.Permission;
import com.projectbrain.shared.web.ConflictException;
import com.projectbrain.shared.web.NotFoundException;
import com.projectbrain.shared.web.UnprocessableException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Each person manages only their own tokens, one organization at a time. */
@Service
class AccessTokenService implements AccessTokenResolver {

	private static final int MAX_ACTIVE = 10;

	private static final int MAX_NAME_CHARS = 100;

	private static final Set<String> SCOPES = Set.of("READ", "READ_WRITE");

	private static final Set<Integer> EXPIRY_DAYS = Set.of(7, 30, 90);

	private final OrgTx orgTx;

	private final JdbcClient jdbc;

	private final CurrentUserProvider currentUser;

	private final AuditLog auditLog;

	AccessTokenService(OrgTx orgTx, JdbcClient jdbc, CurrentUserProvider currentUser, AuditLog auditLog) {
		this.orgTx = orgTx;
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.auditLog = auditLog;
	}

	CreatedToken create(UUID orgId, CreateRequest request) {
		UUID actor = currentUser.get().id();
		String name = request.name() == null ? "" : request.name().strip();
		if (name.isEmpty() || name.length() > MAX_NAME_CHARS) {
			throw new UnprocessableException("Name must be between 1 and 100 characters.");
		}
		if (!SCOPES.contains(request.scope())) {
			throw new UnprocessableException("Scope must be READ or READ_WRITE.");
		}
		if (request.expiresInDays() == null || !EXPIRY_DAYS.contains(request.expiresInDays())) {
			throw new UnprocessableException("Expiry must be 7, 30, or 90 days.");
		}
		return orgTx.inOrg(orgId, Permission.VIEW, () -> {
			long active = jdbc.sql("""
					SELECT count(*) FROM brain.access_tokens
					WHERE organization_id = :orgId AND user_id = :userId AND revoked_at IS NULL AND expires_at > now()
					""")
				.param("orgId", orgId)
				.param("userId", actor)
				.query(Long.class)
				.single();
			if (active >= MAX_ACTIVE) {
				throw new ConflictException("You already have 10 active tokens here. Revoke one first.");
			}
			String token = TokenSecrets.generate();
			UUID id = UUID.randomUUID();
			TokenView view = jdbc.sql("""
					INSERT INTO brain.access_tokens
					    (id, user_id, organization_id, name, token_hash, display_prefix, scope, expires_at)
					VALUES (:id, :userId, :orgId, :name, :hash, :prefix, :scope, now() + make_interval(days => :days))
					RETURNING id, name, scope, display_prefix, expires_at, created_at, last_used_at, revoked_at
					""")
				.param("id", id)
				.param("userId", actor)
				.param("orgId", orgId)
				.param("name", name)
				.param("hash", TokenSecrets.hash(token))
				.param("prefix", token.substring(0, 8))
				.param("scope", request.scope())
				.param("days", request.expiresInDays())
				.query(this::view)
				.single();
			auditLog.record(orgId, "ACCESS_TOKEN_CREATED", "ACCESS_TOKEN", id,
					Map.of("scope", request.scope(), "expiresInDays", request.expiresInDays()));
			return new CreatedToken(view.id(), view.name(), view.scope(), view.displayPrefix(), view.expiresAt(),
					view.createdAt(), token);
		});
	}

	TokenList list(UUID orgId) {
		UUID actor = currentUser.get().id();
		return orgTx.inOrg(orgId, Permission.VIEW, () -> new TokenList(jdbc.sql("""
				SELECT id, name, scope, display_prefix, expires_at, created_at, last_used_at, revoked_at
				FROM brain.access_tokens
				WHERE organization_id = :orgId AND user_id = :userId
				ORDER BY created_at DESC, id
				""")
			.param("orgId", orgId)
			.param("userId", actor)
			.query(this::view)
			.list()));
	}

	void revoke(UUID orgId, UUID tokenId) {
		UUID actor = currentUser.get().id();
		orgTx.inOrg(orgId, Permission.VIEW, () -> {
			Optional<OffsetDateTime> revokedAt = jdbc.sql("""
					SELECT revoked_at FROM brain.access_tokens
					WHERE organization_id = :orgId AND user_id = :userId AND id = :id
					FOR UPDATE
					""")
				.param("orgId", orgId)
				.param("userId", actor)
				.param("id", tokenId)
				.query((row, rowNumber) -> Optional.ofNullable(row.getObject("revoked_at", OffsetDateTime.class)))
				.optional()
				.orElseThrow(() -> new NotFoundException("Access token not found."));
			if (revokedAt.isEmpty()) {
				jdbc.sql("UPDATE brain.access_tokens SET revoked_at = now() WHERE organization_id = :orgId AND id = :id")
					.param("orgId", orgId)
					.param("id", tokenId)
					.update();
				auditLog.record(orgId, "ACCESS_TOKEN_REVOKED", "ACCESS_TOKEN", tokenId, Map.of());
			}
			return null;
		});
	}

	@Override
	public Optional<ResolvedToken> resolve(String presented) {
		if (!TokenSecrets.looksValid(presented)) {
			return Optional.empty();
		}
		return jdbc.sql("SELECT token_id, user_id, org_id, scope FROM brain.resolve_access_token(:hash)")
			.param("hash", TokenSecrets.hash(presented))
			.query((row, rowNumber) -> new ResolvedToken(row.getObject("token_id", UUID.class),
					row.getObject("user_id", UUID.class), row.getObject("org_id", UUID.class), row.getString("scope")))
			.optional();
	}

	private TokenView view(java.sql.ResultSet row, int rowNumber) throws java.sql.SQLException {
		OffsetDateTime expires = row.getObject("expires_at", OffsetDateTime.class);
		OffsetDateTime revoked = row.getObject("revoked_at", OffsetDateTime.class);
		String status = revoked != null ? "REVOKED" : expires.isAfter(OffsetDateTime.now()) ? "ACTIVE" : "EXPIRED";
		return new TokenView(row.getObject("id", UUID.class), row.getString("name"), row.getString("scope"),
				row.getString("display_prefix"), expires, row.getObject("created_at", OffsetDateTime.class),
				row.getObject("last_used_at", OffsetDateTime.class), revoked, status);
	}

	record CreateRequest(String name, String scope, Integer expiresInDays) {
	}

	record CreatedToken(UUID id, String name, String scope, String displayPrefix, OffsetDateTime expiresAt,
			OffsetDateTime createdAt, String token) {
	}

	record TokenView(UUID id, String name, String scope, String displayPrefix, OffsetDateTime expiresAt,
			OffsetDateTime createdAt, OffsetDateTime lastUsedAt, OffsetDateTime revokedAt, String status) {
	}

	record TokenList(List<TokenView> items) {
	}

}
