# Phase 1.5: Team and Approvals Design

Status: Approved by the developer on 2026-11-23
Date: 2026-11-23
Builds on: `2026-11-22-role-based-agent-workflow-design.md`, `docs/decisions/0001-auth-and-tenant-isolation.md`

## 1. Intent

Project Brain should work like a real software company. A founder can start alone and hold every job role. When employees join, they get job roles on projects, and only the right person approves each task.

This must exist before Phase 2, because Phase 2 outputs need human approval. AI agents do not run until Phase 6, so **people submit work in Phase 1.5**. The review records are built so agent submissions can use them later.

## 2. Goals

- Invite employees with a copyable link.
- Manage access roles and remove members safely.
- Assign job roles per project.
- Give every new task one responsible job role.
- Submit, approve and reject tasks with a permanent history.
- Show each person a "waiting for my review" queue.

## 3. Non-goals

- Running AI agents or recording agent runs.
- Sending invite or review emails. This avoids a custom SMTP dependency in this phase.
- Pull request, deployment, database-change or secret-access approvals.
- A general approval engine for PRDs or other artifacts.
- Task comments outside review decisions.
- Restoring archived projects.

## 4. Anti-goals

The work has failed, even if screens function, if any of these is possible:

- A task with a responsible job role reaches `DONE` without an approval record.
- A person joins an organization with an invite meant for another email.
- An Admin grants Admin or Owner access.
- An organization loses its last Owner.
- A review record is edited or deleted through the application.
- Data crosses organizations.

## 5. Approaches

| Approach | Tradeoff | Decision |
|---|---|---|
| A. Job role on each task plus separate review history | More tables, APIs and UI, but stores who, when, version and why, and blocks bypass | **Chosen by developer** |
| B. Task status plus audit log | Fast, but weak history and hard to prevent bypass | Rejected |
| C. General approval engine | Ready for later artifacts, but builds abstractions Phase 1.5 does not need | Rejected |

## 6. Decided rules

### 6.1 Invitations

Decided with the developer:

- Invites are copyable links. There is no invite email in this phase.
- Links last 7 days, work once and can be cancelled.
- Only a SHA-256 hash of the token is stored.
- The signed-in email must match the invite email, ignoring case.
- Owners can invite Admin, Member or Viewer. Admins can invite only Member or Viewer.

Builder rules, marked as design calls:

- The token has 256 bits from a secure random source and is returned only once, when the invite is created.
- The link format is `/invite#token=<token>`, so the token is not sent in request paths or server logs.
- One organization can have only one pending invite per email. A second attempt returns `409`.
- An email that already belongs to a member returns `409`.
- Expired, cancelled, used and unknown tokens all show the same "invite is not valid" message. A wrong-email response must not reveal the organization.
- A signed-out user signs in or signs up, then returns to the invite. If email confirmation interrupts this, the screen tells them to reopen the invite link.

**Required Supabase setting:** "Confirm email" must be enabled wherever invites are used. Otherwise someone could sign up with another person's unconfirmed email and accept that person's invite. Do not trust `user_metadata.email_verified`, because users can edit metadata.

### 6.2 Members

- Only an Owner can change access roles or remove a member.
- The last Owner cannot be removed or downgraded. The check locks the organization's Owner rows, so two concurrent requests cannot remove both Owners.
- When a member is removed, in one transaction:
  - their project job roles are removed
  - their assigned tasks become unassigned, with task versions increased
  - their membership is removed
  - their review and audit history stays
- Pending reviews move to the remaining role holders, or to the Owner fallback.

### 6.3 Job roles

- Each organization has its own job role list. Defaults: Project Manager, Architect, Backend, Frontend, QA, DevOps.
- New organizations receive the defaults. Migration `V3` adds them to existing organizations.
- A person can hold several job roles on a project.
- Owners manage the organization's role list. Owners and Admins assign job roles on projects.
- A role in use cannot be deleted. It can be deactivated, which blocks new assignment but keeps history and existing tasks.
- A Viewer can appear in the project team but never counts as an approver.

