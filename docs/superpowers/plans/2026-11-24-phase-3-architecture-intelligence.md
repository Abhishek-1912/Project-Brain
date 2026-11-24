# Phase 3 Architecture Intelligence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn a project's approved stories into a versioned, human-approved architecture with an overview, data model, and API design.

**Architecture:** Spring runs one AI run with three ordered steps through the Phase 2 worker, validates each step, stores step results, and saves a version only when all three succeed. Python adds three stateless step routes on the existing Gemini provider. React shows versions, run progress, edit forms, review, and Markdown download.

**Tech Stack:** Java 17, Spring Boot, JdbcClient, Flyway, Testcontainers, PostgreSQL RLS, Python 3.12, FastAPI, Pydantic, google-genai, pytest, React, TypeScript, TanStack Query, Vitest.

**Spec:** `docs/superpowers/specs/2026-11-24-phase-3-architecture-intelligence-design.md`

## Global Constraints

- Work on branch `phase-3-architecture-intelligence`, cut from `main`.
- Commits are made only after developer review. Never read, print, or commit `.env`.
- Dummy data only, Gemini free tier, no clients, no spending limit.
- `V4` is applied to Supabase. Schema changes go only in `V5__architecture_intelligence.sql`. Do not edit earlier migrations.
- Do not apply `V5` to Supabase or restart the developer's backend from this branch until Task 8 confirmation.
- Tests must never call Gemini or Supabase.
- New Spring classes live in `com.projectbrain.requirement` with an `Architecture` prefix, so they can use the package-private AI run classes.
- Step names: `OVERVIEW`, `DATA_MODEL`, `API_DESIGN`, always run in that order.
- Prompt versions: `architecture-overview-v1`, `architecture-data-model-v1`, `architecture-api-v1`. Spring and Python must hold the same values.
- Version statuses: `DRAFT`, `APPROVED`, `REJECTED`, `SUPERSEDED`.
- Limits: at most 200 stories, and the serialized step input at most 200,000 characters. Rejection comments are 1 to 2000 characters after trimming.
- Column types: `uuid`, `text`, `integer`, `bigint`, `numeric`, `boolean`, `date`, `timestamptz`, `jsonb`.
- API field types: `string`, `integer`, `number`, `boolean`, `uuid`, `date`, `datetime`, `object`, `array`.
- Endpoint methods: `GET`, `POST`, `PUT`, `PATCH`, `DELETE`.
- New tenant tables use `organization_id`, `ENABLE` and `FORCE ROW LEVEL SECURITY`, same-organization foreign keys, and least-privilege grants.
- Add each route to `docs/api/openapi.yaml` in the task that implements it, using OpenAPI 3.1 null types.
- AI text is untrusted. React renders it as plain text. Audit metadata never contains story or AI text.

## Review Focus

1. Two people click **Generate architecture** at the same moment: one run starts, the other gets `409`.
2. Gemini rate-limits on step 2 after step 1 succeeded: the run fails with step 1's tokens recorded, and **Try again** resumes at step 2.
3. Spring restarts between steps: the run becomes `ABANDONED`, and **Try again** reuses the finished steps.
4. AI text contains pipes, backticks, `#`, or line breaks: the Markdown download stays readable and its tables don't break.
5. A project just over the story or character limit: generation is refused with a clear message and no run is created.

---

### Task 1: Python architecture step routes

**Files:**
- Modify: `ai-service/app/{schemas,prompts,generation,main}.py`
- Create: `ai-service/tests/test_architecture.py`

**Interfaces:**
- Produces: `POST /v1/architecture/overview`, `/v1/architecture/data-model`, `/v1/architecture/api-design`, each returning `{result, usage}` like `/v1/analyze`, and errors with `usage` like Phase 2.
- Request `ArchitectureStepRequest` (camelCase): `stories: [{key, title, asA, iWant, soThat, acceptanceCriteria, priority}]` (max 200), `startingPoint: {status: "APPROVED"|"REJECTED", overview, dataModel, apiDesign, rejectionComments: [str]} | null`, `overview` (required for data-model and api-design), `dataModel` (required for api-design).
- Results:
  - `ArchitectureOverview`: `summary`; `components[{name, responsibility, technology|null}]`; `techStack[{area, choice, reason}]`; `decisions[{title, decision, reason, alternatives[str]}]`; `risks[{text}]`. Each list at most 30.
  - `ArchitectureDataModel`: `tables[{name, description, columns[{name, type, nullable, description}], relations[{column, referencesTable, referencesColumn}], storyRefs[str]}]`, at most 60 tables, 1 to 50 columns, names matching `^[a-z][a-z0-9_]{0,62}$`, `type` from the column types.
  - `ArchitectureApiDesign`: `endpoints[{method, path, summary, requestFields[{name, type, required, description}], responseFields[...], roles[str], storyRefs[str]}]`, at most 150 endpoints, `path` starting with `/`, `roles` at most 10 of 1 to 60 characters.

