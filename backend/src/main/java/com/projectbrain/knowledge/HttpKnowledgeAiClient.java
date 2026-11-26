package com.projectbrain.knowledge;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
class HttpKnowledgeAiClient implements KnowledgeAiClient {

	static final String UNAVAILABLE = "SERVICE_UNAVAILABLE";

	private static final Set<String> KNOWN_CODES = Set.of("EMBEDDING_UNAVAILABLE", "RATE_LIMITED", "PROVIDER_TIMEOUT",
			"PROVIDER_ERROR", "INVALID_OUTPUT");

	private final RestClient rest;

	private final JsonMapper json;

	@Autowired
	HttpKnowledgeAiClient(@Value("${app.ai.service-url}") String baseUrl,
			@Value("${app.ai.service-token}") String token, JsonMapper json) {
		HttpClient http = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(Duration.ofSeconds(5))
			.build();
		JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(http);
		// Answers allow 60 seconds per attempt and may retry invalid output once.
		requests.setReadTimeout(Duration.ofSeconds(130));
		this.rest = RestClient.builder()
			.baseUrl(baseUrl)
			.requestFactory(requests)
			.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)
			.build();
		this.json = json;
	}

	@Override
	public List<float[]> embed(List<String> texts) {
		try {
			JsonNode vectors = post("/v1/embed", Map.of("texts", texts)).required("vectors");
			if (!vectors.isArray() || vectors.size() != texts.size()) {
				throw new KnowledgeAiException(UNAVAILABLE);
			}
			List<float[]> result = new ArrayList<>();
			for (JsonNode vector : vectors) {
				if (!vector.isArray() || vector.size() != DIMENSIONS) {
					throw new KnowledgeAiException(UNAVAILABLE);
				}
				float[] values = new float[DIMENSIONS];
				for (int i = 0; i < DIMENSIONS; i++) {
					values[i] = (float) vector.get(i).asDouble();
				}
				result.add(values);
			}
			return result;
		}
		catch (JacksonException ex) {
			throw new KnowledgeAiException(UNAVAILABLE);
		}
	}

	@Override
	public GeneratedAnswer answer(String question, List<Passage> passages) {
		Map<String, Object> request = new LinkedHashMap<>();
		request.put("question", question);
		request.put("passages", passages);
		try {
			JsonNode body = post("/v1/answer", request);
			JsonNode result = body.required("result");
			JsonNode usage = body.required("usage");
			List<Integer> citations = new ArrayList<>();
			for (JsonNode citation : result.required("citations")) {
				citations.add(citation.asInt());
			}
			return new GeneratedAnswer(result.required("answer").asString(), citations,
					result.required("found").asBoolean(),
					new Usage(usage.required("provider").asString(), usage.required("model").asString(),
							usage.required("promptVersion").asString(), usage.required("inputTokens").asInt(),
							usage.required("outputTokens").asInt()));
		}
		catch (JacksonException ex) {
			throw new KnowledgeAiException(UNAVAILABLE);
		}
	}

	private JsonNode post(String path, Object request) {
		try {
			return rest.post()
				.uri(path)
				.contentType(MediaType.APPLICATION_JSON)
				.body(json.writeValueAsBytes(request))
				.exchange((ignored, response) -> read(response.getStatusCode().is2xxSuccessful(),
						response.getBody().readAllBytes()), true);
		}
		catch (RestClientException | JacksonException ex) {
			throw new KnowledgeAiException(UNAVAILABLE);
		}
	}

	private JsonNode read(boolean success, byte[] bytes) {
		if (success) {
			return json.readTree(bytes);
		}
		throw new KnowledgeAiException(errorCode(bytes));
	}

	private String errorCode(byte[] bytes) {
		try {
			String code = json.readTree(bytes).path("code").asString("");
			return KNOWN_CODES.contains(code) ? code : UNAVAILABLE;
		}
		catch (JacksonException ex) {
			return UNAVAILABLE;
		}
	}

}
