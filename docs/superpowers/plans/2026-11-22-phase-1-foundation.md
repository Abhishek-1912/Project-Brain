# Phase 1 Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the secure multi-tenant foundation and use it to manage Project Brain's own development.

**Architecture:** React authenticates with Supabase Auth and calls only the Spring Boot API. Spring Boot verifies the token, enforces organization roles, and runs each request in a transaction with PostgreSQL RLS context. `docs/api/openapi.yaml` is the only link between the backend and frontend tabs.

**Tech Stack:** Java 17, Spring Boot (current stable from Spring Initializr), Maven, Spring Security Resource Server, JPA, Flyway, Testcontainers, ArchUnit, PostgreSQL, Supabase Auth, React, TypeScript, Vite, Tailwind CSS, React Router, TanStack Query, openapi-typescript, openapi-fetch, Vitest.

**Spec:** `docs/superpowers/specs/2026-11-22-phase-1-foundation-design.md`

## Global Constraints

- Root Java package: `com.projectbrain`. Modules: `identity`, `organization`, `project`, `task`, `audit`, `shared`.
- A module may use another module only through its `api` package, or `shared`.
- Business tables live in schema `brain`, never `public`.
- Runtime role `brain_app`: no table ownership, no `BYPASSRLS`. Migration role: `brain_migrator`.
- Tenant tables use `ENABLE` and `FORCE ROW LEVEL SECURITY`.
- Tenant context uses `set_config(..., true)` only. Missing context matches no rows.
- Non-member access to an organization returns `404`. A member without permission gets `403`.
- Errors use RFC 9457 Problem Details. Stale `version` returns `409`.
- Lists use `page` (default 0), `size` (default 20, max 100), and return `{items, page, size, totalItems}`.
- Field limits: organization and project name 1-120, task title 1-200, description max 10000, all trimmed. Slug: 3-63 characters matching `^[a-z0-9]+(-[a-z0-9]+)*$`.
- React uses only the Supabase publishable key. Secrets stay in untracked `.env` files.
- Schema changes happen only through Flyway.

## Review Focus

1. Two tabs edit the same task: the second save gets `409`, and the UI asks the user to reload without losing their typed text.
2. A login expires mid-session: the client refreshes once, then redirects to login on a second `401`.
3. A valid ID from organization B is used in an organization A URL: the API returns `404` and changes nothing.
4. A project name differs only by case or spaces: the API returns `409`, not `500`.
5. An assignee outside the organization is submitted: the API returns `422`, not a database error.

## How to run this plan with low token use

- **[YOU]** tasks are run by you. Paste only failing output.
- **[BACKEND]** tasks run in a Claude Code tab opened at `backend/`.
- **[FRONTEND]** tasks run in a Claude Code tab opened at `frontend/`.
- Use one active tab at a time, and run `/clear` after every task.
- Start each task with: `Read ../docs/status.md and only Task N of ../docs/superpowers/plans/2026-11-22-phase-1-foundation.md. Implement it with TDD.`
- At the end of each task, update `docs/status.md` with the finished task, the next task and any blocker.
- You review and commit each task.

## File Structure

```text
backend/
  db/bootstrap/create_roles.sql
  src/main/java/com/projectbrain/
    shared/        errors, paging, security config
    identity/      api/CurrentUser, api/CurrentUserProvider, profiles
    audit/         api/AuditLog, audit events
    organization/  api/OrgTx, api/Role, api/Permission, organizations, members
    project/       api/ProjectLookup, projects
    task/          tasks
  src/main/resources/db/migration/V1__schema.sql, V2__rls.sql
frontend/src/
  api/       client.ts, schema.d.ts (generated)
  auth/      supabase.ts, AuthProvider.tsx, RequireAuth.tsx, auth pages
  features/  organizations/, projects/, tasks/, audit/
  components/ shared states and layout
docs/api/openapi.yaml
docs/status.md
```

---

### Task 0: Prepare the machine and repository [YOU]

