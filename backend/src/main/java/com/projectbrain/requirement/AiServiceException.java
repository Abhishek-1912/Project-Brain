package com.projectbrain.requirement;

/** Carries only a safe code from the AI service, never document or provider text. */
public class AiServiceException extends RuntimeException {

	private final String code;

	private final transient AiServiceClient.AiUsage usage;

	public AiServiceException(String code) {
		this(code, null);
	}

	public AiServiceException(String code, AiServiceClient.AiUsage usage) {
		super(code);
		this.code = code;
		this.usage = usage;
	}

	/** Tokens spent before the failure, when the AI service reported them. */
	public AiServiceClient.AiUsage usage() {
		return usage;
	}

	public String code() {
		return code;
	}

}
