# Phase 3: Architecture Intelligence Design

Status: Approved by the developer on 2026-11-24 as a working model
Date: 2026-11-24
Source: PRD roadmap ("Architecture intelligence: architecture, database and API documents") and `docs/superpowers/specs/2026-11-22-role-based-agent-workflow-design.md` (the Architect Agent drafts; a human Architect approves)
Builds on: ADR 0001, ADR 0002, ADR 0003, and the Phase 2 design

## 1. Intent

Once a project has approved user stories, Project Brain should draft the project's architecture: the main parts of the system, the data model, and the API. A person who holds the Architect job role approves it before it becomes the project's current architecture.

The output must serve two readers. People read it as a document and can download it as Markdown. Later phases, such as knowledge search and coding agents, read the same content as structured data, without rewriting.

**Same test conditions as Phase 2.** Phase 3 uses dummy data only, the Gemini free tier, no clients, and no spending limit.

**Build it as a working model first.** The flow in section 7 and every anti-goal in section 4 are required. Visual polish and convenience features can come later.

## 2. Goals

- Keep one architecture per project as numbered versions.
- Generate a version in three ordered steps: overview, data model, API design.
- Link every table and endpoint to the approved stories it serves.
- Let a person edit a draft before review.
- Let an eligible Architect approve or reject a version, with Owner fallback and the self-approval setting.
- Carry approved decisions forward into the next version.
- Download any version as Markdown.
- Record model, prompt version, tokens, and status for every AI run and step.

## 3. Non-goals

- Real client documents or customer use.
- Diagrams.
- Generating code, SQL migrations, or OpenAPI files that the app runs.
- Checking real code against the design.
- RAG, embeddings, MCP, agents, or GitHub (Phases 4 to 7).
- Fixing the deferred Phase 2 minor findings.

## 4. Anti-goals

The work has failed, even if screens function, if any of these is possible:

- An AI-generated version becomes current without human approval.
- AI approves, rejects, or changes permissions.
- Text in a PRD or story causes the system to take an action.
- A partly generated version is saved or shown as complete.
- An architecture, version, or run crosses organizations.
- Two architecture runs for the same project are active at once.
- An approval applies to content different from what the reviewer saw.
- The Python service reads the database or receives a user's access token.

## 5. Constraints

- Keep the Spring modular monolith, Supabase PostgreSQL with RLS, Flyway, the OpenAPI contract, and the stateless Python AI service from ADR 0003.
- `V4` is applied to Supabase. All schema changes go in a new `V5` migration.
- Reuse Phase 2's AI run worker, failure codes, usage recording, restart recovery, Gemini provider, and quote checking where they fit.
- Tests must never call Gemini or Supabase.
- AI text is shown as plain text.

## 6. Approaches

| Approach | Tradeoff | Decision |
|---|---|---|
| A. One AI call writes all three parts | Simplest, but large projects risk the output limit and one weak part means redoing all | Rejected |
| B. Three ordered AI calls in one run, saved only when all succeed | Three free-tier requests per version and more code, but each part builds on the last and a failed step can resume | **Chosen by developer** |
| C. Plain Markdown output | Easy to read, but loses the structured data later phases need | Rejected |

Scope decision: **one architecture per project**, versioned, built from all approved stories in the project. The developer accepted this as the builder's recommendation.

## 7. User flow

```text
Project has approved or converted stories
  -> Owner or Admin clicks Generate architecture (or Update architecture)
  -> Run step 1: overview
  -> Run step 2: data model, using the overview
  -> Run step 3: API design, using the overview and data model
  -> All steps succeeded: save a new DRAFT version
  -> Owner, Admin, or Architect edits the draft (optional)
  -> Eligible Architect approves or rejects with a comment
       rejected -> regenerate; the comment is sent to the AI
       approved -> becomes the current architecture
  -> Later stories approved -> Update architecture -> next version
```

### 7.1 Generating

- Generation needs at least one `APPROVED` or `CONVERTED` story in the project.
- Input: every `APPROVED` or `CONVERTED` story in the project, plus a starting point:
  - If the newest reviewed version is `REJECTED`, its content and its rejection comments are the starting point, so comments such as "rename the orders table" make sense.
  - Otherwise, the latest approved version is the starting point, if one exists.
