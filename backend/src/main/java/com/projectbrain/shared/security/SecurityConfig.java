package com.projectbrain.shared.security;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class SecurityConfig {

	private static final String AUDIENCE = "authenticated";
	private static final String UNAUTHORIZED_BODY = """
			{"title":"Unauthorized","status":401,"detail":"A valid access token is required."}""";

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		AuthenticationEntryPoint unauthorized = (request, response, exception) -> {
			response.setStatus(HttpStatus.UNAUTHORIZED.value());
			response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
			response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
			response.setCharacterEncoding(StandardCharsets.UTF_8);
			response.getWriter().write(UNAUTHORIZED_BODY);
		};
		return http.csrf(AbstractHttpConfigurer::disable)
			.cors(Customizer.withDefaults())
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
			.exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(unauthorized))
			.oauth2ResourceServer(server -> server.authenticationEntryPoint(unauthorized)
				.jwt(Customizer.withDefaults()))
			.build();
	}

	@Bean
	JwtDecoder jwtDecoder(@Value("${app.auth.jwks-uri}") String jwksUri, @Value("${app.auth.issuer}") String issuer) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwksUri)
			.jwsAlgorithm(SignatureAlgorithm.ES256)
			.jwsAlgorithm(SignatureAlgorithm.RS256)
			.build();
		OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<List<String>>("aud",
				aud -> aud != null && aud.contains(AUDIENCE));
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer),
				audience, SecurityConfig::validateIdentity));
		return decoder;
	}

	@Bean
	CorsConfigurationSource corsConfigurationSource(@Value("${app.cors.allowed-origins}") List<String> allowedOrigins) {
		CorsConfiguration cors = new CorsConfiguration();
		cors.setAllowedOrigins(allowedOrigins);
		cors.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
		cors.setAllowedHeaders(List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE));
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/api/**", cors);
		return source;
	}

	private static OAuth2TokenValidatorResult validateIdentity(Jwt jwt) {
		if (isCanonicalUuid(jwt.getSubject()) && hasUsableEmail(jwt.getClaimAsString("email"))) {
			return OAuth2TokenValidatorResult.success();
		}
		return OAuth2TokenValidatorResult
			.failure(new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, "Token lacks a valid subject or email.", null));
	}

	/** UUID.fromString alone also accepts shortened forms such as 1-1-1-1-1. */
	private static boolean isCanonicalUuid(String value) {
		if (value == null) {
			return false;
		}
		try {
			return UUID.fromString(value).toString().equalsIgnoreCase(value);
		}
		catch (IllegalArgumentException ex) {
			return false;
		}
	}

	private static boolean hasUsableEmail(String email) {
		return email != null && email.indexOf('@') > 0;
	}

}
