package com.projectbrain.project.api;

import java.util.UUID;

public interface ProjectLookup {

	/** Must run inside OrgTx.inOrg. Throws NotFoundException when the project is not in the organization. */
	ProjectStatus requireStatus(UUID orgId, UUID projectId);

}