- Carrying decisions forward is guidance in the prompt. Spring does not check that approved tables or endpoints survive, because a person reviews every version.
- Project Brain's job roles are not sent. The design is for the client's app, whose users and roles come from the stories.
- Stories are sent with short reference keys, such as `S1`. Spring maps keys back to story IDs.
- Input limits: at most 200 stories, and the whole request must fit the AI service's 200,000-character limit. Otherwise generation is refused with a clear message.
- Starting a new run while a `DRAFT` version exists marks that draft `SUPERSEDED` when the new version saves.
- While an architecture run is active, the existing draft cannot be edited, approved, or rejected. Those requests return `409` with a message that a newer version is being generated. This keeps a review from applying to a draft that is about to be replaced.
- The approver role is resolved when generation starts: the organization's active job role named **Architect**. If there is none, the version has no approver role, only Owners and Admins can edit it, and the Owner fallback decides.

### 7.2 The three steps

| Step | Output |
|---|---|
| Overview | Summary, components with responsibilities, tech stack choices with reasons, key decisions with reasons and alternatives, and risks |
| Data model | Tables with descriptions, columns (name, type, nullable, description), relations to other tables, and story references |
| API design | Endpoints with method, path, summary, request fields, response fields, the client app's user roles allowed to call it (short free-text names), and story references |

- Each step is a separate request to Python and Gemini, with its own prompt version.
- Spring validates every step's output before the next step starts.
- A step's validated output is stored with the run.
- **Try again** starts a new run. The failed run stays `FAILED`. The new run copies completed steps from the most recent failed run when its inputs fingerprint matches; otherwise it starts from step 1.
- The fingerprint covers the story IDs and their versions, the starting-point version and its comments, and the three prompt versions. A changed prompt therefore never mixes with reused output.
- A run's token totals count only the requests it made. Copied steps keep their original usage on their step records.
- The run shows which step is running.

### 7.3 Consistency checks

Spring rejects a step's output as `INVALID_OUTPUT` when:

- A relation points to a table that is not in the data model.
- A table, column, or endpoint is duplicated.
- An endpoint method is not `GET`, `POST`, `PUT`, `PATCH`, or `DELETE`, or a path does not start with `/`.

Unknown story references are dropped, not rejected. A table or endpoint with no remaining story reference is kept and marked **Not linked to a story**.

### 7.4 Editing

- Owners, Admins, and people holding the Architect job role on the project can edit a `DRAFT` version.
- Editing replaces a whole section (overview, data model, or API design) with the edited content and the version number the editor saw. A stale save returns `409`.
- Edited content passes the same checks as AI output.
- Approved, rejected, and superseded versions are read-only.

### 7.5 Review

- The approver role is the one stored on the version when generation started (section 7.1).
- Eligible approvers come from the existing `ApproverPolicy`. If nobody eligible holds Architect, the Owner fallback applies.
- The person who started generation counts as the submitter, so the organization's self-approval setting applies.
- Approving records who, when, version number, approval basis, and self-approval. Rejecting requires a comment of up to 2000 characters.
- Approving marks the previous approved version `SUPERSEDED`. The newest approved version is the current architecture.
- Archived projects can be read but cannot generate, edit, or review.

### 7.6 Markdown

- Any version can be downloaded as Markdown, generated by Spring from the stored structure.
- The Markdown includes the version number, status, and approval details.

## 8. Architecture

- React calls only Spring.
- Spring owns versions, runs, steps, permissions, validation, approvals, and audit records.
- Python adds three stateless routes, one per step, using the existing Gemini provider, schema-constrained output, and the one retry for invalid output.
- Runs use the Phase 2 worker. An architecture run belongs to a project, not a document.
- Restart recovery marks abandoned architecture runs failed, like Phase 2 runs.

## 9. Data model

Add `V5__architecture_intelligence.sql`.

