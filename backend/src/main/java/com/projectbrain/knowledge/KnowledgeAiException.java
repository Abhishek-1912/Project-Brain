package com.projectbrain.knowledge;

/** Carries only a safe code from the AI service, never knowledge or provider text. */
public class KnowledgeAiException extends RuntimeException {

	private final String code;

	public KnowledgeAiException(String code) {
		super(code);
		this.code = code;
	}

	public String code() {
		return code;
	}

}
