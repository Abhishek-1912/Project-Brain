# Phase 1.5 Team and Approvals Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add employee invitations, project job roles, task approval history, and a "waiting for my review" queue.

**Architecture:** Spring Boot keeps enforcing access through `OrgTx`, with PostgreSQL RLS as the second layer. A new `team` module owns job roles and project assignments, the `organization` module owns invites and members, and the `task` module owns the review workflow. React consumes the updated OpenAPI contract through generated types.

**Tech Stack:** Java 17, Spring Boot, JPA and JdbcClient, Flyway, Testcontainers, ArchUnit, PostgreSQL, Supabase Auth, React, TypeScript, TanStack Query, React Router, openapi-fetch, Vitest.

**Spec:** `docs/superpowers/specs/2026-11-23-phase-1-5-team-approvals-design.md`

## Global Constraints

- Work on branch `phase-1-5-team-approvals`, created from `main`.
- Commits are made only after developer review. Never commit `.env`.
- Add schema only through `V3__team_and_approvals.sql`. Do not edit `V1` or `V2`.
- Business tables stay in schema `brain`, with `ENABLE` and `FORCE ROW LEVEL SECURITY`. Do not use `BYPASSRLS`.
- A module may use another module only through its `api` package, or `shared`.
- Non-member organization access returns `404`. A member without permission gets `403`. Stale `version` returns `409`.
- Default job roles: Project Manager, Architect, Backend, Frontend, QA, DevOps.
- Invites last 7 days, work once, store only a SHA-256 token hash, and use the link `/invite#token=<token>`.
- Rejection comments are required, non-blank, and at most 2000 characters.
- `task_reviews` is append-only for `brain_app`.
- Add each route to `docs/api/openapi.yaml` in the task that implements it, so `ContractRoutesTest` stays green.
- Frontend styling follows `docs/design/brand-theme.md`. Test with real components and a stubbed network, as existing feature tests do.
- Backend verification needs Docker. Use the `DOCKER_HOST` and `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` values in `docs/status.md` if needed.

## Review Focus

1. An invite for `Ravi@Example.com` is accepted by `ravi@example.com`: it succeeds, while any other email fails without revealing the organization.
2. Two reviewers decide the same task at once: one succeeds, the other gets `409`, and only one decision is recorded.
3. The only role holder is removed during review: the task stays in review and moves to the Owner fallback.
4. A task is deleted during review: it leaves every queue, while its review history remains in the database.
5. A Phase 1 task already `IN_REVIEW` with no role: it keeps working, and setting a role returns `409` until it leaves review.

---

### Task 1: Schema and RLS

**Files:**
- Create: `backend/src/main/resources/db/migration/V3__team_and_approvals.sql`
- Modify: `backend/src/test/java/com/projectbrain/RlsIsolationTest.java`

**Interfaces:**
- Produces: tables and columns in spec section 7, plus `brain.current_invite_hash()`, reading `app.current_invite_hash` and returning null when blank.

- [ ] **Step 1:** Create the branch: `git switch -c phase-1-5-team-approvals`.
- [ ] **Step 2:** Add failing tests to `RlsIsolationTest`:

```java
@Test void organizationBCannotReadJobRolesInvitesTeamsOrReviews() {}
@Test void appRoleCannotUpdateOrDeleteTaskReviews() {}
@Test void memberInsertRequiresMatchingPendingInviteHash() {}
@Test void expiredCancelledOrUsedInviteCannotCreateMember() {}
@Test void inviteForAnotherEmailCannotCreateMember() {}
@Test void existingOrganizationsReceiveSixDefaultJobRoles() {}
```

- [ ] **Step 3:** Run `cd backend && ./mvnw -q test -Dtest=RlsIsolationTest`. Expected: FAIL because the tables do not exist.
- [ ] **Step 4:** Write `V3`. Keep the Phase 1 Owner insert policy, add a separate invite insert policy, grant `SELECT, INSERT` on `task_reviews`, and grant `UPDATE, DELETE` on `organization_members`. `task_reviews.task_id` has no foreign key to `tasks`.
- [ ] **Step 5:** Run the same test. Expected: PASS.
- [ ] **Step 6:** After developer review, commit `feat: add team and approval schema`.

### Task 2: Job roles

