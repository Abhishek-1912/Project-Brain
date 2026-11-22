package com.projectbrain.project;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.projectbrain.audit.api.AuditLog;
import com.projectbrain.identity.api.CurrentUserProvider;
import com.projectbrain.organization.api.OrgTx;
import com.projectbrain.organization.api.Permission;
import com.projectbrain.project.api.ProjectLookup;
import com.projectbrain.project.api.ProjectStatus;
import com.projectbrain.shared.web.ConflictException;
import com.projectbrain.shared.web.NotFoundException;
import com.projectbrain.shared.web.PageParams;
import com.projectbrain.shared.web.PageResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class ProjectService implements ProjectLookup {

	private static final String ENTITY_TYPE = "PROJECT";

	private static final String UNIQUE_VIOLATION = "23505";

	private final ProjectRepository projects;

	private final OrgTx orgTx;

	private final CurrentUserProvider currentUser;

	private final AuditLog auditLog;

	ProjectService(ProjectRepository projects, OrgTx orgTx, CurrentUserProvider currentUser, AuditLog auditLog) {
		this.projects = projects;
		this.orgTx = orgTx;
		this.currentUser = currentUser;
		this.auditLog = auditLog;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public ProjectStatus requireStatus(UUID orgId, UUID projectId) {
		return projects.findStatus(orgId, projectId).orElseThrow(ProjectService::notFound);
	}

	PageResponse<ProjectView> list(UUID orgId, PageParams paging) {
		return orgTx.inOrg(orgId, Permission.VIEW, () -> {
			long total = projects.countByOrganizationId(orgId);
			// Checked first because the query layer cannot express an offset beyond the int range.
			List<ProjectView> items = paging.offset() >= total ? List.of()
					: projects.findPage(orgId, PageRequest.of(paging.page(), paging.size()))
						.stream()
						.map(ProjectView::from)
						.toList();
			return new PageResponse<>(items, paging.page(), paging.size(), total);
		});
	}

	ProjectView create(UUID orgId, String name, String description) {
		UUID userId = currentUser.get().id();
		return orgTx.inOrg(orgId, Permission.MANAGE_PROJECTS, () -> {
			String trimmedName = name.trim();
			Project project = new Project(UUID.randomUUID(), orgId, trimmedName, description, userId);
			saveAndFlush(project);
			auditLog.record(orgId, "PROJECT_CREATED", ENTITY_TYPE, project.getId(), Map.of("name", trimmedName));
			return ProjectView.from(project);
		});
	}

	ProjectView get(UUID orgId, UUID projectId) {
		return orgTx.inOrg(orgId, Permission.VIEW, () -> ProjectView.from(require(orgId, projectId)));
	}

	ProjectView update(UUID orgId, UUID projectId, ProjectChanges changes) {
		return orgTx.inOrg(orgId, Permission.MANAGE_PROJECTS, () -> {
			Project project = require(orgId, projectId);
			if (project.getVersion() != changes.version()) {
				throw new ConflictException("The project was changed by someone else. Reload and try again.");
			}
			List<String> changed = new ArrayList<>();
			if (changes.name() != null && !changes.name().trim().equals(project.getName())) {
				project.setName(changes.name().trim());
				changed.add("name");
			}
			if (changes.descriptionProvided() && !Objects.equals(changes.description(), project.getDescription())) {
				project.setDescription(changes.description());
				changed.add("description");
			}
			boolean archived = false;
			if (changes.status() != null && changes.status() != project.getStatus()) {
				archived = changes.status() == ProjectStatus.ARCHIVED;
				project.setStatus(changes.status());
				changed.add("status");
			}
			if (changed.isEmpty()) {
				return ProjectView.from(project);
			}
			saveAndFlush(project);
			auditLog.record(orgId, archived ? "PROJECT_ARCHIVED" : "PROJECT_UPDATED", ENTITY_TYPE, projectId,
					Map.of("name", project.getName(), "changedFields", changed));
			return ProjectView.from(project);
		});
	}

	private Project require(UUID orgId, UUID projectId) {
		return projects.findByOrganizationIdAndId(orgId, projectId).orElseThrow(ProjectService::notFound);
	}

	private void saveAndFlush(Project project) {
		try {
			projects.saveAndFlush(project);
		}
		catch (DataIntegrityViolationException ex) {
			if (ex.getMostSpecificCause() instanceof SQLException sql && UNIQUE_VIOLATION.equals(sql.getSQLState())) {
				throw new ConflictException("A project with that name already exists.");
			}
			throw ex;
		}
	}

	private static NotFoundException notFound() {
		return new NotFoundException("Project not found.");
	}

	record ProjectChanges(String name, boolean descriptionProvided, String description, ProjectStatus status,
			long version) {
	}

	record ProjectView(UUID id, UUID organizationId, String name, String description, ProjectStatus status,
			UUID createdBy, OffsetDateTime createdAt, OffsetDateTime updatedAt, long version) {

		static ProjectView from(Project project) {
			return new ProjectView(project.getId(), project.getOrganizationId(), project.getName(),
					project.getDescription(), project.getStatus(), project.getCreatedBy(), project.getCreatedAt(),
					project.getUpdatedAt(), project.getVersion());
		}

	}

}
