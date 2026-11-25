package com.projectbrain.requirement;

import java.util.List;
import java.util.Map;

/** Must match the prompt versions in ai-service/app/prompts.py, which ship together with this code. */
final class ArchitecturePrompts {

	static final List<String> STEPS = List.of("OVERVIEW", "DATA_MODEL", "API_DESIGN");

	static final Map<String, String> VERSIONS = Map.of("OVERVIEW", "architecture-overview-v1", "DATA_MODEL",
			"architecture-data-model-v1", "API_DESIGN", "architecture-api-v1");

	private ArchitecturePrompts() {
	}

}
