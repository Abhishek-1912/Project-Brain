package com.projectbrain.mcp;

import java.util.Map;
import java.util.UUID;

import com.projectbrain.knowledge.api.NoteWriter;
import com.projectbrain.shared.web.ForbiddenException;
import com.projectbrain.task.api.TaskWriter;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The only two changes MCP can make. Both need a read-and-write token and the user's normal permissions. */
@Configuration
class WriteTools {

	private static final String READ_ONLY = "This token is read-only. Create a read-and-write token to make changes.";

	private final ReadTools tools;

	WriteTools(ReadTools tools) {
		this.tools = tools;
	}

	@Bean
	SyncToolSpecification createTaskTool(TaskWriter tasks) {
		return tools.tool("create_task",
				"Create a task in a project's Backlog, as you. Needs a read-and-write token and permission to edit tasks.",
				"""
						{"type":"object","properties":{
						  "projectId":{"type":"string","description":"Project id from list_projects"},
						  "title":{"type":"string","description":"1 to 200 characters"},
						  "description":{"type":"string","description":"Up to 10,000 characters"},
						  "priority":{"type":"string","enum":["LOW","MEDIUM","HIGH","URGENT"]},
						  "jobRole":{"type":"string","description":"Responsible job role name from list_job_roles"}},
						 "required":["projectId","title","jobRole"]}""",
				(token, args) -> {
					if (!token.canWrite()) {
						throw new ForbiddenException(READ_ONLY);
					}
					UUID projectId = McpCalls.projectId(args);
					UUID id = tasks.create(token.orgId(), projectId, McpCalls.text(args, "title", true),
							McpCalls.text(args, "description", false), McpCalls.text(args, "priority", false),
							McpCalls.text(args, "jobRole", true));
					return Map.of("id", id, "path", "/orgs/" + token.orgId() + "/projects/" + projectId);
				});
	}

	@Bean
	SyncToolSpecification addNoteTool(NoteWriter notes) {
		return tools.tool("add_note",
				"Add a knowledge note to a project, as you. It becomes searchable. Needs a read-and-write token and an Owner or Admin role.",
				"""
						{"type":"object","properties":{
						  "projectId":{"type":"string","description":"Project id from list_projects"},
						  "title":{"type":"string","description":"1 to 200 characters"},
						  "body":{"type":"string","description":"Plain text, 1 to 20,000 characters"}},
						 "required":["projectId","title","body"]}""",
				(token, args) -> {
					if (!token.canWrite()) {
						throw new ForbiddenException(READ_ONLY);
					}
					UUID projectId = McpCalls.projectId(args);
					UUID id = notes.create(token.orgId(), projectId, McpCalls.text(args, "title", true),
							McpCalls.text(args, "body", true));
					return Map.of("id", id,
							"path", "/orgs/" + token.orgId() + "/projects/" + projectId + "/knowledge#note-" + id);
				});
	}

}
