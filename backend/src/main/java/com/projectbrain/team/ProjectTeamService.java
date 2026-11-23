package com.projectbrain.team;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.projectbrain.audit.api.AuditLog;
import com.projectbrain.identity.api.CurrentUserProvider;
import com.projectbrain.organization.api.OrgTx;
import com.projectbrain.organization.api.Permission;
import com.projectbrain.organization.api.Role;
import com.projectbrain.project.api.ProjectLookup;
import com.projectbrain.project.api.ProjectStatus;
import com.projectbrain.shared.web.ConflictException;
import com.projectbrain.shared.web.UnprocessableException;
import com.projectbrain.team.api.ApprovalBasis;
import com.projectbrain.team.api.Approvers;
import com.projectbrain.team.api.ApproverPolicy;
import com.projectbrain.team.api.JobRoleLookup;
import com.projectbrain.team.api.ProjectTeamLookup;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class ProjectTeamService implements ProjectTeamLookup {

	private static final String ENTITY_TYPE = "PROJECT";

	private final OrgTx orgTx;

	private final JdbcClient jdbc;

	private final ProjectLookup projects;

	private final JobRoleLookup jobRoles;

	private final CurrentUserProvider currentUser;

	private final AuditLog auditLog;

	ProjectTeamService(OrgTx orgTx, JdbcClient jdbc, ProjectLookup projects, JobRoleLookup jobRoles,
			CurrentUserProvider currentUser, AuditLog auditLog) {
		this.orgTx = orgTx;
		this.jdbc = jdbc;
		this.projects = projects;
		this.jobRoles = jobRoles;
		this.currentUser = currentUser;
		this.auditLog = auditLog;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public Approvers approvers(UUID orgId, UUID projectId, UUID jobRoleId, UUID submitterId,
			boolean allowSelfApproval) {
		Set<UUID> holders = new HashSet<>(jdbc.sql("""
				SELECT a.user_id
				FROM brain.project_job_roles a
				JOIN brain.organization_members m
				  ON m.organization_id = a.organization_id AND m.user_id = a.user_id
				WHERE a.organization_id = :orgId AND a.project_id = :projectId AND a.job_role_id = :jobRoleId
				  AND m.role IN ('OWNER', 'ADMIN', 'MEMBER')
				""")
			.param("orgId", orgId)
			.param("projectId", projectId)
			.param("jobRoleId", jobRoleId)
			.query(UUID.class)
			.list());
		return ApproverPolicy.resolve(holders, owners(orgId), submitterId, allowSelfApproval);
	}

	ProjectTeam get(UUID orgId, UUID projectId) {
		return orgTx.inOrg(orgId, Permission.VIEW, () -> {
			projects.requireStatus(orgId, projectId);
			List<MemberRow> memberRows = jdbc.sql("""
					SELECT m.user_id, p.email, COALESCE(p.display_name, p.email) AS display_name, m.role
					FROM brain.organization_members m
					JOIN brain.profiles p ON p.id = m.user_id
					WHERE m.organization_id = :orgId
					ORDER BY lower(COALESCE(p.display_name, p.email)), m.user_id
					""")
				.param("orgId", orgId)
				.query((row, rowNumber) -> new MemberRow(row.getObject("user_id", UUID.class), row.getString("email"),
						row.getString("display_name"), Role.valueOf(row.getString("role"))))
				.list();
			Map<UUID, List<UUID>> rolesByUser = new HashMap<>();
			Map<UUID, Set<UUID>> approversByRole = new HashMap<>();
			Map<UUID, Role> accessByUser = new HashMap<>();
			memberRows.forEach(member -> accessByUser.put(member.userId(), member.role()));
			jdbc.sql("""
					SELECT user_id, job_role_id FROM brain.project_job_roles
					WHERE organization_id = :orgId AND project_id = :projectId
					ORDER BY job_role_id
					""")
				.param("orgId", orgId)
				.param("projectId", projectId)
				.query((row, rowNumber) -> new Assignment(row.getObject("user_id", UUID.class),
						row.getObject("job_role_id", UUID.class)))
				.list()
				.forEach(assignment -> {
					rolesByUser.computeIfAbsent(assignment.userId(), key -> new ArrayList<>())
						.add(assignment.jobRoleId());
					if (accessByUser.get(assignment.userId()) != Role.VIEWER) {
						approversByRole.computeIfAbsent(assignment.jobRoleId(), key -> new HashSet<>())
							.add(assignment.userId());
					}
				});
			Set<UUID> owners = owners(orgId);
			List<TeamJobRole> roles = jdbc.sql("""
					SELECT id, name, active FROM brain.job_roles
					WHERE organization_id = :orgId
					ORDER BY lower(name), id
					""")
				.param("orgId", orgId)
				.query((row, rowNumber) -> {
					UUID id = row.getObject("id", UUID.class);
					ApprovalBasis basis = ApproverPolicy.resolve(approversByRole.getOrDefault(id, Set.of()), owners, null,
							true)
						.basis();
					return new TeamJobRole(id, row.getString("name"), row.getBoolean("active"), basis);
				})
				.list();
			List<TeamMember> members = memberRows.stream()
				.map(member -> new TeamMember(member.userId(), member.email(), member.displayName(), member.role(),
						rolesByUser.getOrDefault(member.userId(), List.of())))
				.toList();
			return new ProjectTeam(members, roles);
		});
	}

	void assign(UUID orgId, UUID projectId, UUID userId, UUID jobRoleId) {
		UUID actor = currentUser.get().id();
		orgTx.inOrg(orgId, Permission.MANAGE_PROJECTS, () -> {
			requireWritable(orgId, projectId);
			if (!orgTx.isMember(orgId, userId)) {
				throw new UnprocessableException("That person is not a member of this organization.");
			}
			jobRoles.requireActive(orgId, jobRoleId);
			int inserted = jdbc.sql("""
					INSERT INTO brain.project_job_roles (organization_id, project_id, user_id, job_role_id, created_by)
					VALUES (:orgId, :projectId, :userId, :jobRoleId, :actor)
					ON CONFLICT DO NOTHING
					""")
				.param("orgId", orgId)
				.param("projectId", projectId)
				.param("userId", userId)
				.param("jobRoleId", jobRoleId)
				.param("actor", actor)
				.update();
			if (inserted > 0) {
				audit(orgId, "PROJECT_JOB_ROLE_ASSIGNED", projectId, userId, jobRoleId);
			}
			return null;
		});
	}

	void remove(UUID orgId, UUID projectId, UUID userId, UUID jobRoleId) {
		orgTx.inOrg(orgId, Permission.MANAGE_PROJECTS, () -> {
			requireWritable(orgId, projectId);
			int removed = jdbc.sql("""
					DELETE FROM brain.project_job_roles
					WHERE organization_id = :orgId AND project_id = :projectId
					  AND user_id = :userId AND job_role_id = :jobRoleId
					""")
				.param("orgId", orgId)
				.param("projectId", projectId)
				.param("userId", userId)
				.param("jobRoleId", jobRoleId)
				.update();
			if (removed > 0) {
				audit(orgId, "PROJECT_JOB_ROLE_REMOVED", projectId, userId, jobRoleId);
			}
			return null;
		});
	}

	private void requireWritable(UUID orgId, UUID projectId) {
		if (projects.requireStatus(orgId, projectId) == ProjectStatus.ARCHIVED) {
			throw new ConflictException("The project is archived.");
		}
	}

	private Set<UUID> owners(UUID orgId) {
		return new HashSet<>(jdbc.sql("""
				SELECT user_id FROM brain.organization_members
				WHERE organization_id = :orgId AND role = 'OWNER'
				""")
			.param("orgId", orgId)
			.query(UUID.class)
			.list());
	}

	private void audit(UUID orgId, String action, UUID projectId, UUID userId, UUID jobRoleId) {
		String roleName = jdbc.sql("SELECT name FROM brain.job_roles WHERE organization_id = :orgId AND id = :id")
			.param("orgId", orgId)
			.param("id", jobRoleId)
			.query(String.class)
			.single();
		auditLog.record(orgId, action, ENTITY_TYPE, projectId,
				Map.of("name", roleName, "userId", userId.toString(), "jobRoleId", jobRoleId.toString()));
	}

	private record MemberRow(UUID userId, String email, String displayName, Role role) {
	}

	private record Assignment(UUID userId, UUID jobRoleId) {
	}

	record TeamMember(UUID userId, String email, String displayName, Role role, List<UUID> jobRoleIds) {
	}

	record TeamJobRole(UUID id, String name, boolean active, ApprovalBasis approvalBasis) {
	}

	record ProjectTeam(List<TeamMember> members, List<TeamJobRole> jobRoles) {
	}

}
