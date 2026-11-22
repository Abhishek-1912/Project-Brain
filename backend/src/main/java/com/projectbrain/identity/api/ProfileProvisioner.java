package com.projectbrain.identity.api;

public interface ProfileProvisioner {

	/** Creates the profile if absent. Must run in a transaction with the user context set. */
	void ensureExists(CurrentUser user);

}
