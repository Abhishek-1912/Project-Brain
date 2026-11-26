package com.projectbrain.identity.api;

import java.util.Optional;
import java.util.UUID;

/** Turns a presented personal access token into its user, organization, and scope, if it is active. */
public interface AccessTokenResolver {

	Optional<ResolvedToken> resolve(String presented);

	record ResolvedToken(UUID tokenId, UUID userId, UUID orgId, String scope) {

		public boolean canWrite() {
			return "READ_WRITE".equals(scope);
		}

	}

}
