package com.projectbrain.requirement;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.projectbrain.requirement.AiServiceClient.ArchitectureStoryInput;
import tools.jackson.databind.JsonNode;

/** Everything an architecture run sends to the AI, plus what Spring needs to map its answer back. */
record ArchitectureInput(List<ArchitectureStoryInput> stories, Map<String, UUID> storyIdsByKey, UUID startingVersionId,
		JsonNode startingPoint, String fingerprint) {
}