### 6.4 Responsible role on tasks

- New tasks require an active job role.
- Phase 1 tasks can keep a null role and may move directly to `DONE`.
- Once a task has a role, approval is mandatory and the role cannot be cleared.
- A role cannot be set or changed while a task is `IN_REVIEW`.
- Setting a role on an already `DONE` Phase 1 task does not reopen it.

### 6.5 Review flow

```text
BACKLOG / TODO / IN_PROGRESS
  -> Submit -> IN_REVIEW
       Approve -> DONE
       Reject with comment -> IN_PROGRESS
       Withdraw -> IN_PROGRESS
DONE -> Reopen -> IN_PROGRESS
```

For a task with a responsible role:

- Normal PATCH can move it only among `BACKLOG`, `TODO` and `IN_PROGRESS`. Sending `IN_REVIEW` or `DONE`, or moving it out of those states, returns `409`.
- Owners, Admins and Members can submit, withdraw and reopen.
- Content can be edited during review. Each edit increases the task version.
- Approve and reject must send the version the reviewer saw. A stale version returns `409`, and the UI offers reload without losing the typed comment.
- A `DONE` task must be reopened before editing.
- A rejection requires a non-blank comment of up to 2000 characters.
- Archived projects block every review action, using the existing project write check.

### 6.6 Who can approve

Evaluate at decision time:

1. Find members who hold the task's job role on the project and have Owner, Admin or Member access.
2. If self-approval is off, remove the latest submitter.
3. If anyone remains, they are eligible with basis `ROLE_HOLDER`.
4. Otherwise all Owners are eligible with basis `OWNER_FALLBACK`, excluding the submitter when self-approval is off.
5. If nobody is eligible, the task stays in review and the UI asks the Owner to add a reviewer or enable self-approval.

Self-approval is allowed by default so a solo founder is not blocked. Every self-approval is stored as `self_approved = true`. Only an Owner can change the organization setting.

## 7. Data model

Add one forward migration, `V3__team_and_approvals.sql`. Do not edit `V1` or `V2`.

- `organizations.allow_self_approval boolean NOT NULL DEFAULT true`
- `job_roles`: `id`, `organization_id`, `name`, `active`, timestamps, `version`; unique on organization and `lower(name)`
- `project_job_roles`: `organization_id`, `project_id`, `user_id`, `job_role_id`, `created_by`, `created_at`; primary key on all four identity columns; same-organization foreign keys to project, member and role
- `organization_invites`: `id`, `organization_id`, `email`, `role`, `token_hash`, `created_by`, `expires_at`, `accepted_by`, `accepted_at`, `cancelled_by`, `cancelled_at`, `created_at`; unique `token_hash`; partial unique index for pending invites on organization and `lower(email)`; role limited to Admin, Member or Viewer
- `tasks.responsible_job_role_id uuid NULL`, with a same-organization foreign key
- `task_reviews`: `id`, `organization_id`, `project_id`, `task_id`, `task_version`, `action`, `actor_id`, `job_role_id`, `approval_basis`, `self_approved`, `comment`, `created_at`
  - `action`: `SUBMITTED`, `APPROVED`, `REJECTED`, `WITHDRAWN`, `REOPENED`
  - `approval_basis`: `ROLE_HOLDER`, `OWNER_FALLBACK`, or null for non-decisions
  - `actor_id` references `profiles`, so history survives member removal
  - `task_id` is a historical ID with no foreign key to `tasks`, so review rows remain after a task is permanently deleted

Security:

- Every new table uses `ENABLE` and `FORCE ROW LEVEL SECURITY` with organization-scoped policies.
- `brain_app` gets only `SELECT` and `INSERT` on `task_reviews`, so history is append-only.
- Add `UPDATE` and `DELETE` on `organization_members`, with policies limited to the current organization.
- Invite acceptance uses a transaction-local `app.current_invite_hash`. RLS exposes only the matching invite. The member insert policy allows a non-Owner row only when a pending, unexpired invite matches that hash, organization, role and current user's profile email.
- Do not use `BYPASSRLS`.

## 8. API

Update `docs/api/openapi.yaml` first and regenerate frontend types.

Add permissions in `Permission.java` and its frontend mirror:

- `INVITE_MEMBERS`: Owner, Admin
- `MANAGE_MEMBERS`: Owner
- `MANAGE_JOB_ROLES`: Owner

Endpoints:

- `GET`, `POST /organizations/{orgId}/invites`
- `DELETE /organizations/{orgId}/invites/{inviteId}`, which cancels and keeps the row
- `POST /invites/accept` with the token in the JSON body
- `PATCH`, `DELETE /organizations/{orgId}/members/{userId}`
- `PATCH /organizations/{orgId}/settings` for self-approval, using the organization version
- `GET`, `POST /organizations/{orgId}/job-roles`
- `PATCH /organizations/{orgId}/job-roles/{jobRoleId}`
- `GET /organizations/{orgId}/projects/{projectId}/team`
- `PUT`, `DELETE /organizations/{orgId}/projects/{projectId}/team/{userId}/job-roles/{jobRoleId}`
- `GET /organizations/{orgId}/tasks/{taskId}/reviews`
- `POST /organizations/{orgId}/tasks/{taskId}/submit`, `approve`, `reject`, `withdraw`, `reopen`, each with the current `version`
- `GET /organizations/{orgId}/reviews/mine` with pagination, oldest submission first

Task responses add `responsibleJobRoleId`. Task create requires it. Task PATCH can change it under the rules above.

Audit these actions: invite created, cancelled and accepted; member role changed and removed; job role created and changed; project job role assigned and removed; self-approval setting changed; task submitted, approved, rejected, withdrawn and reopened.

## 9. Screens

Follow `docs/design/brand-theme.md` and the approved mockups.

- **Sidebar:** Projects, My reviews with a count, Team, and Audit log for Owners and Admins.
- **My reviews:** Tasks across the organization's projects with project, role, submitter, age and reason: role holder, Owner fallback or self-approval.
- **Task panel:** Responsible role, review history, and the actions the current user can take. Reject requires a comment.
- **Team:** Members, access roles, pending invites and invite creation. The token link appears once with a copy button.
- **Project team:** The approved **role grid (Option A)**, with people as rows and job roles as columns. A role without an eligible holder shows that the Owner approves.
- **Invite acceptance:** Signed-out, valid, wrong-account and invalid states.

## 10. Testing and acceptance

Use TDD. Backend database tests must run against real PostgreSQL with `brain_app`.

Must be proven by tests:

1. A task with a role cannot reach `DONE` through PATCH.
2. A rejection without a comment fails.
3. A stale approval returns `409`, and the UI keeps the typed comment.
4. Role holder, Owner fallback, self-approval and self-approval-off cases choose the right approvers.
5. An invite cannot be used twice, after expiry, after cancellation or by another email.
6. An Admin cannot invite an Admin, change roles or remove members.
7. Concurrent requests cannot remove the last Owner.
8. A removed member's history stays and their assigned tasks become unassigned.
9. `brain_app` cannot update or delete `task_reviews`.
10. An organization B ID in an organization A URL returns `404` and changes nothing.
11. Phase 1 tasks with no role still work.

Browser acceptance covers two real users: invite, accept, assign a job role, submit, reject with a comment, resubmit, approve, and confirm the audit log. This also closes the Phase 1 gap for real Member and Viewer UI checks.

## 11. Left to the builder

- Package boundaries within the existing module rules.
- Exact error wording, following the existing Problem Details style.
- Task breakdown and order, with the API contract before backend and frontend work.
