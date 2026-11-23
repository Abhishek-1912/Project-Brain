package com.projectbrain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import com.projectbrain.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Token verification is covered by MeApiTest, so these cases supply an authenticated JWT directly. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = { "SUPABASE_URL=http://127.0.0.1:1", "APP_CORS_ALLOWED_ORIGINS=http://localhost:5173" })
class TaskApiTest extends PostgresTestBase {

	@Autowired
	private MockMvc mvc;

	@Test
	void memberCreatesTask() throws Exception {
		UUID member = seedProfile();
		UUID org = seedOrg(member, "MEMBER");
		UUID project = seedProject(org, member, "ACTIVE");

		createTask(member, org, project, "{\"title\":\"Write docs\",\"assigneeId\":\"" + member
				+ "\",\"dueDate\":\"2026-12-01\"}")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.organizationId").value(org.toString()))
			.andExpect(jsonPath("$.projectId").value(project.toString()))
			.andExpect(jsonPath("$.title").value("Write docs"))
			.andExpect(jsonPath("$.status").value("BACKLOG"))
			.andExpect(jsonPath("$.priority").value("MEDIUM"))
			.andExpect(jsonPath("$.assigneeId").value(member.toString()))
			.andExpect(jsonPath("$.dueDate").value("2026-12-01"))
			.andExpect(jsonPath("$.createdBy").value(member.toString()))
			.andExpect(jsonPath("$.version").value(0))
			.andExpect(content().string(containsString("\"description\":null")));

		assertThat(taskCount(project)).isEqualTo(1);
	}

	@Test
	void viewerCannotCreateTask() throws Exception {
		UUID viewer = seedProfile();
		UUID org = seedOrg(viewer, "VIEWER");
		UUID project = seedProject(org, viewer, "ACTIVE");

		createTask(viewer, org, project, "{\"title\":\"Nope\"}")
			.andExpect(status().isForbidden())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

		assertThat(taskCount(project)).isZero();
	}

	@Test
	void viewerCannotPatchOrDeleteTask() throws Exception {
		UUID viewer = seedProfile();
		UUID org = seedOrg(viewer, "VIEWER");
		UUID project = seedProject(org, viewer, "ACTIVE");
		UUID task = seedTask(org, project, viewer, "Kept", "TODO", null);

		patchTask(viewer, org, task, "{\"title\":\"Changed\",\"version\":0}").andExpect(status().isForbidden());
		mvc.perform(delete(taskUrl(org, task)).with(as(viewer))).andExpect(status().isForbidden());

		assertThat(taskTitle(task)).isEqualTo("Kept");
	}

	@Test
	void createInOtherOrgProjectReturns404() throws Exception {
		UUID me = seedProfile();
		UUID myOrg = seedOrg(me, "OWNER");
		UUID other = seedProfile();
		UUID otherProject = seedProject(seedOrg(other, "OWNER"), other, "ACTIVE");

		createTask(me, myOrg, otherProject, "{\"title\":\"Intruder\"}").andExpect(status().isNotFound());

		assertThat(taskCount(otherProject)).isZero();
	}

