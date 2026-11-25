package com.projectbrain.requirement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class HttpAiServiceClientTest {

	private HttpServer server;

	private final AtomicReference<String> authorization = new AtomicReference<>();

	private final AtomicReference<String> body = new AtomicReference<>();

	private int status = 200;

	private String response = "{\"text\":\"Dummy text\",\"characters\":10}";

	@BeforeEach
	void start() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/v1/analyze", exchange -> {
			authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
			body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(status, bytes.length);
			exchange.getResponseBody().write(bytes);
			exchange.close();
		});
		server.createContext("/v1/stories", exchange -> {
			body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(status, bytes.length);
			exchange.getResponseBody().write(bytes);
			exchange.close();
		});
		server.createContext("/v1/architecture/data-model", exchange -> {
			authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
			body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(status, bytes.length);
			exchange.getResponseBody().write(bytes);
			exchange.close();
		});
		server.createContext("/v1/extract", exchange -> {
			authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
			body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
			byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(status, bytes.length);
			exchange.getResponseBody().write(bytes);
			exchange.close();
		});
		server.start();
	}

	@AfterEach
	void stop() {
		server.stop(0);
	}

	@Test
	void sendsFileWithServiceToken() {
		AiServiceClient.ExtractedText result = client("service-token").extract("prd.md", "text/markdown",
				"Dummy bytes".getBytes(StandardCharsets.UTF_8));

		assertThat(result.text()).isEqualTo("Dummy text");
		assertThat(result.characters()).isEqualTo(10);
		assertThat(authorization.get()).isEqualTo("Bearer service-token");
		assertThat(body.get()).contains("name=\"file\"").contains("filename=\"prd.md\"").contains("Dummy bytes");
	}

	@Test
	void mapsServiceErrorToSafeCode() {
		status = 422;
		response = "{\"code\":\"NO_TEXT\"}";

		assertThatThrownBy(() -> client("service-token").extract("scan.pdf", "application/pdf", new byte[] { 1 }))
			.isInstanceOfSatisfying(AiServiceException.class, error -> assertThat(error.code()).isEqualTo("NO_TEXT"));
	}

	@Test
	void unknownErrorBodyDoesNotLeakIntoCode() {
		status = 500;
		response = "private document text";

		assertThatThrownBy(() -> client("service-token").extract("prd.md", "text/markdown", new byte[] { 1 }))
			.isInstanceOfSatisfying(AiServiceException.class, error -> {
				assertThat(error.code()).isEqualTo("SERVICE_UNAVAILABLE");
				assertThat(error.getMessage()).doesNotContain("private");
			});
	}

	@Test
	void unreachableServiceIsUnavailable() {
		server.stop(0);

		assertThatThrownBy(() -> client("service-token").extract("prd.md", "text/markdown", new byte[] { 1 }))
			.isInstanceOfSatisfying(AiServiceException.class,
					error -> assertThat(error.code()).isEqualTo("SERVICE_UNAVAILABLE"));
	}

	@Test
	void analyzeSendsTextAndAnswersAndReadsUsage() {
		response = """
				{"result":{"summary":"Dummy"},
				 "usage":{"provider":"gemini","model":"m","promptVersion":"analysis-v1","inputTokens":7,"outputTokens":3}}
				""";

		AiServiceClient.GeneratedResult result = client("service-token").analyze("Dummy PRD",
				java.util.List.of(new AiServiceClient.ClarificationAnswer("Who?", "Diners")));

		assertThat(result.result().path("summary").asString()).isEqualTo("Dummy");
		assertThat(result.usage())
			.isEqualTo(new AiServiceClient.AiUsage("gemini", "m", "analysis-v1", 7, 3));
		assertThat(authorization.get()).isEqualTo("Bearer service-token");
		assertThat(body.get()).contains("\"text\":\"Dummy PRD\"")
			.contains("\"question\":\"Who?\"")
			.contains("\"answer\":\"Diners\"");
	}

	@Test
	void analyzeMapsProviderErrorToSafeCode() {
		status = 429;
		response = "{\"code\":\"RATE_LIMITED\"}";

		assertThatThrownBy(() -> client("service-token").analyze("Dummy PRD", java.util.List.of()))
			.isInstanceOfSatisfying(AiServiceException.class,
					error -> assertThat(error.code()).isEqualTo("RATE_LIMITED"));
	}

	@Test
	void analyzeResponseWithoutUsageIsUnavailable() {
		response = "{\"result\":{}}";

		assertThatThrownBy(() -> client("service-token").analyze("Dummy PRD", java.util.List.of()))
			.isInstanceOfSatisfying(AiServiceException.class,
					error -> assertThat(error.code()).isEqualTo("SERVICE_UNAVAILABLE"));
	}

	@Test
	void generateStoriesSendsCamelCaseRequest() {
		response = """
				{"result":{"stories":[]},
				 "usage":{"provider":"gemini","model":"m","promptVersion":"stories-v1","inputTokens":7,"outputTokens":3}}
				""";
		JsonMapper mapper = JsonMapper.builder().build();

		AiServiceClient.GeneratedResult result = client("service-token").generateStories(new AiServiceClient.StoriesRequest(
				"Dummy PRD", mapper.readTree("{\"summary\":\"S\"}"), java.util.List.of(), java.util.List.of("Developer"),
				java.util.List.of("Too vague"), java.util.List.of("Kept")));

		assertThat(result.usage().promptVersion()).isEqualTo("stories-v1");
		assertThat(body.get()).contains("\"activeJobRoles\":[\"Developer\"]")
			.contains("\"rejectionComments\":[\"Too vague\"]")
			.contains("\"keptStoryTitles\":[\"Kept\"]")
			.contains("\"analysis\":{\"summary\":\"S\"}");
	}

	@Test
	void analyzeErrorCarriesUsageFromFailedAttempts() {
		status = 502;
		response = """
				{"code":"INVALID_OUTPUT",
				 "usage":{"provider":"gemini","model":"m","promptVersion":"analysis-v1","inputTokens":70,"outputTokens":30}}
				""";

		assertThatThrownBy(() -> client("service-token").analyze("Dummy PRD", java.util.List.of()))
			.isInstanceOfSatisfying(AiServiceException.class, error -> {
				assertThat(error.code()).isEqualTo("INVALID_OUTPUT");
				assertThat(error.usage()).isEqualTo(new AiServiceClient.AiUsage("gemini", "m", "analysis-v1", 70, 30));
			});
	}

	@Test
	void clientPostsStepAndReadsUsage() {
		response = """
				{"result":{"tables":[]},
				 "usage":{"provider":"gemini","model":"m","promptVersion":"architecture-data-model-v1","inputTokens":9,"outputTokens":4}}
				""";
		JsonMapper mapper = JsonMapper.builder().build();
		AiServiceClient.ArchitectureStoryInput story = new AiServiceClient.ArchitectureStoryInput("S1", "Book", "diner",
				"to book", "a seat", java.util.List.of("Saved"), "HIGH");

		AiServiceClient.GeneratedResult result = client("service-token").architectureStep("DATA_MODEL",
				new AiServiceClient.ArchitectureStepRequest(java.util.List.of(story), null, mapper.readTree("{\"summary\":\"S\"}"),
						null));

		assertThat(result.usage().promptVersion()).isEqualTo("architecture-data-model-v1");
		assertThat(authorization.get()).isEqualTo("Bearer service-token");
		assertThat(body.get()).contains("\"key\":\"S1\"")
			.contains("\"asA\":\"diner\"")
			.contains("\"overview\":{\"summary\":\"S\"}")
			.doesNotContain("startingPoint")
			.doesNotContain("dataModel");
	}

	private HttpAiServiceClient client(String token) {
		return new HttpAiServiceClient("http://127.0.0.1:" + server.getAddress().getPort(), token,
				JsonMapper.builder().build());
	}

}