**Files:** Create `.gitignore`, `.env.example`, `docs/status.md`

- [ ] **Step 1:** Install Docker Desktop, open it, and confirm `docker info` prints a server version.
- [ ] **Step 2:** Create a Supabase development project and save its database password in your password manager.
- [ ] **Step 3:** In Supabase Auth, enable email login. Add `http://localhost:5173/**` to the allowed redirect URLs.
- [ ] **Step 4:** In Supabase JWT settings, confirm the current signing key is asymmetric (ECC or RSA). Token verification depends on this.
- [ ] **Step 5:** Create `.gitignore` containing:

```gitignore
.env
.env.*
!.env.example
target/
node_modules/
dist/
.DS_Store
.claude/settings.local.json
```

- [ ] **Step 6:** Create `.env.example` with these names and empty values: `SUPABASE_URL`, `DB_APP_URL`, `DB_APP_USER`, `DB_APP_PASSWORD`, `DB_MIGRATOR_URL`, `DB_MIGRATOR_USER`, `DB_MIGRATOR_PASSWORD`, `APP_CORS_ALLOWED_ORIGINS`, `VITE_SUPABASE_URL`, `VITE_SUPABASE_PUBLISHABLE_KEY`, `VITE_API_BASE_URL`.
- [ ] **Step 7:** Create `docs/status.md` with `Current task: 1`, then run `git init -b main`, `git add .`, and commit.

Expected: `git status` is clean and `git check-ignore .env` prints `.env`.

### Task 1: Write the API contract [BACKEND]

**Files:** Create `docs/api/openapi.yaml`

**Interfaces:**
- Produces: all routes in spec section 10, schemas `Me`, `Organization`, `Member`, `Project`, `Task`, `AuditEvent`, `Problem`, and one paged schema per list.

- [ ] **Step 1:** Write the OpenAPI 3.1 contract using the Global Constraints. PATCH schemas contain optional fields plus required `version`. For task PATCH, an absent `assigneeId` or `dueDate` means unchanged, and explicit `null` clears it.
- [ ] **Step 2:** Run `npx @redocly/cli lint ../docs/api/openapi.yaml`. Expected: no errors.
- [ ] **Step 3:** Commit `docs/api/openapi.yaml`.

### Task 2: Scaffold backend and test harness [YOU, then BACKEND]

**Files:** Create `backend/`, `backend/src/test/java/com/projectbrain/support/PostgresTestBase.java`, `ArchitectureTest.java`, `ContractRoutesTest.java`

**Interfaces:**
- Produces: `PostgresTestBase`, which starts PostgreSQL, runs bootstrap and Flyway, and connects the app as `brain_app`.

- [ ] **Step 1 [YOU]:** Run from the repository root:

```bash
mkdir backend && curl -fsSL https://start.spring.io/starter.tgz -d type=maven-project -d language=java -d javaVersion=17 -d groupId=com.projectbrain -d artifactId=backend -d name=backend -d packageName=com.projectbrain -d dependencies=web,security,oauth2-resource-server,data-jpa,flyway,postgresql,validation,actuator,testcontainers | tar -xzf - -C backend
```

- [ ] **Step 2 [BACKEND]:** Add ArchUnit and SnakeYAML test dependencies, and the PostgreSQL Flyway module if Initializr did not include it.
- [ ] **Step 3:** Write `ArchitectureTest.modulesOnlyUseOtherModulesApi` and `ContractRoutesTest.controllerRoutesMatchOpenApi`. Keep the route test `@Disabled("Enabled in Task 8")`.
- [ ] **Step 4:** Configure separate datasource settings from `DB_APP_*` and Flyway settings from `DB_MIGRATOR_*`, with Flyway default schema `brain` and `spring.jpa.hibernate.ddl-auto=validate`.
- [ ] **Step 5:** Run `./mvnw -q test -Dtest=ArchitectureTest`. Expected: PASS.
- [ ] **Step 6:** Commit.

