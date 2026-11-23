package com.projectbrain.team.api;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class ApproverPolicy {

	private ApproverPolicy() {
	}

	public static Approvers resolve(Set<UUID> roleHolders, Set<UUID> owners, UUID submitterId,
			boolean allowSelfApproval) {
		Set<UUID> eligibleHolders = withoutSubmitter(roleHolders, submitterId, allowSelfApproval);
		if (!eligibleHolders.isEmpty()) {
			return new Approvers(eligibleHolders, ApprovalBasis.ROLE_HOLDER);
		}
		return new Approvers(withoutSubmitter(owners, submitterId, allowSelfApproval), ApprovalBasis.OWNER_FALLBACK);
	}

	private static Set<UUID> withoutSubmitter(Set<UUID> people, UUID submitterId, boolean allowSelfApproval) {
		Set<UUID> eligible = new HashSet<>(people);
		if (!allowSelfApproval) {
			eligible.remove(submitterId);
		}
		return eligible;
	}

}
