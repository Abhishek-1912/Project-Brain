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

	public <T> T inOrg(UUID orgId, Permission required, Supplier<T> work) {
		CurrentUser user = currentUser.get();
		return transactions.execute(status -> {
			setContext(user.id(), "");
			Role role = jdbc.sql("""
					SELECT role FROM brain.organization_members
					WHERE organization_id = :orgId AND user_id = :userId
					""")
				.param("orgId", orgId)
				.param("userId", user.id())
				.query(String.class)
				.optional()
				.map(Role::valueOf)
				.orElseThrow(() -> new NotFoundException("Organization not found."));
			if (!role.allows(required)) {
				throw new ForbiddenException("Your role does not permit this action.");
			}
			setContext(user.id(), orgId.toString());
			return work.get();
		});
	}

	private void setContext(UUID userId, String orgId) {
		jdbc.sql("SELECT set_config('app.current_user_id', :userId, true), set_config('app.current_org_id', :orgId, true)")
			.param("userId", userId.toString())
			.param("orgId", orgId)
			.query((row, rowNumber) -> row.getString(1))
			.single();
	}

}
