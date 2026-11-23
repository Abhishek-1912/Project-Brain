package com.projectbrain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.projectbrain.support.TeamApiTestBase;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = { "SUPABASE_URL=http://127.0.0.1:1", "APP_CORS_ALLOWED_ORIGINS=http://localhost:5173" })
class TaskReviewApiTest extends TeamApiTestBase {

	@Autowired
	private MockMvc mvc;

	@Test
	void submitMovesTaskToInReviewAndRecordsVersion() throws Exception {
		Fixture f = fixture();
		UUID task = seedTask(f, "IN_PROGRESS", 2);

		action("submit", f.author, f.org, task, 2, null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("IN_REVIEW"))
			.andExpect(jsonPath("$.version").value(3));

		assertThat(reviews(task, "SUBMITTED", f.author, 3)).isEqualTo(1);
		assertThat(audits(f.org, "TASK_SUBMITTED", task)).isEqualTo(1);
	}

	@Test
	void roleHolderApprovalMovesTaskToDone() throws Exception {
		Fixture f = fixture();
		assignJobRole(f.org, f.project, f.reviewer, f.role, f.owner);
		UUID task = submittedTask(f);

		action("approve", f.reviewer, f.org, task, 1, "Looks good")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("DONE"))
			.andExpect(jsonPath("$.version").value(2));

