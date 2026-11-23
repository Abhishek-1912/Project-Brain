package com.projectbrain.organization.api;

import java.util.UUID;

/** Clears another module's references to a member, inside the removal transaction. */
public interface MemberRemovalListener {

	void onMemberRemoved(UUID orgId, UUID userId);

}
