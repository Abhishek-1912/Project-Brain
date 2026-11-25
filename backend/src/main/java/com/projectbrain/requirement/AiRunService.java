package com.projectbrain.requirement;

import java.util.Map;
import java.util.UUID;

import com.projectbrain.audit.api.AuditLog;
import com.projectbrain.identity.api.CurrentUserProvider;
import com.projectbrain.organization.api.OrgTx;
import com.projectbrain.organization.api.Permission;
import com.projectbrain.project.api.ProjectLookup;
import com.projectbrain.project.api.ProjectStatus;
import com.projectbrain.requirement.AiRunStore.RunView;
import com.projectbrain.shared.web.ConflictException;
import com.projectbrain.shared.web.NotFoundException;
import com.projectbrain.shared.web.ServiceUnavailableException;
import com.projectbrain.team.api.JobRoleLookup;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
class AiRunService {

	private static final String ARCHITECT_ROLE = "Architect";

	private final OrgTx orgTx;

	private final JdbcClient jdbc;

	private final AiRunStore store;

	private final AiRunWorker worker;

	private final ProjectLookup projects;

	private final CurrentUserProvider currentUser;

	private final AuditLog auditLog;

	private final AnalysisService analyses;

	private final ArchitectureInputService architectureInputs;

	private final ArchitectureVersionStore versions;

	private final JobRoleLookup jobRoles;

	AiRunService(OrgTx orgTx, JdbcClient jdbc, AiRunStore store, AiRunWorker worker, ProjectLookup projects,
			CurrentUserProvider currentUser, AuditLog auditLog, AnalysisService analyses,
			ArchitectureInputService architectureInputs, ArchitectureVersionStore versions, JobRoleLookup jobRoles) {
		this.orgTx = orgTx;
		this.jdbc = jdbc;
		this.store = store;
		this.worker = worker;
		this.projects = projects;
		this.currentUser = currentUser;
		this.auditLog = auditLog;
		this.analyses = analyses;
		this.architectureInputs = architectureInputs;
		this.versions = versions;
		this.jobRoles = jobRoles;
	}

	RunAccepted startAnalysis(UUID orgId, UUID documentId) {
		return start(orgId, documentId, "ANALYSIS");
	}

	RunAccepted startArchitecture(UUID orgId, UUID projectId) {
		UUID actor = currentUser.get().id();
		UUID runId = UUID.randomUUID();
		UUID approverRole = orgTx.inOrg(orgId, Permission.MANAGE_PROJECTS, () -> {
			versions.lockProject(projectId);
			if (projects.requireStatus(orgId, projectId) == ProjectStatus.ARCHIVED) {
				throw new ConflictException("The project is archived.");
			}
			ArchitectureInput input = architectureInputs.load(orgId, projectId);
			if (!store.insertArchitecture(runId, orgId, projectId, actor, input.fingerprint())) {
				throw new ConflictException("An architecture is already being generated for this project.");
			}
			versions.copyReusableSteps(orgId, projectId, runId, input.fingerprint());
			auditLog.record(orgId, "AI_RUN_STARTED", "AI_RUN", runId,
					Map.of("runType", "ARCHITECTURE", "projectId", projectId.toString()));
			return jobRoles.findActiveByName(orgId, ARCHITECT_ROLE).orElse(null);
		});
		if (!worker.submit(new AiRunWorker.Job(runId, orgId, projectId, null, actor, "ARCHITECTURE", approverRole))) {
			throw new ServiceUnavailableException("Too many AI runs are waiting. Try again shortly.");
		}
		return new RunAccepted(runId);
	}

	RunAccepted startStories(UUID orgId, UUID documentId) {
		return start(orgId, documentId, "STORIES");
	}

	private RunAccepted start(UUID orgId, UUID documentId, String runType) {
		UUID actor = currentUser.get().id();
		UUID runId = UUID.randomUUID();
		// The worker starts only after this transaction commits, so it can always see the run.
		UUID projectId = orgTx.inOrg(orgId, Permission.MANAGE_PROJECTS, () -> {
			UUID project = documentProject(orgId, documentId);
			if (projects.requireStatus(orgId, project) == ProjectStatus.ARCHIVED) {
				throw new ConflictException("The project is archived.");
			}
			if ("STORIES".equals(runType)) {
				analyses.storyBasis(orgId, documentId);
			}
			if (!store.insert(runId, orgId, project, documentId, runType, actor)) {
				throw new ConflictException("An AI run is already active for this document.");
			}
			auditLog.record(orgId, "AI_RUN_STARTED", "AI_RUN", runId,
					Map.of("runType", runType, "documentId", documentId.toString()));
			return project;
		});
		if (!worker.submit(new AiRunWorker.Job(runId, orgId, projectId, documentId, actor, runType))) {
			throw new ServiceUnavailableException("Too many AI runs are waiting. Try again shortly.");
		}
		return new RunAccepted(runId);
	}

	RunView get(UUID orgId, UUID runId) {
		return orgTx.inOrg(orgId, Permission.VIEW,
				() -> store.find(orgId, runId).orElseThrow(() -> new NotFoundException("AI run not found.")));
	}

	private UUID documentProject(UUID orgId, UUID documentId) {
		return jdbc.sql("""
				SELECT project_id FROM brain.requirement_documents
				WHERE organization_id = :orgId AND id = :id
				""")
			.param("orgId", orgId)
			.param("id", documentId)
			.query(UUID.class)
			.optional()
			.orElseThrow(() -> new NotFoundException("Requirement document not found."));
	}

	record RunAccepted(UUID runId) {
	}

}
