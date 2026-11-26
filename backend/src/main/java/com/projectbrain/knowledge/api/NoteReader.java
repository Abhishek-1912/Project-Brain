package com.projectbrain.knowledge.api;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** A project's knowledge notes, newest first, at most 100, as the current user. Checks access itself. */
public interface NoteReader {

	List<NoteSummary> list(UUID orgId, UUID projectId);

	record NoteSummary(UUID id, String title, String body, OffsetDateTime updatedAt) {
	}

}
