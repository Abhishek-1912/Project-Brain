package com.projectbrain.team.api;

import java.util.Set;
import java.util.UUID;

/** People who can decide a review. An empty set means nobody is currently eligible. */
public record Approvers(Set<UUID> userIds, ApprovalBasis basis) {

	public Approvers {
		userIds = Set.copyOf(userIds);
	}

}