**Files:**
- Create: `backend/src/main/java/com/projectbrain/team/{JobRoleController,JobRoleService}.java`
- Create: `backend/src/main/java/com/projectbrain/team/api/JobRoleLookup.java`
- Create: `backend/src/main/java/com/projectbrain/organization/api/OrganizationInitializer.java`
- Modify: `organization/api/Permission.java`, `organization/api/Role.java`, `organization/OrganizationService.java`, `docs/api/openapi.yaml`
- Test: `backend/src/test/java/com/projectbrain/JobRoleApiTest.java`, `RolePermissionTest.java`, `ArchitectureTest.java`

**Interfaces:**
- Produces: `Permission.INVITE_MEMBERS`, `MANAGE_MEMBERS`, `MANAGE_JOB_ROLES`.
- Produces: `OrganizationInitializer.initialize(UUID orgId)`, called inside organization creation.
- Produces: `JobRoleLookup.requireActive(UUID orgId, UUID jobRoleId)`, which throws `UnprocessableException` when missing or inactive.

- [ ] **Step 1:** Write failing tests:

```java
@Test void newOrganizationGetsDefaultJobRoles() {}
@Test void ownerCanCreateRenameAndDeactivateJobRole() {}
@Test void adminCannotManageJobRoles() {}
@Test void duplicateJobRoleNameIgnoringCaseReturns409() {}
@Test void jobRoleFromAnotherOrganizationReturns404() {}
```

- [ ] **Step 2:** Run `./mvnw -q test -Dtest=JobRoleApiTest,RolePermissionTest`. Expected: FAIL.
- [ ] **Step 3:** Implement `GET`, `POST /organizations/{orgId}/job-roles` and `PATCH .../{jobRoleId}`. Audit `JOB_ROLE_CREATED` and `JOB_ROLE_UPDATED`.
- [ ] **Step 4:** Run the tests plus `ArchitectureTest` and `ContractRoutesTest`. Expected: PASS.
- [ ] **Step 5:** After developer review, commit `feat: add organization job roles`.

### Task 3: Project team

**Files:**
- Create: `backend/src/main/java/com/projectbrain/team/{ProjectTeamController,ProjectTeamService}.java`
- Create: `backend/src/main/java/com/projectbrain/team/api/{ApproverPolicy,Approvers,ApprovalBasis,ProjectTeamLookup}.java`
- Modify: `docs/api/openapi.yaml`
- Test: `backend/src/test/java/com/projectbrain/ProjectTeamApiTest.java`, `team/ApproverPolicyTest.java`

**Interfaces:**
- Consumes: `JobRoleLookup.requireActive`.
- Produces: `ApprovalBasis { ROLE_HOLDER, OWNER_FALLBACK }`, `record Approvers(Set<UUID> userIds, ApprovalBasis basis)`.
- Produces: `ApproverPolicy.resolve(Set<UUID> roleHolders, Set<UUID> owners, UUID submitterId, boolean allowSelfApproval): Approvers`.
- Produces: `ProjectTeamLookup.approvers(UUID orgId, UUID projectId, UUID jobRoleId, UUID submitterId, boolean allowSelfApproval): Approvers`, excluding Viewers.

- [ ] **Step 1:** Write failing tests:

```java
@ParameterizedTest void resolvesRoleHolderFallbackAndSelfApprovalCases() {}
@Test void viewerCanHoldRoleButIsNotAnApprover() {}
@Test void ownerAndAdminCanAssignJobRoles() {}
@Test void memberCannotAssignJobRoles() {}
@Test void cannotAssignInactiveRoleOrNonMember() {}
@Test void repeatedAssignmentIsIdempotent() {}
```

- [ ] **Step 2:** Run `./mvnw -q test -Dtest=ApproverPolicyTest,ProjectTeamApiTest`. Expected: FAIL.
- [ ] **Step 3:** Implement the team `GET`, assignment `PUT` and `DELETE`, following spec section 6.6. Audit `PROJECT_JOB_ROLE_ASSIGNED` and `PROJECT_JOB_ROLE_REMOVED`.
- [ ] **Step 4:** Run the tests plus `ContractRoutesTest`. Expected: PASS.
- [ ] **Step 5:** After developer review, commit `feat: add project job role assignments`.

### Task 4: Invitations

**Files:**
- Create: `backend/src/main/java/com/projectbrain/organization/{InviteController,InviteService,InviteToken}.java`
- Modify: `organization/api/OrgTx.java`, `docs/api/openapi.yaml`
- Test: `backend/src/test/java/com/projectbrain/InviteApiTest.java`