		assertThat(countRows("""
				SELECT count(*) FROM brain.task_reviews
				WHERE task_id = ? AND action = 'APPROVED' AND actor_id = ? AND task_version = 1
				  AND approval_basis = 'ROLE_HOLDER' AND NOT self_approved AND comment = 'Looks good'
				""", task, f.reviewer)).isEqualTo(1);
		assertThat(audits(f.org, "TASK_APPROVED", task)).isEqualTo(1);
	}

	@Test
	void noRoleHolderUsesOwnerFallback() throws Exception {
		Fixture f = fixture();
		UUID task = submittedTask(f);

		action("approve", f.reviewer, f.org, task, 1, null).andExpect(status().isForbidden());
		action("approve", f.owner, f.org, task, 1, null).andExpect(status().isOk());

		assertThat(countRows("""
				SELECT count(*) FROM brain.task_reviews
				WHERE task_id = ? AND action = 'APPROVED' AND actor_id = ? AND approval_basis = 'OWNER_FALLBACK'
				""", task, f.owner)).isEqualTo(1);
	}

	@Test
	void selfApprovalIsAllowedAndMarked() throws Exception {
		Fixture f = fixture();
		assignJobRole(f.org, f.project, f.author, f.role, f.owner);
		UUID task = submittedTask(f);

		action("approve", f.author, f.org, task, 1, null).andExpect(status().isOk());

		assertThat(countRows("""
				SELECT count(*) FROM brain.task_reviews
				WHERE task_id = ? AND action = 'APPROVED' AND actor_id = ? AND self_approved
				""", task, f.author)).isEqualTo(1);
	}

	@Test
	void selfApprovalOffBlocksSubmitter() throws Exception {
		Fixture f = fixture();
		execute("UPDATE brain.organizations SET allow_self_approval = false WHERE id = ?", f.org);
		assignJobRole(f.org, f.project, f.author, f.role, f.owner);
		UUID task = submittedTask(f);

		action("approve", f.author, f.org, task, 1, null).andExpect(status().isForbidden());
		action("reject", f.author, f.org, task, 1, "Own work").andExpect(status().isForbidden());
		action("approve", f.owner, f.org, task, 1, null).andExpect(status().isOk());

		assertThat(countRows("""
				SELECT count(*) FROM brain.task_reviews
				WHERE task_id = ? AND action = 'APPROVED' AND approval_basis = 'OWNER_FALLBACK' AND NOT self_approved
				""", task)).isEqualTo(1);
	}

	@Test
	void rejectRequiresCommentAndReturnsTaskToInProgress() throws Exception {
		Fixture f = fixture();
		assignJobRole(f.org, f.project, f.reviewer, f.role, f.owner);
		UUID task = submittedTask(f);

		action("reject", f.reviewer, f.org, task, 1, null).andExpect(status().isUnprocessableContent());
		action("reject", f.reviewer, f.org, task, 1, "   ").andExpect(status().isUnprocessableContent());
		action("reject", f.reviewer, f.org, task, 1, "x".repeat(2001)).andExpect(status().isUnprocessableContent());
		action("reject", f.reviewer, f.org, task, 1, "  Fix the expiry check  ")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("IN_PROGRESS"));

		assertThat(countRows("""
				SELECT count(*) FROM brain.task_reviews
				WHERE task_id = ? AND action = 'REJECTED' AND comment = 'Fix the expiry check' AND approval_basis = 'ROLE_HOLDER'
				""", task)).isEqualTo(1);
		action("submit", f.author, f.org, task, 2, null).andExpect(status().isOk());
	}

	@Test
	void staleVersionApprovalReturns409() throws Exception {
		Fixture f = fixture();
		assignJobRole(f.org, f.project, f.reviewer, f.role, f.owner);
		UUID task = submittedTask(f);
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(taskUrl(f.org, task))
			.with(as(f.author))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"Edited during review\",\"version\":1}"))
			.andExpect(status().isOk());

		action("approve", f.reviewer, f.org, task, 1, null).andExpect(status().isConflict());

		assertThat(countRows("SELECT count(*) FROM brain.tasks WHERE id = ? AND status = 'IN_REVIEW'", task)).isEqualTo(1);
		assertThat(reviews(task, "APPROVED", f.reviewer, 1)).isZero();
		action("approve", f.reviewer, f.org, task, 2, null).andExpect(status().isOk());
	}

	@RepeatedTest(5)
	void concurrentDecisionsRecordOneDecision() throws Exception {
		Fixture f = fixture();
		UUID second = seedMember(f.org, "MEMBER");
		assignJobRole(f.org, f.project, f.reviewer, f.role, f.owner);
		assignJobRole(f.org, f.project, second, f.role, f.owner);
		UUID task = submittedTask(f);
		CountDownLatch start = new CountDownLatch(1);
		Callable<Integer> approve = () -> {
			start.await();
			return action("approve", f.reviewer, f.org, task, 1, null).andReturn().getResponse().getStatus();
		};
		Callable<Integer> reject = () -> {
			start.await();
			return action("reject", second, f.org, task, 1, "Needs work").andReturn().getResponse().getStatus();
		};
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<Integer> first = pool.submit(approve);
			Future<Integer> other = pool.submit(reject);
			start.countDown();
			assertThat(List.of(first.get(), other.get())).containsExactlyInAnyOrder(200, 409);
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(countRows("""
				SELECT count(*) FROM brain.task_reviews
				WHERE task_id = ? AND action IN ('APPROVED', 'REJECTED')
				""", task)).isEqualTo(1);
	}

	@Test
	void viewerAndNonHolderCannotDecide() throws Exception {
		Fixture f = fixture();
		UUID viewer = seedMember(f.org, "VIEWER");
		UUID nonHolder = seedMember(f.org, "ADMIN");
		assignJobRole(f.org, f.project, f.reviewer, f.role, f.owner);
		assignJobRole(f.org, f.project, viewer, f.role, f.owner);
		UUID task = submittedTask(f);

		action("approve", viewer, f.org, task, 1, null).andExpect(status().isForbidden());
		action("reject", viewer, f.org, task, 1, "No").andExpect(status().isForbidden());
		action("approve", nonHolder, f.org, task, 1, null).andExpect(status().isForbidden());
		action("approve", f.owner, f.org, task, 1, null).andExpect(status().isForbidden());
		action("withdraw", viewer, f.org, task, 1, null).andExpect(status().isForbidden());
	}

	@Test
	void withdrawAndReopenReturnTaskToInProgress() throws Exception {
		Fixture f = fixture();
		assignJobRole(f.org, f.project, f.reviewer, f.role, f.owner);
		UUID task = submittedTask(f);

		action("withdraw", f.author, f.org, task, 1, null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("IN_PROGRESS"));
		action("submit", f.author, f.org, task, 2, null).andExpect(status().isOk());
		action("approve", f.reviewer, f.org, task, 3, null).andExpect(status().isOk());
		action("reopen", f.author, f.org, task, 4, null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("IN_PROGRESS"));

		assertThat(reviews(task, "WITHDRAWN", f.author, 1)).isEqualTo(1);
		assertThat(reviews(task, "REOPENED", f.author, 4)).isEqualTo(1);
		assertThat(audits(f.org, "TASK_WITHDRAWN", task)).isEqualTo(1);
		assertThat(audits(f.org, "TASK_REOPENED", task)).isEqualTo(1);
	}

	@Test
	void invalidTransitionsReturn409() throws Exception {
		Fixture f = fixture();
		UUID todo = seedTask(f, "TODO", 0);
		UUID inReview = submittedTask(f);
		UUID done = seedTask(f, "DONE", 0);

		action("approve", f.owner, f.org, todo, 0, null).andExpect(status().isConflict());
		action("reject", f.owner, f.org, todo, 0, "No").andExpect(status().isConflict());
		action("withdraw", f.owner, f.org, todo, 0, null).andExpect(status().isConflict());
		action("reopen", f.owner, f.org, todo, 0, null).andExpect(status().isConflict());
		action("submit", f.owner, f.org, inReview, 1, null).andExpect(status().isConflict());
		action("submit", f.owner, f.org, done, 0, null).andExpect(status().isConflict());
	}

	@Test
	void phaseOneTaskWithoutRoleCannotEnterReviewFlow() throws Exception {
		Fixture f = fixture();
		UUID task = UUID.randomUUID();
		execute("""
				INSERT INTO brain.tasks (id, organization_id, project_id, title, status, created_by)
				VALUES (?, ?, ?, 'Old', 'TODO', ?)
				""", task, f.org, f.project, f.owner);

		action("submit", f.owner, f.org, task, 0, null).andExpect(status().isConflict());
	}

	@Test
	void removedOnlyRoleHolderMovesReviewToOwnerFallback() throws Exception {
		Fixture f = fixture();
		assignJobRole(f.org, f.project, f.reviewer, f.role, f.owner);
		UUID task = submittedTask(f);

		mvc.perform(delete(ORGS + "/" + f.org + "/members/" + f.reviewer).with(as(f.owner)))
			.andExpect(status().isNoContent());

		action("approve", f.reviewer, f.org, task, 1, null).andExpect(status().isNotFound());
		action("approve", f.owner, f.org, task, 1, null).andExpect(status().isOk());
		assertThat(countRows("""
				SELECT count(*) FROM brain.task_reviews
				WHERE task_id = ? AND action = 'APPROVED' AND approval_basis = 'OWNER_FALLBACK'
				""", task)).isEqualTo(1);
	}

	@Test
	void archivedProjectBlocksReviewActions() throws Exception {
		Fixture f = fixture();
		UUID task = submittedTask(f);
		execute("UPDATE brain.projects SET status = 'ARCHIVED' WHERE id = ?", f.project);

		action("approve", f.owner, f.org, task, 1, null).andExpect(status().isConflict());
		action("withdraw", f.author, f.org, task, 1, null).andExpect(status().isConflict());
		assertThat(countRows("SELECT count(*) FROM brain.tasks WHERE id = ? AND status = 'IN_REVIEW'", task)).isEqualTo(1);
	}

	@Test
	void deletingTaskKeepsReviewHistory() throws Exception {
		Fixture f = fixture();
		UUID task = submittedTask(f);

		mvc.perform(delete(taskUrl(f.org, task)).with(as(f.author))).andExpect(status().isNoContent());

		assertThat(countRows("SELECT count(*) FROM brain.tasks WHERE id = ?", task)).isZero();
		assertThat(countRows("SELECT count(*) FROM brain.task_reviews WHERE task_id = ?", task)).isEqualTo(1);
	}

	@Test
	void anotherOrganizationsTaskReturns404() throws Exception {
		Fixture f = fixture();
		Fixture other = fixture();
		UUID task = submittedTask(other);

		action("approve", f.owner, f.org, task, 1, null).andExpect(status().isNotFound());
		action("approve", f.owner, other.org, task, 1, null).andExpect(status().isNotFound());
		assertThat(countRows("SELECT count(*) FROM brain.tasks WHERE id = ? AND status = 'IN_REVIEW'", task)).isEqualTo(1);
	}

	@Test
	void myReviewsShowsOnlyTasksCurrentUserCanDecide() throws Exception {
		Fixture f = fixture();
		UUID outsiderRoleHolder = seedMember(f.org, "MEMBER");
		UUID qa = seedJobRole(f.org, "QA", true);
		assignJobRole(f.org, f.project, f.reviewer, f.role, f.owner);
		assignJobRole(f.org, f.project, outsiderRoleHolder, qa, f.owner);
		UUID backendTask = submittedTask(f);
		seedTask(f, "IN_PROGRESS", 0);

		mvc.perform(get(mine(f.org)).with(as(f.reviewer)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalItems").value(1))
			.andExpect(jsonPath("$.items", hasSize(1)))
			.andExpect(jsonPath("$.items[0].task.id").value(backendTask.toString()))
			.andExpect(jsonPath("$.items[0].task.status").value("IN_REVIEW"))
			.andExpect(jsonPath("$.items[0].projectName").value("Project " + f.project))
			.andExpect(jsonPath("$.items[0].jobRoleName").value("Backend"))
			.andExpect(jsonPath("$.items[0].submittedBy").value(f.author.toString()))
			.andExpect(jsonPath("$.items[0].submittedByName").value("User"))
			.andExpect(jsonPath("$.items[0].submittedAt").isNotEmpty())
			.andExpect(jsonPath("$.items[0].approvalBasis").value("ROLE_HOLDER"))
			.andExpect(jsonPath("$.items[0].selfApproval").value(false));
		for (UUID excluded : List.of(outsiderRoleHolder, f.owner, f.author)) {
			mvc.perform(get(mine(f.org)).with(as(excluded))).andExpect(jsonPath("$.totalItems").value(0));
		}
	}

	@Test
	void myReviewsMarksOwnerFallbackAndSelfApproval() throws Exception {
		Fixture f = fixture();
		UUID fallback = submittedTask(f);
		UUID selfRole = seedJobRole(f.org, "QA", true);
		assignJobRole(f.org, f.project, f.author, selfRole, f.owner);
		UUID selfTask = UUID.randomUUID();
		execute("""
				INSERT INTO brain.tasks (id, organization_id, project_id, title, status, responsible_job_role_id, created_by)
				VALUES (?, ?, ?, 'Self', 'TODO', ?, ?)
				""", selfTask, f.org, f.project, selfRole, f.author);
		action("submit", f.author, f.org, selfTask, 0, null).andExpect(status().isOk());

		mvc.perform(get(mine(f.org)).with(as(f.owner)))
			.andExpect(jsonPath("$.totalItems").value(1))
			.andExpect(jsonPath("$.items[0].task.id").value(fallback.toString()))
			.andExpect(jsonPath("$.items[0].approvalBasis").value("OWNER_FALLBACK"))
			.andExpect(jsonPath("$.items[0].selfApproval").value(false));
		mvc.perform(get(mine(f.org)).with(as(f.author)))
			.andExpect(jsonPath("$.totalItems").value(1))
			.andExpect(jsonPath("$.items[0].task.id").value(selfTask.toString()))
			.andExpect(jsonPath("$.items[0].approvalBasis").value("ROLE_HOLDER"))
			.andExpect(jsonPath("$.items[0].selfApproval").value(true));

		execute("UPDATE brain.organizations SET allow_self_approval = false WHERE id = ?", f.org);
		mvc.perform(get(mine(f.org)).with(as(f.author))).andExpect(jsonPath("$.totalItems").value(0));
		mvc.perform(get(mine(f.org)).with(as(f.owner))).andExpect(jsonPath("$.totalItems").value(2));
	}

	@Test
	void myReviewsIsOldestFirstAndPaginated() throws Exception {
		Fixture f = fixture();
		assignJobRole(f.org, f.project, f.reviewer, f.role, f.owner);
		UUID first = submittedTask(f);
		UUID second = submittedTask(f);
		UUID third = submittedTask(f);

		mvc.perform(get(mine(f.org)).with(as(f.reviewer)).param("size", "2"))
			.andExpect(jsonPath("$.totalItems").value(3))
			.andExpect(jsonPath("$.items[0].task.id").value(first.toString()))
			.andExpect(jsonPath("$.items[1].task.id").value(second.toString()));
		mvc.perform(get(mine(f.org)).with(as(f.reviewer)).param("page", "1").param("size", "2"))
			.andExpect(jsonPath("$.items", hasSize(1)))
			.andExpect(jsonPath("$.items[0].task.id").value(third.toString()));
	}

	@Test
	void deletedTaskLeavesMyReviews() throws Exception {
		Fixture f = fixture();
		assignJobRole(f.org, f.project, f.reviewer, f.role, f.owner);
		UUID task = submittedTask(f);

		mvc.perform(delete(taskUrl(f.org, task)).with(as(f.author))).andExpect(status().isNoContent());

		mvc.perform(get(mine(f.org)).with(as(f.reviewer))).andExpect(jsonPath("$.totalItems").value(0));
	}

	@Test
	void archivedProjectLeavesMyReviews() throws Exception {
		Fixture f = fixture();
		assignJobRole(f.org, f.project, f.reviewer, f.role, f.owner);
		submittedTask(f);
		execute("UPDATE brain.projects SET status = 'ARCHIVED' WHERE id = ?", f.project);

		mvc.perform(get(mine(f.org)).with(as(f.reviewer))).andExpect(jsonPath("$.totalItems").value(0));
	}

	@Test
	void queueMembershipMatchesApprovePermission() throws Exception {
		for (boolean selfApproval : List.of(true, false)) {
			for (boolean authorHoldsRole : List.of(true, false)) {
				Fixture f = fixture();
				UUID viewer = seedMember(f.org, "VIEWER");
				execute("UPDATE brain.organizations SET allow_self_approval = ? WHERE id = ?", selfApproval, f.org);
				assignJobRole(f.org, f.project, viewer, f.role, f.owner);
				if (authorHoldsRole) {
					assignJobRole(f.org, f.project, f.author, f.role, f.owner);
				}
				UUID task = submittedTask(f);
				for (UUID user : List.of(f.owner, f.author, f.reviewer, viewer)) {
					String body = mvc.perform(get(mine(f.org)).with(as(user)))
						.andExpect(status().isOk())
						.andReturn()
						.getResponse()
						.getContentAsString();
					boolean queued = body.contains(task.toString());
					int approval = action("reject", user, f.org, task, 1, "Permission probe").andReturn()
						.getResponse()
						.getStatus();
					assertThat(approval == 200).as("user %s self=%s holder=%s", user, selfApproval, authorHoldsRole)
						.isEqualTo(queued);
					if (approval == 200) {
						action("submit", f.author, f.org, task, 2, null).andExpect(status().isOk());
						execute("UPDATE brain.tasks SET version = 1 WHERE id = ?", task);
					}
				}
			}
		}
	}

	@Test
	void reviewHistoryIsNewestFirstAndKeepsRemovedActor() throws Exception {
		Fixture f = fixture();
		assignJobRole(f.org, f.project, f.reviewer, f.role, f.owner);
		UUID task = submittedTask(f);
		action("reject", f.reviewer, f.org, task, 1, "Fix expiry").andExpect(status().isOk());
		mvc.perform(delete(ORGS + "/" + f.org + "/members/" + f.reviewer).with(as(f.owner)))
			.andExpect(status().isNoContent());

		mvc.perform(get(taskUrl(f.org, task) + "/reviews").with(as(f.author)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalItems").value(2))
			.andExpect(jsonPath("$.items[0].action").value("REJECTED"))
			.andExpect(jsonPath("$.items[0].actorId").value(f.reviewer.toString()))
			.andExpect(jsonPath("$.items[0].actorName").doesNotExist())
			.andExpect(jsonPath("$.items[0].comment").value("Fix expiry"))
			.andExpect(jsonPath("$.items[0].taskVersion").value(1))
			.andExpect(jsonPath("$.items[0].jobRoleName").value("Backend"))
			.andExpect(jsonPath("$.items[0].approvalBasis").value("ROLE_HOLDER"))
			.andExpect(jsonPath("$.items[1].action").value("SUBMITTED"))
			.andExpect(jsonPath("$.items[1].actorName").value("User"));

		Fixture other = fixture();
		mvc.perform(get(taskUrl(other.org, task) + "/reviews").with(as(other.owner))).andExpect(status().isNotFound());
	}

	private static String mine(UUID org) {
		return ORGS + "/" + org + "/reviews/mine";
	}

	private Fixture fixture() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner);
		UUID author = seedMember(org, "MEMBER");
		UUID reviewer = seedMember(org, "MEMBER");
		UUID project = seedProject(org, owner, "ACTIVE");
		UUID role = seedJobRole(org, "Backend", true);
		return new Fixture(owner, org, author, reviewer, project, role);
	}

	private UUID submittedTask(Fixture f) throws Exception {
		UUID task = seedTask(f, "IN_PROGRESS", 0);
		action("submit", f.author, f.org, task, 0, null).andExpect(status().isOk());
		return task;
	}

	private static UUID seedTask(Fixture f, String taskStatus, long version) throws Exception {
		UUID task = UUID.randomUUID();
		execute("""
				INSERT INTO brain.tasks (id, organization_id, project_id, title, status, responsible_job_role_id, created_by, version)
				VALUES (?, ?, ?, 'Review me', ?, ?, ?, ?)
				""", task, f.org, f.project, taskStatus, f.role, f.author, version);
		return task;
	}

	private ResultActions action(String name, UUID user, UUID org, UUID task, long version, String comment)
			throws Exception {
		String body = comment == null ? "{\"version\":" + version + "}"
				: "{\"version\":" + version + ",\"comment\":\"" + comment + "\"}";
		return mvc.perform(post(taskUrl(org, task) + "/" + name).with(as(user))
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	private static String taskUrl(UUID org, UUID task) {
		return ORGS + "/" + org + "/tasks/" + task;
	}

	private static long reviews(UUID task, String action, UUID actor, long version) throws Exception {
		return countRows("""
				SELECT count(*) FROM brain.task_reviews
				WHERE task_id = ? AND action = ? AND actor_id = ? AND task_version = ?
				""", task, action, actor, version);
	}

	private static long audits(UUID org, String action, UUID task) throws Exception {
		return countRows("""
				SELECT count(*) FROM brain.audit_events
				WHERE organization_id = ? AND action = ? AND entity_type = 'TASK' AND entity_id = ?
				""", org, action, task);
	}

	private record Fixture(UUID owner, UUID org, UUID author, UUID reviewer, UUID project, UUID role) {
	}

}