- [ ] **Step 1:** Write failing tests:

```python
def test_each_step_requires_service_token(): ...
def test_overview_returns_result_and_usage_with_step_prompt_version(): ...
def test_data_model_requires_overview(): ...            # 422 INVALID_REQUEST
def test_api_design_requires_overview_and_data_model(): ...
def test_invalid_column_type_retries_once_then_invalid_output(): ...
def test_stories_and_starting_point_are_sent_only_as_untrusted_data(): ...
def test_request_over_200_stories_is_rejected_without_calling_provider(): ...
def test_request_over_200000_characters_is_rejected_without_calling_provider(): ...  # TEXT_TOO_LONG
def test_step_error_includes_usage(): ...
```

- [ ] **Step 2:** Run `cd ai-service && .venv/bin/pytest -q tests/test_architecture.py`. Expected: FAIL.
- [ ] **Step 3:** Add the schemas, three prompts with the pinned prompt versions, `run_architecture_step(provider, step, request) -> tuple[BaseModel, Usage]` in `generation.py`, and the routes. Reuse `_generate` and the JSON-payload separation from `prompts.py`. The size check measures the serialized request.
- [ ] **Step 4:** Run `cd ai-service && .venv/bin/pytest -q`. Expected: PASS.
- [ ] **Step 5:** After developer review, commit `feat: add architecture AI steps`.

### Task 2: V5 schema

**Files:**
- Create: `backend/src/main/resources/db/migration/V5__architecture_intelligence.sql`
- Create: `backend/src/test/java/com/projectbrain/ArchitectureSchemaTest.java`

**Interfaces:**
- `ai_runs`: add `ARCHITECTURE` to `run_type`; drop `NOT NULL` on `document_id`; add `CHECK ((run_type = 'ARCHITECTURE') = (document_id IS NULL))`; add `current_step text` limited to the step names; add `input_fingerprint text`; add unique index `ai_runs_one_active_architecture_per_project` on `(organization_id, project_id)` where `run_type = 'ARCHITECTURE' AND status IN ('QUEUED','RUNNING')`.
- `architecture_versions`: `id, organization_id, project_id, version_number (unique per project), status, ai_run_id, based_on_version_id, approver_job_role_id (nullable), overview jsonb, data_model jsonb, api_design jsonb, story_ids jsonb, generated_by, created_at, updated_at, version`. Partial unique indexes allow one `DRAFT` and one `APPROVED` per project.
- `architecture_run_steps`: `organization_id, ai_run_id, step, output jsonb, provider, model, prompt_version, input_tokens, output_tokens, reused_from_run_id, created_at`, unique `(organization_id, ai_run_id, step)`.
- `architecture_reviews`: append-only `id, organization_id, project_id, version_id, content_version, action ('APPROVED','REJECTED'), actor_id, job_role_id, approval_basis, self_approved, comment, created_at`, with the Phase 2 review checks.
- Grants: `brain_app` gets `SELECT, INSERT, UPDATE` on versions; `SELECT, INSERT` on steps and reviews.

- [ ] **Step 1:** Write failing tests:

```java
@Test void architectureRunNeedsNoDocumentButOthersDo() {}
@Test void projectCanHaveOnlyOneActiveArchitectureRun() {}
@Test void projectCanHaveOnlyOneDraftAndOneApprovedVersion() {}
@Test void architectureTablesAreTenantIsolated() {}
@Test void architectureReviewsAreAppendOnlyForBrainApp() {}
@Test void restartRecoveryFailsActiveArchitectureRuns() {}
```

- [ ] **Step 2:** Run `./mvnw -q test -Dtest=ArchitectureSchemaTest`. Expected: FAIL.
- [ ] **Step 3:** Write `V5`. The existing recovery function already covers all run types; prove it with the test.
- [ ] **Step 4:** Run `./mvnw -q verify`. Expected: BUILD SUCCESS.
- [ ] **Step 5:** After developer review, commit `feat: add architecture schema`.

### Task 3: Architecture input and step validation