**Interfaces:**
- Produces: `OrgTx.withInvite(String tokenHash, Supplier<T> work)`, setting user and invite context with `set_config(..., true)`.
- Produces: `InviteToken.generate(): String` and `InviteToken.hash(String token): String`.
- Produces: create response with `id`, `email`, `role`, `expiresAt`, `token`. List responses never include a token or hash.

- [ ] **Step 1:** Write failing tests:

```java
@Test void ownerCanInviteAdminMemberOrViewer() {}
@Test void adminCanInviteOnlyMemberOrViewer() {}
@Test void tokenIsReturnedOnceAndOnlyHashIsStored() {}
@Test void pendingDuplicateAndExistingMemberReturn409() {}
@Test void acceptIgnoresEmailCase() {}
@Test void wrongEmailExpiredCancelledUsedAndUnknownReturnSameError() {}
@Test void concurrentAcceptCreatesOneMembership() {}
```

- [ ] **Step 2:** Run `./mvnw -q test -Dtest=InviteApiTest`. Expected: FAIL.
- [ ] **Step 3:** Implement invite list, create, cancel and accept. All invalid accept cases return `404` with one identical body. Ensure the profile exists before accepting. Audit `INVITE_CREATED`, `INVITE_CANCELLED` and `INVITE_ACCEPTED`, with no token in metadata.
- [ ] **Step 4:** Run the tests plus `ContractRoutesTest`. Expected: PASS.
- [ ] **Step 5:** After developer review, commit `feat: add organization invitations`.

### Task 5: Member management

**Files:**
- Create: `backend/src/main/java/com/projectbrain/organization/MemberService.java`
- Create: `backend/src/main/java/com/projectbrain/organization/api/MemberRemovalListener.java`
- Modify: `organization/MemberController.java`, `team/ProjectTeamService.java`, `task/TaskService.java`, `docs/api/openapi.yaml`
- Test: `backend/src/test/java/com/projectbrain/MemberManagementApiTest.java`

**Interfaces:**
- Produces: `MemberRemovalListener.onMemberRemoved(UUID orgId, UUID userId)`, run inside the removal transaction by the `team` and `task` modules.

- [ ] **Step 1:** Write failing tests:

```java
@Test void ownerCanChangeRoleAndRemoveMember() {}
@Test void adminCannotChangeRoleOrRemoveMember() {}
@Test void lastOwnerCannotBeDowngradedOrRemoved() {}
@Test void concurrentRequestsCannotRemoveLastOwner() {}
@Test void removalUnassignsTasksAndClearsJobRolesButKeepsHistory() {}
```

- [ ] **Step 2:** Run `./mvnw -q test -Dtest=MemberManagementApiTest`. Expected: FAIL.
- [ ] **Step 3:** Implement `PATCH`, `DELETE /organizations/{orgId}/members/{userId}`. Lock Owner rows with `FOR UPDATE` before the last-Owner check. Increase versions of unassigned tasks. Audit `MEMBER_ROLE_CHANGED` and `MEMBER_REMOVED`.
- [ ] **Step 4:** Run the tests plus `ContractRoutesTest`. Expected: PASS.
- [ ] **Step 5:** After developer review, commit `feat: add member role management`.

### Task 6: Responsible role on tasks

**Files:**
- Modify: `task/{Task,TaskController,TaskPatch,TaskService}.java`, `docs/api/openapi.yaml`, `TaskApiTest.java`

**Interfaces:**
- Consumes: `JobRoleLookup.requireActive`.
- Produces: `Task.responsibleJobRoleId`, and API field `responsibleJobRoleId`.

- [ ] **Step 1:** Add failing tests:

```java
@Test void newTaskRequiresActiveResponsibleRole() {}
@Test void phaseOneTaskWithoutRoleCanMoveDirectlyToDone() {}
@Test void taskWithRoleCannotPatchToInReviewOrDone() {}
@Test void taskInReviewOrDoneCannotLeaveThroughPatch() {}
@Test void roleCannotBeClearedOrChangedDuringReview() {}
@Test void phaseOneTaskInReviewCannotReceiveRoleUntilItLeavesReview() {}
@Test void settingRoleOnPhaseOneDoneTaskDoesNotReopenIt() {}
@Test void doneTaskWithRoleCannotBeEdited() {}
```

- [ ] **Step 2:** Run `./mvnw -q test -Dtest=TaskApiTest`. Expected: FAIL.
- [ ] **Step 3:** Implement spec section 6.4 and the PATCH limits in 6.5. Update test helpers to create tasks with a role.
- [ ] **Step 4:** Run `./mvnw -q test -Dtest=TaskApiTest,ContractRoutesTest`. Expected: PASS.
- [ ] **Step 5:** After developer review, commit `feat: add responsible job role to tasks`.