### Task 3: Schema, database roles and RLS [BACKEND]

**Files:** Create `backend/db/bootstrap/create_roles.sql`, `V1__schema.sql`, `V2__rls.sql`, test `RlsIsolationTest.java`

**Interfaces:**
- Produces: tables in spec section 8, helpers `brain.current_user_id()` and `brain.current_org_id()`, both returning `uuid` or `NULL`.

- [ ] **Step 1:** Write failing `RlsIsolationTest` cases, connected as `brain_app`:

```java
noContextSeesNoTenantRows();          // count(projects, tasks, audit_events) == 0
orgASeesOnlyOrgAProjects();           // returned org IDs == {orgA}
orgACannotUpdateOrgBTask();           // rows updated == 0
orgACannotDeleteOrgBTask();           // rows deleted == 0
cannotInsertRowForAnotherOrg();       // throws SQLState 42501
emptyContextDoesNotRaiseCastError();  // count == 0 after a prior transaction
taskCannotReferenceOtherOrgProject(); // throws SQLState 23503
assigneeMustBelongToOrg();            // throws SQLState 23503
auditEventsCannotBeUpdatedOrDeleted();// throws SQLState 42501
contextDoesNotSurviveTransaction();   // next transaction sees 0 rows
```

- [ ] **Step 2:** Run `./mvnw -q test -Dtest=RlsIsolationTest`. Expected: FAIL because the schema is missing.
- [ ] **Step 3:** Write `create_roles.sql` using psql variables `:'migrator_password'` and `:'app_password'`. It creates both roles and schema `brain` owned by `brain_migrator`, with `USAGE` for `brain_app`.
- [ ] **Step 4:** Write `V1__schema.sql`, including enum `CHECK` constraints, unique `(organization_id, id)` on projects, unique `(organization_id, lower(name))`, both composite task foreign keys, and indexes for task filters.
- [ ] **Step 5:** Write `V2__rls.sql` with policies from spec section 7. Grants for `brain_app`: `profiles`, `organizations`, `projects` get SELECT/INSERT/UPDATE; `organization_members`, `audit_events` get SELECT/INSERT; `tasks` gets SELECT/INSERT/UPDATE/DELETE.
- [ ] **Step 6:** Run the test again. Expected: PASS.
- [ ] **Step 7:** Commit.

### Task 4: Token verification and `/me` [BACKEND]

**Files:** Create `shared/security/SecurityConfig.java`, `shared/web/ProblemHandler.java`, `identity/api/CurrentUser.java`, `identity/api/CurrentUserProvider.java`, `identity/ProfileService.java`, `identity/MeController.java`, test `MeApiTest.java`

**Interfaces:**
- Produces: `record CurrentUser(UUID id, String email)`, `CurrentUser CurrentUserProvider.get()`, `NotFoundException`, `ForbiddenException`, `ConflictException`, `UnprocessableException` in `shared.web`.

- [ ] **Step 1:** Write failing `MeApiTest` cases with locally signed test JWTs:

```java
missingTokenReturns401();
expiredTokenReturns401();
wrongIssuerReturns401();
wrongAudienceReturns401();
tokenWithoutEmailReturns401();
firstRequestCreatesProfile();     // profiles count == 1
secondRequestDoesNotDuplicate();  // profiles count == 1
corsAllowsConfiguredOriginOnly();
```

- [ ] **Step 2:** Run `./mvnw -q test -Dtest=MeApiTest`. Expected: FAIL.
- [ ] **Step 3:** Configure JWKS at `${SUPABASE_URL}/auth/v1/.well-known/jwks.json`, issuer `${SUPABASE_URL}/auth/v1`, audience `authenticated`, stateless sessions, and CORS from `APP_CORS_ALLOWED_ORIGINS`.
- [ ] **Step 4:** Implement profile upsert in a transaction with `app.current_user_id` set. Default `display_name` is the part of the email before `@`.
- [ ] **Step 5:** Run the test. Expected: PASS.
- [ ] **Step 6:** Commit.

