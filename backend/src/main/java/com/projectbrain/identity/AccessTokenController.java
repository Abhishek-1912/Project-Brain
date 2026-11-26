package com.projectbrain.identity;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organizations/{orgId}/access-tokens")
class AccessTokenController {

	private final AccessTokenService tokens;

	AccessTokenController(AccessTokenService tokens) {
		this.tokens = tokens;
	}

	@GetMapping
	AccessTokenService.TokenList list(@PathVariable UUID orgId) {
		return tokens.list(orgId);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	AccessTokenService.CreatedToken create(@PathVariable UUID orgId,
			@RequestBody AccessTokenService.CreateRequest request) {
		return tokens.create(orgId, request);
	}

	@PostMapping("/{tokenId}/revoke")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void revoke(@PathVariable UUID orgId, @PathVariable UUID tokenId) {
		tokens.revoke(orgId, tokenId);
	}

}
