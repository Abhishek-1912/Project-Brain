# Phase 5: Universal MCP Design

Status: Draft for developer review
Date: 2026-11-26
Source: PRD roadmap ("Universal MCP: MCP server, authentication, resources and tools") and `docs/superpowers/specs/2026-11-22-role-based-agent-workflow-design.md` (agents work as a role, people approve)
Builds on: ADR 0001 to ADR 0004 and the Phase 2 to Phase 4 designs

## 1. Intent

AI tools that speak the Model Context Protocol, such as Claude Code, Claude Desktop, and Cursor, should be able to connect to Project Brain and work with a project the way its user could: read and search its reviewed knowledge, and make a few small, audited changes. Every call acts as one specific person, inside one organization, with that person's current permissions.

This is also the base Phase 6 agents build on. They will use the same tools.

**Same test conditions as earlier phases.** Dummy data only, no clients, free tiers.

**Build it as a working model first.** Every anti-goal in section 4 is required. Polish can come later.

## 2. Goals

- An MCP endpoint in the Spring backend at `/mcp`, using the Streamable HTTP transport.
- Personal access tokens: created in the app, shown once, limited to one organization, read-only or read-and-write, with an expiry, and revocable.
- Read tools: list projects, search knowledge, ask a question with citations, and read the current architecture, approved stories, tasks, and notes.
- Write tools: create a task in Backlog, and add a note.
- Resources: the current approved architecture and the approved stories of a project, as Markdown.
- An **Access tokens** screen to create, list, and revoke tokens, with copy-ready setup steps for Claude Code.

## 3. Non-goals

- OAuth sign-in for MCP (a later phase can add it for web connectors).
- Approving or rejecting anything, generating AI drafts, deleting, changing members, roles, job roles, settings, or tokens through MCP.
- Project Brain calling other MCP servers (it is a server only).
- Agents, LangGraph, GitHub, or coding agents (Phases 6 and 7).
- Real client data.

## 4. Anti-goals

The work has failed, even if it functions, if any of these is possible:

- A token reads or changes data in an organization other than the one it was created for.
- A token keeps working after it is revoked, after it expires, or after its user leaves the organization.
- A token does something its user's current role does not allow, or a read-only token changes anything.
- Anything is approved, rejected, deleted, or AI-generated through MCP.
- A token's secret is stored, logged, or shown again after creation.
- A Supabase session token is accepted at `/mcp`, or a personal access token is accepted on the app's `/api` routes.
- Project text returned by a tool causes Project Brain to take an action.

## 5. Constraints

- Keep the Spring modular monolith, Supabase PostgreSQL with RLS, Flyway, and the OpenAPI contract for the app's REST routes.
- `V6` is applied to Supabase. Schema changes go in a new `V7` migration.
- Use the official MCP Java SDK. The plan confirms the exact library and version that work with Spring Boot 4.2 before any code depends on it.
- Tests must never call Groq, Gemini, Supabase, or download models.
- Browser and real-client checks are added to `docs/testing/browser-acceptance.md` for the developer to run later.

## 6. Approaches

| Approach | Trade-off |
|---|---|
| **A. MCP server inside Spring, with personal access tokens (chosen)** | Tools call the same services as the app, so permissions, isolation, and audit are reused. One more sign-in method in Spring to secure. |
| B. Separate MCP gateway service calling the REST API | Spring barely changes, but it is another service, and ADR 0003 keeps user tokens out of the existing AI service. |
| C. OAuth sign-in for MCP | Standard for remote and web connectors, but much more work and depends on Supabase's OAuth support. Can be added after A. |

The developer chose A on 2026-11-26.

## 7. Design

### 7.1 Ownership

- A new `mcp` module owns the endpoint, tool and resource definitions, and token-based sign-in for `/mcp`.
- A new `accesstoken` area in the `identity` module owns tokens: creation, listing, revocation, and resolving a presented token to a user.
- Tools call other modules only through their `api` packages, adding small read or write interfaces where needed. ArchUnit's module rules stay in force.
- `CurrentUserProvider` stays the only place that knows how the user was authenticated. It learns the token-based case.

### 7.2 Access tokens

