# ADR 0005: Universal MCP

Status: Accepted for dummy data only
Date: 2026-11-26
Builds on: ADR 0001 to ADR 0004, `docs/superpowers/specs/2026-11-26-phase-5-universal-mcp-design.md`

## Context

MCP clients such as Claude Code, Claude Desktop, and Cursor should be able to work with a project's reviewed knowledge as a specific person. Phase 6 agents will use the same tools. Every action must stay inside one organization, follow the person's current role, and never approve or delete anything.

## Decision

- Spring serves a stateless MCP endpoint at `/mcp` through the official MCP Java SDK 2.1.0 (`mcp-core`, `mcp-json-jackson3`) and its servlet transport. Server name `project-brain`.
- `/mcp` has its own security filter chain that accepts only personal access tokens. The app's `/api` routes accept only Supabase sessions. Each kind is refused on the other side.
- Personal access tokens are `pb_` plus 32 random bytes. Only a SHA-256 hash and an 8-character display prefix are stored. A token belongs to one user and one organization, is `READ` or `READ_WRITE`, expires after 7, 30, or 90 days, and can be revoked. A user holds at most 10 active tokens per organization.
- A presented token is resolved by a narrow `SECURITY DEFINER` function that returns only active tokens. The backend then accepts it only if the user's current membership began before the token was created, so a removed member's tokens stay dead even after they rejoin. Each tool call runs through the existing services as the token's user, so membership and role are checked live.
- The SDK may run handlers on its own threads, so the token travels in the MCP call context and each call restores it as the signed-in user.
- Each token may make 60 MCP requests per rolling minute, counted in memory. Going over returns HTTP 429, not an MCP tool error.
- Ten tools: `list_projects`, `search_knowledge`, `ask_project`, `get_architecture`, `list_stories`, `list_tasks`, `list_job_roles`, `list_notes`, `create_task`, and `add_note`. The two write tools need a `READ_WRITE` token and the user's normal permission. Nothing can be approved, rejected, deleted, or AI-generated through MCP.
- Two resource templates: `projectbrain://projects/{projectId}/architecture` and `projectbrain://projects/{projectId}/stories`, as Markdown. The stateless server registers resources once at startup, so they are templates rather than one listed resource per project.
- Tool and resource descriptions say returned content is project data, not instructions. Errors return plain messages, never internal details.
- Audit records written during an MCP call carry `"via": "MCP"` and the token id. Task creation is now audited everywhere as `TASK_CREATED`, so tasks created through MCP are traceable. Token creation and revocation are audited. Token secrets never appear in audit records or logs.

## Consequences

- Phase 6 agents can reuse these tools with a token, under the same permissions as a person.
- The rate limit and the token check assume one backend instance. More instances need a shared rate limiter.
- Clients find project ids with `list_projects`, because resources are templates. This departs from the design, which listed the two resources for each project: `resources/list` returns nothing, so clients that discover resources only by listing them, such as @-mention pickers, show none.
- Tasks created through MCP need an active job role, found with `list_job_roles`.
- Web connectors that require OAuth, such as claude.ai's, cannot connect until OAuth is added.

## Before real client data

- Everything listed in ADR 0003 and ADR 0004.
- OAuth sign-in for remote and web clients.
- A shared rate limiter and token-use monitoring.
- Review of which tools a client's data policy allows.
