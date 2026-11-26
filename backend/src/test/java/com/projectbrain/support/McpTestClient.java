package com.projectbrain.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.databind.json.JsonMapper;

/** A real MCP client over Streamable HTTP, plus helpers for seeding tokens and sending raw requests. */
public final class McpTestClient implements AutoCloseable {

	private static final SecureRandom RANDOM = new SecureRandom();

	private final McpSyncClient client;

	private McpTestClient(McpSyncClient client) {
		this.client = client;
	}

	public static McpTestClient connect(int port, String token) {
		HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
			.builder("http://localhost:" + port)
			.endpoint("/mcp")
			.jsonMapper(new JacksonMcpJsonMapper(JsonMapper.builder().build()))
			.requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer " + token))
			.build();
		McpSyncClient client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(20)).build();
		client.initialize();
		return new McpTestClient(client);
	}

	public McpSyncClient client() {
		return client;
	}

	public McpSchema.CallToolResult call(String tool, Map<String, Object> arguments) {
		return client.callTool(new McpSchema.CallToolRequest(tool, arguments));
	}

	@Override
	public void close() {
		client.close();
	}

	/** Inserts an active token for the user and returns its secret. */
	public static String seedToken(UUID user, UUID org, String scope) throws Exception {
		return seedToken(user, org, scope, "now() + interval '1 day'", null);
	}

	public static String seedToken(UUID user, UUID org, String scope, String expires, String revoked) throws Exception {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		String token = "pb_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		execute("INSERT INTO brain.access_tokens (id, user_id, organization_id, name, token_hash, display_prefix, scope, created_at, expires_at, revoked_at) "
				+ "VALUES (?, ?, ?, 'Test', ?, ?, ?, LEAST(clock_timestamp(), " + expires + " - interval '1 minute'), " + expires + ", "
				+ (revoked == null ? "NULL" : revoked) + ")", UUID.randomUUID(), user, org, sha256(token),
				token.substring(0, 8), scope);
		return token;
	}

	/** Posts one JSON-RPC message to /mcp and returns the HTTP status, without the SDK client. */
	public static int rawStatus(int port, String authorization, String path) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
			.header("Content-Type", "application/json")
			.header("Accept", "application/json, text/event-stream")
			.POST(HttpRequest.BodyPublishers
				.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}"));
		if (authorization != null) {
			request.header("Authorization", authorization);
		}
		return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
	}

	private static void execute(String sql, Object... params) throws Exception {
		try (java.sql.Connection admin = PostgresTestBase.adminConnection();
				java.sql.PreparedStatement statement = admin.prepareStatement(sql)) {
			for (int i = 0; i < params.length; i++) {
				statement.setObject(i + 1, params[i]);
			}
			statement.executeUpdate();
		}
	}

	static String sha256(String value) throws Exception {
		return HexFormat.of()
			.formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
	}

}
