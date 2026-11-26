package com.projectbrain.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class KnowledgeAiClientTest {

	private HttpServer server;

	private final AtomicReference<String> authorization = new AtomicReference<>();

	private final AtomicReference<String> body = new AtomicReference<>();

	private int status = 200;

	private String response;

	@BeforeEach
	void start() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		for (String path : List.of("/v1/embed", "/v1/answer")) {
			server.createContext(path, exchange -> {
				authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
				body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
				byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().add("Content-Type", "application/json");
				exchange.sendResponseHeaders(status, bytes.length);
				exchange.getResponseBody().write(bytes);
				exchange.close();
			});
		}
		server.start();
	}

	@AfterEach
	void stop() {
		server.stop(0);
	}

	@Test
	void embedSendsTextsWithServiceToken() {
		response = "{\"vectors\":[" + vector("0.5") + "," + vector("0.25") + "],\"model\":\"m\",\"dimensions\":384}";

		List<float[]> vectors = client().embed(List.of("first", "second"));

		assertThat(vectors).hasSize(2);
		assertThat(vectors.get(0)).hasSize(384).startsWith(0.5f);
		assertThat(vectors.get(1)[383]).isEqualTo(0.25f);
		assertThat(authorization.get()).isEqualTo("Bearer token");
		assertThat(body.get()).isEqualTo("{\"texts\":[\"first\",\"second\"]}");
	}

	@Test
	void embedWithWrongDimensionsIsUnavailable() {
		response = "{\"vectors\":[[0.1,0.2]],\"model\":\"m\",\"dimensions\":2}";

		assertThatThrownBy(() -> client().embed(List.of("first")))
			.isInstanceOfSatisfying(KnowledgeAiException.class,
					error -> assertThat(error.code()).isEqualTo("SERVICE_UNAVAILABLE"));
	}

	@Test
	void embedMapsKnownCodeAndHidesUnknownBody() {
		status = 503;
		response = "{\"code\":\"EMBEDDING_UNAVAILABLE\"}";
		assertThatThrownBy(() -> client().embed(List.of("first")))
			.isInstanceOfSatisfying(KnowledgeAiException.class,
					error -> assertThat(error.code()).isEqualTo("EMBEDDING_UNAVAILABLE"));

		status = 500;
		response = "private note text";
		assertThatThrownBy(() -> client().embed(List.of("first")))
			.isInstanceOfSatisfying(KnowledgeAiException.class, error -> {
				assertThat(error.code()).isEqualTo("SERVICE_UNAVAILABLE");
				assertThat(error.getMessage()).doesNotContain("private");
			});
	}

	@Test
	void answerSendsPassagesAndReadsResultAndUsage() {
		response = """
				{"result":{"answer":"Yes.","citations":[2],"found":true},
				 "usage":{"provider":"groq","model":"m","promptVersion":"knowledge-answer-v1","inputTokens":7,"outputTokens":3}}
				""";

		KnowledgeAiClient.GeneratedAnswer answer = client().answer("Can I cancel?",
				List.of(new KnowledgeAiClient.Passage(1, "One"), new KnowledgeAiClient.Passage(2, "Two")));

		assertThat(answer).isEqualTo(new KnowledgeAiClient.GeneratedAnswer("Yes.", List.of(2), true,
				new KnowledgeAiClient.Usage("groq", "m", "knowledge-answer-v1", 7, 3)));
		assertThat(body.get()).contains("\"question\":\"Can I cancel?\"").contains("{\"id\":2,\"text\":\"Two\"}");
	}

	@Test
	void unreachableServiceIsUnavailable() {
		server.stop(0);

		assertThatThrownBy(() -> client().embed(List.of("first")))
			.isInstanceOfSatisfying(KnowledgeAiException.class,
					error -> assertThat(error.code()).isEqualTo("SERVICE_UNAVAILABLE"));
	}

	private HttpKnowledgeAiClient client() {
		return new HttpKnowledgeAiClient("http://127.0.0.1:" + server.getAddress().getPort(), "token",
				JsonMapper.builder().build());
	}

	private static String vector(String value) {
		return "[" + String.join(",", Collections.nCopies(384, value)) + "]";
	}

}
