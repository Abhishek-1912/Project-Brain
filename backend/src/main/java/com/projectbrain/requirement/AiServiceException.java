package com.projectbrain.requirement;

/** Carries only a safe code from the AI service, never document or provider text. */
public class AiServiceException extends RuntimeException {

	private final String code;

	public AiServiceException(String code) {
		super(code);
		this.code = code;
	}

	public String code() {
		return code;
	}

}
