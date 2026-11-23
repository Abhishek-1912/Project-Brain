package com.projectbrain.task;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/** A Java null component means the field was absent. Optional.empty() means an explicit JSON null. */
record TaskPatch(Optional<String> title, Optional<String> description, Optional<TaskStatus> status,
		Optional<TaskPriority> priority, Optional<UUID> assigneeId, Optional<LocalDate> dueDate,
		Optional<UUID> responsibleJobRoleId, long version) {
}
