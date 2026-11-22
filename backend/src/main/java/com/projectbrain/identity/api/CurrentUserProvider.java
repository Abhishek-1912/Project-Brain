package com.projectbrain.identity.api;

import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/** The only place that knows the user comes from a JWT, so the token issuer can change. */
@Component
public class CurrentUserProvider {

	public CurrentUser get() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication instanceof JwtAuthenticationToken token) {
			Jwt jwt = token.getToken();
			return new CurrentUser(UUID.fromString(jwt.getSubject()), jwt.getClaimAsString("email"));
		}
		throw new IllegalStateException("No authenticated user in the security context");
	}

}
