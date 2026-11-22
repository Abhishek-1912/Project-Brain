package com.projectbrain.project;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.projectbrain.project.api.ProjectStatus;
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
@Table(name = "projects")
public class Project {

	@Id
	private UUID id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private UUID organizationId;

	@Column(nullable = false)
	private String name;

	private String description;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private ProjectStatus status;

	@Column(name = "created_by", nullable = false, updatable = false)
	private UUID createdBy;

	@Column(name = "created_at", nullable = false, updatable = false)
	private OffsetDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private OffsetDateTime updatedAt;

	// A wrapper type, so Spring Data treats a new entity with an assigned ID as an insert.
	@Version
	private Long version;

	protected Project() {
	}

	Project(UUID id, UUID organizationId, String name, String description, UUID createdBy) {
		this.id = id;
		this.organizationId = organizationId;
		this.name = name;
		this.description = description;
		this.status = ProjectStatus.ACTIVE;
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

	UUID getId() {
		return id;
	}

	UUID getOrganizationId() {
		return organizationId;
	}

	String getName() {
		return name;
	}

	void setName(String name) {
		this.name = name;
	}

	String getDescription() {
		return description;
	}

	void setDescription(String description) {
		this.description = description;
	}

	ProjectStatus getStatus() {
		return status;
	}

	void setStatus(ProjectStatus status) {
		this.status = status;
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
