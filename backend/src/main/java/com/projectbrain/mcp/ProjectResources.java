package com.projectbrain.mcp;

import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.projectbrain.identity.api.AccessTokenResolver.ResolvedToken;
import com.projectbrain.requirement.api.RequirementReader;
import com.projectbrain.requirement.api.RequirementReader.StorySummary;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncResourceTemplateSpecification;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Two Markdown resources per project. The stateless server registers resources once at startup, so they are
 * templates with a projectId slot rather than one listed resource per project.
 */
@Configuration
class ProjectResources {

	private static final int RESOURCE_NOT_FOUND = -32002;

	private static final String MARKDOWN = "text/markdown";

	private static final Pattern URI = Pattern
		.compile("^projectbrain://projects/([0-9a-fA-F-]{36})/(architecture|stories)$");

	private final McpCalls calls;

	ProjectResources(McpCalls calls) {
		this.calls = calls;
	}

	@Bean
	SyncResourceTemplateSpecification architectureResource(RequirementReader requirements) {
		return template("architecture", "Current approved architecture",
				(token, projectId) -> requirements.currentArchitectureMarkdown(token.orgId(), projectId)
					.orElse("This project has no approved architecture yet."));
	}

	@Bean
	SyncResourceTemplateSpecification storiesResource(RequirementReader requirements) {
		return template("stories", "Approved user stories",
				(token, projectId) -> stories(requirements.approvedStories(token.orgId(), projectId)));
	}

	private SyncResourceTemplateSpecification template(String kind, String title,
			BiFunction<ResolvedToken, UUID, String> read) {
		McpSchema.ResourceTemplate template = McpSchema.ResourceTemplate.builder()
			.uriTemplate("projectbrain://projects/{projectId}/" + kind)
			.name("project-" + kind)
			.title(title)
			.description(title + " for a project, as Markdown." + ReadTools.DATA_NOTE)
			.mimeType(MARKDOWN)
			.build();
		return new SyncResourceTemplateSpecification(template, (context, request) -> {
			Matcher match = URI.matcher(request.uri());
			if (!match.matches() || !match.group(2).equals(kind)) {
				throw notFound("Resource not found.");
			}
			try {
				UUID projectId = UUID.fromString(match.group(1));
				String text = calls.asUser(context, token -> read.apply(token, projectId));
				return new McpSchema.ReadResourceResult(
						List.of(new McpSchema.TextResourceContents(request.uri(), MARKDOWN, text)));
			}
			catch (McpError ex) {
				throw ex;
			}
			catch (RuntimeException ex) {
				throw notFound(ToolErrors.message(ex));
			}
		});
	}

	private static String stories(List<StorySummary> stories) {
		if (stories.isEmpty()) {
			return "This project has no approved stories yet.";
		}
		StringBuilder md = new StringBuilder("# Approved stories\n");
		for (StorySummary story : stories) {
			md.append("\n## ").append(story.title()).append("\n\n");
			md.append("As a ").append(story.asA()).append(", I want ").append(story.iWant()).append(", so that ")
				.append(story.soThat()).append(".\n\n");
			md.append("Priority: ").append(story.priority()).append(". Status: ").append(story.status()).append(".\n");
			if (!story.acceptanceCriteria().isEmpty()) {
				md.append("\nAcceptance criteria:\n");
				story.acceptanceCriteria().forEach(criterion -> md.append("- ").append(criterion).append('\n'));
			}
		}
		return md.toString();
	}

	private static McpError notFound(String message) {
		return McpError.builder(RESOURCE_NOT_FOUND).message(message).build();
	}

}
