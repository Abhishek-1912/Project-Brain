package com.projectbrain.requirement;

import java.util.List;

import tools.jackson.databind.JsonNode;

/** Private connection to the stateless Python AI service. */
public interface AiServiceClient {

	ExtractedText extract(String filename, String contentType, byte[] content);

	GeneratedResult analyze(String text, List<ClarificationAnswer> answers);

	GeneratedResult generateStories(StoriesRequest request);

	/** Runs one architecture step. Null fields are left out of the request, because the service rejects extras. */
	default GeneratedResult architectureStep(String step, ArchitectureStepRequest request) {
		throw new UnsupportedOperationException("Architecture steps are not available in this client");
	}

	record ArchitectureStoryInput(String key, String title, String asA, String iWant, String soThat,
			List<String> acceptanceCriteria, String priority) {
	}

	record ArchitectureStepRequest(List<ArchitectureStoryInput> stories, JsonNode startingPoint, JsonNode overview,
			JsonNode dataModel) {
	}

	record ExtractedText(String text, int characters) {
	}

	record ClarificationAnswer(String question, String answer) {
	}

	record StoriesRequest(String text, JsonNode analysis, List<ClarificationAnswer> answers, List<String> activeJobRoles,
			List<String> rejectionComments, List<String> keptStoryTitles) {
	}

	record AiUsage(String provider, String model, String promptVersion, int inputTokens, int outputTokens) {
	}

	record GeneratedResult(JsonNode result, AiUsage usage) {
	}

}
