package com.projectbrain.identity;

import com.projectbrain.identity.api.CurrentUserProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class MeController {

	private final CurrentUserProvider currentUser;

	private final ProfileService profiles;

	MeController(CurrentUserProvider currentUser, ProfileService profiles) {
		this.currentUser = currentUser;
		this.profiles = profiles;
	}

	@GetMapping("/api/v1/me")
	ProfileService.Me me() {
		return profiles.getOrCreate(currentUser.get());
	}

}
