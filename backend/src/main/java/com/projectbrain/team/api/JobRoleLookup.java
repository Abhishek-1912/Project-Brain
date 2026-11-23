package com.projectbrain.team.api;

import java.util.UUID;

public interface JobRoleLookup {

	/** Must run inside an organization transaction. Throws 422 when the role is missing or inactive. */
	void requireActive(UUID orgId, UUID jobRoleId);

}