**Files:**
- Create: `backend/src/main/java/com/projectbrain/requirement/{ArchitectureInputService,ArchitectureValidator,ArchitecturePrompts}.java`
- Modify: `AiServiceClient.java`, `HttpAiServiceClient.java`, fake AI clients in tests
- Test: `backend/src/test/java/com/projectbrain/requirement/{ArchitectureValidatorTest,HttpAiServiceClientTest}.java`, `backend/src/test/java/com/projectbrain/ArchitectureInputTest.java`

**Interfaces:**
- `AiServiceClient.architectureStep(String step, ArchitectureStepRequest request): GeneratedResult`, with record `ArchitectureStepRequest(List<StoryInput> stories, JsonNode startingPoint, JsonNode overview, JsonNode dataModel)` and `StoryInput(String key, String title, String asA, String iWant, String soThat, List<String> acceptanceCriteria, String priority)`.
- `ArchitecturePrompts.VERSIONS: Map<String, String>` with the pinned values.
- `ArchitectureInputService.load(UUID orgId, UUID projectId): ArchitectureInput`, inside an org transaction. `ArchitectureInput(List<StoryInput> stories, Map<String, UUID> storyIdsByKey, UUID startingVersionId, JsonNode startingPoint, String fingerprint)`. Throws `ConflictException` with no stories, `UnprocessableException` over 200 stories or over 200,000 serialized characters.
- Fingerprint: SHA-256 hex of canonical JSON of sorted `(storyId, storyVersion)`, starting version ID and its `version`, rejection comments, and `ArchitecturePrompts.VERSIONS`.
- `ArchitectureValidator.overview(JsonNode)`, `.dataModel(JsonNode, Map<String, UUID> storyIdsByKey)`, `.apiDesign(JsonNode, Map<String, UUID> storyIdsByKey)`, each returning the stored form with `storyRefs` replaced by `storyIds`. Throws `InvalidAiOutputException`. Also `.editedDataModel(JsonNode, Set<UUID> allowedStoryIds)` and `.editedApiDesign(...)`, which keep only allowed story IDs.

- [ ] **Step 1:** Write failing tests:

```java
@Test void relationToUnknownTableOrColumnIsInvalid() {}
@Test void duplicateTableColumnOrEndpointIsInvalid() {}
@Test void badMethodPathOrColumnTypeIsInvalid() {}
@Test void unknownStoryRefsAreDropped() {}
@Test void inputUsesApprovedAndConvertedStoriesOnly() {}
@Test void rejectedVersionAndCommentsBecomeTheStartingPoint() {}
@Test void approvedVersionIsTheStartingPointWhenNewestReviewIsApproval() {}
@Test void noStoriesReturns409() {}
@Test void over200StoriesOrCharacterLimitReturns422() {}   // Review Focus 5
@Test void fingerprintChangesWithStoriesStartingPointOrPromptVersions() {}
@Test void clientPostsStepAndReadsUsage() {}                // HttpAiServiceClientTest
```

- [ ] **Step 2:** Run `./mvnw -q test -Dtest='ArchitectureValidatorTest,ArchitectureInputTest,HttpAiServiceClientTest'`. Expected: FAIL.
- [ ] **Step 3:** Implement. Story keys are `S1`, `S2`, … in story creation order. Rejection comments come from the latest rejected version only.
- [ ] **Step 4:** Run the same tests. Expected: PASS.
- [ ] **Step 5:** After developer review, commit `feat: add architecture input and validation`.

### Task 4: Architecture runs, steps, and versions

**Files:**
- Create: `backend/src/main/java/com/projectbrain/requirement/{ArchitectureRunner,ArchitectureVersionStore,ArchitectureController}.java`
- Modify: `AiRunService.java`, `AiRunWorker.java`, `AiRunStore.java`, `docs/api/openapi.yaml`
- Test: `backend/src/test/java/com/projectbrain/ArchitectureGenerationApiTest.java`

**Interfaces:**
- Consumes: Task 3 interfaces, `AiRunWorker.Job`, `RequirementAccess`.
- Route: `POST /organizations/{orgId}/projects/{projectId}/architecture/generate` → `202 {runId}`, permission `MANAGE_PROJECTS`.
- `AiRunService.startArchitecture(UUID orgId, UUID projectId): RunAccepted`. In one transaction: take `pg_advisory_xact_lock` keyed on the project, require writable, load input, resolve the approver role with `JobRoleLookup.findActiveByName(orgId, "Architect")`, insert the run with its fingerprint, copy step rows from the most recent `FAILED` architecture run with the same fingerprint (setting `reused_from_run_id`), and audit `AI_RUN_STARTED`.
- `AiRunStore.addUsage(UUID orgId, UUID runId, AiUsage usage)`, `setStep(UUID orgId, UUID runId, String step)`, `succeedKeepingUsage(UUID orgId, UUID runId)`. `fail` adds tokens instead of replacing them.
- `ArchitectureRunner.run(AiRunWorker.Job job)`: for each step without a stored row: transaction 1 (`inOrgAs`, require writable, set step); AI call outside a transaction; transaction 2 (validate, insert step row, add usage). Then a final transaction: insert the version as `DRAFT` with the next version number, mark any other `DRAFT` `SUPERSEDED`, succeed the run, audit `AI_RUN_SUCCEEDED`. A step result whose `promptVersion` differs from `ArchitecturePrompts` fails as `INVALID_OUTPUT`.
- `RunView` gains `currentStep`; `documentId` becomes nullable in OpenAPI.

