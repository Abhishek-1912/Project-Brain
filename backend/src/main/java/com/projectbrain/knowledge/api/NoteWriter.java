package com.projectbrain.knowledge.api;

import java.util.UUID;

/** Adds a knowledge note as the current user, with the app's permission checks. The note gets indexed. */
public interface NoteWriter {

	UUID create(UUID orgId, UUID projectId, String title, String body);

}