	@Test
	void filterByStatusAndAssignee() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner, "OWNER");
		UUID teammate = seedProfile();
		addMember(org, teammate, "MEMBER");
		UUID project = seedProject(org, owner, "ACTIVE");
		UUID match = seedTask(org, project, owner, "Match", "TODO", teammate);
		seedTask(org, project, owner, "Wrong status", "DONE", teammate);
		seedTask(org, project, owner, "Wrong assignee", "TODO", owner);
		seedTask(org, project, owner, "Unassigned", "TODO", null);

		mvc.perform(get(tasksUrl(org, project)).with(as(owner)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalItems").value(4));
		mvc.perform(get(tasksUrl(org, project)).with(as(owner)).param("status", "TODO"))
			.andExpect(jsonPath("$.totalItems").value(3));
		mvc.perform(get(tasksUrl(org, project)).with(as(owner)).param("assigneeId", teammate.toString()))
			.andExpect(jsonPath("$.totalItems").value(2));
		mvc.perform(get(tasksUrl(org, project)).with(as(owner))
			.param("status", "TODO")
			.param("assigneeId", teammate.toString()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(1)))
			.andExpect(jsonPath("$.items[0].id").value(match.toString()))
			.andExpect(jsonPath("$.totalItems").value(1));
	}

	@Test
	void listOnlyReturnsTasksFromRequestedProject() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner, "OWNER");
		UUID project = seedProject(org, owner, "ACTIVE");
		UUID otherProject = seedProject(org, owner, "ACTIVE");
		UUID task = seedTask(org, project, owner, "Mine", "TODO", null);
		seedTask(org, otherProject, owner, "Elsewhere", "TODO", null);

		mvc.perform(get(tasksUrl(org, project)).with(as(owner)))
			.andExpect(jsonPath("$.items", hasSize(1)))
			.andExpect(jsonPath("$.items[0].id").value(task.toString()));
	}

	@Test
	void invalidFilterReturns422() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner, "OWNER");
		UUID project = seedProject(org, owner, "ACTIVE");

		mvc.perform(get(tasksUrl(org, project)).with(as(owner)).param("status", "WONTFIX"))
			.andExpect(status().isUnprocessableContent());
		mvc.perform(get(tasksUrl(org, project)).with(as(owner)).param("assigneeId", "not-a-uuid"))
			.andExpect(status().isUnprocessableContent());
	}

	@Test
	void pageBeyondIntegerOffsetReturnsEmpty() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner, "OWNER");
		UUID project = seedProject(org, owner, "ACTIVE");
		seedTask(org, project, owner, "Only", "TODO", null);

		mvc.perform(get(tasksUrl(org, project)).with(as(owner)).param("page", "2147483647").param("size", "100"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(0)))
			.andExpect(jsonPath("$.totalItems").value(1));
	}

	@Test
	void otherOrgTaskIdReturns404AndChangesNothing() throws Exception {
		UUID me = seedProfile();
		UUID myOrg = seedOrg(me, "OWNER");
		UUID other = seedProfile();
		UUID otherOrg = seedOrg(other, "OWNER");
		UUID otherProject = seedProject(otherOrg, other, "ACTIVE");
		UUID task = seedTask(otherOrg, otherProject, other, "Secret", "TODO", null);

		mvc.perform(get(taskUrl(myOrg, task)).with(as(me))).andExpect(status().isNotFound());
		patchTask(me, myOrg, task, "{\"title\":\"Stolen\",\"version\":0}").andExpect(status().isNotFound());
		mvc.perform(delete(taskUrl(myOrg, task)).with(as(me))).andExpect(status().isNotFound());

		assertThat(taskTitle(task)).isEqualTo("Secret");
	}

	@Test
	void outsideAssigneeReturns422() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner, "OWNER");
		UUID project = seedProject(org, owner, "ACTIVE");
		UUID task = seedTask(org, project, owner, "Task", "TODO", null);
		UUID outsider = seedProfile();
		seedOrg(outsider, "OWNER");

		createTask(owner, org, project, "{\"title\":\"Bad\",\"assigneeId\":\"" + outsider + "\"}")
			.andExpect(status().isUnprocessableContent())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
		patchTask(owner, org, task, "{\"assigneeId\":\"" + outsider + "\",\"version\":0}")
			.andExpect(status().isUnprocessableContent());

		assertThat(taskCount(project)).isEqualTo(1);
		assertThat(taskAssignee(task)).isNull();
	}

	@Test
	void absentAssigneeLeavesValue() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner, "OWNER");
		UUID project = seedProject(org, owner, "ACTIVE");
		UUID task = seedTask(org, project, owner, "Task", "TODO", owner);

		patchTask(owner, org, task, "{\"title\":\"Renamed\",\"version\":0}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.title").value("Renamed"))
			.andExpect(jsonPath("$.assigneeId").value(owner.toString()))
			.andExpect(jsonPath("$.version").value(1));

		assertThat(taskAssignee(task)).isEqualTo(owner.toString());
	}

	@Test
	void nullAssigneeClearsValue() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner, "OWNER");
		UUID project = seedProject(org, owner, "ACTIVE");
		UUID task = seedTask(org, project, owner, "Task", "TODO", owner);

		patchTask(owner, org, task, "{\"assigneeId\":null,\"version\":0}")
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("\"assigneeId\":null")));

		assertThat(taskAssignee(task)).isNull();
	}

	@Test
	void dueDateAndDescriptionFollowAbsentAndNullRules() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner, "OWNER");
		UUID project = seedProject(org, owner, "ACTIVE");
		UUID task = seedTask(org, project, owner, "Task", "TODO", null);

		patchTask(owner, org, task, "{\"dueDate\":\"2026-12-24\",\"description\":\"Notes\",\"version\":0}")
			.andExpect(jsonPath("$.dueDate").value("2026-12-24"))
			.andExpect(jsonPath("$.description").value("Notes"));
		patchTask(owner, org, task, "{\"priority\":\"URGENT\",\"status\":\"DONE\",\"version\":1}")
			.andExpect(jsonPath("$.priority").value("URGENT"))
			.andExpect(jsonPath("$.status").value("DONE"))
			.andExpect(jsonPath("$.dueDate").value("2026-12-24"))
			.andExpect(jsonPath("$.description").value("Notes"));
		patchTask(owner, org, task, "{\"dueDate\":null,\"description\":null,\"version\":2}")
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("\"dueDate\":null")))
			.andExpect(content().string(containsString("\"description\":null")));
	}

	@Test
	void nullForRequiredFieldReturns422() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner, "OWNER");
		UUID project = seedProject(org, owner, "ACTIVE");
		UUID task = seedTask(org, project, owner, "Kept", "TODO", null);

		for (String field : new String[] { "title", "status", "priority" }) {
			patchTask(owner, org, task, "{\"" + field + "\":null,\"version\":0}")
				.andExpect(status().isUnprocessableContent());
		}

		assertThat(taskTitle(task)).isEqualTo("Kept");
	}

	@Test
	void blankTitleReturns422() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner, "OWNER");
		UUID project = seedProject(org, owner, "ACTIVE");
		UUID task = seedTask(org, project, owner, "Kept", "TODO", null);

		createTask(owner, org, project, "{\"title\":\"   \"}")
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.errors.title").isArray());
		patchTask(owner, org, task, "{\"title\":\"   \",\"version\":0}")
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.errors.title").isArray());
	}

	@Test
	void invalidStatusInBodyReturns422() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner, "OWNER");
		UUID project = seedProject(org, owner, "ACTIVE");
		UUID task = seedTask(org, project, owner, "Task", "TODO", null);

		createTask(owner, org, project, "{\"title\":\"Bad\",\"status\":\"WONTFIX\"}")
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.errors.status").isArray());
		patchTask(owner, org, task, "{\"priority\":\"CRITICAL\",\"version\":0}")
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.errors.priority").isArray());
	}

	@Test
	void stalePatchReturns409() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner, "OWNER");
		UUID project = seedProject(org, owner, "ACTIVE");
		UUID task = seedTask(org, project, owner, "Original", "TODO", null);
		patchTask(owner, org, task, "{\"title\":\"First\",\"version\":0}").andExpect(status().isOk());

		patchTask(owner, org, task, "{\"title\":\"Second\",\"version\":0}")
			.andExpect(status().isConflict())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

		assertThat(taskTitle(task)).isEqualTo("First");
	}

	@Test
	void writeToArchivedProjectReturns409() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner, "OWNER");
		UUID project = seedProject(org, owner, "ARCHIVED");
		UUID task = seedTask(org, project, owner, "Frozen", "TODO", null);

		createTask(owner, org, project, "{\"title\":\"New\"}").andExpect(status().isConflict());
		patchTask(owner, org, task, "{\"title\":\"Changed\",\"version\":0}").andExpect(status().isConflict());
		mvc.perform(delete(taskUrl(org, task)).with(as(owner))).andExpect(status().isConflict());

		assertThat(taskCount(project)).isEqualTo(1);
		assertThat(taskTitle(task)).isEqualTo("Frozen");
		mvc.perform(get(taskUrl(org, task)).with(as(owner))).andExpect(status().isOk());
		mvc.perform(get(tasksUrl(org, project)).with(as(owner))).andExpect(jsonPath("$.totalItems").value(1));
	}

	@Test
	void deleteRemovesTaskAndAudits() throws Exception {
		UUID member = seedProfile();
		UUID org = seedOrg(member, "MEMBER");
		UUID project = seedProject(org, member, "ACTIVE");
		UUID task = seedTask(org, project, member, "Doomed", "TODO", null);

		mvc.perform(delete(taskUrl(org, task)).with(as(member))).andExpect(status().isNoContent());

		assertThat(taskCount(project)).isZero();
		assertThat(deletedAuditCount(org, member, task)).isEqualTo(1);
		mvc.perform(get(taskUrl(org, task)).with(as(member))).andExpect(status().isNotFound());
	}

	@Test
	void newTaskRequiresActiveResponsibleRole() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner, "OWNER");
		UUID project = seedProject(org, owner, "ACTIVE");
		UUID active = seedRole(org, "Backend", true);
		UUID inactive = seedRole(org, "Legacy", false);
		UUID otherOwner = seedProfile();
		UUID otherRole = seedRole(seedOrg(otherOwner, "OWNER"), "Backend", true);

		postRaw(owner, org, project, "{\"title\":\"No role\"}").andExpect(status().isUnprocessableContent());
		postRaw(owner, org, project, withRole("{\"title\":\"Inactive\"}", inactive))
			.andExpect(status().isUnprocessableContent());
		postRaw(owner, org, project, withRole("{\"title\":\"Other org\"}", otherRole))
			.andExpect(status().isUnprocessableContent());
		assertThat(taskCount(project)).isZero();

		postRaw(owner, org, project, withRole("{\"title\":\"Valid\"}", active))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.responsibleJobRoleId").value(active.toString()));
	}

	@Test
	void newTaskCannotStartInReviewOrDone() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner, "OWNER");
		UUID project = seedProject(org, owner, "ACTIVE");
		UUID role = seedRole(org, "Backend", true);

		for (String taskStatus : new String[] { "IN_REVIEW", "DONE" }) {
			postRaw(owner, org, project, withRole("{\"title\":\"Bypass\",\"status\":\"" + taskStatus + "\"}", role))
				.andExpect(status().isUnprocessableContent());
		}
		assertThat(taskCount(project)).isZero();
	}

	@Test
	void phaseOneTaskWithoutRoleCanMoveDirectlyToDone() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner, "OWNER");
		UUID project = seedProject(org, owner, "ACTIVE");
		UUID task = seedTask(org, project, owner, "Old", "TODO", null);

		patchTask(owner, org, task, "{\"status\":\"DONE\",\"version\":0}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("DONE"))
			.andExpect(content().string(containsString("\"responsibleJobRoleId\":null")));
	}

	@Test
	void taskWithRoleCannotPatchToInReviewOrDone() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner, "OWNER");
		UUID project = seedProject(org, owner, "ACTIVE");
		UUID role = seedRole(org, "Backend", true);
		UUID task = seedRoleTask(org, project, owner, "TODO", role);

		patchTask(owner, org, task, "{\"status\":\"IN_REVIEW\",\"version\":0}").andExpect(status().isConflict());
		patchTask(owner, org, task, "{\"status\":\"DONE\",\"version\":0}").andExpect(status().isConflict());
		patchTask(owner, org, task, "{\"status\":\"IN_PROGRESS\",\"version\":0}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("IN_PROGRESS"));
	}

	@Test
	void settingRoleInSamePatchCannotBypassReview() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner, "OWNER");
		UUID project = seedProject(org, owner, "ACTIVE");
		UUID role = seedRole(org, "Backend", true);
		UUID task = seedTask(org, project, owner, "Old", "TODO", null);

		patchTask(owner, org, task, "{\"status\":\"DONE\",\"responsibleJobRoleId\":\"" + role + "\",\"version\":0}")
			.andExpect(status().isConflict());
		assertThat(taskColumn("status", task)).isEqualTo("TODO");
	}

	@Test
	void taskInReviewOrDoneCannotLeaveThroughPatch() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner, "OWNER");
		UUID project = seedProject(org, owner, "ACTIVE");
		UUID role = seedRole(org, "Backend", true);
		UUID inReview = seedRoleTask(org, project, owner, "IN_REVIEW", role);
		UUID done = seedRoleTask(org, project, owner, "DONE", role);

		patchTask(owner, org, inReview, "{\"status\":\"TODO\",\"version\":0}").andExpect(status().isConflict());
		patchTask(owner, org, inReview, "{\"status\":\"DONE\",\"version\":0}").andExpect(status().isConflict());
		patchTask(owner, org, done, "{\"status\":\"IN_PROGRESS\",\"version\":0}").andExpect(status().isConflict());
	}

	@Test
	void contentCanChangeDuringReview() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner, "OWNER");
		UUID project = seedProject(org, owner, "ACTIVE");
		UUID role = seedRole(org, "Backend", true);
		UUID task = seedRoleTask(org, project, owner, "IN_REVIEW", role);

		patchTask(owner, org, task, "{\"title\":\"Improved\",\"version\":0}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("IN_REVIEW"))
			.andExpect(jsonPath("$.version").value(1));
	}

	@Test
	void roleCannotBeClearedOrChangedDuringReview() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner, "OWNER");
		UUID project = seedProject(org, owner, "ACTIVE");
		UUID backend = seedRole(org, "Backend", true);
		UUID qa = seedRole(org, "QA", true);
		UUID inactive = seedRole(org, "Legacy", false);
		UUID todo = seedRoleTask(org, project, owner, "TODO", backend);
		UUID inReview = seedRoleTask(org, project, owner, "IN_REVIEW", backend);

		patchTask(owner, org, todo, "{\"responsibleJobRoleId\":null,\"version\":0}")
			.andExpect(status().isUnprocessableContent());
		patchTask(owner, org, todo, "{\"responsibleJobRoleId\":\"" + inactive + "\",\"version\":0}")
			.andExpect(status().isUnprocessableContent());
		patchTask(owner, org, inReview, "{\"responsibleJobRoleId\":\"" + qa + "\",\"version\":0}")
			.andExpect(status().isConflict());
		patchTask(owner, org, todo, "{\"responsibleJobRoleId\":\"" + qa + "\",\"version\":0}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.responsibleJobRoleId").value(qa.toString()));
	}

	@Test
	void phaseOneTaskInReviewCannotReceiveRoleUntilItLeavesReview() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner, "OWNER");
		UUID project = seedProject(org, owner, "ACTIVE");
		UUID role = seedRole(org, "Backend", true);
		UUID task = seedTask(org, project, owner, "Old review", "IN_REVIEW", null);

		patchTask(owner, org, task, "{\"responsibleJobRoleId\":\"" + role + "\",\"version\":0}")
			.andExpect(status().isConflict());
		patchTask(owner, org, task, "{\"status\":\"IN_PROGRESS\",\"version\":0}").andExpect(status().isOk());
		patchTask(owner, org, task, "{\"responsibleJobRoleId\":\"" + role + "\",\"version\":1}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.responsibleJobRoleId").value(role.toString()));
	}

	@Test
	void settingRoleOnPhaseOneDoneTaskDoesNotReopenIt() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner, "OWNER");
		UUID project = seedProject(org, owner, "ACTIVE");
		UUID role = seedRole(org, "Backend", true);
		UUID task = seedTask(org, project, owner, "Old done", "DONE", null);

		patchTask(owner, org, task, "{\"responsibleJobRoleId\":\"" + role + "\",\"version\":0}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("DONE"))
			.andExpect(jsonPath("$.responsibleJobRoleId").value(role.toString()));
	}

	@Test
	void doneTaskWithRoleCannotBeEdited() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner, "OWNER");
		UUID project = seedProject(org, owner, "ACTIVE");
		UUID role = seedRole(org, "Backend", true);
		UUID qa = seedRole(org, "QA", true);
		UUID task = seedRoleTask(org, project, owner, "DONE", role);

		patchTask(owner, org, task, "{\"title\":\"Changed\",\"version\":0}").andExpect(status().isConflict());
		patchTask(owner, org, task, "{\"priority\":\"URGENT\",\"version\":0}").andExpect(status().isConflict());
		patchTask(owner, org, task, "{\"assigneeId\":\"" + owner + "\",\"version\":0}").andExpect(status().isConflict());
		patchTask(owner, org, task, "{\"responsibleJobRoleId\":\"" + qa + "\",\"version\":0}")
			.andExpect(status().isConflict());
		patchTask(owner, org, task, "{\"title\":\"Role task\",\"version\":0}").andExpect(status().isOk());
		assertThat(taskTitle(task)).isEqualTo("Role task");
	}

	private ResultActions createTask(UUID user, UUID org, UUID project, String json) throws Exception {
		return postRaw(user, org, project, withRole(json, seedRole(org, "Default test role", true)));
	}

	private ResultActions postRaw(UUID user, UUID org, UUID project, String json) throws Exception {
		return mvc.perform(post(tasksUrl(org, project)).with(as(user))
			.contentType(MediaType.APPLICATION_JSON)
			.content(json));
	}

	private static String withRole(String json, UUID role) {
		return json.replaceFirst("\\{", "{\"responsibleJobRoleId\":\"" + role + "\",");
	}

	private static UUID seedRole(UUID org, String name, boolean active) throws SQLException {
		try (Connection admin = adminConnection();
				PreparedStatement statement = admin.prepareStatement("""
						INSERT INTO brain.job_roles (id, organization_id, name, active) VALUES (?, ?, ?, ?)
						ON CONFLICT (organization_id, lower(name)) DO UPDATE SET active = EXCLUDED.active
						RETURNING id
						""")) {
			statement.setObject(1, UUID.randomUUID());
			statement.setObject(2, org);
			statement.setString(3, name);
			statement.setBoolean(4, active);
			try (ResultSet rows = statement.executeQuery()) {
				rows.next();
				return rows.getObject(1, UUID.class);
			}
		}
	}

	private static UUID seedRoleTask(UUID org, UUID project, UUID creator, String taskStatus, UUID role)
			throws SQLException {
		UUID task = seedTask(org, project, creator, "Role task", taskStatus, null);
		try (Connection admin = adminConnection();
				PreparedStatement statement = admin
					.prepareStatement("UPDATE brain.tasks SET responsible_job_role_id = ? WHERE id = ?")) {
			statement.setObject(1, role);
			statement.setObject(2, task);
			statement.executeUpdate();
		}
		return task;
	}

	private ResultActions patchTask(UUID user, UUID org, UUID task, String json) throws Exception {
		return mvc.perform(patch(taskUrl(org, task)).with(as(user))
			.contentType(MediaType.APPLICATION_JSON)
			.content(json));
	}

	private static String tasksUrl(UUID org, UUID project) {
		return "/api/v1/organizations/" + org + "/projects/" + project + "/tasks";
	}

	private static String taskUrl(UUID org, UUID task) {
		return "/api/v1/organizations/" + org + "/tasks/" + task;
	}

	private static RequestPostProcessor as(UUID user) {
		return jwt().jwt(token -> token.subject(user.toString()).claim("email", "user-" + user + "@example.test"));
	}

	private static UUID seedProfile() throws SQLException {
		UUID user = UUID.randomUUID();
		try (Connection admin = adminConnection();
				PreparedStatement statement = admin
					.prepareStatement("INSERT INTO brain.profiles (id, email, display_name) VALUES (?, ?, 'User')")) {
			statement.setObject(1, user);
			statement.setString(2, user + "@example.test");
			statement.executeUpdate();
		}
		return user;
	}

	private static UUID seedOrg(UUID user, String role) throws SQLException {
		UUID org = UUID.randomUUID();
		try (Connection admin = adminConnection();
				PreparedStatement statement = admin.prepareStatement(
						"INSERT INTO brain.organizations (id, name, slug, created_by) VALUES (?, 'Seeded', ?, ?)")) {
			statement.setObject(1, org);
			statement.setString(2, "seed-" + org);
			statement.setObject(3, user);
			statement.executeUpdate();
		}
		addMember(org, user, role);
		return org;
	}

	private static void addMember(UUID org, UUID user, String role) throws SQLException {
		try (Connection admin = adminConnection();
				PreparedStatement statement = admin.prepareStatement(
						"INSERT INTO brain.organization_members (organization_id, user_id, role) VALUES (?, ?, ?)")) {
			statement.setObject(1, org);
			statement.setObject(2, user);
			statement.setString(3, role);
			statement.executeUpdate();
		}
	}

	private static UUID seedProject(UUID org, UUID creator, String projectStatus) throws SQLException {
		UUID project = UUID.randomUUID();
		try (Connection admin = adminConnection();
				PreparedStatement statement = admin.prepareStatement(
						"INSERT INTO brain.projects (id, organization_id, name, status, created_by) VALUES (?, ?, ?, ?, ?)")) {
			statement.setObject(1, project);
			statement.setObject(2, org);
			statement.setString(3, "Project " + project);
			statement.setString(4, projectStatus);
			statement.setObject(5, creator);
			statement.executeUpdate();
		}
		return project;
	}

	private static UUID seedTask(UUID org, UUID project, UUID creator, String title, String taskStatus, UUID assignee)
			throws SQLException {
		UUID task = UUID.randomUUID();
		try (Connection admin = adminConnection();
				PreparedStatement statement = admin.prepareStatement("""
						INSERT INTO brain.tasks (id, organization_id, project_id, title, status, priority, assignee_id, created_by)
						VALUES (?, ?, ?, ?, ?, 'LOW', ?, ?)
						""")) {
			statement.setObject(1, task);
			statement.setObject(2, org);
			statement.setObject(3, project);
			statement.setString(4, title);
			statement.setString(5, taskStatus);
			statement.setObject(6, assignee, java.sql.Types.OTHER);
			statement.setObject(7, creator);
			statement.executeUpdate();
		}
		return task;
	}

	private static String taskTitle(UUID task) throws SQLException {
		return taskColumn("title", task);
	}

	private static String taskAssignee(UUID task) throws SQLException {
		return taskColumn("assignee_id", task);
	}

	private static String taskColumn(String column, UUID task) throws SQLException {
		try (Connection admin = adminConnection();
				PreparedStatement statement = admin
					.prepareStatement("SELECT " + column + " FROM brain.tasks WHERE id = ?")) {
			statement.setObject(1, task);
			try (ResultSet rows = statement.executeQuery()) {
				rows.next();
				return rows.getString(1);
			}
		}
	}

	private static long taskCount(UUID project) throws SQLException {
		try (Connection admin = adminConnection();
				PreparedStatement statement = admin
					.prepareStatement("SELECT count(*) FROM brain.tasks WHERE project_id = ?")) {
			statement.setObject(1, project);
			try (ResultSet rows = statement.executeQuery()) {
				rows.next();
				return rows.getLong(1);
			}
		}
	}

	private static long deletedAuditCount(UUID org, UUID actor, UUID task) throws SQLException {
		try (Connection admin = adminConnection();
				PreparedStatement statement = admin.prepareStatement("""
						SELECT count(*) FROM brain.audit_events
						WHERE organization_id = ? AND actor_id = ? AND action = 'TASK_DELETED'
						AND entity_type = 'TASK' AND entity_id = ?
						""")) {
			statement.setObject(1, org);
			statement.setObject(2, actor);
			statement.setObject(3, task);
			try (ResultSet rows = statement.executeQuery()) {
				rows.next();
				return rows.getLong(1);
			}
		}
	}

}
