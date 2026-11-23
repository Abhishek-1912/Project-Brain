package com.projectbrain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.jayway.jsonpath.JsonPath;
import com.projectbrain.support.TeamApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = { "SUPABASE_URL=http://127.0.0.1:1", "APP_CORS_ALLOWED_ORIGINS=http://localhost:5173" })
class InviteApiTest extends TeamApiTestBase {

	private static final String ACCEPT = "/api/v1/invites/accept";

	@Autowired
	private MockMvc mvc;

	@Test
	void ownerCanInviteAdminMemberOrViewer() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner);

		for (String role : List.of("ADMIN", "MEMBER", "VIEWER")) {
			mvc.perform(post(invites(org)).with(as(owner)).contentType(MediaType.APPLICATION_JSON)
				.content(body(uniqueEmail(), role)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.role").value(role))
				.andExpect(jsonPath("$.status").value("PENDING"))
				.andExpect(jsonPath("$.expiresAt").isNotEmpty())
				.andExpect(jsonPath("$.token").isNotEmpty());
		}
		mvc.perform(post(invites(org)).with(as(owner)).contentType(MediaType.APPLICATION_JSON)
			.content(body(uniqueEmail(), "OWNER")))
			.andExpect(status().isUnprocessableContent());
		assertThat(countRows("""
				SELECT count(*) FROM brain.organization_invites
				WHERE organization_id = ? AND expires_at BETWEEN now() + interval '6 days 23 hours' AND now() + interval '7 days 1 minute'
				""", org)).isEqualTo(3);
		assertThat(audits(org, "INVITE_CREATED")).isEqualTo(3);
	}

	@Test
	void adminCanInviteOnlyMemberOrViewer() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner);
		UUID admin = seedMember(org, "ADMIN");
		UUID member = seedMember(org, "MEMBER");

		for (String role : List.of("MEMBER", "VIEWER")) {
			mvc.perform(post(invites(org)).with(as(admin)).contentType(MediaType.APPLICATION_JSON)
				.content(body(uniqueEmail(), role)))
				.andExpect(status().isCreated());
		}
		mvc.perform(post(invites(org)).with(as(admin)).contentType(MediaType.APPLICATION_JSON)
			.content(body(uniqueEmail(), "ADMIN")))
			.andExpect(status().isForbidden());
		mvc.perform(post(invites(org)).with(as(member)).contentType(MediaType.APPLICATION_JSON)
			.content(body(uniqueEmail(), "VIEWER")))
			.andExpect(status().isForbidden());
		mvc.perform(get(invites(org)).with(as(member))).andExpect(status().isForbidden());
	}

	@Test
	void tokenIsReturnedOnceAndOnlyHashIsStored() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner);
		String email = uniqueEmail();

		MvcResult created = mvc.perform(post(invites(org)).with(as(owner)).contentType(MediaType.APPLICATION_JSON)
			.content(body(email, "MEMBER")))
			.andExpect(status().isCreated())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andReturn();
		String token = JsonPath.read(created.getResponse().getContentAsString(), "$.token");

		assertThat(token).matches("[A-Za-z0-9_-]{43}");
		assertThat(countRows("SELECT count(*) FROM brain.organization_invites WHERE organization_id = ? AND token_hash = ?",
				org, sha256(token))).isEqualTo(1);
		assertThat(countRows("SELECT count(*) FROM brain.organization_invites WHERE token_hash = ?", token)).isZero();
		assertThat(countRows("SELECT count(*) FROM brain.audit_events WHERE organization_id = ? AND metadata::text LIKE ?",
				org, "%" + token + "%")).isZero();
		String list = mvc.perform(get(invites(org)).with(as(owner)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(1)))
			.andExpect(jsonPath("$.items[0].email").value(email))
			.andExpect(jsonPath("$.items[0].token").doesNotExist())
			.andExpect(jsonPath("$.items[0].tokenHash").doesNotExist())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(list).doesNotContain(token).doesNotContain(sha256(token));
	}

	@Test
	void pendingDuplicateAndExistingMemberReturn409() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner);
		UUID member = seedMember(org, "MEMBER");
		String email = uniqueEmail();
		createInvite(org, owner, email, "MEMBER");

		mvc.perform(post(invites(org)).with(as(owner)).contentType(MediaType.APPLICATION_JSON)
			.content(body(email.toUpperCase(), "VIEWER")))
			.andExpect(status().isConflict());
		mvc.perform(post(invites(org)).with(as(owner)).contentType(MediaType.APPLICATION_JSON)
			.content(body(emailOf(member).toUpperCase(), "VIEWER")))
			.andExpect(status().isConflict());
	}

	@Test
	void expiredInviteCanBeReplaced() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner);
		String email = uniqueEmail();
		String oldToken = createInvite(org, owner, email, "MEMBER").token();
		execute("UPDATE brain.organization_invites SET expires_at = now() - interval '1 minute' WHERE token_hash = ?",
				sha256(oldToken));

		mvc.perform(get(invites(org)).with(as(owner)))
			.andExpect(jsonPath("$.items[0].status").value("EXPIRED"));
		mvc.perform(post(invites(org)).with(as(owner)).contentType(MediaType.APPLICATION_JSON)
			.content(body(email, "VIEWER")))
			.andExpect(status().isCreated());

		assertThat(countRows("SELECT count(*) FROM brain.organization_invites WHERE token_hash = ? AND cancelled_at IS NOT NULL",
				sha256(oldToken))).isEqualTo(1);
	}

	@Test
	void acceptIgnoresEmailCase() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner);
		UUID user = UUID.randomUUID();
		String email = "Ravi-" + user + "@Example.test";
		Invite invite = createInvite(org, owner, email, "VIEWER");

		mvc.perform(post(ACCEPT).with(asEmail(user, email.toLowerCase())).contentType(MediaType.APPLICATION_JSON)
			.content(tokenBody(invite.token())))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.organizationId").value(org.toString()));

		assertThat(countRows("""
				SELECT count(*) FROM brain.organization_members
				WHERE organization_id = ? AND user_id = ? AND role = 'VIEWER'
				""", org, user)).isEqualTo(1);
		assertThat(countRows("""
				SELECT count(*) FROM brain.organization_invites
				WHERE id = ? AND accepted_by = ? AND accepted_at IS NOT NULL
				""", invite.id(), user)).isEqualTo(1);
		assertThat(audits(org, "INVITE_ACCEPTED")).isEqualTo(1);
	}

	@Test
	void wrongEmailExpiredCancelledUsedAndUnknownReturnSameError() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner);
		UUID user = UUID.randomUUID();
		String email = "person-" + user + "@example.test";

		String wrongEmail = createInvite(org, owner, uniqueEmail(), "MEMBER").token();
		String expired = createInvite(org, owner, uniqueEmail(), "MEMBER").token();
		execute("UPDATE brain.organization_invites SET email = ?, expires_at = now() - interval '1 minute' WHERE token_hash = ?",
				email, sha256(expired));
		Invite cancelled = createInvite(org, owner, email, "MEMBER");
		mvc.perform(delete(invites(org) + "/" + cancelled.id()).with(as(owner))).andExpect(status().isNoContent());
		UUID firstUser = UUID.randomUUID();
		String firstEmail = "first-" + firstUser + "@example.test";
		String used = createInvite(org, owner, firstEmail, "MEMBER").token();
		mvc.perform(post(ACCEPT).with(asEmail(firstUser, firstEmail)).contentType(MediaType.APPLICATION_JSON)
			.content(tokenBody(used)))
			.andExpect(status().isOk());

		List<String> errors = new ArrayList<>();
		for (String token : List.of(wrongEmail, expired, cancelled.token(), used, "unknown-token", "x".repeat(5000))) {
			errors.add(mvc.perform(post(ACCEPT).with(asEmail(user, email)).contentType(MediaType.APPLICATION_JSON)
				.content(tokenBody(token)))
				.andExpect(status().isNotFound())
				.andReturn()
				.getResponse()
				.getContentAsString());
		}

		assertThat(errors).allMatch(errors.get(0)::equals);
		assertThat(errors.get(0)).doesNotContain(org.toString()).doesNotContain("Seeded");
		assertThat(countRows("SELECT count(*) FROM brain.organization_members WHERE organization_id = ? AND user_id = ?",
				org, user)).isZero();
	}

	@Test
	void acceptUsesCurrentTokenEmailNotStaleProfileEmail() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner);
		UUID changedUser = UUID.randomUUID();
		String victim = "victim-" + changedUser + "@example.test";
		String current = "current-" + changedUser + "@example.test";
		execute("INSERT INTO brain.profiles (id, email, display_name) VALUES (?, ?, 'Old')", changedUser, victim);
		Invite victimInvite = createInvite(org, owner, victim, "MEMBER");
		Invite currentInvite = createInvite(org, owner, current, "MEMBER");

		mvc.perform(post(ACCEPT).with(asEmail(changedUser, current)).contentType(MediaType.APPLICATION_JSON)
			.content(tokenBody(victimInvite.token())))
			.andExpect(status().isNotFound());
		mvc.perform(post(ACCEPT).with(asEmail(changedUser, current)).contentType(MediaType.APPLICATION_JSON)
			.content(tokenBody(currentInvite.token())))
			.andExpect(status().isOk());
	}

	@Test
	void concurrentAcceptCreatesOneMembership() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner);
		UUID user = UUID.randomUUID();
		String email = "race-" + user + "@example.test";
		String token = createInvite(org, owner, email, "MEMBER").token();
		CountDownLatch start = new CountDownLatch(1);
		Callable<Integer> accept = () -> {
			start.await();
			return mvc.perform(post(ACCEPT).with(asEmail(user, email)).contentType(MediaType.APPLICATION_JSON)
				.content(tokenBody(token)))
				.andReturn()
				.getResponse()
				.getStatus();
		};
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<Integer> first = pool.submit(accept);
			Future<Integer> second = pool.submit(accept);
			start.countDown();
			assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(200, 404);
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(countRows("SELECT count(*) FROM brain.organization_members WHERE organization_id = ? AND user_id = ?",
				org, user)).isEqualTo(1);
		assertThat(audits(org, "INVITE_ACCEPTED")).isEqualTo(1);
	}

	@Test
	void cancelFollowsRoleLimitsAndOrganizationBoundary() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner);
		UUID admin = seedMember(org, "ADMIN");
		UUID otherOwner = seedProfile();
		UUID otherOrg = seedOrg(otherOwner);
		Invite adminInvite = createInvite(org, owner, uniqueEmail(), "ADMIN");
		Invite memberInvite = createInvite(org, owner, uniqueEmail(), "MEMBER");

		mvc.perform(delete(invites(org) + "/" + adminInvite.id()).with(as(admin))).andExpect(status().isForbidden());
		mvc.perform(delete(invites(otherOrg) + "/" + memberInvite.id()).with(as(otherOwner)))
			.andExpect(status().isNotFound());
		mvc.perform(delete(invites(org) + "/" + memberInvite.id()).with(as(admin))).andExpect(status().isNoContent());
		mvc.perform(delete(invites(org) + "/" + memberInvite.id()).with(as(admin))).andExpect(status().isConflict());

		assertThat(audits(org, "INVITE_CANCELLED")).isEqualTo(1);
	}

	private Invite createInvite(UUID org, UUID actor, String email, String role) throws Exception {
		String response = mvc.perform(post(invites(org)).with(as(actor)).contentType(MediaType.APPLICATION_JSON)
			.content(body(email, role)))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return new Invite(UUID.fromString(JsonPath.read(response, "$.id")), JsonPath.read(response, "$.token"));
	}

	private static String invites(UUID org) {
		return ORGS + "/" + org + "/invites";
	}

	private static String body(String email, String role) {
		return "{\"email\":\"" + email + "\",\"role\":\"" + role + "\"}";
	}

	private static String tokenBody(String token) {
		return "{\"token\":\"" + token + "\"}";
	}

	private static String uniqueEmail() {
		return "invitee-" + UUID.randomUUID() + "@example.test";
	}

	private static RequestPostProcessor asEmail(UUID user, String email) {
		return jwt().jwt(token -> token.subject(user.toString()).claim("email", email));
	}

	private static long audits(UUID org, String action) throws Exception {
		return countRows("SELECT count(*) FROM brain.audit_events WHERE organization_id = ? AND action = ?", org, action);
	}

	private static String sha256(String value) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
	}

	private record Invite(UUID id, String token) {
	}

}
