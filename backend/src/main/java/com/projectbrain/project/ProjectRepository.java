package com.projectbrain.project;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.projectbrain.project.api.ProjectStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectRepository extends JpaRepository<Project, UUID> {

	Optional<Project> findByOrganizationIdAndId(UUID organizationId, UUID id);

	long countByOrganizationId(UUID organizationId);

	@Query("select p from Project p where p.organizationId = :orgId order by lower(p.name), p.id")
	List<Project> findPage(@Param("orgId") UUID orgId, Pageable pageable);

	@Query("select p.status from Project p where p.organizationId = :orgId and p.id = :projectId")
	Optional<ProjectStatus> findStatus(@Param("orgId") UUID orgId, @Param("projectId") UUID projectId);

}
