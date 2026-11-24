package com.projectbrain.organization.api;

import java.util.UUID;
import java.util.function.Supplier;

import com.projectbrain.identity.api.CurrentUser;
import com.projectbrain.identity.api.CurrentUserProvider;
import com.projectbrain.shared.web.ForbiddenException;
import com.projectbrain.shared.web.NotFoundException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Runs work in one transaction with the row-level security context set. */
@Component
public class OrgTx {

	private final TransactionTemplate transactions;

	private final JdbcClient jdbc;

	private final CurrentUserProvider currentUser;

	OrgTx(PlatformTransactionManager transactionManager, JdbcClient jdbc, CurrentUserProvider currentUser) {
		this.transactions = new TransactionTemplate(transactionManager);
		this.jdbc = jdbc;
		this.currentUser = currentUser;
	}

	public <T> T asUser(Supplier<T> work) {
		CurrentUser user = currentUser.get();
		return transactions.execute(status -> {
			setContext(user.id(), "");
			return work.get();
		});
	}

	/** Exposes only the invite matching this hash, for a user who is not yet a member. */
	public <T> T withInvite(String tokenHash, Supplier<T> work) {
		CurrentUser user = currentUser.get();
		return transactions.execute(status -> {
			setContext(user.id(), "");
			jdbc.sql("SELECT set_config('app.current_invite_hash', :tokenHash, true)")
				.param("tokenHash", tokenHash)
				.query(String.class)
				.single();
			return work.get();
		});
	}

	public <T> T inOrg(UUID orgId, Permission required, Supplier<T> work) {
		return inOrgAs(currentUser.get().id(), orgId, required, work);
	}

	/** For background work with no request: re-checks the user's current membership and role. */
	public <T> T inOrgAs(UUID userId, UUID orgId, Permission required, Supplier<T> work) {
		return transactions.execute(status -> {
			setContext(userId, "");
			Role role = jdbc.sql("""
					SELECT role FROM brain.organization_members
					WHERE organization_id = :orgId AND user_id = :userId
					""")
				.param("orgId", orgId)
				.param("userId", userId)
				.query(String.class)
				.optional()
				.map(Role::valueOf)
				.orElseThrow(() -> new NotFoundException("Organization not found."));
			if (!role.allows(required)) {
				throw new ForbiddenException("Your role does not permit this action.");
			}
			setContext(userId, orgId.toString());
			return work.get();
		});
	}

	/**
	 * Skips the membership check, so a background job can record that it failed after the user lost access. Never use
	 * it to read or change business data.
	 */
	public <T> T withOrgContext(UUID userId, UUID orgId, Supplier<T> work) {
		return transactions.execute(status -> {
			setContext(userId, orgId.toString());
			return work.get();
		});
	}

	/** Must run inside inOrg for the same organization, because row-level security limits what it can see. */
	public boolean isMember(UUID orgId, UUID userId) {
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

	/** Must run inside inOrg for the same organization, because row-level security limits what it can see. */
	public boolean hasPermission(UUID orgId, UUID userId, Permission permission) {
		return jdbc.sql("""
				SELECT role FROM brain.organization_members
				WHERE organization_id = :orgId AND user_id = :userId
				""")
			.param("orgId", orgId)
			.param("userId", userId)
			.query(String.class)
			.optional()
			.map(Role::valueOf)
			.filter(role -> role.allows(permission))
			.isPresent();
	}

	private void setContext(UUID userId, String orgId) {
		jdbc.sql("SELECT set_config('app.current_user_id', :userId, true), set_config('app.current_org_id', :orgId, true)")
			.param("userId", userId.toString())
			.param("orgId", orgId)
			.query((row, rowNumber) -> row.getString(1))
			.single();
	}

}
