package com.projectbrain.mcp;

import java.io.IOException;
import java.util.Optional;

import com.projectbrain.identity.api.AccessTokenAuthentication;
import com.projectbrain.identity.api.AccessTokenResolver;
import com.projectbrain.identity.api.AccessTokenResolver.ResolvedToken;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.web.filter.OncePerRequestFilter;

/** Signs in /mcp requests that carry an active personal access token. Anything else stays signed out. */
class AccessTokenAuthenticationFilter extends OncePerRequestFilter {

	private static final String BEARER = "Bearer ";

	private final AccessTokenResolver resolver;

	private final McpRateLimiter limiter;

	private final SecurityContextHolderStrategy contexts = SecurityContextHolder.getContextHolderStrategy();

	AccessTokenAuthenticationFilter(AccessTokenResolver resolver, McpRateLimiter limiter) {
		this.resolver = resolver;
		this.limiter = limiter;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		Optional<ResolvedToken> token = header != null && header.startsWith(BEARER)
				? resolver.resolve(header.substring(BEARER.length()).strip()) : Optional.empty();
		if (token.isEmpty()) {
			chain.doFilter(request, response);
			return;
		}
		if (!limiter.tryAcquire(token.get().tokenId())) {
			response.setStatus(429);
			response.setHeader(HttpHeaders.RETRY_AFTER, "60");
			response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
			response.getWriter()
				.write("{\"title\":\"Too Many Requests\",\"status\":429,\"detail\":\"Wait a minute, then try again.\"}");
			return;
		}
		SecurityContext context = contexts.createEmptyContext();
		context.setAuthentication(new AccessTokenAuthentication(token.get()));
		contexts.setContext(context);
		try {
			chain.doFilter(request, response);
		}
		finally {
			contexts.clearContext();
		}
	}

}
