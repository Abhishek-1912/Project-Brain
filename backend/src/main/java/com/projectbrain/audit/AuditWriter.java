package com.projectbrain.audit;

import java.util.Map;
import java.util.UUID;

import com.projectbrain.audit.api.AuditLog;
import com.projectbrain.identity.api.CurrentUserProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Component
class AuditWriter implements AuditLog {

	private final JdbcClient jdbc;

	private final CurrentUserProvider currentUser;

	private final JsonMapper json;

	AuditWriter(JdbcClient jdbc, CurrentUserProvider currentUser, JsonMapper json) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.json = json;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void record(UUID orgId, String action, String entityType, UUID entityId, Map<String, Object> metadata) {
		jdbc.sql("""
				INSERT INTO brain.audit_events (id, organization_id, actor_id, action, entity_type, entity_id, metadata)
				VALUES (:id, :orgId, :actorId, :action, :entityType, :entityId, CAST(:metadata AS jsonb))
				""")
			.param("id", UUID.randomUUID())
			.param("orgId", orgId)
			.param("actorId", currentUser.get().id())
			.param("action", action)
			.param("entityType", entityType)
			.param("entityId", entityId)
			.param("metadata", json.writeValueAsString(metadata))
			.update();
	}

}
