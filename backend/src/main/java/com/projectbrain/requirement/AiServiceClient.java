package com.projectbrain.requirement;

/** Private connection to the stateless Python AI service. */
public interface AiServiceClient {

	ExtractedText extract(String filename, String contentType, byte[] content);

	record ExtractedText(String text, int characters) {
	}

}
