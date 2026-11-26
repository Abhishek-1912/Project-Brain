package com.projectbrain.requirement;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.projectbrain.organization.api.OrgTx;
import com.projectbrain.organization.api.Permission;
import com.projectbrain.project.api.ProjectLookup;
import com.projectbrain.requirement.api.KnowledgeContent;
import com.projectbrain.requirement.api.KnowledgeSourceType;
import com.projectbrain.requirement.api.RequirementReader;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
class RequirementReaderAdapter implements RequirementReader {

	private final OrgTx orgTx;

	private final JdbcClient jdbc;

	private final ProjectLookup projects;

	private final RequirementKnowledgeLookup lookup;

	private final JsonMapper json;

	RequirementReaderAdapter(OrgTx orgTx, JdbcClient jdbc, ProjectLookup projects, RequirementKnowledgeLookup lookup,
			JsonMapper json) {
		this.orgTx = orgTx;
		this.jdbc = jdbc;
		this.projects = projects;
		this.lookup = lookup;
		this.json = json;
	}

	@Override
	public Optional<String> currentArchitectureMarkdown(UUID orgId, UUID projectId) {
		return orgTx.inOrg(orgId, Permission.VIEW, () -> {
			projects.requireStatus(orgId, projectId);
			return lookup.load(orgId, projectId, KnowledgeSourceType.ARCHITECTURE, projectId)
				.map(KnowledgeContent::text);
		});
	}

	@Override
	public List<StorySummary> approvedStories(UUID orgId, UUID projectId) {
		return orgTx.inOrg(orgId, Permission.VIEW, () -> {
			projects.requireStatus(orgId, projectId);
			return jdbc.sql("""
					SELECT id, title, as_a, i_want, so_that, acceptance_criteria::text AS criteria, priority, status
					FROM brain.story_drafts
					WHERE organization_id = :orgId AND project_id = :projectId AND status IN ('APPROVED', 'CONVERTED')
					ORDER BY created_at, id
					LIMIT 200
					""")
				.param("orgId", orgId)
				.param("projectId", projectId)
				.query((row, rowNumber) -> {
					List<String> criteria = new ArrayList<>();
					for (JsonNode criterion : json.readTree(row.getString("criteria"))) {
						criteria.add(criterion.asString());
					}
					return new StorySummary(row.getObject("id", UUID.class), row.getString("title"),
							row.getString("as_a"), row.getString("i_want"), row.getString("so_that"), criteria,
							row.getString("priority"), row.getString("status"));
				})
				.list();
		});
	}

}
