package com.projectbrain.organization.api;

import java.util.UUID;

/** Sets up another module's data for a new organization, inside the creating transaction. */
public interface OrganizationInitializer {

	void initialize(UUID orgId);

}
