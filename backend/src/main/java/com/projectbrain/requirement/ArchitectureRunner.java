package com.projectbrain.requirement;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.projectbrain.audit.api.AuditLog;
import com.projectbrain.organization.api.OrgTx;
import com.projectbrain.organization.api.Permission;
import com.projectbrain.project.api.ProjectLookup;
import com.projectbrain.project.api.ProjectStatus;
import com.projectbrain.requirement.AiServiceClient.ArchitectureStepRequest;
import com.projectbrain.requirement.AiServiceClient.GeneratedResult;
import com.projectbrain.shared.web.ConflictException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Runs the three architecture steps in order. No transaction is held during an AI call, access is rechecked before
 * every write, and the version is saved only after all three steps pass.
 */
@Component
class ArchitectureRunner {

	private final OrgTx orgTx;

	private final AiRunStore runs;

	private final ArchitectureVersionStore versions;

	private final ArchitectureInputService inputs;

	private final ArchitectureValidator validator;

	private final AiServiceClient ai;

	private final ProjectLookup projects;

	private final AuditLog auditLog;

	ArchitectureRunner(OrgTx orgTx, AiRunStore runs, ArchitectureVersionStore versions, ArchitectureInputService inputs,
			ArchitectureValidator validator, AiServiceClient ai, ProjectLookup projects, AuditLog auditLog) {
		this.orgTx = orgTx;
		this.runs = runs;
		this.versions = versions;
		this.inputs = inputs;
		this.validator = validator;
		this.ai = ai;
		this.projects = projects;
		this.auditLog = auditLog;
	}

	void run(AiRunWorker.Job job) {
		record Start(ArchitectureInput input, Map<String, JsonNode> done) {
		}
		Start start = write(job, () -> {
			if (!runs.markRunning(job.orgId(), job.runId())) {
				throw new IllegalStateException("Run is no longer queued");
			}
			ArchitectureInput input = inputs.load(job.orgId(), job.projectId());
			if (!input.fingerprint().equals(runs.fingerprint(job.orgId(), job.runId()))) {
				// The stories or starting point changed between starting the run and running it.
				throw new AiServiceException("INPUTS_CHANGED");
			}
			return new Start(input, versions.stepOutputs(job.orgId(), job.runId()));
		});
		ArchitectureInput input = start.input();
		Map<String, JsonNode> done = start.done();
		for (String step : ArchitecturePrompts.STEPS) {
			if (done.containsKey(step)) {
				continue;
			}
			write(job, () -> {
				runs.setStep(job.orgId(), job.runId(), step);
				return null;
			});
			GeneratedResult generated = ai.architectureStep(step, request(step, input, done));
			JsonNode output = write(job, () -> {
				if (!ArchitecturePrompts.VERSIONS.get(step).equals(generated.usage().promptVersion())) {
					throw new InvalidAiOutputException();
				}
				check(step, generated.result(), input.storyIdsByKey());
				versions.insertStep(job.orgId(), job.runId(), step, generated.result(), generated.usage());
				runs.addUsage(job.orgId(), job.runId(), generated.usage());
				return generated.result();
			});
			done.put(step, output);
		}
		write(job, () -> {
			JsonNode overview = validator.overview(done.get("OVERVIEW"));
			JsonNode dataModel = validator.dataModel(done.get("DATA_MODEL"), input.storyIdsByKey());
			JsonNode apiDesign = validator.apiDesign(done.get("API_DESIGN"), input.storyIdsByKey());
			List<UUID> storyIds = List.copyOf(input.storyIdsByKey().values());
			UUID version = versions.saveDraft(job.orgId(), job.projectId(), job.runId(), input.startingVersionId(),
					job.approverRoleId(), overview, dataModel, apiDesign, storyIds, job.actor());
			if (!runs.succeedKeepingUsage(job.orgId(), job.runId())) {
				throw new IllegalStateException("Run is no longer running");
			}
			auditLog.recordAs(job.actor(), job.orgId(), "AI_RUN_SUCCEEDED", "AI_RUN", job.runId(),
					Map.of("runType", "ARCHITECTURE", "versionId", version.toString()));
			return null;
		});
	}

	private static ArchitectureStepRequest request(String step, ArchitectureInput input, Map<String, JsonNode> done) {
		JsonNode overview = "OVERVIEW".equals(step) ? null : done.get("OVERVIEW");
		JsonNode dataModel = "API_DESIGN".equals(step) ? done.get("DATA_MODEL") : null;
		return new ArchitectureStepRequest(input.stories(), input.startingPoint(), overview, dataModel);
	}

	private void check(String step, JsonNode result, Map<String, UUID> storyIdsByKey) {
		switch (step) {
			case "OVERVIEW" -> validator.overview(result);
			case "DATA_MODEL" -> validator.dataModel(result, storyIdsByKey);
			default -> validator.apiDesign(result, storyIdsByKey);
		}
	}

	private <T> T write(AiRunWorker.Job job, java.util.function.Supplier<T> work) {
		return orgTx.inOrgAs(job.actor(), job.orgId(), Permission.MANAGE_PROJECTS, () -> {
			if (projects.requireStatus(job.orgId(), job.projectId()) == ProjectStatus.ARCHIVED) {
				throw new ConflictException("The project is archived.");
			}
			return work.get();
		});
	}

}
