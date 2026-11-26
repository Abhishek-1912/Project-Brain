package com.projectbrain.requirement;

import java.sql.Types;
import java.util.Map;
import java.util.UUID;

import com.projectbrain.audit.api.AuditLog;
import com.projectbrain.requirement.api.KnowledgeChanged;
import com.projectbrain.requirement.api.KnowledgeSourceType;
import com.projectbrain.identity.api.CurrentUserProvider;
import com.projectbrain.organization.api.OrgTx;
import com.projectbrain.organization.api.Permission;
import com.projectbrain.requirement.AnalysisService.QuestionView;
import com.projectbrain.shared.web.ConflictException;
import com.projectbrain.shared.web.NotFoundException;
import com.projectbrain.shared.web.UnprocessableException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
class ClarificationService {

	private static final int MAX_ANSWER_CHARS = 5000;

	private final OrgTx orgTx;

	private final JdbcClient jdbc;

	private final RequirementAccess access;

	private final CurrentUserProvider currentUser;

	private final AuditLog auditLog;

	private final ApplicationEventPublisher events;

	ClarificationService(OrgTx orgTx, JdbcClient jdbc, RequirementAccess access, CurrentUserProvider currentUser,
			AuditLog auditLog,
			ApplicationEventPublisher events) {
		this.orgTx = orgTx;
		this.jdbc = jdbc;
		this.access = access;
		this.currentUser = currentUser;
		this.auditLog = auditLog;
		this.events = events;
	}

	QuestionView update(UUID orgId, UUID questionId, ClarificationUpdate update) {
		UUID actor = currentUser.get().id();
		return orgTx.inOrg(orgId, Permission.VIEW, () -> {
			Target target = lockTarget(orgId, questionId);
			access.requireEditor(orgId, target.projectId(), target.approverRole(), actor);
			access.requireWritable(orgId, target.projectId());
			if (!target.latest()) {
				throw new ConflictException("A newer analysis exists. Answer its questions instead.");
			}
			if (update.version() == null || update.version() != target.version()) {
				throw new ConflictException("This question was changed by someone else. Reload and try again.");
			}
			String answer = cleanAnswer(update);
			jdbc.sql("""
					UPDATE brain.clarification_questions
					SET status = :status, answer = :answer, answered_by = :actor, answered_at = now(),
					    updated_at = now(), version = version + 1
					WHERE organization_id = :orgId AND id = :id
					""")
				.param("status", update.status())
				.param("answer", answer, Types.VARCHAR)
				.param("actor", actor)
				.param("orgId", orgId)
				.param("id", questionId)
				.update();
			auditLog.record(orgId, "CLARIFICATION_UPDATED", "CLARIFICATION_QUESTION", questionId,
					Map.of("status", update.status(), "documentId", target.documentId().toString()));
			events.publishEvent(new KnowledgeChanged(orgId, target.projectId(), KnowledgeSourceType.CLARIFICATION,
					questionId, actor));
			return load(orgId, questionId);
		});
	}

	private static String cleanAnswer(ClarificationUpdate update) {
		if ("DISMISSED".equals(update.status())) {
			return null;
		}
		if (!"ANSWERED".equals(update.status())) {
			throw new UnprocessableException("status must be ANSWERED or DISMISSED.");
		}
		String answer = update.answer() == null ? "" : update.answer().strip();
		if (answer.isEmpty() || answer.length() > MAX_ANSWER_CHARS) {
			throw new UnprocessableException("Answer must be between 1 and 5000 characters.");
		}
		return answer;
	}

	private Target lockTarget(UUID orgId, UUID questionId) {
		return jdbc.sql("""
				SELECT q.version, a.document_id, d.project_id, d.approver_job_role_id,
				       a.analysis_version = (
				           SELECT MAX(latest.analysis_version) FROM brain.requirement_analyses latest
				           WHERE latest.organization_id = a.organization_id AND latest.document_id = a.document_id
				       ) AS latest
				FROM brain.clarification_questions q
				JOIN brain.requirement_analyses a ON a.organization_id = q.organization_id AND a.id = q.analysis_id
				JOIN brain.requirement_documents d ON d.organization_id = a.organization_id AND d.id = a.document_id
				WHERE q.organization_id = :orgId AND q.id = :id
				FOR UPDATE OF q
				""")
			.param("orgId", orgId)
			.param("id", questionId)
			.query((row, rowNumber) -> new Target(row.getLong("version"), row.getObject("document_id", UUID.class),
					row.getObject("project_id", UUID.class), row.getObject("approver_job_role_id", UUID.class),
					row.getBoolean("latest")))
			.optional()
			.orElseThrow(() -> new NotFoundException("Question not found."));
	}

	private QuestionView load(UUID orgId, UUID questionId) {
		return jdbc.sql("""
				SELECT id, position, question, reason, status, answer, version
				FROM brain.clarification_questions
				WHERE organization_id = :orgId AND id = :id
				""")
			.param("orgId", orgId)
			.param("id", questionId)
			.query((row, rowNumber) -> new QuestionView(row.getObject("id", UUID.class), row.getInt("position"),
					row.getString("question"), row.getString("reason"), row.getString("status"),
					row.getString("answer"), row.getLong("version")))
			.single();
	}

	record ClarificationUpdate(String status, String answer, Long version) {
	}

	private record Target(long version, UUID documentId, UUID projectId, UUID approverRole, boolean latest) {
	}

}
