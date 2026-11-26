package com.projectbrain.identity.api;

import java.util.List;

import com.projectbrain.identity.api.AccessTokenResolver.ResolvedToken;
import org.springframework.security.authentication.AbstractAuthenticationToken;

/** A request signed in with a personal access token. It never carries the token's secret. */
public final class AccessTokenAuthentication extends AbstractAuthenticationToken {

	private final ResolvedToken token;

	public AccessTokenAuthentication(ResolvedToken token) {
		super(List.of());
		this.token = token;
		setAuthenticated(true);
	}

	public ResolvedToken token() {
		return token;
	}

	@Override
	public Object getCredentials() {
		return "";
	}

	@Override
	public Object getPrincipal() {
		return token.userId();
	}

}
