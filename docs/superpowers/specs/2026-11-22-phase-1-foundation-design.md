# Project Brain Phase 1: Foundation Design

Status: Approved by the developer on 2026-11-22
Date: 2026-11-22
Source: PRD Addendum v1.1 and the solo developer roadmap

## 1. Intent

Build the secure multi-tenant base of Project Brain and use it to manage Project Brain's own development.

**Phase 1 is done when the developer can log in, create an organization, add "Project Brain" as its first project, and manage real development tasks there, with data isolated between organizations.**

## 2. Goals, non-goals, anti-goals

Goals:

- Email and password login through Supabase Auth.
- Organizations, memberships and role enforcement.
- Project and task management.
- Database-level tenant isolation.
- Audit records for sensitive actions.
- An API contract, automated tests and documentation that let the two Claude Code tabs work independently.

Non-goals for Phase 1:

- AI features, RAG, MCP, agents, GitHub and n8n.
- Client portal, CRM, proposals, contracts and billing.
- Subscription plans and usage limits.
- Member invitations and member management UI.
- Kanban drag and drop, comments, attachments and notifications.
- Production deployment and private deployment.

Anti-goals, which count as failure even if the app works:

- One organization can read or change another organization's data.
- React holds a privileged key or reads business tables directly.
- Schema changes are made by hand in the Supabase dashboard.
- The core design depends on Supabase in a way that blocks private deployment.

## 3. Constraints

- Solo developer with AI agents, and a limited Claude Code token budget.
- Java 17, Maven, Spring Boot, React, TypeScript, Tailwind CSS, Supabase PostgreSQL.
- Modular monolith. No microservices for business modules.
- Secrets stay in untracked `.env` files.
- The Supabase dashboard is used to inspect data, not to change schema.

## 4. Approaches considered

| Approach | Trade-off | Result |
|---|---|---|
| Supabase Auth, with Spring Boot owning permissions and RLS as a second lock | Least security code, small auth dependency | **Chosen by the developer** |
| Spring Security owns all auth | Most portable, about a week more work, more sensitive code to maintain | Rejected |
| React talks directly to Supabase | Fastest start, splits business rules and weakens audit control | Rejected |

## 5. Repository layout

```text
Project-Brain/
├── backend/      Spring Boot modular monolith
├── frontend/     React + TypeScript + Tailwind
├── docs/
│   ├── api/openapi.yaml     API contract, the only link between tabs
│   ├── decisions/           Short ADRs
│   ├── status.md            Current task and next step
│   └── superpowers/         Specs and plans
├── .env.example
└── .gitignore
```

`ai-service/` is added in Phase 2.

Backend modules live under one root package: `identity`, `organization`, `project`, `task`, `audit` and `shared`. A module calls another only through its public service interface. An automated architecture test enforces this.

## 6. Authentication and request flow

1. React signs in with `supabase-js` using only the publishable (anon) key.
2. React sends the access token to Spring Boot as a Bearer token.
3. Spring Boot verifies signature (through Supabase JWKS), expiry, issuer and the `authenticated` audience.
4. On first request, Spring Boot creates the user's `profiles` row from the token's `sub` and `email`.
5. For organization routes, Spring Boot loads the membership. A non-member gets `404`, so the API does not reveal that the organization exists.
6. Spring Boot checks the role, then runs the work in a transaction with the tenant context set.

Auth sits behind an internal `CurrentUser` abstraction, so another token issuer can replace Supabase without changing business modules.

## 7. Tenant isolation

Business tables live in a dedicated `brain` schema, which is **not exposed** through the Supabase Data API.

Two database roles:

- `brain_migrator` owns the schema and is used only by Flyway.
- `brain_app` is used at runtime. It does not own tables and has no `BYPASSRLS`.

Every business transaction starts with:

```sql
SELECT set_config('app.current_user_id', :userId, true),
       set_config('app.current_org_id', :orgId, true);
```

The `true` argument limits the values to the transaction, so they cannot leak through a pooled connection. Use the Supabase transaction pooler or a direct connection. With the transaction pooler, set `prepareThreshold=0` in the JDBC URL, because server-side prepared statements are not safe across pooled transactions. All tenant tables use `ENABLE` and `FORCE ROW LEVEL SECURITY`. Because `FORCE` also applies to the table owner, any Flyway migration that changes existing tenant rows must set its context or explicitly handle RLS.

Policies read context through a helper that treats an empty value as `NULL`, for example `NULLIF(current_setting('app.current_org_id', true), '')::uuid`. A reused connection can hold an empty string after a transaction ends, and casting that directly to `uuid` would raise an error. Missing context must match no rows.

Policies:

- `projects`, `tasks`, `audit_events`: row `organization_id` must equal `app.current_org_id`.
- `organization_members`: visible if `user_id` is the current user or `organization_id` is the current organization.
- `organizations`: visible if `id` is the current organization or the current user is a member.
- `profiles`: visible for the current user or a user sharing the current organization.

Creating an organization, in one transaction:

1. Generate the organization UUID in the application.
2. Set `app.current_org_id` to that UUID, so the insert's returned row passes the select policy.
3. Insert the organization with `created_by` equal to the current user.
4. Insert the creator's `OWNER` membership. The policy allows this only when the current user created that organization.
5. Write the audit event.

## 8. Data model

All IDs are UUIDs. All timestamps are `timestamptz`. Mutable tables have `created_at`, `updated_at` and a `version` column for optimistic locking.

