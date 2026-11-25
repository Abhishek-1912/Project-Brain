package com.projectbrain.requirement;

import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.projectbrain.requirement.AiServiceClient.AiUsage;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Every method must run inside an organization transaction. */
@Component
class ArchitectureVersionStore {

	private final JdbcClient jdbc;

	private final JsonMapper json;

	ArchitectureVersionStore(JdbcClient jdbc, JsonMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	/** Serializes generation, editing, and review of one project's architecture until the transaction ends. */
	void lockProject(UUID projectId) {
		jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended('architecture:' || :projectId, 0))")
			.param("projectId", projectId.toString())
			.query((row, rowNumber) -> 1)
			.list();
	}

	/** Reuses finished steps of the newest failed run with the same inputs. */
	int copyReusableSteps(UUID orgId, UUID projectId, UUID runId, String fingerprint) {
		return jdbc.sql("""
				INSERT INTO brain.architecture_run_steps
				    (organization_id, ai_run_id, step, output, provider, model, prompt_version, input_tokens,
				     output_tokens, reused_from_run_id)
				SELECT s.organization_id, :runId, s.step, s.output, s.provider, s.model, s.prompt_version,
				       s.input_tokens, s.output_tokens, COALESCE(s.reused_from_run_id, s.ai_run_id)
				FROM brain.architecture_run_steps s
				WHERE s.organization_id = :orgId AND s.ai_run_id = (
				    SELECT r.id FROM brain.ai_runs r
				    WHERE r.organization_id = :orgId AND r.project_id = :projectId AND r.run_type = 'ARCHITECTURE'
				      AND r.status = 'FAILED' AND r.input_fingerprint = :fingerprint
				    ORDER BY r.created_at DESC, r.id
				    LIMIT 1
				)
				""")
			.param("runId", runId)
			.param("orgId", orgId)
			.param("projectId", projectId)
			.param("fingerprint", fingerprint)
			.update();
	}

	Map<String, JsonNode> stepOutputs(UUID orgId, UUID runId) {
		Map<String, JsonNode> outputs = new LinkedHashMap<>();
		jdbc.sql("SELECT step, output::text AS output FROM brain.architecture_run_steps WHERE organization_id = :orgId AND ai_run_id = :runId")
			.param("orgId", orgId)
			.param("runId", runId)
			.query((row, rowNumber) -> Map.entry(row.getString("step"), json.readTree(row.getString("output"))))
			.list()
			.forEach(entry -> outputs.put(entry.getKey(), entry.getValue()));
		return outputs;
	}

	void insertStep(UUID orgId, UUID runId, String step, JsonNode output, AiUsage usage) {
		jdbc.sql("""
				INSERT INTO brain.architecture_run_steps
				    (organization_id, ai_run_id, step, output, provider, model, prompt_version, input_tokens, output_tokens)
				VALUES (:orgId, :runId, :step, CAST(:output AS jsonb), :provider, :model, :promptVersion, :inputTokens,
				        :outputTokens)
				""")
			.param("orgId", orgId)
			.param("runId", runId)
			.param("step", step)
			.param("output", json.writeValueAsString(output))
			.param("provider", usage.provider())
			.param("model", usage.model())
			.param("promptVersion", usage.promptVersion())
			.param("inputTokens", usage.inputTokens())
			.param("outputTokens", usage.outputTokens())
			.update();
	}

	/** Replaces any unreviewed draft, because a project keeps at most one draft. */
	UUID saveDraft(UUID orgId, UUID projectId, UUID runId, UUID basedOnVersionId, UUID approverRoleId, JsonNode overview,
			JsonNode dataModel, JsonNode apiDesign, List<UUID> storyIds, UUID generatedBy) {
		jdbc.sql("""
				UPDATE brain.architecture_versions SET status = 'SUPERSEDED', updated_at = now(), version = version + 1
				WHERE organization_id = :orgId AND project_id = :projectId AND status = 'DRAFT'
				""")
			.param("orgId", orgId)
			.param("projectId", projectId)
			.update();
		int number = jdbc.sql("""
				SELECT COALESCE(MAX(version_number), 0) + 1 FROM brain.architecture_versions
				WHERE organization_id = :orgId AND project_id = :projectId
				""")
			.param("orgId", orgId)
			.param("projectId", projectId)
			.query(Integer.class)
			.single();
		UUID id = UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO brain.architecture_versions
				    (id, organization_id, project_id, version_number, ai_run_id, based_on_version_id, approver_job_role_id,
				     overview, data_model, api_design, story_ids, generated_by)
				VALUES (:id, :orgId, :projectId, :number, :runId, :basedOn, :approverRole, CAST(:overview AS jsonb),
				        CAST(:dataModel AS jsonb), CAST(:apiDesign AS jsonb), CAST(:storyIds AS jsonb), :generatedBy)
				""")
			.param("id", id)
			.param("orgId", orgId)
			.param("projectId", projectId)
			.param("number", number)
			.param("runId", runId)
			.param("basedOn", basedOnVersionId, Types.OTHER)
			.param("approverRole", approverRoleId, Types.OTHER)
			.param("overview", json.writeValueAsString(overview))
			.param("dataModel", json.writeValueAsString(dataModel))
			.param("apiDesign", json.writeValueAsString(apiDesign))
			.param("storyIds", json.writeValueAsString(storyIds.stream().map(UUID::toString).toList()))
			.param("generatedBy", generatedBy)
			.update();
		return id;
	}

}
