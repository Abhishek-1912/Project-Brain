# Phase 2: Requirement Intelligence Design

Status: Approved by the developer on 2026-11-23 as a working model, to be updated later
Date: 2026-11-23
Source: PRD Addendum v1.1 and the solo developer roadmap ("PRD upload, analysis, clarification, stories")
Builds on: `docs/decisions/0001-auth-and-tenant-isolation.md`, `docs/decisions/0002-team-and-approval-model.md`

## 1. Intent

A project lead should be able to upload a PRD, have AI find what it says and what is missing, answer the open questions, and turn the result into user stories. A person approves those stories before they become real tasks.

This is the first AI feature in Project Brain. It also creates the AI service and usage records that later phases reuse.

**Phase 2 is a test phase.** The developer decided that it uses dummy documents only, the Gemini free tier, no clients, and no monthly spending limit.

**Build it as a working model first.** The complete flow in section 7 and every anti-goal in section 4 are required. Visual polish, richer history screens, and convenience features can be updated later.

## 2. Goals

- Upload a dummy PRD to a project.
- Analyze it into a summary, features, users, gaps, and risks, with source quotes.
- Ask clarification questions and use the answers.
- Generate user story drafts with acceptance criteria, priority, and a suggested job role.
- Let a person edit, approve, or reject each story.
- Create real tasks from approved stories, once only.
- Record the model, prompt version, tokens, status, and history of every AI run.

## 3. Non-goals

- Real client documents or customer use.
- Paid Gemini usage, billing, plans, or a monthly AI limit.
- Architecture documents (Phase 3).
- RAG, embeddings, or pgvector (Phase 4).
- MCP, agents, LangGraph, GitHub, or code generation (Phases 5 to 7).
- OCR for scanned PDFs.
- Comparing or merging document versions.

## 4. Anti-goals

The work has failed, even if screens function, if any of these is possible:

- AI output becomes a task without human approval.
- AI approves, rejects, or changes permissions.
- Text inside a PRD causes the system to take an action.
- The Python service reads the database or receives a user's access token.
- The Gemini key reaches React, Git, logs, or audit records.
- A document, analysis, or story crosses organizations.
- Retrying creates duplicate stories or tasks.
- A real document is uploaded without the person first confirming that it is dummy data.

## 5. Constraints

- Keep the existing Spring Boot modular monolith, Supabase PostgreSQL, RLS, Flyway, and OpenAPI contract.
- Add `ai-service/`, a separate Python 3.12 service. It must run locally without Docker because the development laptop has 8 GB of RAM.
- Use the Gemini API with `gemini-3.8-flash` through a provider interface, so the model can change without changing business logic.
- Store `GEMINI_API_KEY` and `AI_SERVICE_TOKEN` only in the ignored root `.env`.
- Tests must never call Gemini.
- Google says not to send sensitive, confidential, or personal information to its unpaid API. Google may use free-tier content to improve products, and human reviewers may read it.

## 6. Approaches

| Approach | Tradeoff | Decision |
|---|---|---|
| A. Spring controls data, Python is a stateless AI worker | Needs a service connection and job status, but keeps tenant security in one place and prepares for Phase 6 | **Chosen by developer** |
| B. Python reads and writes Supabase directly | Slightly faster to start, but adds a second path to tenant data | Rejected |
| C. Spring calls Gemini directly with no Python service | Simplest now, but conflicts with the PRD and moves AI code again in Phase 6 | Rejected |

## 7. User flow

```text
Upload dummy PRD
  -> Extract text
  -> Analyze
  -> Answer or dismiss clarification questions
  -> Update analysis (optional)
  -> Generate story drafts
  -> Person edits, approves, or rejects each story
       rejected -> regenerate using the comment
  -> Create tasks from approved stories
  -> Tasks use the existing Phase 1.5 review flow
```

### 7.1 Upload