- `profiles`: `id` (Supabase user ID, no foreign key to `auth.users`, for portability), `email`, `display_name`.
- `organizations`: `id`, `name`, `slug` (unique), `created_by`.
- `organization_members`: `organization_id`, `user_id`, `role`. Primary key is both IDs.
- `projects`: `id`, `organization_id`, `name`, `description`, `status` (`ACTIVE`, `ARCHIVED`), `created_by`. Name is unique per organization.
- `tasks`: `id`, `organization_id`, `project_id`, `title`, `description`, `status`, `priority`, `assignee_id`, `due_date`, `created_by`.
- `audit_events`: `id`, `organization_id`, `actor_id`, `action`, `entity_type`, `entity_id`, `metadata` (jsonb), `created_at`. `brain_app` has only `INSERT` and `SELECT`.

Task status: `BACKLOG`, `TODO`, `IN_PROGRESS`, `IN_REVIEW`, `DONE`.
Task priority: `LOW`, `MEDIUM`, `HIGH`, `URGENT`.

`projects` has a unique constraint on `(organization_id, id)`. `tasks` uses a composite foreign key `(organization_id, project_id)` to it, so a task cannot point to another organization's project. `tasks` also uses a composite foreign key `(organization_id, assignee_id)` to `organization_members (organization_id, user_id)`, so the database rejects an assignee from outside the organization. Removing a member is out of scope for Phase 1, and its assignee handling is decided when member management is built.

## 9. Roles

| Action | OWNER | ADMIN | MEMBER | VIEWER |
|---|---|---|---|---|
| View organization, projects, tasks | Yes | Yes | Yes | Yes |
| Create and edit tasks | Yes | Yes | Yes | No |
| Create, edit, archive projects | Yes | Yes | No | No |
| View audit events | Yes | Yes | No | No |
| Edit organization | Yes | No | No | No |

Phase 1 has no invitation flow. Non-owner roles are enforced and covered by tests using seeded members.

## 10. API

Base path: `/api/v1`. Errors use RFC 9457 Problem Details. Lists are paginated.

```text
GET    /me
GET    /organizations
POST   /organizations
GET    /organizations/{orgId}
PATCH  /organizations/{orgId}
GET    /organizations/{orgId}/members
GET    /organizations/{orgId}/projects
POST   /organizations/{orgId}/projects
GET    /organizations/{orgId}/projects/{projectId}
PATCH  /organizations/{orgId}/projects/{projectId}
GET    /organizations/{orgId}/projects/{projectId}/tasks?status=&assigneeId=
POST   /organizations/{orgId}/projects/{projectId}/tasks
GET    /organizations/{orgId}/tasks/{taskId}
PATCH  /organizations/{orgId}/tasks/{taskId}
DELETE /organizations/{orgId}/tasks/{taskId}
GET    /organizations/{orgId}/audit-events
```

`PATCH` requests include `version`. A stale version returns `409`. Projects are archived through `PATCH`, not deleted.

Audited actions: organization created or updated, project created, updated or archived, task deleted.

## 11. Frontend

Stack: Vite, React, TypeScript, Tailwind CSS, React Router, TanStack Query, and API types generated from `docs/api/openapi.yaml`.

Screens:

- Login, sign up and password reset.
- Create first organization.
- Project list with create and archive.
- Project task list with status and assignee filters.
- Task create and edit panel.
- Audit log for owners and admins.

Every data screen handles loading, empty and error states. Controls the user's role cannot use are hidden, while the backend remains the enforcement point.

## 12. Testing

- Backend integration tests run against real PostgreSQL through Testcontainers, which requires Docker.
- Isolation tests use two organizations and cover read, update, delete and direct SQL as `brain_app`.
- Role tests cover the table in section 9.
- Unit tests cover task and project rules.
- A Postman collection covers the main API flow.
- Frontend tests cover auth guarding and the task form. One manual end-to-end pass confirms the done condition.

## 13. Token-saving workflow

- Backend tab opens in `backend/`. Frontend tab opens in `frontend/`.
- Only one tab is active at a time. Each API is finished before its screen.
- `docs/api/openapi.yaml` and `docs/status.md` carry context between tabs.
- Use `/clear` after each task.
- The developer runs installs, scaffolding, Postman and repetitive paste tasks, and shares only relevant error lines.

## 14. Prerequisites for the developer

1. Install Docker Desktop.
2. Create a Supabase development project.
3. Enable email login in Supabase Auth.
4. Copy the project URL and publishable key into local `.env` files. Do not paste them into chat.

Python 3.12 is needed from Phase 2, not Phase 1.

## 15. Acceptance criteria

1. A new user can sign up, log in and reset a password.
2. The user can create an organization and the "Project Brain" project.
3. The user can create, edit, filter, complete and delete tasks.
4. Automated tests prove one organization cannot access another's data through the API or as `brain_app` in SQL.
5. Role rules in section 9 pass automated tests.
6. Sensitive actions create audit events.
7. No privileged key appears in frontend code or Git history.
8. A clean database can be rebuilt from Flyway migrations alone.
9. `docs/api/openapi.yaml` matches the implemented API.
10. Project Brain's remaining Phase 1 and Phase 2 work is tracked inside the app.

## 16. Calls made for the developer to check

- Member invitations are deferred to keep Phase 1 small.
- Tasks use a filtered list instead of a Kanban board.
- Task deletion is a hard delete with an audit record.
- Four roles: Owner, Admin, Member, Viewer.
- JPA is used for persistence, with Flyway SQL for schema and RLS.
- AI usage tracking starts in Phase 2, when the first AI call exists.