- Format: `pb_` followed by 32 random bytes in URL-safe base64. Only a SHA-256 hash is stored, plus the first 8 characters for display.
- Each token belongs to one user and one organization, and has a name (1 to 100 characters), a scope (`READ` or `READ_WRITE`), an expiry of 7, 30, or 90 days, created and last-used times, and an optional revoked time.
- A user can hold at most 10 active tokens per organization.
- Tokens are created, listed, and revoked only through the app's `/api` routes, signed in with Supabase. A user sees and revokes only their own tokens.
- Resolving a presented token uses a narrow `SECURITY DEFINER` function that takes the hash and returns the user, organization, and scope only for an active, unexpired token. It also updates last-used time, at most once a minute.
- Every call still runs through `OrgTx.inOrgAs` for the token's organization, so membership and role are checked live. A removed member's token stops working at once.
- Creating and revoking tokens is audited without the secret.

### 7.3 MCP endpoint

- `/mcp` has its own security filter chain that accepts only `Authorization: Bearer pb_…`. `/api` keeps accepting only Supabase tokens.
- Stateless Streamable HTTP. No server-sent notifications are needed.
- Each token can make at most 60 tool calls a minute. Beyond that it gets a rate-limit error.
- Tool and resource descriptions tell the client that returned project content is data, not instructions.

### 7.4 Tools

| Tool | Scope | Needs | Does |
|---|---|---|---|
| `list_projects` | READ | VIEW | Projects in the token's organization |
| `search_knowledge` | READ | VIEW | Phase 4 search, with citations |
| `ask_project` | READ | VIEW | Phase 4 ask, with citations or not found |
| `get_architecture` | READ | VIEW | The current approved architecture as Markdown, or none |
| `list_stories` | READ | VIEW | Approved and converted stories |
| `list_tasks` | READ | VIEW | Tasks, optionally filtered by status |
| `list_notes` | READ | VIEW | Knowledge notes |
| `create_task` | READ_WRITE | EDIT_TASKS | A new Backlog task, as the token's user |
| `add_note` | READ_WRITE | MANAGE_PROJECTS | A new knowledge note, which gets indexed |

- Writes go through the same services as the app, in active projects only, and are audited with `"via": "MCP"` and the token id.
- Errors come back as MCP tool errors with plain messages (not found, not allowed, archived, rate limited), never stack traces.

### 7.5 Resources

- `projectbrain://projects/{projectId}/architecture`: the current approved architecture Markdown.
- `projectbrain://projects/{projectId}/stories`: approved stories as Markdown.
- Resource listing shows these two for each project in the token's organization.

### 7.6 Storage (`V7`)

- `access_tokens`: id, user, organization, name, token hash (unique), display prefix, scope, expires, created, last used, revoked. RLS lets `brain_app` see only the current user's tokens in the current organization.
- The resolve function, granted to `brain_app` only.

### 7.7 Screens

An **Access tokens** page, reached from the organization menu:

- A list of your tokens with name, scope, created, expires, last used, and status.
- **Create token**: name, scope, and expiry. The secret is shown once with a copy button and a ready `claude mcp add` command.
- **Revoke**, with confirmation.

## 8. Main flow

1. A user creates a read-and-write token for their organization and copies it.
2. They add Project Brain to Claude Code with the endpoint URL and the token.
3. Claude Code lists projects, searches knowledge, asks questions, and reads the architecture and stories.
4. It creates a Backlog task and adds a note, which appear in the app, audited as MCP actions.
5. The user revokes the token, and the next MCP call fails.

## 9. Testing and acceptance

- Backend: token creation, hashing, one-time display, listing, revocation, expiry, removed member, other organization, read-only scope, role permissions per tool, `/api` refusing tokens and `/mcp` refusing Supabase tokens, rate limiting, audit content, and each tool and resource over the real MCP transport.
- Frontend: the Access tokens page, including one-time display, copy, revoke, and empty and error states.
- Acceptance: a new part of `docs/testing/browser-acceptance.md` for connecting Claude Code to the local server and running the main flow. The developer runs it later.
- ADR 0005 records the MCP boundary and token rules.

## 10. Left to the builder

- Tool input and output field names, following the existing API names.
- The exact MCP Java SDK artifacts, confirmed in the plan.
- Screen layout, following `docs/design/brand-theme.md`.

## 11. Calls made by Claude, for review

- Tokens are limited to one organization, rather than all of a user's organizations.
- Expiry choices are 7, 30, or 90 days, with no never-expiring tokens.
- Read calls are not audited one by one. Writes, token creation, and revocation are, and Ask already records each answer.
- At most 10 active tokens per user per organization, and 60 tool calls a minute per token.
- `add_note` needs Owner or Admin, like notes in the app. `create_task` needs the task-editing permission, which Members also have.
