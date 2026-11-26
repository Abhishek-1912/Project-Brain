package com.projectbrain.task;

import java.util.UUID;

import com.projectbrain.organization.api.OrgTx;
import com.projectbrain.organization.api.Permission;
import com.projectbrain.project.api.ProjectLookup;
import com.projectbrain.shared.web.UnprocessableException;
import com.projectbrain.task.TaskService.NewTask;
import com.projectbrain.task.api.TaskWriter;
import com.projectbrain.team.api.JobRoleLookup;
import org.springframework.stereotype.Component;

@Component
class TaskWriterAdapter implements TaskWriter {

	private static final int MAX_TITLE_CHARS = 200;

	private static final int MAX_DESCRIPTION_CHARS = 10000;

	private final TaskService tasks;

	private final OrgTx orgTx;

	private final JobRoleLookup jobRoles;

	private final ProjectLookup projects;

	TaskWriterAdapter(TaskService tasks, OrgTx orgTx, JobRoleLookup jobRoles, ProjectLookup projects) {
		this.tasks = tasks;
		this.orgTx = orgTx;
		this.jobRoles = jobRoles;
		this.projects = projects;
	}

	@Override
	public UUID create(UUID orgId, UUID projectId, String title, String description, String priority,
			String jobRoleName) {
		String cleanTitle = title == null ? "" : title.strip();
		if (cleanTitle.isEmpty() || cleanTitle.length() > MAX_TITLE_CHARS) {
			throw new UnprocessableException("title must be between 1 and 200 characters.");
		}
		String cleanDescription = description == null || description.isBlank() ? null : description.strip();
		if (cleanDescription != null && cleanDescription.length() > MAX_DESCRIPTION_CHARS) {
			throw new UnprocessableException("description must be 10,000 characters or fewer.");
		}
		String roleName = jobRoleName == null ? "" : jobRoleName.strip();
		// The project is checked first, so another organization's project is simply not found.
		UUID role = orgTx.inOrg(orgId, Permission.VIEW, () -> {
			projects.requireStatus(orgId, projectId);
			return jobRoles.findActiveByName(orgId, roleName);
		})
			.orElseThrow(() -> new UnprocessableException(
					"No active job role named \"" + roleName + "\". Call list_job_roles to see the choices."));
		return tasks.create(orgId, projectId,
				new NewTask(cleanTitle, cleanDescription, TaskStatus.BACKLOG, priority(priority), null, null, role))
			.id();
	}

	private static TaskPriority priority(String value) {
		if (value == null || value.isBlank()) {
			return TaskPriority.MEDIUM;
		}
		try {
			return TaskPriority.valueOf(value.strip());
		}
		catch (IllegalArgumentException ex) {
			throw new UnprocessableException("priority must be LOW, MEDIUM, HIGH, or URGENT.");
		}
	}

}
