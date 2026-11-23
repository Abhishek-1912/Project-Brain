package com.projectbrain.requirement;

import java.util.Map;
import java.util.UUID;

import com.projectbrain.audit.api.AuditLog;
import com.projectbrain.organization.api.OrgTx;
import com.projectbrain.organization.api.Permission;
import com.projectbrain.project.api.ProjectLookup;
import com.projectbrain.project.api.ProjectStatus;
import com.projectbrain.requirement.AiServiceClient.GeneratedResult;
import com.projectbrain.requirement.AnalysisService.AnalysisInput;
import com.projectbrain.requirement.AnalysisService.InvalidAnalysisException;
import com.projectbrain.shared.web.ConflictException;
import com.projectbrain.shared.web.ForbiddenException;
import com.projectbrain.shared.web.NotFoundException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/** Holds no database transaction during the AI call, and re-checks access before saving its result. */
@Component
class AiRunWorker {

	private static final Logger log = LoggerFactory.getLogger(AiRunWorker.class);

	private static final int THREADS = 2;

	private static final int QUEUE = 20;

	private final OrgTx orgTx;

	private final AiRunStore store;

	private final AnalysisService analyses;

	private final AiServiceClient ai;

	private final ProjectLookup projects;

	private final AuditLog auditLog;

	private final ThreadPoolTaskExecutor executor;

	AiRunWorker(OrgTx orgTx, AiRunStore store, AnalysisService analyses, AiServiceClient ai, ProjectLookup projects,
			AuditLog auditLog) {
		this.orgTx = orgTx;
		this.store = store;
		this.analyses = analyses;
		this.ai = ai;
		this.projects = projects;
		this.auditLog = auditLog;
		this.executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(THREADS);
		executor.setMaxPoolSize(THREADS);
		executor.setQueueCapacity(QUEUE);
		executor.setThreadNamePrefix("ai-run-");
		executor.initialize();
	}

	/** Returns false, with the run marked failed, when too many runs are already waiting. */
	boolean submit(Job job) {
		try {
			executor.execute(() -> run(job));
			return true;
		}
		catch (TaskRejectedException ex) {
			fail(job, "SERVICE_BUSY");
			return false;
		}
	}

	@PreDestroy
	void stop() {
		executor.shutdown();
	}

	private void run(Job job) {
		try {
			AnalysisInput input = orgTx.inOrgAs(job.actor(), job.orgId(), Permission.MANAGE_PROJECTS, () -> {
				requireWritable(job);
				if (!store.markRunning(job.orgId(), job.runId())) {
					throw new IllegalStateException("Run is no longer queued");
				}
				return analyses.loadInput(job.orgId(), job.documentId());
			});
			GeneratedResult generated = ai.analyze(input.text(), input.answers());
			orgTx.inOrgAs(job.actor(), job.orgId(), Permission.MANAGE_PROJECTS, () -> {
				requireWritable(job);
				analyses.save(job.orgId(), job.documentId(), job.runId(), job.actor(), generated.result());
				if (!store.succeed(job.orgId(), job.runId(), generated.usage())) {
					throw new IllegalStateException("Run is no longer running");
				}
				auditLog.recordAs(job.actor(), job.orgId(), "AI_RUN_SUCCEEDED", "AI_RUN", job.runId(),
						Map.of("runType", "ANALYSIS", "inputTokens", generated.usage().inputTokens(), "outputTokens",
								generated.usage().outputTokens()));
				return null;
			});
		}
		catch (RuntimeException ex) {
			String code = errorCode(ex);
			if ("INTERNAL_ERROR".equals(code)) {
				log.error("AI run {} failed: {}", job.runId(), ex.getClass().getSimpleName());
			}
			fail(job, code);
		}
	}

	private void requireWritable(Job job) {
		if (projects.requireStatus(job.orgId(), job.projectId()) == ProjectStatus.ARCHIVED) {
			throw new ConflictException("The project is archived.");
		}
	}

	private void fail(Job job, String code) {
		try {
			orgTx.withOrgContext(job.actor(), job.orgId(), () -> {
				if (store.fail(job.orgId(), job.runId(), code)) {
					auditLog.recordAs(job.actor(), job.orgId(), "AI_RUN_FAILED", "AI_RUN", job.runId(),
							Map.of("runType", "ANALYSIS", "errorCode", code));
				}
				return null;
			});
		}
		catch (RuntimeException ex) {
			log.error("Could not mark AI run {} failed: {}", job.runId(), ex.getClass().getSimpleName());
		}
	}

	private static String errorCode(RuntimeException ex) {
		if (ex instanceof AiServiceException service) {
			return service.code();
		}
		if (ex instanceof InvalidAnalysisException) {
			return "INVALID_OUTPUT";
		}
		if (ex instanceof ForbiddenException || ex instanceof NotFoundException) {
			return "ACCESS_REVOKED";
		}
		if (ex instanceof ConflictException) {
			return "PROJECT_ARCHIVED";
		}
		return "INTERNAL_ERROR";
	}

	record Job(UUID runId, UUID orgId, UUID projectId, UUID documentId, UUID actor) {
	}

}
