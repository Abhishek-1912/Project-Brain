package com.projectbrain.requirement;

import java.util.List;

import tools.jackson.databind.JsonNode;

/** Private connection to the stateless Python AI service. */
public interface AiServiceClient {

	ExtractedText extract(String filename, String contentType, byte[] content);

	GeneratedResult analyze(String text, List<ClarificationAnswer> answers);

	record ExtractedText(String text, int characters) {
	}

	record ClarificationAnswer(String question, String answer) {
	}

	record AiUsage(String provider, String model, String promptVersion, int inputTokens, int outputTokens) {
	}

	record GeneratedResult(JsonNode result, AiUsage usage) {
	}

}
