package com.projectbrain.task;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "tasks")
public class Task {

	@Id
	private UUID id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private UUID organizationId;

	@Column(name = "project_id", nullable = false, updatable = false)
	private UUID projectId;

	@Column(nullable = false)
	private String title;

	private String description;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private TaskStatus status;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private TaskPriority priority;

	@Column(name = "assignee_id")
	private UUID assigneeId;

	@Column(name = "due_date")
	private LocalDate dueDate;

	@Column(name = "responsible_job_role_id")
	private UUID responsibleJobRoleId;

	@Column(name = "created_by", nullable = false, updatable = false)
	private UUID createdBy;

	@Column(name = "created_at", nullable = false, updatable = false)
	private OffsetDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private OffsetDateTime updatedAt;

	@Column(name = "source_story_id", updatable = false)
	private UUID sourceStoryId;

	// A wrapper type, so Spring Data treats a new entity with an assigned ID as an insert.
	@Version
	private Long version;

	protected Task() {
	}

	Task(UUID id, UUID organizationId, UUID projectId, String title, String description, TaskStatus status,
			TaskPriority priority, UUID assigneeId, LocalDate dueDate, UUID responsibleJobRoleId, UUID createdBy) {
		this.id = id;
		this.organizationId = organizationId;
		this.projectId = projectId;
		this.title = title;
		this.description = description;
		this.status = status;
		this.priority = priority;
		this.assigneeId = assigneeId;
		this.dueDate = dueDate;
		this.responsibleJobRoleId = responsibleJobRoleId;
		this.createdBy = createdBy;
	}

	@PrePersist
	void onCreate() {
		this.createdAt = now();
		this.updatedAt = this.createdAt;
	}

	@PreUpdate
	void onUpdate() {
		this.updatedAt = now();
	}

	// PostgreSQL stores microseconds, so responses match what a later read returns.
	private static OffsetDateTime now() {
		return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
	}

	void setSourceStoryId(UUID sourceStoryId) {
		this.sourceStoryId = sourceStoryId;
	}

	UUID getId() {
		return id;
	}

	UUID getOrganizationId() {
		return organizationId;
	}

	UUID getProjectId() {
		return projectId;
	}

	String getTitle() {
		return title;
	}

	void setTitle(String title) {
		this.title = title;
	}

	String getDescription() {
		return description;
	}

	void setDescription(String description) {
		this.description = description;
	}

	TaskStatus getStatus() {
		return status;
	}

	void setStatus(TaskStatus status) {
		this.status = status;
	}

	TaskPriority getPriority() {
		return priority;
	}

	void setPriority(TaskPriority priority) {
		this.priority = priority;
	}

	UUID getAssigneeId() {
		return assigneeId;
	}

	void setAssigneeId(UUID assigneeId) {
		this.assigneeId = assigneeId;
	}

	LocalDate getDueDate() {
		return dueDate;
	}

	void setDueDate(LocalDate dueDate) {
		this.dueDate = dueDate;
	}

	UUID getResponsibleJobRoleId() {
		return responsibleJobRoleId;
	}

	void setResponsibleJobRoleId(UUID responsibleJobRoleId) {
		this.responsibleJobRoleId = responsibleJobRoleId;
	}

	UUID getCreatedBy() {
		return createdBy;
	}

	OffsetDateTime getCreatedAt() {
		return createdAt;
	}

	OffsetDateTime getUpdatedAt() {
		return updatedAt;
	}

	Long getVersion() {
		return version;
	}

}
