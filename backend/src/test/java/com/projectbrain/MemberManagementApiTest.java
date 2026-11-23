package com.projectbrain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = { "SUPABASE_URL=http://127.0.0.1:1", "APP_CORS_ALLOWED_ORIGINS=http://localhost:5173" })
class MemberManagementApiTest extends TeamApiTestBase {

	@Autowired
	private MockMvc mvc;

	@Test
	void ownerCanChangeRoleAndRemoveMember() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner);
		UUID member = seedMember(org, "MEMBER");

		mvc.perform(get(ORGS + "/" + org + "/members").with(as(owner)))
			.andExpect(jsonPath("$.items[?(@.userId=='" + member + "')].version").value(0));
		mvc.perform(patch(member(org, member)).with(as(owner)).contentType(MediaType.APPLICATION_JSON)
			.content(roleBody("ADMIN", 0)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.userId").value(member.toString()))
			.andExpect(jsonPath("$.role").value("ADMIN"))
			.andExpect(jsonPath("$.version").value(1));
		mvc.perform(delete(member(org, member)).with(as(owner))).andExpect(status().isNoContent());

		assertThat(memberCount(org, member)).isZero();
		assertThat(audits(org, "MEMBER_ROLE_CHANGED", member)).isEqualTo(1);
		assertThat(audits(org, "MEMBER_REMOVED", member)).isEqualTo(1);
		mvc.perform(get(ORGS + "/" + org).with(as(member))).andExpect(status().isNotFound());
	}

	@Test
	void ownerCanPromoteAnotherOwnerThenStepDown() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner);
		UUID member = seedMember(org, "MEMBER");

		mvc.perform(patch(member(org, member)).with(as(owner)).contentType(MediaType.APPLICATION_JSON)
			.content(roleBody("OWNER", 0)))
			.andExpect(status().isOk());
		mvc.perform(patch(member(org, owner)).with(as(owner)).contentType(MediaType.APPLICATION_JSON)
			.content(roleBody("MEMBER", 0)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.role").value("MEMBER"));
	}

	@Test
	void adminCannotChangeRoleOrRemoveMember() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner);
		UUID admin = seedMember(org, "ADMIN");
		UUID member = seedMember(org, "MEMBER");

		mvc.perform(patch(member(org, member)).with(as(admin)).contentType(MediaType.APPLICATION_JSON)
			.content(roleBody("VIEWER", 0)))
			.andExpect(status().isForbidden());
		mvc.perform(patch(member(org, admin)).with(as(admin)).contentType(MediaType.APPLICATION_JSON)
			.content(roleBody("OWNER", 0)))
			.andExpect(status().isForbidden());
		mvc.perform(delete(member(org, member)).with(as(admin))).andExpect(status().isForbidden());
		assertThat(memberCount(org, member)).isEqualTo(1);
	}

	@Test
	void lastOwnerCannotBeDowngradedOrRemoved() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner);

		mvc.perform(patch(member(org, owner)).with(as(owner)).contentType(MediaType.APPLICATION_JSON)
			.content(roleBody("ADMIN", 0)))
			.andExpect(status().isConflict());
		mvc.perform(delete(member(org, owner)).with(as(owner))).andExpect(status().isConflict());
		assertThat(ownerCount(org)).isEqualTo(1);
	}

	@RepeatedTest(5)
	void concurrentRequestsCannotRemoveLastOwner() throws Exception {
		UUID first = seedProfile();
		UUID org = seedOrg(first);
		UUID second = seedMember(org, "OWNER");
		CountDownLatch start = new CountDownLatch(1);
		Callable<Integer> firstRemovesSecond = () -> {
			start.await();
			return mvc.perform(delete(member(org, second)).with(as(first))).andReturn().getResponse().getStatus();
		};
		Callable<Integer> secondDowngradesFirst = () -> {
			start.await();
			return mvc.perform(patch(member(org, first)).with(as(second)).contentType(MediaType.APPLICATION_JSON)
				.content(roleBody("MEMBER", 0)))
				.andReturn()
				.getResponse()
				.getStatus();
		};
		ExecutorService pool = Executors.newFixedThreadPool(2);
		List<Integer> statuses;
		try {
			Future<Integer> removal = pool.submit(firstRemovesSecond);
			Future<Integer> downgrade = pool.submit(secondDowngradesFirst);
			start.countDown();
			statuses = List.of(removal.get(), downgrade.get());
		}
		finally {
			pool.shutdownNow();
		}

		assertThat(ownerCount(org)).isEqualTo(1);
		assertThat(statuses).doesNotContain(500);
	}

	@Test
	void removalUnassignsTasksAndClearsJobRolesButKeepsHistory() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner);
		UUID member = seedMember(org, "MEMBER");
		UUID project = seedProject(org, owner, "ACTIVE");
		UUID jobRole = seedJobRole(org, "Backend", true);
		assignJobRole(org, project, member, jobRole, owner);
		UUID task = UUID.randomUUID();
		execute("""
				INSERT INTO brain.tasks (id, organization_id, project_id, title, assignee_id, created_by, version)
				VALUES (?, ?, ?, 'Assigned', ?, ?, 4)
				""", task, org, project, member, owner);
		execute("""
				INSERT INTO brain.task_reviews (id, organization_id, project_id, task_id, task_version, action, actor_id, job_role_id)
				VALUES (?, ?, ?, ?, 4, 'SUBMITTED', ?, ?)
				""", UUID.randomUUID(), org, project, task, member, jobRole);

		mvc.perform(delete(member(org, member)).with(as(owner))).andExpect(status().isNoContent());

		assertThat(countRows("SELECT count(*) FROM brain.tasks WHERE id = ? AND assignee_id IS NULL AND version = 5", task))
			.isEqualTo(1);
		assertThat(countRows("SELECT count(*) FROM brain.project_job_roles WHERE organization_id = ? AND user_id = ?", org,
				member)).isZero();
		assertThat(countRows("SELECT count(*) FROM brain.task_reviews WHERE task_id = ? AND actor_id = ?", task, member))
			.isEqualTo(1);
		assertThat(countRows("SELECT count(*) FROM brain.profiles WHERE id = ?", member)).isEqualTo(1);
	}

	@Test
	void removalDoesNotTouchSamePersonInAnotherOrganization() throws Exception {
		UUID ownerA = seedProfile();
		UUID orgA = seedOrg(ownerA);
		UUID ownerB = seedProfile();
		UUID orgB = seedOrg(ownerB);
		UUID shared = seedMember(orgA, "MEMBER");
		addMember(orgB, shared, "MEMBER");
		UUID projectB = seedProject(orgB, ownerB, "ACTIVE");
		UUID roleB = seedJobRole(orgB, "Backend", true);
		assignJobRole(orgB, projectB, shared, roleB, ownerB);
		UUID taskB = UUID.randomUUID();
		execute("""
				INSERT INTO brain.tasks (id, organization_id, project_id, title, assignee_id, created_by)
				VALUES (?, ?, ?, 'Other org', ?, ?)
				""", taskB, orgB, projectB, shared, ownerB);

		mvc.perform(delete(member(orgA, shared)).with(as(ownerA))).andExpect(status().isNoContent());

		assertThat(memberCount(orgB, shared)).isEqualTo(1);
		assertThat(countRows("SELECT count(*) FROM brain.tasks WHERE id = ? AND assignee_id = ?", taskB, shared))
			.isEqualTo(1);
		assertThat(countRows("SELECT count(*) FROM brain.project_job_roles WHERE organization_id = ? AND user_id = ?", orgB,
				shared)).isEqualTo(1);
	}

	@Test
	void staleVersionUnknownMemberAndInvalidRoleAreRejected() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner);
		UUID member = seedMember(org, "MEMBER");

		mvc.perform(patch(member(org, member)).with(as(owner)).contentType(MediaType.APPLICATION_JSON)
			.content(roleBody("VIEWER", 9)))
			.andExpect(status().isConflict());
		mvc.perform(patch(member(org, UUID.randomUUID())).with(as(owner)).contentType(MediaType.APPLICATION_JSON)
			.content(roleBody("VIEWER", 0)))
			.andExpect(status().isNotFound());
		mvc.perform(patch(member(org, member)).with(as(owner)).contentType(MediaType.APPLICATION_JSON)
			.content(roleBody("SUPERUSER", 0)))
			.andExpect(status().isUnprocessableContent());
		mvc.perform(delete(member(org, UUID.randomUUID())).with(as(owner))).andExpect(status().isNotFound());
	}

	private static String member(UUID org, UUID user) {
		return ORGS + "/" + org + "/members/" + user;
	}

	private static String roleBody(String role, long version) {
		return "{\"role\":\"" + role + "\",\"version\":" + version + "}";
	}

	private static long memberCount(UUID org, UUID user) throws Exception {
		return countRows("SELECT count(*) FROM brain.organization_members WHERE organization_id = ? AND user_id = ?", org,
				user);
	}

	private static long ownerCount(UUID org) throws Exception {
		return countRows("SELECT count(*) FROM brain.organization_members WHERE organization_id = ? AND role = 'OWNER'", org);
	}

	private static long audits(UUID org, String action, UUID user) throws Exception {
		return countRows("""
				SELECT count(*) FROM brain.audit_events
				WHERE organization_id = ? AND action = ? AND entity_type = 'MEMBER' AND entity_id = ?
				""", org, action, user);
	}

}
