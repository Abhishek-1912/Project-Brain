package com.projectbrain.team.api;

import java.util.UUID;

public interface ProjectTeamLookup {

	/** Must run inside an organization transaction. Viewers never count as approvers. */
	Approvers approvers(UUID orgId, UUID projectId, UUID jobRoleId, UUID submitterId, boolean allowSelfApproval);

}