- [ ] **Step 1:** Write failing tests:

```java
@Test void generationRunsThreeStepsAndSavesOneDraft() {}
@Test void stepInputsCarryEarlierStepOutputs() {}
@Test void failedStepSavesNoVersionAndKeepsEarlierStepTokens() {}
@Test void tryAgainResumesFromFailedStepWhenFingerprintMatches() {}   // Review Focus 2
@Test void tryAgainRestartsWhenStoriesChanged() {}
@Test void abandonedRunCanBeResumed() {}                             // Review Focus 3
@Test void concurrentGenerateStartsOneRun() {}                      // Review Focus 1
@Test void newVersionSupersedesUnreviewedDraft() {}
@Test void memberCannotGenerate() {}
@Test void userWhoLosesAccessDuringRunSavesNothing() {}
@Test void archivedProjectCannotGenerate() {}
@Test void noArchitectRoleStoresNullApproverRole() {}
@Test void storyTextSayingApproveCausesNoAction() {}
@Test void anotherOrganizationGets404() {}
```

- [ ] **Step 2:** Run `./mvnw -q test -Dtest=ArchitectureGenerationApiTest`. Expected: FAIL.
- [ ] **Step 3:** Implement. Phase 2 run types keep their current behavior.
- [ ] **Step 4:** Run `./mvnw -q verify`. Expected: BUILD SUCCESS.
- [ ] **Step 5:** After developer review, commit `feat: add architecture generation runs`.

### Task 5: Reading, editing, and reviewing versions

**Files:**
- Create: `backend/src/main/java/com/projectbrain/requirement/{ArchitectureService,ArchitectureReviewService,ArchitectureMarkdown}.java`
- Modify: `ArchitectureController.java`, `docs/api/openapi.yaml`
- Test: `backend/src/test/java/com/projectbrain/ArchitectureReviewApiTest.java`, `backend/src/test/java/com/projectbrain/requirement/ArchitectureMarkdownTest.java`

**Interfaces:**
- `GET /projects/{projectId}/architecture` → `{current: VersionSummary|null, draft: VersionSummary|null, latestRun: AiRun|null}`.
- `GET /projects/{projectId}/architecture/versions` → page of `VersionSummary(id, versionNumber, status, createdAt, generatedBy)`, newest first.
- `GET /architecture-versions/{versionId}` → `ArchitectureVersion(id, projectId, versionNumber, status, basedOnVersionId, approverJobRoleId, overview, dataModel, apiDesign, storyIds, generatedBy, createdAt, updatedAt, version, canEdit, canReview)`.
- `PATCH /architecture-versions/{versionId}/{section}` where section is `overview`, `data-model`, or `api-design`; body `{content, version}`.
- `POST /architecture-versions/{versionId}/approve` with `{version}`; `POST .../reject` with `{version, comment}`.
- `GET /architecture-versions/{versionId}/reviews` → page of reviews, oldest first.
- `GET /architecture-versions/{versionId}/markdown` → `text/markdown`.
- Edit, approve, and reject take the same project advisory lock as generation, and return `409` while an architecture run is active.
- Editors: `RequirementAccess.requireEditor` with the version's approver role. Approvers: `ProjectTeamLookup.approvers` with the version's approver role, `generatedBy` as submitter, and `ApprovalSettings.allowsSelfApproval`.
- `ArchitectureMarkdown.render(ArchitectureVersion version, List<ReviewView> reviews): String`.

- [ ] **Step 1:** Write failing tests:

