package com.projectbrain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.projectbrain.support.PostgresTestBase;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Exercises the real decoder against a local JWKS endpoint, so nothing about verification is mocked. */
@SpringBootTest
@AutoConfigureMockMvc
class MeApiTest extends PostgresTestBase {

	private static final String ME = "/api/v1/me";
	private static final String ALLOWED_ORIGIN = "http://localhost:5173";

	private static final ECKey EC_KEY;
	private static final ECKey UNTRUSTED_EC_KEY;
	private static final RSAKey RSA_KEY;
	private static final HttpServer JWKS_SERVER;
	private static final String ISSUER;

	static {
		try {
			EC_KEY = new ECKeyGenerator(Curve.P_256).keyID("ec-key").generate();
			// Same key ID as the trusted key, so rejection must come from the signature check.
			UNTRUSTED_EC_KEY = new ECKeyGenerator(Curve.P_256).keyID("ec-key").generate();
			RSA_KEY = new RSAKeyGenerator(2048).keyID("rsa-key").generate();
			byte[] jwks = new JWKSet(List.<JWK>of(EC_KEY.toPublicJWK(), RSA_KEY.toPublicJWK())).toString()
				.getBytes(StandardCharsets.UTF_8);
			JWKS_SERVER = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
			JWKS_SERVER.createContext("/auth/v1/.well-known/jwks.json", exchange -> {
				exchange.getResponseHeaders().add("Content-Type", "application/json");
				exchange.sendResponseHeaders(200, jwks.length);
				exchange.getResponseBody().write(jwks);
				exchange.close();
			});
			JWKS_SERVER.start();
			ISSUER = "http://127.0.0.1:" + JWKS_SERVER.getAddress().getPort() + "/auth/v1";
		}
		catch (JOSEException | IOException ex) {
			throw new ExceptionInInitializerError(ex);
		}
	}

	@Autowired
	private MockMvc mvc;

	@DynamicPropertySource
	static void authProperties(DynamicPropertyRegistry registry) {
		registry.add("SUPABASE_URL", () -> "http://127.0.0.1:" + JWKS_SERVER.getAddress().getPort());
		registry.add("APP_CORS_ALLOWED_ORIGINS", () -> ALLOWED_ORIGIN + ",https://app.example.test");
	}

	@AfterAll
	static void stopJwksServer() {
		JWKS_SERVER.stop(0);
	}

	@Test
	void missingTokenReturns401() throws Exception {
		assertUnauthorized(null);
	}

	@Test
	void expiredTokenReturns401() throws Exception {
		UUID user = UUID.randomUUID();
		assertUnauthorized(signEc(validClaims(user).expirationTime(Date.from(Instant.now().minusSeconds(3600))).build()));
		assertThat(profileCount(user)).isZero();
	}

	@Test
	void wrongIssuerReturns401() throws Exception {
		UUID user = UUID.randomUUID();
		assertUnauthorized(signEc(validClaims(user).issuer("https://evil.example.test/auth/v1").build()));
		assertThat(profileCount(user)).isZero();
	}

	@Test
	void wrongAudienceReturns401() throws Exception {
		UUID user = UUID.randomUUID();
		assertUnauthorized(signEc(validClaims(user).audience("anon").build()));
		assertThat(profileCount(user)).isZero();
	}

	@Test
	void tokenWithoutEmailReturns401() throws Exception {
		UUID user = UUID.randomUUID();
		assertUnauthorized(signEc(validClaims(user).claim("email", null).build()));
		assertThat(profileCount(user)).isZero();
	}

	@Test
	void emailWithoutLocalPartReturns401() throws Exception {
		UUID user = UUID.randomUUID();
		assertUnauthorized(signEc(validClaims(user).claim("email", "@example.test").build()));
		assertThat(profileCount(user)).isZero();
	}

	@Test
	void nonUuidSubjectReturns401() throws Exception {
		assertUnauthorized(signEc(validClaims(UUID.randomUUID()).subject("not-a-uuid").build()));
	}

	@Test
	void tokenSignedByUnknownKeyReturns401() throws Exception {
		UUID user = UUID.randomUUID();
		assertUnauthorized(sign(validClaims(user).build(), UNTRUSTED_EC_KEY));
		assertThat(profileCount(user)).isZero();
	}

	@Test
	void firstRequestCreatesProfile() throws Exception {
		UUID user = UUID.randomUUID();

		mvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + signEc(validClaims(user).build())))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(user.toString()))
			.andExpect(jsonPath("$.email").value("ada.lovelace@example.test"))
			.andExpect(jsonPath("$.displayName").value("ada.lovelace"));

		assertThat(profileCount(user)).isEqualTo(1);
	}

	@Test
	void secondRequestDoesNotDuplicate() throws Exception {
		UUID user = UUID.randomUUID();
		String token = signEc(validClaims(user).build());

		mvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andExpect(status().isOk());
		mvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(user.toString()));

		assertThat(profileCount(user)).isEqualTo(1);
	}

	@Test
	void acceptsRs256Token() throws Exception {
		UUID user = UUID.randomUUID();

		mvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + sign(validClaims(user).build(), RSA_KEY)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(user.toString()));
	}

	@Test
	void corsAllowsConfiguredOriginOnly() throws Exception {
		mvc.perform(options(ME).header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
			.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
			.header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization"))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN));

		mvc.perform(options(ME).header(HttpHeaders.ORIGIN, "https://evil.example.test")
			.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
			.andExpect(status().isForbidden())
			.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
	}

	private void assertUnauthorized(String token) throws Exception {
		var request = get(ME);
		if (token != null) {
			request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
		}
		mvc.perform(request)
			.andExpect(status().isUnauthorized())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.status").value(401))
			.andExpect(jsonPath("$.title").value("Unauthorized"));
	}

	private static JWTClaimsSet.Builder validClaims(UUID user) {
		return new JWTClaimsSet.Builder().subject(user.toString())
			.issuer(ISSUER)
			.audience("authenticated")
			.issueTime(new Date())
			.expirationTime(Date.from(Instant.now().plusSeconds(3600)))
			.claim("email", "ada.lovelace@example.test");
	}

	private static String signEc(JWTClaimsSet claims) throws JOSEException {
		return sign(claims, EC_KEY);
	}

	private static String sign(JWTClaimsSet claims, JWK key) throws JOSEException {
		boolean ec = key instanceof ECKey;
		JWSHeader header = new JWSHeader.Builder(ec ? JWSAlgorithm.ES256 : JWSAlgorithm.RS256).keyID(key.getKeyID())
			.type(JOSEObjectType.JWT)
			.build();
		SignedJWT jwt = new SignedJWT(header, claims);
		jwt.sign(ec ? new ECDSASigner(key.toECKey()) : new RSASSASigner(key.toRSAKey()));
		return jwt.serialize();
	}

	private static long profileCount(UUID user) throws SQLException {
		try (Connection admin = adminConnection();
				PreparedStatement statement = admin.prepareStatement("SELECT count(*) FROM brain.profiles WHERE id = ?")) {
			statement.setObject(1, user);
			try (ResultSet rows = statement.executeQuery()) {
				rows.next();
				return rows.getLong(1);
			}
		}
	}

}
