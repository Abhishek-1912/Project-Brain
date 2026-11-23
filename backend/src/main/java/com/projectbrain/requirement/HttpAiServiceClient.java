package com.projectbrain.requirement;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
class HttpAiServiceClient implements AiServiceClient {

	static final String UNAVAILABLE = "SERVICE_UNAVAILABLE";

	private static final Set<String> KNOWN_CODES = Set.of("UNSUPPORTED_TYPE", "FILE_TOO_LARGE", "NO_TEXT",
			"TEXT_TOO_LONG", "CORRUPT_FILE", "RATE_LIMITED", "PROVIDER_TIMEOUT", "PROVIDER_ERROR", "INVALID_OUTPUT");

	private final RestClient rest;

	private final JsonMapper json;

	@Autowired
	HttpAiServiceClient(@Value("${app.ai.service-url}") String baseUrl, @Value("${app.ai.service-token}") String token,
			JsonMapper json) {
		HttpClient http = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(Duration.ofSeconds(5))
			.build();
		JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(http);
		// The AI service allows 60 seconds per attempt and may retry invalid output once.
		requests.setReadTimeout(Duration.ofSeconds(130));
		this.rest = RestClient.builder()
			.baseUrl(baseUrl)
			.requestFactory(requests)
			.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)
			.build();
		this.json = json;
	}

	@Override
	public ExtractedText extract(String filename, String contentType, byte[] content) {
		HttpHeaders partHeaders = new HttpHeaders();
		partHeaders.setContentType(MediaType.APPLICATION_OCTET_STREAM);
		partHeaders.setContentDisposition(ContentDisposition.formData().name("file").filename(filename).build());
		LinkedMultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
		form.add("file", new HttpEntity<>(new ByteArrayResource(content), partHeaders));
		try {
			JsonNode body = rest.post()
				.uri("/v1/extract")
				.contentType(MediaType.MULTIPART_FORM_DATA)
				.body(form)
				.exchange((request, response) -> read(response.getStatusCode().is2xxSuccessful(),
						response.getBody().readAllBytes()), true);
			return new ExtractedText(body.required("text").asString(), body.required("characters").asInt());
		}
		catch (RestClientException | JacksonException ex) {
			throw new AiServiceException(UNAVAILABLE);
		}
	}

	@Override
	public GeneratedResult analyze(String text, List<ClarificationAnswer> answers) {
		try {
			JsonNode body = rest.post()
				.uri("/v1/analyze")
				.contentType(MediaType.APPLICATION_JSON)
				.body(json.writeValueAsBytes(Map.of("text", text, "answers", answers)))
				.exchange((request, response) -> read(response.getStatusCode().is2xxSuccessful(),
						response.getBody().readAllBytes()), true);
			JsonNode usage = body.required("usage");
			return new GeneratedResult(body.required("result"),
					new AiUsage(usage.required("provider").asString(), usage.required("model").asString(),
							usage.required("promptVersion").asString(), usage.required("inputTokens").asInt(),
							usage.required("outputTokens").asInt()));
		}
		catch (RestClientException | JacksonException ex) {
			throw new AiServiceException(UNAVAILABLE);
		}
	}

	private JsonNode read(boolean success, byte[] bytes) {
		if (success) {
			return json.readTree(bytes);
		}
		throw new AiServiceException(errorCode(bytes));
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
