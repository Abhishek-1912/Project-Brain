package com.projectbrain.audit.api;

import java.util.Map;
import java.util.UUID;

/** Writes inside the caller's transaction, so a failed audit rolls back the audited change. */
public interface AuditLog {

	void record(UUID orgId, String action, String entityType, UUID entityId, Map<String, Object> metadata);

}