### Task 7: Review workflow

**Files:**
- Create: `backend/src/main/java/com/projectbrain/task/{TaskReviewController,TaskReviewService,TaskReviewAction}.java`
- Modify: `docs/api/openapi.yaml`
- Test: `backend/src/test/java/com/projectbrain/TaskReviewApiTest.java`

**Interfaces:**
- Consumes: `ProjectTeamLookup.approvers`, `ProjectStatus`, `AuditLog`.
- Produces: `TaskReviewAction { SUBMITTED, APPROVED, REJECTED, WITHDRAWN, REOPENED }`.
- Produces: `POST /organizations/{orgId}/tasks/{taskId}/{submit|approve|reject|withdraw|reopen}` with body `{version, comment?}`, returning `Task`.

- [ ] **Step 1:** Write failing tests:

```java
@Test void submitMovesTaskToInReviewAndRecordsVersion() {}
@Test void roleHolderApprovalMovesTaskToDone() {}
@Test void noRoleHolderUsesOwnerFallback() {}
@Test void selfApprovalIsAllowedAndMarked() {}
@Test void selfApprovalOffBlocksSubmitter() {}
@Test void rejectRequiresCommentAndReturnsTaskToInProgress() {}
@Test void staleVersionApprovalReturns409() {}
@Test void concurrentDecisionsRecordOneDecision() {}
@Test void viewerAndNonHolderCannotDecide() {}
@Test void withdrawAndReopenReturnTaskToInProgress() {}
@Test void removedOnlyRoleHolderMovesReviewToOwnerFallback() {}
@Test void archivedProjectBlocksReviewActions() {}
@Test void deletingTaskKeepsReviewHistory() {}
```

- [ ] **Step 2:** Run `./mvnw -q test -Dtest=TaskReviewApiTest`. Expected: FAIL.
- [ ] **Step 3:** Implement the transitions in spec section 6.5. Task status, version, review row and audit event change in one transaction. Treat optimistic-lock failure as `409`.
- [ ] **Step 4:** Run the tests plus `ContractRoutesTest`. Expected: PASS.
- [ ] **Step 5:** After developer review, commit `feat: add task review workflow`.

### Task 8: Review queue, history and settings

**Files:**
- Modify: `task/{TaskReviewController,TaskReviewService}.java`, `organization/{OrganizationController,OrganizationService}.java`, `docs/api/openapi.yaml`
- Test: `TaskReviewApiTest.java`, `OrganizationApiTest.java`

**Interfaces:**
- Produces: `GET /organizations/{orgId}/tasks/{taskId}/reviews`.
- Produces: `GET /organizations/{orgId}/reviews/mine`, with items `{task, projectName, jobRoleName, submittedBy, submittedAt, approvalBasis, selfApproval}`.
- Produces: `PATCH /organizations/{orgId}/settings` with `{allowSelfApproval, version}`, and `allowSelfApproval` on `Organization`.

- [ ] **Step 1:** Add failing tests:

```java
@Test void myReviewsShowsOnlyTasksCurrentUserCanDecide() {}
@Test void myReviewsMarksOwnerFallbackAndSelfApproval() {}
@Test void myReviewsIsOldestFirstAndPaginated() {}
@Test void deletedTaskLeavesMyReviews() {}
@Test void onlyOwnerCanChangeSelfApprovalSetting() {}
```

- [ ] **Step 2:** Run `./mvnw -q test -Dtest=TaskReviewApiTest,OrganizationApiTest`. Expected: FAIL.
- [ ] **Step 3:** Implement. Paginate in SQL, using the same eligibility as `ApproverPolicy`, and add a test that compares queue membership with approve permission. Audit `SELF_APPROVAL_SETTING_CHANGED`.
- [ ] **Step 4:** Run `./mvnw -q verify`. Expected: BUILD SUCCESS.
- [ ] **Step 5:** After developer review, commit `feat: add review queue and approval settings`.

### Task 9: Frontend team and invites

**Files:**
- Create: `frontend/src/features/team/{useTeam.ts,TeamPage.tsx,InviteDialog.tsx,InviteAcceptPage.tsx}` and tests
- Modify: `frontend/src/api/schema.d.ts`, `features/organizations/useOrganizations.ts`, `components/AppLayout.tsx`, `AppRoutes.tsx`