### Task 5: Organizations, roles, tenant transaction and audit writer [BACKEND]

**Files:** Create `organization/api/{OrgTx,Role,Permission}.java`, `organization/{OrganizationController,OrganizationService}.java`, `audit/api/AuditLog.java`, `audit/AuditWriter.java`, tests `OrganizationApiTest.java`, `RolePermissionTest.java`

**Interfaces:**
- Consumes: `CurrentUserProvider`.
- Produces:

```java
enum Permission { VIEW, EDIT_TASKS, MANAGE_PROJECTS, VIEW_AUDIT, EDIT_ORGANIZATION }
enum Role { OWNER, ADMIN, MEMBER, VIEWER; boolean allows(Permission p); }
<T> T OrgTx.asUser(Supplier<T> work);
<T> T OrgTx.inOrg(UUID orgId, Permission required, Supplier<T> work);
void AuditLog.record(UUID orgId, String action, String entityType, UUID entityId, Map<String,Object> metadata);
```

- [ ] **Step 1:** Write failing tests:

```java
roleMatrixMatchesSpecSection9();
createOrganizationMakesCreatorOwnerAndAudits();
listReturnsOnlyMyOrganizations();
nonMemberGets404();
adminCannotEditOrganization();       // 403
stalePatchReturns409();
duplicateSlugReturns409();
invalidSlugReturns422();
failedAuditRollsBackOrganization();
```

- [ ] **Step 2:** Run `./mvnw -q test -Dtest='OrganizationApiTest,RolePermissionTest'`. Expected: FAIL.
- [ ] **Step 3:** Implement `OrgTx.inOrg` in this order: open transaction, set user context, load membership, throw 404 if absent, throw 403 if role lacks permission, set org context, run work.
- [ ] **Step 4:** Implement organization creation in the five-step order from spec section 7. `AuditLog` joins the current transaction.
- [ ] **Step 5:** Run the tests. Expected: PASS.
- [ ] **Step 6:** Commit.

### Task 6: Projects API [BACKEND]

**Files:** Create `project/api/ProjectLookup.java`, `project/{Project,ProjectRepository,ProjectService,ProjectController}.java`, test `ProjectApiTest.java`

**Interfaces:**
- Consumes: `OrgTx`, `AuditLog`.
- Produces: `ProjectStatus ProjectLookup.requireStatus(UUID orgId, UUID projectId)`, throwing 404 when absent.

- [ ] **Step 1:** Write failing tests:

```java
adminCreatesProjectAndAudits();
memberCannotCreateProject();          // 403
viewerCanList();
otherOrgProjectIdReturns404();
duplicateNameIgnoringCaseAndSpacesReturns409();
archiveThroughPatchAudits();
stalePatchReturns409();
paginationCapsSizeAt100();
```

- [ ] **Step 2:** Run `./mvnw -q test -Dtest=ProjectApiTest`. Expected: FAIL.
- [ ] **Step 3:** Implement the project routes. Translate unique violations to `ConflictException` and `OptimisticLockException` to `409`.
- [ ] **Step 4:** Run the test. Expected: PASS.
- [ ] **Step 5:** Commit.

### Task 7: Tasks API [BACKEND]

**Files:** Create `task/{Task,TaskRepository,TaskService,TaskController,TaskPatch}.java`, test `TaskApiTest.java`

**Interfaces:**
- Consumes: `OrgTx`, `AuditLog`, `ProjectLookup`.
- Produces: `record TaskPatch(Optional<String> title, Optional<String> description, Optional<TaskStatus> status, Optional<TaskPriority> priority, Optional<UUID> assigneeId, Optional<LocalDate> dueDate, long version)`. A Java `null` component means absent, and `Optional.empty()` means explicit JSON `null`.

- [ ] **Step 1:** Write failing tests:

