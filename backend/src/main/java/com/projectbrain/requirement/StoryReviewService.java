package com.projectbrain.requirement;

import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.projectbrain.audit.api.AuditLog;
import com.projectbrain.identity.api.CurrentUserProvider;
import com.projectbrain.organization.api.ApprovalSettings;
import com.projectbrain.organization.api.OrgTx;
import com.projectbrain.organization.api.Permission;
import com.projectbrain.requirement.StoryService.StoryView;
import com.projectbrain.shared.web.ConflictException;
import com.projectbrain.shared.web.ForbiddenException;
import com.projectbrain.shared.web.NotFoundException;
import com.projectbrain.shared.web.UnprocessableException;
import com.projectbrain.task.api.TaskCreator;
import com.projectbrain.team.api.Approvers;
import com.projectbrain.team.api.JobRoleLookup;
import com.projectbrain.team.api.ProjectTeamLookup;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Human decisions on stories. Nothing the AI returns can reach these methods. */
@Service
class StoryReviewService {

	private static final int MAX_COMMENT_CHARS = 2000;

	private static final int MAX_TASK_DESCRIPTION_CHARS = 10000;

	private final OrgTx orgTx;

	private final JdbcClient jdbc;

	private final StoryService stories;

	private final RequirementAccess access;

	private final ProjectTeamLookup team;

	private final ApprovalSettings approvalSettings;

	private final JobRoleLookup jobRoles;

	private final TaskCreator taskCreator;

	private final CurrentUserProvider currentUser;

	private final AuditLog auditLog;

	private final JsonMapper json;

	StoryReviewService(OrgTx orgTx, JdbcClient jdbc, StoryService stories, RequirementAccess access,
			ProjectTeamLookup team, ApprovalSettings approvalSettings, JobRoleLookup jobRoles, TaskCreator taskCreator,
			CurrentUserProvider currentUser, AuditLog auditLog, JsonMapper json) {
		this.orgTx = orgTx;
		this.jdbc = jdbc;
		this.stories = stories;
		this.access = access;
		this.team = team;
		this.approvalSettings = approvalSettings;
		this.jobRoles = jobRoles;
		this.taskCreator = taskCreator;
		this.currentUser = currentUser;
		this.auditLog = auditLog;
		this.json = json;
	}

	StoryView approve(UUID orgId, UUID storyId, Long version) {
		UUID actor = currentUser.get().id();
		return orgTx.inOrg(orgId, Permission.VIEW, () -> {
			Target target = lock(orgId, storyId);
			Approvers approvers = requireApprover(orgId, target, actor);
			requireDecidable(orgId, target, version);
			if (target.jobRoleId() == null) {
				throw new UnprocessableException("Choose a job role before approving this story.");
			}
			jobRoles.requireActive(orgId, target.jobRoleId());
			boolean selfApproved = actor.equals(target.generatedBy());
			recordDecision(orgId, storyId, target, "APPROVED", actor, approvers, selfApproved, null);
			auditLog.record(orgId, "STORY_APPROVED", "STORY", storyId, Map.of("storyVersion", target.version(), "basis",
					approvers.basis().name(), "selfApproved", selfApproved));
			return stories.load(orgId, storyId);
		});
	}

	StoryView reject(UUID orgId, UUID storyId, Long version, String comment) {
		UUID actor = currentUser.get().id();
		return orgTx.inOrg(orgId, Permission.VIEW, () -> {
			Target target = lock(orgId, storyId);
			Approvers approvers = requireApprover(orgId, target, actor);
			requireDecidable(orgId, target, version);
			String cleaned = comment == null ? "" : comment.strip();
			if (cleaned.isEmpty() || cleaned.length() > MAX_COMMENT_CHARS) {
				throw new UnprocessableException("Add a rejection comment of up to 2000 characters.");
			}
			recordDecision(orgId, storyId, target, "REJECTED", actor, approvers, false, cleaned);
			auditLog.record(orgId, "STORY_REJECTED", "STORY", storyId,
					Map.of("storyVersion", target.version(), "basis", approvers.basis().name()));
			return stories.load(orgId, storyId);
		});
	}

	CreateTasksResult createTasks(UUID orgId, UUID documentId) {
		UUID actor = currentUser.get().id();
		return orgTx.inOrg(orgId, Permission.VIEW, () -> {
			DocumentRef document = document(orgId, documentId);
			access.requireEditor(orgId, document.projectId(), document.approverRole(), actor);
			access.requireWritable(orgId, document.projectId());
			// A waiting request re-reads each locked row after the first commits, so it skips converted stories.
			List<ApprovedStory> approved = jdbc.sql("""
					SELECT id, title, as_a, i_want, so_that, acceptance_criteria::text AS criteria, priority, job_role_id
					FROM brain.story_drafts
					WHERE organization_id = :orgId AND document_id = :documentId AND status = 'APPROVED'
					ORDER BY created_at, id
					FOR UPDATE
					""")
				.param("orgId", orgId)
				.param("documentId", documentId)
				.query((row, rowNumber) -> new ApprovedStory(row.getObject("id", UUID.class), row.getString("title"),
						row.getString("as_a"), row.getString("i_want"), row.getString("so_that"),
						json.readTree(row.getString("criteria")), row.getString("priority"),
						row.getObject("job_role_id", UUID.class)))
				.list();
			List<CreatedTask> created = new ArrayList<>();
			for (ApprovedStory story : approved) {
				requireActiveRole(orgId, story);
				String description = description(story);
				if (description.length() > MAX_TASK_DESCRIPTION_CHARS) {
					throw new UnprocessableException(
							"\"" + story.title() + "\" is too long to become a task. Shorten it and approve it again.");
				}
				UUID taskId = taskCreator.createFromStory(orgId, document.projectId(), story.id(), story.title(),
						description, story.priority(), story.jobRoleId());
				jdbc.sql("""
						UPDATE brain.story_drafts
						SET status = 'CONVERTED', task_id = :taskId, updated_at = now(), version = version + 1
						WHERE organization_id = :orgId AND id = :id
						""")
					.param("taskId", taskId)
					.param("orgId", orgId)
					.param("id", story.id())
					.update();
				auditLog.record(orgId, "STORY_CONVERTED", "STORY", story.id(), Map.of("taskId", taskId.toString()));
				created.add(new CreatedTask(story.id(), taskId));
			}
			return new CreateTasksResult(created);
		});
	}