```java
@Test void stateShowsCurrentDraftAndLatestRun() {}
@Test void architectEditsDraftSectionWithVersion() {}
@Test void staleEditOrApprovalReturns409() {}
@Test void editedContentPassesSameChecks() {}
@Test void reviewedOrSupersededVersionIsReadOnly() {}
@Test void draftCannotBeChangedWhileRunIsActive() {}
@Test void eligibleArchitectApprovesAndOldApprovedIsSuperseded() {}
@Test void ownerFallbackAndSelfApprovalAreRecorded() {}
@Test void selfApprovalOffBlocksGenerator() {}
@Test void rejectRequiresComment() {}
@Test void canEditAndCanReviewMatchTheRules() {}
@Test void markdownMatchesStoredVersion() {}
@Test void markdownEscapesTableBreakingText() {}                     // Review Focus 4
@Test void anotherOrganizationGets404() {}
```

- [ ] **Step 2:** Run `./mvnw -q test -Dtest='ArchitectureReviewApiTest,ArchitectureMarkdownTest'`. Expected: FAIL.
- [ ] **Step 3:** Implement. Audit `ARCHITECTURE_EDITED`, `ARCHITECTURE_APPROVED`, and `ARCHITECTURE_REJECTED` without content or comments.
- [ ] **Step 4:** Run `./mvnw -q verify`. Expected: BUILD SUCCESS.
- [ ] **Step 5:** After developer review, commit `feat: add architecture review`.

### Task 6: Architecture screens

**Files:**
- Create: `frontend/src/features/architecture/{useArchitecture.ts,ArchitecturePage.tsx,ArchitectureVersionView.tsx}` and tests
- Modify: `frontend/src/api/schema.d.ts`, `AppRoutes.tsx`, `features/tasks/TaskListPage.tsx`

**Interfaces:**
- Routes: `/orgs/:orgId/projects/:projectId/architecture` and `/orgs/:orgId/projects/:projectId/architecture/versions/:versionId`.
- Hooks: `useArchitecture`, `useArchitectureVersions`, `useArchitectureVersion`, `useGenerateArchitecture`. Reuse `useAiRun`, `isActiveRun`, `runErrorMessage`, and `TestModeBanner` from `features/requirements`.

- [ ] **Step 1:** Run `npm run api:types`, then write failing tests:

```ts
it("shows the test banner, current version, draft, and history");
it("shows Generate only to owners and admins");
it("polls a run and names the current step");
it("offers try again after a failed run");
it("shows the three sections with story links and Not linked to a story");
it("renders AI text as plain text");
```

- [ ] **Step 2:** Run `npm test`. Expected: FAIL.
- [ ] **Step 3:** Implement with the brand theme. Add an **Architecture** link beside **Requirements**.
- [ ] **Step 4:** Run `npm test && npm run build && npm run lint`. Expected: PASS.
- [ ] **Step 5:** After developer review, commit `feat: add architecture screens`.

### Task 7: Edit, review, and download screens

**Files:**
- Create: `frontend/src/features/architecture/{SectionEditor.tsx,ArchitectureReview.tsx}` and tests
- Modify: `useArchitecture.ts`, `ArchitectureVersionView.tsx`

**Interfaces:**
- Hooks: `useUpdateArchitectureSection`, `useApproveArchitecture`, `useRejectArchitecture`, `useArchitectureReviews`, `downloadArchitectureMarkdown(orgId, versionId): Promise<void>`.

- [ ] **Step 1:** Write failing tests:

```ts
it("edits a table and sends the section with its version");
it("adds and removes columns and endpoints");
it("keeps typed text and offers reload on 409");
it("requires a comment before rejecting");
it("shows approve only when canReview is true");
it("blocks review while edits are unsaved or a run is active");
it("downloads the version as Markdown");
```

- [ ] **Step 2:** Run `npm test`. Expected: FAIL.
- [ ] **Step 3:** Implement form-based editing for components, tech stack, decisions, risks, tables with columns and relations, and endpoints with fields.
- [ ] **Step 4:** Run `npm test && npm run build && npm run lint`. Expected: PASS.
- [ ] **Step 5:** After developer review, commit `feat: add architecture review screens`.

### Task 8: Phase 3 acceptance

**Files:**
- Modify: `docs/status.md`

- [ ] **Step 1:** Run `cd ai-service && .venv/bin/pytest -q`. Expected: PASS.
- [ ] **Step 2:** Run `cd backend && ./mvnw -q verify`. Expected: BUILD SUCCESS.
- [ ] **Step 3:** Run `cd frontend && npm test && npm run build && npm run lint`. Expected: PASS.
- [ ] **Step 4:** Get developer confirmation, then apply `V5` to Supabase by restarting the backend from this branch, and start the AI service with only its own settings.
- [ ] **Step 5 [YOU]:** In the Phase 2 dummy project: generate, watch the three steps, edit a table, reject with a comment, regenerate, approve, download Markdown, and check audit and token records.
- [ ] **Step 6:** Update `docs/status.md`, then commit after developer review.
