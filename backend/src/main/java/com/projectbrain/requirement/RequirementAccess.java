package com.projectbrain.requirement;

import java.util.UUID;

import com.projectbrain.organization.api.OrgTx;
import com.projectbrain.organization.api.Permission;
import com.projectbrain.project.api.ProjectLookup;
import com.projectbrain.project.api.ProjectStatus;
import com.projectbrain.shared.web.ConflictException;
import com.projectbrain.shared.web.ForbiddenException;
import com.projectbrain.team.api.ProjectTeamLookup;
import org.springframework.stereotype.Component;

/** Every method must run inside an organization transaction. */
@Component
class RequirementAccess {

	private final OrgTx orgTx;

	private final ProjectTeamLookup team;

	private final ProjectLookup projects;

	RequirementAccess(OrgTx orgTx, ProjectTeamLookup team, ProjectLookup projects) {
		this.orgTx = orgTx;
		this.team = team;
		this.projects = projects;
	}

	/** Owners, Admins, and people holding the document's requirements approver role may edit. */
	void requireEditor(UUID orgId, UUID projectId, UUID approverJobRoleId, UUID actor) {
		if (orgTx.hasPermission(orgId, actor, Permission.MANAGE_PROJECTS)) {
			return;
		}
		if (approverJobRoleId != null
				&& team.approvers(orgId, projectId, approverJobRoleId, actor, true).userIds().contains(actor)) {
			return;
		}
		throw new ForbiddenException("Only an Owner, Admin, or requirements approver can do this.");
	}

	void requireWritable(UUID orgId, UUID projectId) {
		if (projects.requireStatus(orgId, projectId) == ProjectStatus.ARCHIVED) {
			throw new ConflictException("The project is archived.");
		}
	}

}