```java
memberCreatesTask();
viewerCannotCreateTask();                // 403
filterByStatusAndAssignee();
otherOrgTaskIdReturns404AndChangesNothing();
outsideAssigneeReturns422();
absentAssigneeLeavesValue();
nullAssigneeClearsValue();
stalePatchReturns409();
writeToArchivedProjectReturns409();
deleteRemovesTaskAndAudits();
```

- [ ] **Step 2:** Run `./mvnw -q test -Dtest=TaskApiTest`. Expected: FAIL.
- [ ] **Step 3:** Implement task routes. Check assignee membership before saving and throw `UnprocessableException` if it fails.
- [ ] **Step 4:** Run the test. Expected: PASS.
- [ ] **Step 5:** Commit.

### Task 8: Members, audit list, and contract check [BACKEND, then YOU]

**Files:** Create `organization/MemberController.java`, `audit/AuditController.java`, `docs/api/postman/project-brain-phase-1.json`, test `MemberAuditApiTest.java`; modify `ContractRoutesTest.java`

- [ ] **Step 1:** Write failing tests: `viewerCanListMembers`, `memberCannotViewAudit`, `adminSeesOnlyOwnOrgAuditNewestFirst`.
- [ ] **Step 2:** Implement both read routes, and remove `@Disabled` from `ContractRoutesTest`.
- [ ] **Step 3:** Create the Postman collection covering: `/me`, create organization, create project, create, update and delete task, read audit.
- [ ] **Step 4:** Run `./mvnw -q verify`. Expected: BUILD SUCCESS.
- [ ] **Step 5 [YOU]:** Run the bootstrap with your role passwords and start the backend against Supabase:

```bash
psql "$SUPABASE_ADMIN_DB_URL" -v migrator_password="$DB_MIGRATOR_PASSWORD" -v app_password="$DB_APP_PASSWORD" -f backend/db/bootstrap/create_roles.sql
```

Use the Supabase session pooler for `DB_MIGRATOR_URL`. If `DB_APP_URL` uses the transaction pooler, add `prepareThreshold=0`. Pooler usernames use the form `brain_app.<project-ref>`.

- [ ] **Step 6 [YOU]:** Run the Postman collection. Expected: all requests pass, and the tables appear in schema `brain`.
- [ ] **Step 7:** Commit.

### Task 9: Frontend scaffold, API client and auth [YOU, then FRONTEND]

**Files:** Create `frontend/`, `src/api/client.ts`, `src/auth/{supabase.ts,AuthProvider.tsx,RequireAuth.tsx,LoginPage.tsx,SignUpPage.tsx,ResetPasswordPage.tsx}`, tests beside each file

**Interfaces:**
- Produces: `useAuth(): { user, session, loading, signOut }`, typed `api` client, `ApiError { status, problem }`.

- [ ] **Step 1 [YOU]:** Run from the repository root:

```bash
npm create vite@latest frontend -- --template react-ts
```

```bash
cd frontend && npm install @supabase/supabase-js @tanstack/react-query react-router-dom openapi-fetch && npm install -D tailwindcss @tailwindcss/vite openapi-typescript vitest jsdom @testing-library/react @testing-library/user-event @testing-library/jest-dom
```

- [ ] **Step 2 [FRONTEND]:** Add scripts `test` (`vitest run`) and `api:types` (`openapi-typescript ../docs/api/openapi.yaml -o src/api/schema.d.ts`). Configure Tailwind.
- [ ] **Step 3:** Write failing tests:

```ts
it("redirects a signed-out user to /login");
it("renders protected content for a signed-in user");
it("adds the bearer token to API requests");
it("refreshes once after 401 and retries");
it("signs out after a second 401");
```

- [ ] **Step 4:** Run `npm test`. Expected: FAIL.
- [ ] **Step 5:** Implement auth, password reset and the API client.
- [ ] **Step 6:** Run `npm test && npm run build`. Expected: PASS.
- [ ] **Step 7:** Commit.

### Task 10: App shell and organization onboarding [FRONTEND]

