package com.projectbrain.requirement;

import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

import com.projectbrain.audit.api.AuditLog;
import com.projectbrain.organization.api.OrgTx;
import com.projectbrain.organization.api.Permission;
import com.projectbrain.project.api.ProjectLookup;
import com.projectbrain.project.api.ProjectStatus;
import com.projectbrain.requirement.AiServiceClient.AiUsage;
import com.projectbrain.requirement.AiServiceClient.GeneratedResult;
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

	private static final long[] FAIL_RETRY_MILLIS = { 250, 1000, 3000 };

	private final OrgTx orgTx;

	private final AiRunStore store;

	private final AnalysisService analyses;

	private final StoryService stories;

	private final ArchitectureRunner architecture;

	private final AiServiceClient ai;

	private final ProjectLookup projects;

	private final AuditLog auditLog;

	private final ThreadPoolTaskExecutor executor;

	AiRunWorker(OrgTx orgTx, AiRunStore store, AnalysisService analyses, StoryService stories, AiServiceClient ai,
			ProjectLookup projects, AuditLog auditLog, ArchitectureRunner architecture) {
		this.orgTx = orgTx;
		this.store = store;
		this.analyses = analyses;
		this.stories = stories;
		this.architecture = architecture;
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
			fail(job, "SERVICE_BUSY", null);
			return false;
		}
	}

	@PreDestroy
	void stop() {
		executor.shutdown();
	}

	private void run(Job job) {
		try {
			if ("ARCHITECTURE".equals(job.runType())) {
				architecture.run(job);
			}
			else if ("STORIES".equals(job.runType())) {
				execute(job, () -> stories.loadInput(job.orgId(), job.documentId()),
						input -> ai.generateStories(input.request()),
						(input, generated) -> stories.save(job.orgId(), job.projectId(), job.documentId(), job.runId(),
								input.analysisId(), job.actor(), generated.result()));
			}
			else {
				execute(job, () -> analyses.loadInput(job.orgId(), job.documentId()),
						input -> ai.analyze(input.text(), input.answers()),
						(input, generated) -> analyses.save(job.orgId(), job.documentId(), job.runId(), job.actor(),
								generated.result()));
			}
		}
		catch (RuntimeException ex) {
			String code = errorCode(ex);
			if ("INTERNAL_ERROR".equals(code)) {
				log.error("AI run {} failed: {}", job.runId(), ex.getClass().getSimpleName());
			}
			fail(job, code, ex instanceof AiServiceException service ? service.usage() : null);
		}
		catch (Throwable ex) {
			// An Error must not leave the document blocked by a run that can never finish.
			log.error("AI run {} failed: {}", job.runId(), ex.getClass().getSimpleName());
			fail(job, "INTERNAL_ERROR", null);
		}
	}

	private <I> void execute(Job job, Supplier<I> load, Function<I, GeneratedResult> call,
			BiConsumer<I, GeneratedResult> save) {
		I input = orgTx.inOrgAs(job.actor(), job.orgId(), Permission.MANAGE_PROJECTS, () -> {
			requireWritable(job);
			if (!store.markRunning(job.orgId(), job.runId())) {
				throw new IllegalStateException("Run is no longer queued");
			}
			return load.get();
		});
		GeneratedResult generated = call.apply(input);
		orgTx.inOrgAs(job.actor(), job.orgId(), Permission.MANAGE_PROJECTS, () -> {
			requireWritable(job);
			save.accept(input, generated);
			if (!store.succeed(job.orgId(), job.runId(), generated.usage())) {
				throw new IllegalStateException("Run is no longer running");
			}
			auditLog.recordAs(job.actor(), job.orgId(), "AI_RUN_SUCCEEDED", "AI_RUN", job.runId(),
					Map.of("runType", job.runType(), "inputTokens", generated.usage().inputTokens(), "outputTokens",
							generated.usage().outputTokens()));
			return null;
		});
	}

	private void requireWritable(Job job) {
		if (projects.requireStatus(job.orgId(), job.projectId()) == ProjectStatus.ARCHIVED) {
			throw new ConflictException("The project is archived.");
		}
	}

	/** Retries, because a run left active blocks its document until the next restart. */
	private void fail(Job job, String code, AiUsage usage) {
		for (int attempt = 0; attempt <= FAIL_RETRY_MILLIS.length; attempt++) {
			try {
				orgTx.withOrgContext(job.actor(), job.orgId(), () -> {
					if (store.fail(job.orgId(), job.runId(), code, usage)) {
						auditLog.recordAs(job.actor(), job.orgId(), "AI_RUN_FAILED", "AI_RUN", job.runId(),
								Map.of("runType", job.runType(), "errorCode", code));
					}
					return null;
				});
				return;
			}
			catch (RuntimeException ex) {
				log.error("Could not mark AI run {} failed: {}", job.runId(), ex.getClass().getSimpleName());
				if (attempt == FAIL_RETRY_MILLIS.length || !pause(FAIL_RETRY_MILLIS[attempt])) {
					return;
				}
			}
		}
	}

	private static boolean pause(long millis) {
		try {
			Thread.sleep(millis);
			return true;
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	private static String errorCode(RuntimeException ex) {
		if (ex instanceof AiServiceException service) {
			return service.code();
		}
		if (ex instanceof InvalidAiOutputException) {
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

	/** approverRoleId is set only for architecture runs, which store it on the version they create. */
	record Job(UUID runId, UUID orgId, UUID projectId, UUID documentId, UUID actor, String runType, UUID approverRoleId) {

		Job(UUID runId, UUID orgId, UUID projectId, UUID documentId, UUID actor, String runType) {
			this(runId, orgId, projectId, documentId, actor, runType, null);
		}

	}

}
