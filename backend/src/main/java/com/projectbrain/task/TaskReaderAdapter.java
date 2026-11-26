package com.projectbrain.task;

import java.util.List;
import java.util.UUID;

import com.projectbrain.organization.api.OrgTx;
import com.projectbrain.organization.api.Permission;
import com.projectbrain.shared.web.PageParams;
import com.projectbrain.shared.web.UnprocessableException;
import com.projectbrain.task.api.TaskReader;
import com.projectbrain.team.api.JobRoleLookup;
import org.springframework.stereotype.Component;

@Component
class TaskReaderAdapter implements TaskReader {

	private final TaskService tasks;

	private final OrgTx orgTx;

	private final JobRoleLookup jobRoles;

	TaskReaderAdapter(TaskService tasks, OrgTx orgTx, JobRoleLookup jobRoles) {
		this.tasks = tasks;
		this.orgTx = orgTx;
		this.jobRoles = jobRoles;
	}

	@Override
	public List<String> activeJobRoles(UUID orgId) {
		return orgTx.inOrg(orgId, Permission.VIEW, () -> jobRoles.activeNames(orgId));
	}

	@Override
	public List<TaskSummary> list(UUID orgId, UUID projectId, String status) {
		return tasks.list(orgId, projectId, parse(status), null, PageParams.of(0, 100))
			.items()
			.stream()
			.map(task -> new TaskSummary(task.id(), task.title(), task.description(), task.status().name(),
					task.priority().name(), task.assigneeId(), task.dueDate()))
			.toList();
	}

	private static TaskStatus parse(String status) {
		if (status == null || status.isBlank()) {
			return null;
		}
		try {
			return TaskStatus.valueOf(status.strip());
		}
		catch (IllegalArgumentException ex) {
			throw new UnprocessableException("status must be BACKLOG, TODO, IN_PROGRESS, IN_REVIEW, or DONE.");
		}
	}

}
