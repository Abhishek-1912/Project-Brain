package com.projectbrain.requirement;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import com.projectbrain.requirement.ArchitectureReviewService.ReviewView;
import com.projectbrain.requirement.ArchitectureService.ArchitectureVersion;
import tools.jackson.databind.JsonNode;

/** Renders a stored version as Markdown. Every AI or human value is escaped, so it can't break the layout. */
final class ArchitectureMarkdown {

	private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;

	private static final String ESCAPED = "\\`*_[]<>#|";

	private ArchitectureMarkdown() {
	}

	static String render(ArchitectureVersion version, List<ReviewView> reviews, Map<UUID, String> storyTitles) {
		StringBuilder md = new StringBuilder();
		md.append("# Architecture version ").append(version.versionNumber()).append("\n\n");
		md.append("Status: ").append(statusLabel(version.status())).append("  \n");
		md.append("Generated: ").append(version.createdAt().format(DATE)).append("\n\n");

		JsonNode overview = version.overview();
		md.append("## Overview\n\n").append(text(overview.path("summary").asString(""))).append("\n\n");
		md.append("### Components\n\n");
		list(md, overview.path("components"), item -> "**" + text(item.path("name").asString("")) + "**: "
				+ text(item.path("responsibility").asString(""))
				+ (item.path("technology").isString() ? " (" + text(item.path("technology").asString()) + ")" : ""));
		md.append("### Tech stack\n\n");
		table(md, List.of("Area", "Choice", "Reason"), overview.path("techStack"), item -> List
			.of(item.path("area").asString(""), item.path("choice").asString(""), item.path("reason").asString("")));
		md.append("### Key decisions\n\n");
		if (overview.path("decisions").isEmpty()) {
			md.append("None.\n\n");
		}
		for (JsonNode decision : overview.path("decisions")) {
			md.append("#### ").append(text(decision.path("title").asString(""))).append("\n\n");
			md.append("- Decision: ").append(text(decision.path("decision").asString(""))).append('\n');
			md.append("- Reason: ").append(text(decision.path("reason").asString(""))).append('\n');
			List<String> alternatives = new ArrayList<>();
			decision.path("alternatives").forEach(alternative -> alternatives.add(text(alternative.asString(""))));
			md.append("- Alternatives: ").append(alternatives.isEmpty() ? "None" : String.join(", ", alternatives))
				.append("\n\n");
		}
		md.append("### Risks\n\n");
		list(md, overview.path("risks"), item -> text(item.path("text").asString("")));

		md.append("## Data model\n\n");
		if (version.dataModel().path("tables").isEmpty()) {
			md.append("None.\n\n");
		}
		for (JsonNode table : version.dataModel().path("tables")) {
			md.append("### ").append(text(table.path("name").asString(""))).append("\n\n");
			md.append(text(table.path("description").asString(""))).append("\n\n");
			table(md, List.of("Column", "Type", "Nullable", "Description"), table.path("columns"),
					column -> List.of(column.path("name").asString(""), column.path("type").asString(""),
							column.path("nullable").asBoolean() ? "Yes" : "No", column.path("description").asString("")));
			if (!table.path("relations").isEmpty()) {
				md.append("Relations:\n\n");
				list(md, table.path("relations"), relation -> text(relation.path("column").asString("")) + " references "
						+ text(relation.path("referencesTable").asString("")) + "."
						+ text(relation.path("referencesColumn").asString("")));
			}
			md.append(stories(table, storyTitles)).append("\n\n");
		}

		md.append("## API design\n\n");
		table(md, List.of("Method", "Path", "Summary", "Roles"), version.apiDesign().path("endpoints"), endpoint -> {
			List<String> roles = new ArrayList<>();
			endpoint.path("roles").forEach(role -> roles.add(role.asString("")));
			return List.of(endpoint.path("method").asString(""), endpoint.path("path").asString(""),
					endpoint.path("summary").asString(""), roles.isEmpty() ? "Any" : String.join(", ", roles));
		});
		for (JsonNode endpoint : version.apiDesign().path("endpoints")) {
			md.append("### ").append(text(endpoint.path("method").asString(""))).append(' ')
				.append(text(endpoint.path("path").asString(""))).append("\n\n");
			for (String fields : List.of("requestFields", "responseFields")) {
				if (!endpoint.path(fields).isEmpty()) {
					md.append("requestFields".equals(fields) ? "Request:\n\n" : "Response:\n\n");
					table(md, List.of("Field", "Type", "Required", "Description"), endpoint.path(fields),
							field -> List.of(field.path("name").asString(""), field.path("type").asString(""),
									field.path("required").asBoolean() ? "Yes" : "No",
									field.path("description").asString("")));
				}
			}
			md.append(stories(endpoint, storyTitles)).append("\n\n");
		}

		md.append("## Review history\n\n");
		if (reviews.isEmpty()) {
			md.append("No decisions yet.\n");
		}
		for (ReviewView review : reviews) {
			md.append("- ").append(review.createdAt().format(DATE)).append(": ")
				.append("APPROVED".equals(review.action()) ? "Approved" : "Rejected")
				.append("OWNER_FALLBACK".equals(review.approvalBasis()) ? " by the Owner fallback" : " by an Architect")
				.append(review.selfApproved() ? ", self-approved" : "")
				.append(review.comment() == null ? "" : ". Comment: " + text(review.comment()))
				.append('\n');
		}
		return md.toString();
	}

	private static String stories(JsonNode item, Map<UUID, String> storyTitles) {
		List<String> titles = new ArrayList<>();
		item.path("storyIds").forEach(id -> {
			String title = storyTitles.get(UUID.fromString(id.asString()));
			if (title != null) {
				titles.add(text(title));
			}
		});
		return titles.isEmpty() ? "Not linked to a story." : "Stories: " + String.join("; ", titles);
	}

	private static void list(StringBuilder md, JsonNode items, Function<JsonNode, String> line) {
		if (items.isEmpty()) {
			md.append("None.\n\n");
			return;
		}
		items.forEach(item -> md.append("- ").append(line.apply(item)).append('\n'));
		md.append('\n');
	}

	private static void table(StringBuilder md, List<String> headers, JsonNode items,
			Function<JsonNode, List<String>> cells) {
		if (items.isEmpty()) {
			md.append("None.\n\n");
			return;
		}
		md.append("| ").append(String.join(" | ", headers)).append(" |\n");
		md.append("|").append(" --- |".repeat(headers.size())).append('\n');
		items.forEach(item -> md.append("| ")
			.append(String.join(" | ", cells.apply(item).stream().map(ArchitectureMarkdown::text).toList()))
			.append(" |\n"));
		md.append('\n');
	}

	/** Escapes Markdown syntax and folds line breaks, so a value stays inside its paragraph or table cell. */
	static String text(String value) {
		String flat = value.replaceAll("\\s*[\\r\\n]+\\s*", " ").strip();
		StringBuilder escaped = new StringBuilder();
		for (int i = 0; i < flat.length(); i++) {
			char c = flat.charAt(i);
			// A leading - or + would otherwise start a list.
			if (ESCAPED.indexOf(c) >= 0 || (i == 0 && (c == '-' || c == '+'))) {
				escaped.append('\\');
			}
			escaped.append(c);
		}
		return escaped.toString();
	}

	private static String statusLabel(String status) {
		return switch (status) {
			case "APPROVED" -> "Approved";
			case "REJECTED" -> "Rejected";
			case "SUPERSEDED" -> "Superseded";
			default -> "Draft";
		};
	}

}
