package com.projectbrain.knowledge;

import java.util.List;

/** Private connection to the stateless Python AI service for embeddings and answers. */
public interface KnowledgeAiClient {

	String EMBEDDING_MODEL = "BAAI/bge-small-en-v1.5";

	int DIMENSIONS = 384;

	List<float[]> embed(List<String> texts);

	GeneratedAnswer answer(String question, List<Passage> passages);

	record Passage(int id, String text) {
	}

	record Usage(String provider, String model, String promptVersion, int inputTokens, int outputTokens) {
	}

	record GeneratedAnswer(String answer, List<Integer> citations, boolean found, Usage usage) {
	}

}
