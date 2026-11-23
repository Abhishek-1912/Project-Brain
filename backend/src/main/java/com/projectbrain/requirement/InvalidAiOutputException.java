package com.projectbrain.requirement;

/** The AI result failed Spring's own checks, so nothing from it may be saved. */
class InvalidAiOutputException extends RuntimeException {

	InvalidAiOutputException() {
		super("INVALID_OUTPUT");
	}

}
