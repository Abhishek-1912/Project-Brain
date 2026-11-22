package com.projectbrain.audit;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.projectbrain.organization.api.OrgTx;
import com.projectbrain.organization.api.Permission;
import com.projectbrain.shared.web.PageParams;
import com.projectbrain.shared.web.PageResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@RestController
class AuditController {

	private static final TypeReference<Map<String, Object>> METADATA_TYPE = new TypeReference<>() {
	};

	private final OrgTx orgTx;

	private final JdbcClient jdbc;

	private final JsonMapper json;

	AuditController(OrgTx orgTx, JdbcClient jdbc, JsonMapper json) {
		this.orgTx = orgTx;
		this.jdbc = jdbc;
		this.json = json;
	}

	@GetMapping("/api/v1/organizations/{orgId}/audit-events")
	PageResponse<AuditEvent> list(@PathVariable UUID orgId, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		PageParams paging = PageParams.of(page, size);
		return orgTx.inOrg(orgId, Permission.VIEW_AUDIT, () -> {
			long total = jdbc.sql("SELECT count(*) FROM brain.audit_events WHERE organization_id = :orgId")
				.param("orgId", orgId)
				.query(Long.class)
				.single();
			List<AuditEvent> items = jdbc.sql("""
					SELECT id, organization_id, actor_id, action, entity_type, entity_id, metadata::text AS metadata, created_at
					FROM brain.audit_events
					WHERE organization_id = :orgId
					ORDER BY created_at DESC, id DESC
					LIMIT :limit OFFSET :offset
					""")
				.param("orgId", orgId)
				.param("limit", paging.size())
				.param("offset", paging.offset())
				.query((row, rowNumber) -> new AuditEvent(row.getObject("id", UUID.class),
						row.getObject("organization_id", UUID.class), row.getObject("actor_id", UUID.class),
						row.getString("action"), row.getString("entity_type"), row.getObject("entity_id", UUID.class),
						json.readValue(row.getString("metadata"), METADATA_TYPE),
						row.getObject("created_at", OffsetDateTime.class)))
				.list();
			return new PageResponse<>(items, paging.page(), paging.size(), total);
		});
	}

	record AuditEvent(UUID id, UUID organizationId, UUID actorId, String action, String entityType, UUID entityId,
			Map<String, Object> metadata, OffsetDateTime createdAt) {
	}

}