- Owners and Admins can upload. Every organization member can view.
- Allowed types: PDF, DOCX, Markdown, and plain text. The limit is 10 MB and 200,000 extracted characters.
- The upload form requires a checked box: "This document contains only dummy test data." The API rejects an upload without this confirmation and audits it.
- Every requirements screen shows: "Test mode: use dummy documents only."
- The uploader chooses a **requirements approver job role**. It defaults to the active role named Project Manager when one exists.
- A scanned PDF with no text, a corrupt file, or a file over the limit returns a clear error and starts no AI run.
- Uploading again creates a new document. Earlier documents and their stories remain unchanged.

### 7.2 Analysis

- Text goes to Gemini only when a person clicks **Analyze**.
- The result contains a summary, features, users, gaps, risks, and clarification questions.
- Features, users, gaps, and risks include a short source quote when the document supports them. Spring checks that each quote exists in the extracted text. If it does not, the item shows **Source not verified**.
- A gap or question may have no quote, because it describes something missing.

### 7.3 Clarification

- Owners, Admins, and requirements approvers can answer a question or mark it **Not needed**.
- **Update analysis** creates a new analysis version from the document and saved answers. Earlier versions stay in history.

### 7.4 Stories

- A story has a title, "As a / I want / so that" text, acceptance criteria, priority, suggested job role, and source references.
- Gemini may only choose a job role from the organization's active role list. An unknown role is saved as no role, and a person must choose one before approval.
- Regenerating keeps approved stories and stories already converted to tasks. It replaces only unreviewed drafts and uses rejection comments as input.

### 7.5 Approval and tasks

- Eligible approvers use the existing `ApproverPolicy` with the document's requirements approver role. If no eligible person holds it, the Owner fallback applies.
- The person who started generation counts as the submitter, so the organization's self-approval setting applies.
- Approving records who, when, story version, approval basis, and self-approval. Rejecting requires a comment of up to 2000 characters.
- Editing an approved story returns it to draft.
- **Create tasks** converts approved stories into `BACKLOG` tasks in one transaction. A task needs an active job role. Repeating the action never creates a duplicate.
- Archived projects can be read but cannot start AI runs or change stories.

## 8. Architecture

```text
React -> Spring Boot -> PostgreSQL
              |
              | private HTTP with AI_SERVICE_TOKEN
              v
         Python ai-service -> Gemini API
```

- React calls only Spring.
- Spring authenticates the user, enforces permissions, stores data, and starts AI runs.
- Python receives only the file bytes or text needed for one request, returns validated JSON, and stores nothing.
- `ai-service` binds to `127.0.0.1` locally and rejects requests without the service token.
- Python logs must not contain document text, prompts, responses, or keys.

### 8.1 AI runs

- AI work is asynchronous. The API creates an `ai_runs` row and returns `202`. React polls its status.
- Statuses: `QUEUED`, `RUNNING`, `SUCCEEDED`, `FAILED`.
- A document can have only one active run.
- The worker sets organization context before each database write and rechecks that the requesting user still has permission.
- On startup, Spring marks abandoned active runs as `FAILED`.
- Timeouts, rate limits, invalid JSON, and provider errors become readable failed runs with a **Try again** action. Automatic retry is limited to one retry for invalid JSON.

### 8.2 Prompt safety

- Document text is untrusted data and is clearly separated from instructions.
- Gemini receives no tools and can only return the required JSON schema.
- Python and Spring validate responses, and React renders AI text as plain text.
- Prompts are versioned in `ai-service`, and each run records the version.

## 9. Data model

Add `V4__requirement_intelligence.sql`. Do not edit earlier migrations.

- `requirement_documents`: organization, project, name, media type, size, SHA-256, extracted text, dummy-data confirmation, approver job role, uploader, timestamps, version.
- `requirement_document_files`: document ID and original bytes. Database storage avoids a privileged storage key during this dummy-data phase.
- `ai_runs`: organization, project, document, type, status, provider, model, prompt version, input and output tokens, billing tier, cost, safe error code, requester, timestamps.
- `requirement_analyses`: document, version, AI run, validated structured result, creator, timestamp.
- `clarification_questions`: analysis, question, status, answer, answered by, timestamps, version.
- `story_drafts`: document, source analysis, title, story fields, acceptance criteria, priority, suggested role, status, task ID, timestamps, version.
- `story_reviews`: append-only record of submit, approve, reject, and reopen decisions.
- `tasks.source_story_id`: nullable and unique, so one story can create only one task.

