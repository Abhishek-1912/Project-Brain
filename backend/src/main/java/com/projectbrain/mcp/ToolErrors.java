package com.projectbrain.mcp;

import com.projectbrain.shared.web.ConflictException;
import com.projectbrain.shared.web.ForbiddenException;
import com.projectbrain.shared.web.NotFoundException;
import com.projectbrain.shared.web.ServiceUnavailableException;
import com.projectbrain.shared.web.UnprocessableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Turns the app's errors into plain messages for MCP clients, never stack traces or internal details. */
final class ToolErrors {

	private static final Logger log = LoggerFactory.getLogger(ToolErrors.class);

	private ToolErrors() {
	}

	static String message(RuntimeException ex) {
		if (ex instanceof NotFoundException || ex instanceof ForbiddenException || ex instanceof ConflictException
				|| ex instanceof UnprocessableException || ex instanceof ServiceUnavailableException
				|| ex instanceof ToolInputException) {
			return ex.getMessage();
		}
		log.error("MCP tool failed: {}", ex.getClass().getSimpleName());
		return "Something went wrong. Try again.";
	}

	/** A bad tool argument, described for the person or agent calling the tool. */
	static final class ToolInputException extends RuntimeException {

		ToolInputException(String message) {
			super(message);
		}

	}

}
