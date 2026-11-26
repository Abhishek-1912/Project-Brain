package com.projectbrain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import com.projectbrain.identity.api.AccessTokenResolver;
import com.projectbrain.support.TeamApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = { "SUPABASE_URL=http://127.0.0.1:1", "APP_CORS_ALLOWED_ORIGINS=http://localhost:5173" })
class AccessTokenApiTest extends TeamApiTestBase {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private AccessTokenResolver resolver;

	@Test
	void createdTokenIsShownOnceAndOnlyItsHashIsStored() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner);

		String body = mvc.perform(create(org, owner, "Claude Code laptop", "READ_WRITE", 30))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.name").value("Claude Code laptop"))
			.andExpect(jsonPath("$.scope").value("READ_WRITE"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		String token = JsonPath.read(body, "$.token");
		String id = JsonPath.read(body, "$.id");

		assertThat(token).startsWith("pb_").hasSize(46);
		assertThat((String) JsonPath.read(body, "$.displayPrefix")).isEqualTo(token.substring(0, 8));
		OffsetDateTime expires = OffsetDateTime.parse(JsonPath.read(body, "$.expiresAt"));
		assertThat(expires).isBetween(OffsetDateTime.now().plusDays(30).minusMinutes(1),
				OffsetDateTime.now().plusDays(30).plusMinutes(1));
		String listing = mvc.perform(get(tokensUrl(org)).with(as(owner)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(1)))
			.andExpect(jsonPath("$.items[0].id").value(id))
			.andExpect(jsonPath("$.items[0].status").value("ACTIVE"))
			.andExpect(jsonPath("$.items[0].token").doesNotExist())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(listing).doesNotContain(token);
		assertThat(countRows("SELECT count(*) FROM brain.access_tokens WHERE token_hash = ?",
				sha256(token))).isEqualTo(1);
		assertThat(countRows("SELECT count(*) FROM brain.access_tokens WHERE token_hash = ?", token)).isZero();
	}

	@Test
	void resolverFindsActiveTokensOnly() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner);
		String body = mvc.perform(create(org, owner, "CLI", "READ", 7))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		String token = JsonPath.read(body, "$.token");
		String id = JsonPath.read(body, "$.id");

		assertThat(resolver.resolve(token)).hasValueSatisfying(resolved -> {
			assertThat(resolved.tokenId()).hasToString(id);
			assertThat(resolved.userId()).isEqualTo(owner);
			assertThat(resolved.orgId()).isEqualTo(org);
			assertThat(resolved.scope()).isEqualTo("READ");
		});
		assertThat(resolver.resolve("pb_short")).isEmpty();
		assertThat(resolver.resolve(null)).isEmpty();
		assertThat(resolver.resolve("eyJhbGciOiJIUzI1NiJ9.e30.x")).isEmpty();

		mvc.perform(post(tokensUrl(org) + "/" + id + "/revoke").with(as(owner))).andExpect(status().isNoContent());
		mvc.perform(post(tokensUrl(org) + "/" + id + "/revoke").with(as(owner))).andExpect(status().isNoContent());

		assertThat(resolver.resolve(token)).isEmpty();
		mvc.perform(get(tokensUrl(org)).with(as(owner))).andExpect(jsonPath("$.items[0].status").value("REVOKED"));
		assertThat(countRows("SELECT count(*) FROM brain.audit_events WHERE entity_id = ? AND action = ?",
				UUID.fromString(id), "ACCESS_TOKEN_REVOKED")).isEqualTo(1);
	}

	@Test
	void anotherUserCannotSeeOrRevokeYourToken() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner);
		UUID admin = seedMember(org, "ADMIN");
		String body = mvc.perform(create(org, owner, "Mine", "READ", 7))
			.andReturn()
			.getResponse()
			.getContentAsString();
		String id = JsonPath.read(body, "$.id");

		mvc.perform(get(tokensUrl(org)).with(as(admin))).andExpect(jsonPath("$.items", hasSize(0)));
		mvc.perform(post(tokensUrl(org) + "/" + id + "/revoke").with(as(admin))).andExpect(status().isNotFound());
		assertThat(resolver.resolve((String) JsonPath.read(body, "$.token"))).isPresent();
	}

	@Test
	void viewerCanCreateTheirOwnReadToken() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner);
		UUID viewer = seedMember(org, "VIEWER");

		mvc.perform(create(org, viewer, "Viewer laptop", "READ", 7)).andExpect(status().isCreated());
	}

	@Test
	void eleventhActiveTokenIsRefused() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner);
		for (int i = 0; i < 10; i++) {
			mvc.perform(create(org, owner, "Token " + i, "READ", 7)).andExpect(status().isCreated());
		}

		mvc.perform(create(org, owner, "One too many", "READ", 7)).andExpect(status().isConflict());
	}

	@Test
	void invalidRequestsAreRejected() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner);

		mvc.perform(create(org, owner, "   ", "READ", 7)).andExpect(status().isUnprocessableContent());
		mvc.perform(create(org, owner, "x".repeat(101), "READ", 7)).andExpect(status().isUnprocessableContent());
		mvc.perform(create(org, owner, "Name", "ADMIN", 7)).andExpect(status().isUnprocessableContent());
		mvc.perform(create(org, owner, "Name", "READ", 365)).andExpect(status().isUnprocessableContent());
	}

	@Test
	void auditNeverContainsTheSecret() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner);
		String body = mvc.perform(create(org, owner, "Audit check", "READ_WRITE", 90))
			.andReturn()
			.getResponse()
			.getContentAsString();
		String token = JsonPath.read(body, "$.token");

		assertThat(countRows("SELECT count(*) FROM brain.audit_events WHERE organization_id = ? AND action = ?", org,
				"ACCESS_TOKEN_CREATED")).isEqualTo(1);
		assertThat(countRows("SELECT count(*) FROM brain.audit_events WHERE metadata::text LIKE ?",
				"%" + token.substring(3) + "%")).isZero();
	}

	@Test
	void anotherOrganizationGets404() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner);
		UUID stranger = seedProfile();

		mvc.perform(get(tokensUrl(org)).with(as(stranger))).andExpect(status().isNotFound());
		mvc.perform(create(org, stranger, "Nope", "READ", 7)).andExpect(status().isNotFound());
	}

	private static MockHttpServletRequestBuilder create(UUID org, UUID user, String name, String scope, int days) {
		return post(tokensUrl(org)).with(as(user))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"" + name + "\",\"scope\":\"" + scope + "\",\"expiresInDays\":" + days + "}");
	}

	private static String sha256(String value) throws Exception {
		return java.util.HexFormat.of()
			.formatHex(java.security.MessageDigest.getInstance("SHA-256")
				.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
	}

	private static String tokensUrl(UUID org) {
		return ORGS + "/" + org + "/access-tokens";
	}

}
