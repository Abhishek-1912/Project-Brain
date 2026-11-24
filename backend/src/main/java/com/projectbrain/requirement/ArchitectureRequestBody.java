package com.projectbrain.requirement;

import com.projectbrain.requirement.AiServiceClient.ArchitectureStepRequest;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** One JSON shape for both the size check and the request, so the two can never disagree. */
final class ArchitectureRequestBody {

	private ArchitectureRequestBody() {
	}

	static ObjectNode of(JsonMapper json, ArchitectureStepRequest request) {
		ObjectNode body = json.createObjectNode();
		body.set("stories", json.valueToTree(request.stories()));
		if (request.startingPoint() != null) {
			body.set("startingPoint", request.startingPoint());
		}
		if (request.overview() != null) {
			body.set("overview", request.overview());
		}
		if (request.dataModel() != null) {
			body.set("dataModel", request.dataModel());
		}
		return body;
	}

}
