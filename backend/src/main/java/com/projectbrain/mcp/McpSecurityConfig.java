package com.projectbrain.mcp;

import java.nio.charset.StandardCharsets;

import com.projectbrain.identity.api.AccessTokenResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

/** /mcp accepts only personal access tokens. The app's /api chain keeps accepting only Supabase sessions. */
@Configuration
class McpSecurityConfig {

	private static final String UNAUTHORIZED_BODY = """
			{"title":"Unauthorized","status":401,"detail":"A valid personal access token is required."}""";

	@Bean
	@Order(1)
	SecurityFilterChain mcpSecurityFilterChain(HttpSecurity http, AccessTokenResolver resolver, McpRateLimiter limiter)
			throws Exception {
		return http.securityMatcher("/mcp", "/mcp/**")
			.csrf(AbstractHttpConfigurer::disable)
			.cors(AbstractHttpConfigurer::disable)
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.addFilterBefore(new AccessTokenAuthenticationFilter(resolver, limiter),
					AnonymousAuthenticationFilter.class)
			.authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
			.exceptionHandling(exceptions -> exceptions.authenticationEntryPoint((request, response, exception) -> {
				response.setStatus(HttpStatus.UNAUTHORIZED.value());
				response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
				response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
				response.setCharacterEncoding(StandardCharsets.UTF_8);
				response.getWriter().write(UNAUTHORIZED_BODY);
			}))
			.build();
	}

}
