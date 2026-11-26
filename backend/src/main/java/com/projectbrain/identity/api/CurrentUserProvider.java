package com.projectbrain.identity.api;

import java.util.Optional;
import java.util.UUID;

import com.projectbrain.identity.api.AccessTokenResolver.ResolvedToken;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/** The only place that knows whether the user came from a Supabase JWT or a personal access token. */
@Component
public class CurrentUserProvider {

	public CurrentUser get() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication instanceof JwtAuthenticationToken token) {
			Jwt jwt = token.getToken();
			return new CurrentUser(UUID.fromString(jwt.getSubject()), jwt.getClaimAsString("email"));
		}
		if (authentication instanceof AccessTokenAuthentication token) {
			return new CurrentUser(token.token().userId(), null);
		}
		throw new IllegalStateException("No authenticated user in the security context");
	}

	/** The personal access token behind this request, when it came through MCP. */
	public Optional<ResolvedToken> accessToken() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		return authentication instanceof AccessTokenAuthentication token ? Optional.of(token.token()) : Optional.empty();
	}

}
