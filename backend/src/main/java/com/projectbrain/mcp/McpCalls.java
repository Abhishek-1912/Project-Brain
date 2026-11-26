package com.projectbrain.mcp;

import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import com.projectbrain.identity.api.AccessTokenAuthentication;
import com.projectbrain.identity.api.AccessTokenResolver.ResolvedToken;
import com.projectbrain.mcp.ToolErrors.ToolInputException;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Runs one tool call as the token's user, on whatever thread the SDK chose, and shapes the result. */
@Component
class McpCalls {

	private final JsonMapper json;

	private final SecurityContextHolderStrategy contexts = SecurityContextHolder.getContextHolderStrategy();

	McpCalls(JsonMapper json) {
		this.json = json;
	}

	CallToolResult run(McpTransportContext context, Function<ResolvedToken, Object> work) {
		Object value = context.get(McpServerConfig.TOKEN_KEY);
		if (!(value instanceof ResolvedToken token)) {
			return error("Sign in with a personal access token.");
		}
		SecurityContext previous = contexts.getContext();
		SecurityContext signedIn = contexts.createEmptyContext();
		signedIn.setAuthentication(new AccessTokenAuthentication(token));
		contexts.setContext(signedIn);
		try {
			Object result = work.apply(token);
			String text = result instanceof String plain ? plain : json.writeValueAsString(result);
			return CallToolResult.builder().addTextContent(text).isError(false).build();
		}
		catch (RuntimeException ex) {
			return error(ToolErrors.message(ex));
		}
		finally {
			contexts.setContext(previous);
		}
	}

	static UUID projectId(Map<String, Object> arguments) {
		return uuid(arguments, "projectId");
	}

	static UUID uuid(Map<String, Object> arguments, String name) {
		Object value = arguments == null ? null : arguments.get(name);
		try {
			return UUID.fromString(String.valueOf(value).strip());
		}
		catch (IllegalArgumentException ex) {
			throw new ToolInputException(name + " must be an id from list_projects.");
		}
	}

	static String text(Map<String, Object> arguments, String name, boolean required) {
		Object value = arguments == null ? null : arguments.get(name);
		if (value == null || String.valueOf(value).isBlank()) {
			if (required) {
				throw new ToolInputException(name + " is required.");
			}
			return null;
		}
		return String.valueOf(value);
	}

	private static CallToolResult error(String message) {
		return CallToolResult.builder().addTextContent(message).isError(true).build();
	}

}