**Interfaces:**
- Produces: routes `/invite` and `/orgs/:orgId/team`.
- Produces: `useMembers` remains available; `useInvites(orgId)`, `useCreateInvite(orgId)`, `useCancelInvite(orgId)`, `useUpdateMember(orgId)`, `useRemoveMember(orgId)`.

- [ ] **Step 1:** Run `npm run api:types`, then write failing tests:

```ts
it("shows the invite link once with a copy action");
it("limits an admin to Member and Viewer invites");
it("hides role changes and removal from an admin");
it("confirms before removing a member");
it("preserves the invite token through sign-in");
it("shows the same invalid state for every failed accept");
```

- [ ] **Step 2:** Run `npm test`. Expected: FAIL.
- [ ] **Step 3:** Implement. Read the token from `location.hash`, keep it in `sessionStorage` through sign-in, remove it after accept, and clear it from the URL.
- [ ] **Step 4:** Run `npm test && npm run build && npm run lint`. Expected: PASS.
- [ ] **Step 5:** After developer review, commit `feat: add team and invite screens`.

### Task 10: Project role grid

**Files:**
- Create: `frontend/src/features/team/{useProjectTeam.ts,ProjectTeamPage.tsx}` and tests
- Modify: `AppRoutes.tsx`, `features/tasks/TaskListPage.tsx`

**Interfaces:**
- Produces: route `/orgs/:orgId/projects/:projectId/team`.
- Produces: `useJobRoles(orgId)`, `useProjectTeam(orgId, projectId)`, `useSetProjectJobRole(orgId, projectId)`.

- [ ] **Step 1:** Write failing tests:

```ts
it("shows people as rows and active job roles as columns");
it("assigns and removes a job role");
it("marks a role without an eligible holder as Owner approval");
it("does not count a viewer as an approver");
it("is read-only for a member");
```

- [ ] **Step 2:** Run `npm test`. Expected: FAIL.
- [ ] **Step 3:** Implement the approved Option A grid with accessible checkboxes named like `Backend for Ravi`, and horizontal scroll inside the grid on mobile.
- [ ] **Step 4:** Run `npm test && npm run build && npm run lint`. Expected: PASS.
- [ ] **Step 5:** After developer review, commit `feat: add project role grid`.

### Task 11: Task review UI and My reviews

**Files:**
- Create: `frontend/src/features/reviews/{useReviews.ts,MyReviewsPage.tsx,TaskReviewSection.tsx}` and tests
- Modify: `features/tasks/{TaskPanel.tsx,TaskListPage.tsx,useTasks.ts}`, `components/AppLayout.tsx`, `AppRoutes.tsx`

**Interfaces:**
- Consumes: `useJobRoles(orgId)`.
- Produces: route `/orgs/:orgId/reviews`.
- Produces: `useMyReviews(orgId, page)`, `useTaskReviews(orgId, taskId)`, `useReviewAction(orgId, projectId)`.

- [ ] **Step 1:** Write failing tests:

```ts
it("requires a responsible role on a new task");
it("hides review status options from a task with a role");
it("submits, withdraws and reopens a task");
it("requires a comment before rejecting");
it("keeps the typed comment and offers reload on 409");
it("shows role holder, Owner fallback and self-approval reasons");
it("shows the My reviews count in the sidebar");
it("keeps Phase 1 tasks without a role working");
```

- [ ] **Step 2:** Run `npm test`. Expected: FAIL.
- [ ] **Step 3:** Implement the approved mockups. Do not show Approve or Reject unless the task appears in the user's queue.
- [ ] **Step 4:** Run `npm test && npm run build && npm run lint`. Expected: PASS.
- [ ] **Step 5:** After developer review, commit `feat: add task review screens`.

### Task 12: Phase 1.5 acceptance

**Files:**
- Modify: `docs/status.md`
- Create: `docs/decisions/0002-team-and-approval-model.md`

- [ ] **Step 1:** Run `cd backend && ./mvnw -q verify`. Expected: BUILD SUCCESS.
- [ ] **Step 2:** Run `cd frontend && npm test && npm run build && npm run lint`. Expected: PASS.
- [ ] **Step 3:** Confirm Supabase **Confirm email** is enabled.
- [ ] **Step 4:** With two real users, invite and accept, assign a job role, submit, reject with a comment, resubmit, approve, and confirm the audit log.
- [ ] **Step 5:** Sign in as a Member and a Viewer. Confirm their controls and blocked screens.
- [ ] **Step 6:** Write the short ADR, mark Phase 1.5 complete in `docs/status.md`, and commit after developer review.
