# ADR 0002: Team and Approval Model

Status: Accepted
Date: 2026-11-23
Builds on: ADR 0001, `docs/superpowers/specs/2026-11-23-phase-1-5-team-approvals-design.md`

## Context

Project Brain needs people with the right skills to verify work before Phase 2 produces AI output. A founder must be able to work alone, and employees must be able to join later without changing the system. AI agents do not run until Phase 6, so people submit work in Phase 1.5.

## Decision

**Two kinds of role**

- Access roles (`OWNER`, `ADMIN`, `MEMBER`, `VIEWER`) control what a person can do in an organization.
- Job roles control whose work a person can approve on a project. Each organization has its own list, starting with Project Manager, Architect, Backend, Frontend, QA and DevOps.
- A Viewer can hold a job role but never counts as an approver.

**Approval**

- Each new task has one responsible job role. A task with a role reaches `IN_REVIEW` and `DONE` only through review actions.
- Eligible approvers are the role's holders on the project. If none remain, the organization's Owners approve, recorded as `OWNER_FALLBACK`.
- Self-approval is on by default for solo founders and is always recorded. An Owner can turn it off, which removes the latest submitter from the approvers.
- A decision must use the task version the reviewer saw. A stale decision returns `409`.
- A rejection requires a comment.
- `task_reviews` is append-only for `brain_app` and has no foreign key to `tasks`, so history survives task deletion and member removal.

**Invitations**

- Invites are one-time links that last 7 days. Only a SHA-256 token hash is stored, and the link keeps the token in the URL fragment.
- The signed-in email must match the invite. Every invalid accept returns the same `404`.
- Supabase **Confirm email** must stay enabled wherever invites are used.
- Owners can invite Admin, Member or Viewer. Admins can invite only Member or Viewer.

**Membership**

- Only Owners change access roles or remove members.
- Owner rows are locked during these changes, so concurrent requests cannot remove the last Owner.
- Removing a member clears their project roles and task assignments and keeps their history.

## Consequences

- Phase 2 can add AI work to the same submit and decision flow.
- Phase 1 tasks without a role keep working. A Phase 1 task already `DONE` stays done if it later receives a role.
- The app enforces access role boundaries. RLS enforces tenant isolation and invite matching, but it does not independently stop arbitrary SQL running as `brain_app` from changing a role inside the current organization.
- The frontend role map and review eligibility display must stay aligned with the backend.

## Alternatives rejected

- **Task status plus audit log:** too easy to bypass and too weak as approval history.
- **General approval engine:** adds abstractions before a second work type exists.
- **Email invitations in this phase:** adds a custom SMTP dependency that a copyable link avoids.