	private void requireActiveRole(UUID orgId, ApprovedStory story) {
		try {
			if (story.jobRoleId() == null) {
				throw new UnprocessableException("No job role");
			}
			jobRoles.requireActive(orgId, story.jobRoleId());
		}
		catch (UnprocessableException ex) {
			throw new UnprocessableException(
					"\"" + story.title() + "\" needs an active job role before it can become a task.");
		}
	}

	private Approvers requireApprover(UUID orgId, Target target, UUID actor) {
		Approvers approvers = team.approvers(orgId, target.projectId(), target.approverRole(), target.generatedBy(),
				approvalSettings.allowsSelfApproval(orgId));
		if (!approvers.userIds().contains(actor)) {
			throw new ForbiddenException("You are not an approver for this story.");
		}
		return approvers;
	}

	private void requireDecidable(UUID orgId, Target target, Long version) {
		access.requireWritable(orgId, target.projectId());
		if (version == null || version != target.version()) {
			throw new ConflictException("This story was changed by someone else. Reload and try again.");
		}
		if (!"DRAFT".equals(target.status())) {
			throw new ConflictException("Only a draft story can be approved or rejected.");
		}
	}

	private void recordDecision(UUID orgId, UUID storyId, Target target, String decision, UUID actor, Approvers approvers,
			boolean selfApproved, String comment) {
		jdbc.sql("""
				INSERT INTO brain.story_reviews
				    (id, organization_id, project_id, document_id, story_id, story_version, action, actor_id,
				     job_role_id, approval_basis, self_approved, comment)
				VALUES (:id, :orgId, :projectId, :documentId, :storyId, :version, :action, :actor,
				        :jobRoleId, :basis, :selfApproved, :comment)
				""")
			.param("id", UUID.randomUUID())
			.param("orgId", orgId)
			.param("projectId", target.projectId())
			.param("documentId", target.documentId())
			.param("storyId", storyId)
			.param("version", target.version())
			.param("action", decision)
			.param("actor", actor)
			.param("jobRoleId", target.approverRole(), Types.OTHER)
			.param("basis", approvers.basis().name())
			.param("selfApproved", selfApproved)
			.param("comment", comment, Types.VARCHAR)
			.update();
		jdbc.sql("""
				UPDATE brain.story_drafts
				SET status = :status, updated_at = now(), version = version + 1
				WHERE organization_id = :orgId AND id = :id
				""")
			.param("status", decision)
			.param("orgId", orgId)
			.param("id", storyId)
			.update();
	}

	private Target lock(UUID orgId, UUID storyId) {
		return jdbc.sql("""
				SELECT s.status, s.version, s.project_id, s.document_id, s.job_role_id, s.generated_by,
				       d.approver_job_role_id
				FROM brain.story_drafts s
				JOIN brain.requirement_documents d ON d.organization_id = s.organization_id AND d.id = s.document_id
				WHERE s.organization_id = :orgId AND s.id = :id
				FOR UPDATE OF s
				""")
			.param("orgId", orgId)
			.param("id", storyId)
			.query((row, rowNumber) -> new Target(row.getString("status"), row.getLong("version"),
					row.getObject("project_id", UUID.class), row.getObject("document_id", UUID.class),
					row.getObject("job_role_id", UUID.class), row.getObject("generated_by", UUID.class),
					row.getObject("approver_job_role_id", UUID.class)))
			.optional()
			.orElseThrow(() -> new NotFoundException("Story not found."));
	}

	private DocumentRef document(UUID orgId, UUID documentId) {
		return jdbc.sql("""
				SELECT project_id, approver_job_role_id FROM brain.requirement_documents
				WHERE organization_id = :orgId AND id = :id
				""")
			.param("orgId", orgId)
			.param("id", documentId)
			.query((row, rowNumber) -> new DocumentRef(row.getObject("project_id", UUID.class),
					row.getObject("approver_job_role_id", UUID.class)))
			.optional()
			.orElseThrow(() -> new NotFoundException("Requirement document not found."));
	}

	private static String description(ApprovedStory story) {
		StringBuilder text = new StringBuilder("As a ").append(sentence(story.asA()))
			.append(", I want ")
			.append(sentence(story.iWant()))
			.append(", so that ")
			.append(sentence(story.soThat()))
			.append(".\n\nAcceptance criteria:");
		story.criteria().forEach(item -> text.append("\n- ").append(item.asString()));
		return text.toString();
	}

	private static String sentence(String value) {
		return value.replaceAll("[.\\s]+$", "");
	}

	record CreatedTask(UUID storyId, UUID taskId) {
	}

	record CreateTasksResult(List<CreatedTask> created) {
	}

	private record Target(String status, long version, UUID projectId, UUID documentId, UUID jobRoleId, UUID generatedBy,
			UUID approverRole) {
	}

	private record DocumentRef(UUID projectId, UUID approverRole) {
	}

	private record ApprovedStory(UUID id, String title, String asA, String iWant, String soThat, JsonNode criteria,
			String priority, UUID jobRoleId) {
	}

}