**Files:** Create `src/components/{AppLayout,LoadingState,EmptyState,ErrorState}.tsx`, `src/features/organizations/{useOrganizations.ts,CreateOrganizationPage.tsx,OrganizationSwitcher.tsx}`

**Interfaces:**
- Produces: route base `/orgs/:orgId`, `useCurrentOrg(): { org, role, can(permission) }`.

- [ ] **Step 1:** Write failing tests: `sends a user with no organization to onboarding`, `shows a slug conflict on the form`, `shows not found for an inaccessible organization`.
- [ ] **Step 2:** Run `npm test`. Expected: FAIL.
- [ ] **Step 3:** Implement the shell and onboarding. Use the `frontend-design` skill once here to set the visual direction.
- [ ] **Step 4:** Run `npm test && npm run build`. Expected: PASS.
- [ ] **Step 5:** Commit.

### Task 11: Project screens [FRONTEND]

**Files:** Create `src/features/projects/{useProjects.ts,ProjectListPage.tsx,ProjectFormDialog.tsx}`

- [ ] **Step 1:** Write failing tests: `shows empty state`, `hides create for a member`, `shows duplicate-name error`, `archives a project after confirmation`.
- [ ] **Step 2:** Run `npm test`. Expected: FAIL.
- [ ] **Step 3:** Implement the screens.
- [ ] **Step 4:** Run `npm test && npm run build`. Expected: PASS.
- [ ] **Step 5:** Commit.

### Task 12: Task screens [FRONTEND]

**Files:** Create `src/features/tasks/{useTasks.ts,TaskListPage.tsx,TaskFilters.tsx,TaskPanel.tsx}`

- [ ] **Step 1:** Write failing tests:

```ts
it("filters by status and assignee through the URL");
it("hides editing controls for a viewer");
it("sends null when the assignee is cleared");
it("keeps typed text and offers reload on 409");
it("confirms before permanent delete");
it("disables task changes in an archived project");
```

- [ ] **Step 2:** Run `npm test`. Expected: FAIL.
- [ ] **Step 3:** Implement the screens.
- [ ] **Step 4:** Run `npm test && npm run build`. Expected: PASS.
- [ ] **Step 5:** Commit.

### Task 13: Audit screen [FRONTEND]

**Files:** Create `src/features/audit/{useAuditEvents.ts,AuditLogPage.tsx}`

- [ ] **Step 1:** Write failing tests: `shows audit events to an admin`, `hides audit navigation from a member`, `shows empty state`.
- [ ] **Step 2:** Run `npm test`. Expected: FAIL.
- [ ] **Step 3:** Implement the page with pagination.
- [ ] **Step 4:** Run `npm test && npm run build`. Expected: PASS.
- [ ] **Step 5:** Commit.

### Task 14: Phase 1 acceptance [YOU, with one tab for fixes]

**Files:** Modify `docs/status.md`; create `docs/decisions/0001-auth-and-tenant-isolation.md`

- [ ] **Step 1:** Run `cd backend && ./mvnw -q verify`. Expected: BUILD SUCCESS, including a clean rebuild from Flyway.
- [ ] **Step 2:** Run `cd frontend && npm test && npm run build`. Expected: PASS.
- [ ] **Step 3:** Confirm no privileged key is present. Expected: no output.

```bash
git grep -nE "service_role|sb_secret_" $(git rev-list --all) -- frontend; grep -RInE "service_role|sb_secret_" frontend/dist
```

- [ ] **Step 4:** In the browser, sign up, log in, reset your password, create your organization and the "Project Brain" project.
- [ ] **Step 5:** Create, edit, filter, complete and delete a task, then confirm the deletion appears in the audit log.
- [ ] **Step 6:** Add remaining Phase 1 and Phase 2 work as real tasks in the app.
- [ ] **Step 7:** Write the short ADR for auth and tenant isolation, mark Phase 1 complete in `docs/status.md`, and commit.
