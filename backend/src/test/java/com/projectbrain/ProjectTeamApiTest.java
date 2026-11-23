package com.projectbrain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.projectbrain.support.TeamApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = { "SUPABASE_URL=http://127.0.0.1:1", "APP_CORS_ALLOWED_ORIGINS=http://localhost:5173" })
class ProjectTeamApiTest extends TeamApiTestBase {

	@Autowired
	private MockMvc mvc;

	@Test
	void ownerAndAdminCanAssignJobRoles() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner);
		UUID admin = seedMember(org, "ADMIN");
		UUID ravi = seedMember(org, "MEMBER");
		UUID project = seedProject(org, owner, "ACTIVE");
		UUID backend = seedJobRole(org, "Backend", true);
		UUID qa = seedJobRole(org, "QA", true);

		mvc.perform(put(assignment(org, project, ravi, backend)).with(as(owner))).andExpect(status().isNoContent());
		mvc.perform(put(assignment(org, project, ravi, qa)).with(as(admin))).andExpect(status().isNoContent());

		mvc.perform(get(team(org, project)).with(as(ravi)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.members[?(@.userId=='" + ravi + "')].email").value(emailOf(ravi)))
			.andExpect(jsonPath("$.members[?(@.userId=='" + ravi + "')].role").value("MEMBER"))
			.andExpect(jsonPath("$.members[?(@.userId=='" + ravi + "')].jobRoleIds.length()").value(2))
			.andExpect(jsonPath("$.jobRoles[?(@.id=='" + backend + "')].approvalBasis").value("ROLE_HOLDER"));

		mvc.perform(delete(assignment(org, project, ravi, backend)).with(as(admin))).andExpect(status().isNoContent());

		mvc.perform(get(team(org, project)).with(as(owner)))
			.andExpect(jsonPath("$.members[?(@.userId=='" + ravi + "')].jobRoleIds[*]", contains(qa.toString())))
			.andExpect(jsonPath("$.jobRoles[?(@.id=='" + backend + "')].approvalBasis").value("OWNER_FALLBACK"));
		assertThat(audits(org, "PROJECT_JOB_ROLE_ASSIGNED", project)).isEqualTo(2);
		assertThat(audits(org, "PROJECT_JOB_ROLE_REMOVED", project)).isEqualTo(1);
	}

	@Test
	void viewerCanHoldRoleButIsNotAnApprover() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner);
		UUID viewer = seedMember(org, "VIEWER");
		UUID project = seedProject(org, owner, "ACTIVE");
		UUID backend = seedJobRole(org, "Backend", true);

		mvc.perform(put(assignment(org, project, viewer, backend)).with(as(owner))).andExpect(status().isNoContent());

		mvc.perform(get(team(org, project)).with(as(viewer)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.members[?(@.userId=='" + viewer + "')].jobRoleIds[*]", contains(backend.toString())))
			.andExpect(jsonPath("$.jobRoles[?(@.id=='" + backend + "')].approvalBasis").value("OWNER_FALLBACK"));
	}

	@Test
	void memberCannotAssignJobRoles() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner);
		UUID member = seedMember(org, "MEMBER");
		UUID project = seedProject(org, owner, "ACTIVE");
		UUID backend = seedJobRole(org, "Backend", true);

		mvc.perform(put(assignment(org, project, member, backend)).with(as(member))).andExpect(status().isForbidden());
		assignJobRole(org, project, member, backend, owner);
		mvc.perform(delete(assignment(org, project, member, backend)).with(as(member)))
			.andExpect(status().isForbidden());
	}

	@Test
	void cannotAssignInactiveRoleOrNonMember() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner);
		UUID member = seedMember(org, "MEMBER");
		UUID outsider = seedProfile();
		UUID project = seedProject(org, owner, "ACTIVE");
		UUID active = seedJobRole(org, "Backend", true);
		UUID inactive = seedJobRole(org, "Legacy", false);

		mvc.perform(put(assignment(org, project, member, inactive)).with(as(owner)))
			.andExpect(status().isUnprocessableContent());
		mvc.perform(put(assignment(org, project, outsider, active)).with(as(owner)))
			.andExpect(status().isUnprocessableContent());
	}

	@Test
	void repeatedAssignmentIsIdempotent() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner);
		UUID member = seedMember(org, "MEMBER");
		UUID project = seedProject(org, owner, "ACTIVE");
		UUID backend = seedJobRole(org, "Backend", true);

		mvc.perform(put(assignment(org, project, member, backend)).with(as(owner))).andExpect(status().isNoContent());
		mvc.perform(put(assignment(org, project, member, backend)).with(as(owner))).andExpect(status().isNoContent());
		mvc.perform(delete(assignment(org, project, member, backend)).with(as(owner))).andExpect(status().isNoContent());
		mvc.perform(delete(assignment(org, project, member, backend)).with(as(owner))).andExpect(status().isNoContent());

		assertThat(audits(org, "PROJECT_JOB_ROLE_ASSIGNED", project)).isEqualTo(1);
		assertThat(audits(org, "PROJECT_JOB_ROLE_REMOVED", project)).isEqualTo(1);
		mvc.perform(get(team(org, project)).with(as(owner)))
			.andExpect(jsonPath("$.members[?(@.userId=='" + member + "')].jobRoleIds[*]", empty()));
	}

	@Test
	void archivedProjectBlocksAssignmentChanges() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner);
		UUID member = seedMember(org, "MEMBER");
		UUID project = seedProject(org, owner, "ARCHIVED");
		UUID backend = seedJobRole(org, "Backend", true);

		mvc.perform(put(assignment(org, project, member, backend)).with(as(owner))).andExpect(status().isConflict());
		mvc.perform(delete(assignment(org, project, member, backend)).with(as(owner))).andExpect(status().isConflict());
		mvc.perform(get(team(org, project)).with(as(owner))).andExpect(status().isOk());
	}

	@Test
	void anotherOrganizationsProjectAndRoleAreRejected() throws Exception {
		UUID ownerA = seedProfile();
		UUID orgA = seedOrg(ownerA);
		UUID member = seedMember(orgA, "MEMBER");
		UUID projectA = seedProject(orgA, ownerA, "ACTIVE");
		UUID roleA = seedJobRole(orgA, "Backend", true);
		UUID ownerB = seedProfile();
		UUID orgB = seedOrg(ownerB);
		UUID projectB = seedProject(orgB, ownerB, "ACTIVE");
		UUID roleB = seedJobRole(orgB, "Backend", true);

		mvc.perform(get(team(orgA, projectB)).with(as(ownerA))).andExpect(status().isNotFound());
		mvc.perform(get(team(orgB, projectB)).with(as(ownerA))).andExpect(status().isNotFound());
		mvc.perform(put(assignment(orgA, projectB, member, roleA)).with(as(ownerA))).andExpect(status().isNotFound());
		mvc.perform(put(assignment(orgA, projectA, member, roleB)).with(as(ownerA)))
			.andExpect(status().isUnprocessableContent());
		assertThat(countRows("SELECT count(*) FROM brain.project_job_roles WHERE organization_id = ?", orgA)).isZero();
	}

	private static String team(UUID org, UUID project) {
		return ORGS + "/" + org + "/projects/" + project + "/team";
	}

	private static String assignment(UUID org, UUID project, UUID user, UUID role) {
		return team(org, project) + "/" + user + "/job-roles/" + role;
	}

	private static long audits(UUID org, String action, UUID project) throws Exception {
		return countRows("""
				SELECT count(*) FROM brain.audit_events
				WHERE organization_id = ? AND action = ? AND entity_type = 'PROJECT' AND entity_id = ?
				""", org, action, project);
	}

}
