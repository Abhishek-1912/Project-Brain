package com.projectbrain.requirement;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import com.projectbrain.requirement.AiServiceClient.AiUsage;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Every method must run inside an organization transaction. */
@Component
class AiRunStore {

	private static final String COLUMNS = """
			id, document_id, run_type, status, provider, model, prompt_version, input_tokens, output_tokens,
			billing_tier, cost_usd, error_code, created_at, started_at, finished_at
			""";

	private static final RowMapper<RunView> MAPPER = (row, rowNumber) -> new RunView(row.getObject("id", UUID.class),
			row.getObject("document_id", UUID.class), row.getString("run_type"), row.getString("status"),
			row.getString("provider"), row.getString("model"), row.getString("prompt_version"),
			row.getInt("input_tokens"), row.getInt("output_tokens"), row.getString("billing_tier"),
			row.getBigDecimal("cost_usd"), row.getString("error_code"),
			row.getObject("created_at", OffsetDateTime.class), row.getObject("started_at", OffsetDateTime.class),
			row.getObject("finished_at", OffsetDateTime.class));

	private final JdbcClient jdbc;

	AiRunStore(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Returns false when the document already has an active run. */
	boolean insert(UUID id, UUID orgId, UUID projectId, UUID documentId, String runType, UUID requestedBy) {
		return jdbc.sql("""
				INSERT INTO brain.ai_runs (id, organization_id, project_id, document_id, run_type, requested_by)
				VALUES (:id, :orgId, :projectId, :documentId, :runType, :requestedBy)
				ON CONFLICT (organization_id, document_id) WHERE status IN ('QUEUED', 'RUNNING') DO NOTHING
				""")
			.param("id", id)
			.param("orgId", orgId)
			.param("projectId", projectId)
			.param("documentId", documentId)
			.param("runType", runType)
			.param("requestedBy", requestedBy)
			.update() == 1;
	}

	boolean markRunning(UUID orgId, UUID id) {
		return jdbc.sql("""
				UPDATE brain.ai_runs SET status = 'RUNNING', started_at = now()
				WHERE organization_id = :orgId AND id = :id AND status = 'QUEUED'
				""")
			.param("orgId", orgId)
			.param("id", id)
			.update() == 1;
	}

	boolean succeed(UUID orgId, UUID id, AiUsage usage) {
		return jdbc.sql("""
				UPDATE brain.ai_runs
				SET status = 'SUCCEEDED', provider = :provider, model = :model, prompt_version = :promptVersion,
				    input_tokens = :inputTokens, output_tokens = :outputTokens, finished_at = now()
				WHERE organization_id = :orgId AND id = :id AND status = 'RUNNING'
				""")
			.param("provider", usage.provider())
			.param("model", usage.model())
			.param("promptVersion", usage.promptVersion())
			.param("inputTokens", usage.inputTokens())
			.param("outputTokens", usage.outputTokens())
			.param("orgId", orgId)
			.param("id", id)
			.update() == 1;
	}

	boolean fail(UUID orgId, UUID id, String errorCode, AiUsage usage) {
		return jdbc.sql("""
				UPDATE brain.ai_runs
				SET status = 'FAILED', error_code = :errorCode, finished_at = now(), provider = :provider, model = :model,
				    prompt_version = :promptVersion, input_tokens = :inputTokens, output_tokens = :outputTokens
				WHERE organization_id = :orgId AND id = :id AND status IN ('QUEUED', 'RUNNING')
				""")
			.param("errorCode", errorCode)
			.param("provider", usage == null ? null : usage.provider(), Types.VARCHAR)
			.param("model", usage == null ? null : usage.model(), Types.VARCHAR)
			.param("promptVersion", usage == null ? null : usage.promptVersion(), Types.VARCHAR)
			.param("inputTokens", usage == null ? 0 : usage.inputTokens())
			.param("outputTokens", usage == null ? 0 : usage.outputTokens())
			.param("orgId", orgId)
			.param("id", id)
			.update() == 1;
	}

	Optional<RunView> find(UUID orgId, UUID id) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM brain.ai_runs WHERE organization_id = :orgId AND id = :id")
			.param("orgId", orgId)
			.param("id", id)
			.query(MAPPER)
			.optional();
	}

	Optional<RunView> latest(UUID orgId, UUID documentId, String runType) {
		return jdbc.sql("SELECT " + COLUMNS + """
				FROM brain.ai_runs
				WHERE organization_id = :orgId AND document_id = :documentId AND run_type = :runType
				ORDER BY created_at DESC, id
				LIMIT 1
				""")
			.param("orgId", orgId)
			.param("documentId", documentId)
			.param("runType", runType)
			.query(MAPPER)
			.optional();
	}

	record RunView(UUID id, UUID documentId, String runType, String status, String provider, String model,
			String promptVersion, int inputTokens, int outputTokens, String billingTier, BigDecimal costUsd,
			String errorCode, OffsetDateTime createdAt, OffsetDateTime startedAt, OffsetDateTime finishedAt) {
	}

}