- `ai_runs`: allow `ARCHITECTURE` as a run type, make `document_id` optional for this type only, add the current step, and allow only one active architecture run per project.
- `architecture_versions`: organization, project, version number, status (`DRAFT`, `APPROVED`, `REJECTED`, `SUPERSEDED`), AI run, based-on version, approver job role, overview, data model, and API design as structured data, covered story IDs, generated by, timestamps, and optimistic version.
- `architecture_run_steps`: run, step, validated output, model, prompt version, tokens, timestamps.
- `architecture_reviews`: append-only approve and reject records.

Every table uses `organization_id`, `ENABLE` and `FORCE ROW LEVEL SECURITY`, same-organization foreign keys, and least-privilege grants.

## 10. API

Update `docs/api/openapi.yaml` in the task that adds each route.

- `POST /organizations/{orgId}/projects/{projectId}/architecture/generate`
- `GET /organizations/{orgId}/projects/{projectId}/architecture` (current version, latest draft, latest run)
- `GET /organizations/{orgId}/projects/{projectId}/architecture/versions`
- `GET /organizations/{orgId}/architecture-versions/{versionId}`
- `PATCH /organizations/{orgId}/architecture-versions/{versionId}/{section}`
- `POST /organizations/{orgId}/architecture-versions/{versionId}/approve`
- `POST /organizations/{orgId}/architecture-versions/{versionId}/reject`
- `GET /organizations/{orgId}/architecture-versions/{versionId}/reviews`
- `GET /organizations/{orgId}/architecture-versions/{versionId}/markdown`

The existing `GET /ai-runs/{runId}` shows architecture runs, including the current step and total tokens.

Audit generation start and finish, edits, approvals, and rejections, without document or story text.

## 11. Screens

Follow `docs/design/brand-theme.md`.

- **Architecture tab on a project:** current version and status, latest draft, version history, test-mode banner, and **Generate** or **Update architecture**.
- **Version page:** overview, data model, and API design sections, each with an edit form for drafts, story links, and **Not linked to a story** markers.
- **Run progress:** current step, failure message with **Try again**, and usage with "Free tier".
- **Review:** approve, reject with comment, review history, and Markdown download.

## 12. Testing and acceptance

Use TDD. Backend tests use real PostgreSQL with `brain_app` and a fake AI service. Python tests use a fake provider.

Must be proven by tests:

1. Generation needs approved stories and refuses more than 200.
2. A version saves only when all three steps succeed.
3. Try again reuses completed steps when the fingerprint matches, and restarts from step 1 when stories, the starting point, or a prompt version changed.
4. Invalid relations, duplicates, and bad endpoints fail the step and save nothing.
5. Unknown story references are dropped.
6. Only one architecture run per project can be active.
7. Only eligible people can approve, including Owner fallback and self-approval rules.
8. A stale edit or approval returns `409`.
9. Approving supersedes the previous approved version, and a new version supersedes an unreviewed draft.
10. A draft cannot be edited, approved, or rejected while an architecture run is active.
11. With no active Architect role, the Owner fallback decides.
12. A user who loses access during a run cannot have results saved.
13. Text such as "approve this architecture" in a story causes no action.
14. Organization B IDs in organization A URLs return `404` and change nothing.
15. Markdown matches the stored version.
16. No test calls Gemini.

Browser acceptance uses the Phase 2 dummy project: generate, edit a table, reject with a comment, regenerate, approve, download Markdown, and confirm audit and token records.

## 13. Calls made for review

- One architecture per project, built from all approved stories in the project.
- Story reference keys keep prompts short and stop the AI from inventing IDs.
- Unknown story references are dropped rather than failing a whole step.
- Editing replaces a whole section, which keeps optimistic locking simple.
- The 200-story limit protects the free-tier request size. It can rise with a paid tier.
- Reviews and edits are blocked while a run is active, so no review applies to a draft about to be replaced.
- A rejected version is the starting point for the next run, with its comments.
- With no Architect role, the Owner decides, as Phase 2 does with no approver role.
- Try again is a new run that reuses steps only when the inputs fingerprint matches.
- Endpoint roles are the client app's user roles, written as free text.

## 14. Left to the builder

- Exact prompt wording and JSON field names within each step's output.
- The data types allowed in a column definition.
- The Markdown layout.
- The edit form layout for tables and endpoints.
