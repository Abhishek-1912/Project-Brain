package com.projectbrain.requirement.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Reviewed requirement content, read as the current user. Checks access itself. */
public interface RequirementReader {

	/** The current approved architecture as Markdown, without review history. */
	Optional<String> currentArchitectureMarkdown(UUID orgId, UUID projectId);

	/** Approved stories, including ones already turned into tasks. */
	List<StorySummary> approvedStories(UUID orgId, UUID projectId);

	record StorySummary(UUID id, String title, String asA, String iWant, String soThat,
			List<String> acceptanceCriteria, String priority, String status) {
	}

}
