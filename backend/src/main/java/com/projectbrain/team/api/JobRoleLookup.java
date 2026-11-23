package com.projectbrain.team.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JobRoleLookup {

	/** Must run inside an organization transaction. Throws 422 when the role is missing or inactive. */
	void requireActive(UUID orgId, UUID jobRoleId);

	/** Must run inside an organization transaction. Matches the name ignoring case. */
	Optional<UUID> findActiveByName(UUID orgId, String name);

	/** Must run inside an organization transaction. */
	List<String> activeNames(UUID orgId);

}