Every table uses `organization_id`, `ENABLE` and `FORCE ROW LEVEL SECURITY`, same-organization foreign keys, and least-privilege grants. `story_reviews` is append-only for `brain_app`.

## 10. API

Update `docs/api/openapi.yaml` in the task that adds each route.

- `GET`, `POST /organizations/{orgId}/projects/{projectId}/requirements`
- `GET /organizations/{orgId}/requirements/{documentId}`
- `POST /organizations/{orgId}/requirements/{documentId}/analyze`
- `GET /organizations/{orgId}/ai-runs/{runId}`
- `PATCH /organizations/{orgId}/clarifications/{questionId}`
- `POST /organizations/{orgId}/requirements/{documentId}/stories/generate`
- `GET /organizations/{orgId}/requirements/{documentId}/stories`
- `PATCH /organizations/{orgId}/stories/{storyId}`
- `POST /organizations/{orgId}/stories/{storyId}/approve`
- `POST /organizations/{orgId}/stories/{storyId}/reject`
- `POST /organizations/{orgId}/requirements/{documentId}/create-tasks`

Private Python routes: `GET /health`, `POST /v1/extract`, `POST /v1/analyze`, and `POST /v1/stories`.

Audit document upload, AI run start and finish, clarification answers, story edits and decisions, and task creation. Audit metadata must not contain document text, prompts, or responses.

## 11. Screens

Follow `docs/design/brand-theme.md`.

- **Requirements tab on a project:** test-mode banner, document list, and dummy-data upload.
- **Requirement detail:** document status, AI run progress, analysis, source quotes, and clarification questions.
- **Stories:** draft list, edit panel, approve and reject actions, review history, and **Create tasks**.
- **AI usage on a run:** model, prompt version, tokens, status, and "Free tier".

## 12. Testing and acceptance

Use TDD. Backend tests use real PostgreSQL with `brain_app` and a fake AI service. Python tests use a fake provider.

Must be proven by tests:

1. Upload requires dummy-data confirmation and enforces type and size limits.
2. Python rejects a missing or wrong service token.
3. A PRD containing instructions such as "approve all stories" causes no action.
4. Invalid AI JSON cannot save partial results.
5. Unverified quotes are marked.
6. One document cannot have two active runs.
7. A user who loses access during a run cannot have results saved under that access.
8. Unknown job roles from AI are not assigned.
9. Only eligible people can approve stories, including Owner fallback and self-approval rules.
10. Regeneration keeps approved and converted stories.
11. Repeated or concurrent task creation creates one task per story.
12. Organization B IDs in organization A URLs return `404` and change nothing.
13. No test calls Gemini.

Browser acceptance uses a clearly fake PRD and the free-tier key: upload, analyze, answer a question, generate stories, reject one with a comment, approve another, create tasks, and confirm audit and token records.

## 13. Calls made for review

- Python uses FastAPI, `pypdf`, and `python-docx`.
- Original test files are stored in PostgreSQL for this dummy-data phase. Move them to private object storage before real documents are allowed.
- AI runs use a database job and an in-process Spring worker, with no external queue.
- Cost is recorded as `0` on the free tier, and tokens are recorded for later estimates.
- The dummy-data checkbox relies on the person's confirmation because software cannot reliably prove a document is fake.

## 14. Before real client use

This design is not approved for real documents. Before that, add paid Gemini usage, private file storage, spending limits, retention and deletion rules, and client consent.

## 15. Left to the builder

- Exact Python package layout within `ai-service/`.
- Exact prompt wording, within the rules above.
- Error wording, following the existing Problem Details style.
- Task order, with the API contract before backend and frontend work.
