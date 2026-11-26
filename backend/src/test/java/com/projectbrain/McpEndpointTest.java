package com.projectbrain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import com.projectbrain.support.McpTestClient;
import com.projectbrain.support.TeamApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = { "SUPABASE_URL=http://127.0.0.1:1", "APP_CORS_ALLOWED_ORIGINS=http://localhost:5173" })
class McpEndpointTest extends TeamApiTestBase {

	private static final String JWT_SHAPED = "Bearer eyJhbGciOiJFUzI1NiJ9.eyJzdWIiOiJ4In0.c2lnbmF0dXJl";

	@LocalServerPort
	private int port;

	@Test
	void validTokenInitializesAndListsTools() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner);
		String token = McpTestClient.seedToken(owner, org, "READ");

		try (McpTestClient mcp = McpTestClient.connect(port, token)) {
			assertThat(mcp.client().listTools().tools()).isNotNull();
			assertThat(mcp.client().getServerInfo().name()).isEqualTo("project-brain");
		}
	}

	@Test
	void missingMalformedRevokedExpiredAndSessionTokensAreRefused() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner);
		String revoked = McpTestClient.seedToken(owner, org, "READ", "now() + interval '1 day'", "now()");
		String expired = McpTestClient.seedToken(owner, org, "READ", "now() - interval '1 second'", null);

		assertThat(McpTestClient.rawStatus(port, null, "/mcp")).isEqualTo(401);
		assertThat(McpTestClient.rawStatus(port, "Bearer pb_tooshort", "/mcp")).isEqualTo(401);
		assertThat(McpTestClient.rawStatus(port, "Bearer " + revoked, "/mcp")).isEqualTo(401);
		assertThat(McpTestClient.rawStatus(port, "Bearer " + expired, "/mcp")).isEqualTo(401);
		assertThat(McpTestClient.rawStatus(port, JWT_SHAPED, "/mcp")).isEqualTo(401);
	}

	@Test
	void removedMembersTokenStaysDeadEvenAfterTheyRejoin() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner);
		UUID admin = seedMember(org, "ADMIN");
		String token = McpTestClient.seedToken(admin, org, "READ_WRITE");
		assertThat(McpTestClient.rawStatus(port, "Bearer " + token, "/mcp")).isEqualTo(200);

		execute("DELETE FROM brain.organization_members WHERE organization_id = ? AND user_id = ?", org, admin);
		assertThat(McpTestClient.rawStatus(port, "Bearer " + token, "/mcp")).isEqualTo(401);

		addMember(org, admin, "ADMIN");
		assertThat(McpTestClient.rawStatus(port, "Bearer " + token, "/mcp")).isEqualTo(401);
		String fresh = McpTestClient.seedToken(admin, org, "READ_WRITE");
		assertThat(McpTestClient.rawStatus(port, "Bearer " + fresh, "/mcp")).isEqualTo(200);
	}

	@Test
	void accessTokenIsRefusedOnTheAppApi() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner);
		String token = McpTestClient.seedToken(owner, org, "READ_WRITE");

		assertThat(McpTestClient.rawStatus(port, "Bearer " + token, "/api/v1/organizations")).isEqualTo(401);
	}

	@Test
	void sixtyFirstRequestInAMinuteIsRateLimited() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner);
		String token = McpTestClient.seedToken(owner, org, "READ");

		for (int i = 0; i < 60; i++) {
			assertThat(McpTestClient.rawStatus(port, "Bearer " + token, "/mcp")).as("request %d", i + 1)
				.isEqualTo(200);
		}

		assertThat(McpTestClient.rawStatus(port, "Bearer " + token, "/mcp")).isEqualTo(429);
		String other = McpTestClient.seedToken(owner, org, "READ");
		assertThat(McpTestClient.rawStatus(port, "Bearer " + other, "/mcp")).isEqualTo(200);
	}

}
