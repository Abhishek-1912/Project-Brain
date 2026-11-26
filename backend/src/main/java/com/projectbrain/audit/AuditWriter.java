package com.projectbrain.audit;

import java.util.LinkedHashMap;
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
		recordAs(currentUser.get().id(), orgId, action, entityType, entityId, metadata);
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void recordAs(UUID actorId, UUID orgId, String action, String entityType, UUID entityId,
			Map<String, Object> metadata) {
		Map<String, Object> recorded = currentUser.accessToken().<Map<String, Object>>map(token -> {
			Map<String, Object> withChannel = new LinkedHashMap<>(metadata);
			withChannel.put("via", "MCP");
			withChannel.put("tokenId", token.tokenId().toString());
			return withChannel;
		}).orElse(metadata);
		jdbc.sql("""
				INSERT INTO brain.audit_events (id, organization_id, actor_id, action, entity_type, entity_id, metadata)
				VALUES (:id, :orgId, :actorId, :action, :entityType, :entityId, CAST(:metadata AS jsonb))
				""")
			.param("id", UUID.randomUUID())
			.param("orgId", orgId)
			.param("actorId", actorId)
			.param("action", action)
			.param("entityType", entityType)
			.param("entityId", entityId)
			.param("metadata", json.writeValueAsString(recorded))
			.update();
	}

}
