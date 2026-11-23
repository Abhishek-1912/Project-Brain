package com.projectbrain.organization.api;

import java.util.UUID;

public interface ApprovalSettings {

	/** Must run inside OrgTx.inOrg for this organization. */
	boolean allowsSelfApproval(UUID orgId);

}
