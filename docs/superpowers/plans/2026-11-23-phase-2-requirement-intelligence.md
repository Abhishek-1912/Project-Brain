# Phase 2 Requirement Intelligence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a working model that turns a dummy PRD into analyzed, clarified, human-approved user stories and real tasks.

**Architecture:** React calls only Spring Boot. Spring owns permissions, PostgreSQL data, AI run state, approvals, and task creation. A stateless Python 3.12 FastAPI service extracts text and calls Gemini through a provider interface.

**Tech Stack:** Java 17, Spring Boot, JdbcClient, Flyway, Testcontainers, PostgreSQL RLS, Python 3.12, FastAPI, Pydantic, pypdf, python-docx, google-genai, pytest, React, TypeScript, TanStack Query, Vitest.

**Spec:** `docs/superpowers/specs/2026-11-23-phase-2-requirement-intelligence-design.md`

## Global Constraints

- Work on branch `phase-2-requirement-intelligence`.
- Commits are made only after developer review. Never read, print, or commit `.env`.
- This phase uses dummy documents, the Gemini free tier, no clients, and no monthly AI limit.
- Model: `gemini-3.8-flash`, configured by `GEMINI_MODEL`, behind `AiProvider`.
- Environment names: `GEMINI_API_KEY`, `GEMINI_MODEL`, `AI_SERVICE_TOKEN`, `AI_SERVICE_URL`. Add names only to `.env.example`.
- Tests must never call Gemini.
- File limits: PDF, DOCX, Markdown, plain text; 10 MB; 200,000 extracted characters.
- Rejection comments are required, non-blank, and at most 2000 characters.
- Python has no database access, stores nothing, binds to `127.0.0.1`, and never logs document text, prompts, responses, or keys.
- Schema changes go only in `V4__requirement_intelligence.sql`.
- New tenant tables use `organization_id`, `ENABLE` and `FORCE ROW LEVEL SECURITY`, same-organization foreign keys, and least-privilege grants.
- Module rule: other modules are used only through their `api` package. Add `requirement` to `ArchitectureTest`.
- Add each route to `docs/api/openapi.yaml` in the task that implements it.
- AI text is untrusted. Gemini gets no tools, and React renders AI text as plain text.
- Working model: complete flow and all anti-goals are required; visual polish can wait.

## Review Focus

1. Gemini rate-limits during a run: the run becomes failed with **Try again**, and saved data stays unchanged.
2. Spring restarts during a run: the abandoned run becomes failed, and the document can run again.
3. Gemini returns cut-off or malformed JSON: nothing partial is saved.
4. Two people click **Create tasks** together: each approved story creates one task.
5. A correct source quote differs from the PRD only by line breaks or spacing: it is still verified.

---

### Task 1: AI service and text extraction

**Files:**
- Create: `ai-service/pyproject.toml`, `ai-service/app/{__init__,main,config,security,extract}.py`
- Create: `ai-service/tests/{conftest,test_security,test_extract}.py`
- Modify: `.gitignore`, `.env.example`

**Interfaces:**
- Produces: `GET /health -> {"status":"ok"}`.
- Produces: `POST /v1/extract`, multipart `file`, returning `{"text": str, "characters": int}`.
- Every `/v1/*` route requires `Authorization: Bearer <AI_SERVICE_TOKEN>`.
- Errors: `{"code":"UNSUPPORTED_TYPE"|"FILE_TOO_LARGE"|"NO_TEXT"|"TEXT_TOO_LONG"|"CORRUPT_FILE"}`.

- [ ] **Step 1 [YOU]:** Install Python 3.12: `brew install python@3.12`.
- [ ] **Step 2:** Create `ai-service/.venv` with Python 3.12 and install the project with test dependencies.
- [ ] **Step 3:** Write failing tests:

```python
def test_v1_rejects_missing_and_wrong_service_token(): ...
def test_health_needs_no_token_and_returns_no_config(): ...
def test_extracts_pdf_docx_markdown_and_text(): ...
def test_rejects_unsupported_type_and_mismatched_content(): ...
def test_rejects_file_over_10_mb(): ...
def test_rejects_scanned_pdf_with_no_text(): ...
def test_rejects_text_over_200000_characters(): ...
def test_rejects_corrupt_pdf_and_docx(): ...
def test_rejects_docx_that_expands_beyond_safe_limit(): ...
```

- [ ] **Step 4:** Run `cd ai-service && .venv/bin/pytest -q`. Expected: FAIL.
- [ ] **Step 5:** Implement. Compare tokens with `hmac.compare_digest`, and limit DOCX decompressed size before parsing.
- [ ] **Step 6:** Run `cd ai-service && .venv/bin/pytest -q`. Expected: PASS.
- [ ] **Step 7:** After developer review, commit `feat: add AI service text extraction`.

### Task 2: Gemini analysis and story generation

**Files:**
- Create: `ai-service/app/{schemas,provider,gemini,prompts,generation}.py`
- Create: `ai-service/tests/{test_analysis,test_stories,test_gemini}.py`
- Modify: `ai-service/app/main.py`

**Interfaces:**
- Produces: `class AiProvider(Protocol): def generate_json(self, system: str, document: str, schema: type[BaseModel]) -> ProviderResult`.
- Produces: `ProviderResult(data, model, input_tokens, output_tokens)`.
- Produces: `POST /v1/analyze` with `{text, answers:[{question, answer}]}`.
- Produces: `POST /v1/stories` with `{text, analysis, answers, activeJobRoles, rejectionComments, keptStoryTitles}`.
- Successful responses include `{result, usage:{provider, model, promptVersion, inputTokens, outputTokens}}`.
- Errors: `{"code":"RATE_LIMITED"|"PROVIDER_TIMEOUT"|"PROVIDER_ERROR"|"INVALID_OUTPUT"}`.

- [ ] **Step 1:** Write failing tests with a fake provider:

```python
def test_analysis_returns_required_sections(): ...
def test_story_role_outside_active_list_becomes_none(): ...
def test_invalid_json_retries_once_then_returns_invalid_output(): ...
def test_cut_off_response_returns_invalid_output(): ...
def test_document_instructions_are_sent_only_as_untrusted_data(): ...
def test_rate_limit_and_timeout_map_to_safe_codes(): ...
def test_errors_do_not_contain_document_text_or_key(): ...
```

- [ ] **Step 2:** Run `cd ai-service && .venv/bin/pytest -q`. Expected: FAIL.
- [ ] **Step 3:** Implement. Use `google-genai` structured JSON output with no tools, a 60-second timeout, and prompt versions `analysis-v1` and `stories-v1`.
- [ ] **Step 4:** Run `cd ai-service && .venv/bin/pytest -q`. Expected: PASS.
- [ ] **Step 5:** After developer review, commit `feat: add Gemini requirement generation`.

### Task 3: Requirement schema and RLS

**Files:**
- Create: `backend/src/main/resources/db/migration/V4__requirement_intelligence.sql`
- Modify: `backend/src/test/java/com/projectbrain/RlsIsolationTest.java`

**Interfaces:**
- Produces: tables and `tasks.source_story_id` from spec section 9.
- `ai_runs`: partial unique index on `(organization_id, document_id)` for `QUEUED` and `RUNNING`.
- `story_reviews`: `SELECT, INSERT` only for `brain_app`.

- [ ] **Step 1:** Add failing tests:

```java
@Test void organizationBCannotReadRequirementData() {}
@Test void appRoleCannotUpdateOrDeleteStoryReviews() {}
@Test void documentCannotHaveTwoActiveRuns() {}
@Test void oneStoryCannotCreateTwoTasks() {}
@Test void storiesCannotReferenceAnotherOrganizationsRoleOrTask() {}
@Test void uploadRowRequiresDummyDataConfirmation() {}
```

- [ ] **Step 2:** Run `cd backend && ./mvnw -q test -Dtest=RlsIsolationTest`. Expected: FAIL.
- [ ] **Step 3:** Write `V4`.
- [ ] **Step 4:** Run the same test. Expected: PASS.
- [ ] **Step 5:** After developer review, commit `feat: add requirement intelligence schema`.

### Task 4: Upload and document APIs

**Files:**
- Create: `backend/src/main/java/com/projectbrain/requirement/{RequirementController,RequirementService,AiServiceClient,AiServiceException}.java`
- Create: `backend/src/test/java/com/projectbrain/RequirementDocumentApiTest.java`
- Modify: `application.properties`, `ArchitectureTest.java`, `docs/api/openapi.yaml`

**Interfaces:**
- Produces: `AiServiceClient.extract(filename, contentType, bytes): ExtractedText`.
- Produces: list, upload, and detail routes from spec section 10.
- Upload multipart fields: `file`, `dummyDataConfirmed`, `approverJobRoleId`.

- [ ] **Step 1:** Write failing tests with a fake AI client:

```java
@Test void ownerAndAdminCanUploadDummyDocument() {}
@Test void memberCanViewButCannotUpload() {}
@Test void uploadRequiresDummyConfirmation() {}
@Test void invalidFileErrorsStoreNothing() {}
@Test void approverRoleMustBeActiveInOrganization() {}
@Test void archivedProjectBlocksUpload() {}
@Test void anotherOrganizationsDocumentReturns404() {}
@Test void auditDoesNotContainDocumentText() {}
```

- [ ] **Step 2:** Run `./mvnw -q test -Dtest=RequirementDocumentApiTest`. Expected: FAIL.
- [ ] **Step 3:** Implement. Call extraction outside a database transaction, and set a 10 MB multipart limit.
- [ ] **Step 4:** Run the tests plus `ArchitectureTest,ContractRoutesTest`. Expected: PASS.
- [ ] **Step 5:** After developer review, commit `feat: add requirement document upload`.

### Task 5: AI runs and analysis

**Files:**
- Create: `backend/src/main/java/com/projectbrain/requirement/{AiRunService,AiRunWorker,AnalysisService,QuoteVerifier}.java`
- Create: `backend/src/test/java/com/projectbrain/RequirementAnalysisApiTest.java`, `requirement/QuoteVerifierTest.java`
- Modify: `organization/api/OrgTx.java`, `audit/api/AuditLog.java`, `audit/AuditWriter.java`, `docs/api/openapi.yaml`

**Interfaces:**
- Produces: `OrgTx.inOrgAs(UUID userId, UUID orgId, Permission permission, Supplier<T> work)` for workers.
- Produces: `AuditLog.recordAs(UUID actorId, UUID orgId, String action, String entityType, UUID entityId, Map<String,Object> metadata)`.
- Produces: `QuoteVerifier.isVerified(String quote, String documentText): boolean`, normalizing case and whitespace.
- Produces: analyze route returning `202 {runId}`, and `GET /ai-runs/{runId}`.

- [ ] **Step 1:** Write failing tests:

```java
@Test void analyzeCreatesRunAndSavesValidatedResult() {}
@Test void documentCannotStartTwoActiveRuns() {}
@Test void providerFailureSavesSafeErrorAndNoPartialAnalysis() {}
@Test void userWhoLosesAccessDuringRunCannotSaveResult() {}
@Test void startupMarksAbandonedRunsFailed() {}
@Test void failedRunCanBeRetried() {}
@Test void usageIsRecordedAsFreeTierWithZeroCost() {}
@Test void quoteVerificationIgnoresCaseAndWhitespaceOnly() {}
@Test void unverifiedQuoteIsMarked() {}
```

- [ ] **Step 2:** Run `./mvnw -q test -Dtest=RequirementAnalysisApiTest,QuoteVerifierTest`. Expected: FAIL.
- [ ] **Step 3:** Implement with an in-process executor. Never hold a database transaction during an AI call.
- [ ] **Step 4:** Run the tests plus `ContractRoutesTest`. Expected: PASS.
- [ ] **Step 5:** After developer review, commit `feat: add requirement analysis runs`.

### Task 6: Clarifications and story generation

**Files:**
- Create: `backend/src/main/java/com/projectbrain/requirement/{ClarificationService,StoryService,StoryController}.java`
- Create: `backend/src/test/java/com/projectbrain/RequirementStoryApiTest.java`
- Modify: `AnalysisService.java`, `docs/api/openapi.yaml`

**Interfaces:**
- Consumes: `AiRunService`, `JobRoleLookup`, `ProjectTeamLookup`.
- Produces: clarification PATCH, story generation, story list, and story PATCH routes.

- [ ] **Step 1:** Write failing tests:

```java
@Test void approverCanAnswerOrDismissQuestion() {}
@Test void viewerCannotAnswerQuestion() {}
@Test void updateAnalysisCreatesNewVersionUsingAnswers() {}
@Test void generationSavesDraftStories() {}
@Test void unknownAiRoleIsNotAssigned() {}
@Test void regenerationKeepsApprovedAndConvertedStories() {}
@Test void rejectionCommentsAreSentToRegeneration() {}
@Test void editingApprovedStoryReturnsItToDraft() {}
@Test void staleStoryEditReturns409() {}
```

- [ ] **Step 2:** Run `./mvnw -q test -Dtest=RequirementStoryApiTest`. Expected: FAIL.
- [ ] **Step 3:** Implement spec sections 7.3 and 7.4.
- [ ] **Step 4:** Run the tests plus `ContractRoutesTest`. Expected: PASS.
- [ ] **Step 5:** After developer review, commit `feat: add clarifications and story drafts`.

### Task 7: Story approval and task creation

**Files:**
- Create: `backend/src/main/java/com/projectbrain/task/api/TaskCreator.java`
- Create: `backend/src/test/java/com/projectbrain/RequirementApprovalApiTest.java`
- Modify: `StoryService.java`, `StoryController.java`, `task/Task.java`, `task/TaskService.java`, `docs/api/openapi.yaml`

**Interfaces:**
- Produces: `TaskCreator.createFromStory(UUID orgId, UUID projectId, UUID storyId, String title, String description, String priority, UUID jobRoleId): UUID`.
- Produces: story approve, reject, and create-tasks routes.

- [ ] **Step 1:** Write failing tests:

```java
@Test void eligibleApproverCanApproveStory() {}
@Test void ownerFallbackAndSelfApprovalAreRecorded() {}
@Test void selfApprovalOffBlocksGenerator() {}
@Test void rejectRequiresComment() {}
@Test void staleApprovalReturns409() {}
@Test void storyWithoutActiveRoleCannotBeApproved() {}
@Test void createTasksConvertsOnlyApprovedStories() {}
@Test void concurrentCreateTasksCreatesOneTaskPerStory() {}
@Test void aiCannotApproveThroughDocumentInstructions() {}
@Test void anotherOrganizationsStoryReturns404() {}
```

- [ ] **Step 2:** Run `./mvnw -q test -Dtest=RequirementApprovalApiTest`. Expected: FAIL.
- [ ] **Step 3:** Implement. Lock stories during conversion, and create tasks as `BACKLOG`.
- [ ] **Step 4:** Run `./mvnw -q verify`. Expected: BUILD SUCCESS.
- [ ] **Step 5:** After developer review, commit `feat: add story approval and task creation`.

### Task 8: Requirements upload and analysis screens

**Files:**
- Create: `frontend/src/features/requirements/{useRequirements.ts,RequirementListPage.tsx,UploadRequirementDialog.tsx,RequirementDetailPage.tsx,AnalysisView.tsx}` and tests
- Modify: `frontend/src/api/schema.d.ts`, `AppRoutes.tsx`, `features/tasks/TaskListPage.tsx`

**Interfaces:**
- Produces: routes `/orgs/:orgId/projects/:projectId/requirements` and `/orgs/:orgId/projects/:projectId/requirements/:documentId`.
- Produces: `useRequirements`, `useRequirement`, `useUploadRequirement`, `useStartAnalysis`, `useAiRun`, `useAnswerClarification`.

- [ ] **Step 1:** Run `npm run api:types`, then write failing tests:

```ts
it("always shows the dummy-data test banner");
it("requires the dummy-data checkbox before upload");
it("hides upload from a member");
it("polls a run until analysis appears");
it("offers try again after a failed run");
it("marks an unverified source");
it("renders AI text as plain text");
it("answers and dismisses a clarification");
```

- [ ] **Step 2:** Run `npm test`. Expected: FAIL.
- [ ] **Step 3:** Implement with the brand theme.
- [ ] **Step 4:** Run `npm test && npm run build && npm run lint`. Expected: PASS.
- [ ] **Step 5:** After developer review, commit `feat: add requirement analysis screens`.

### Task 9: Story review and task creation screens

**Files:**
- Create: `frontend/src/features/requirements/{StoryList.tsx,StoryPanel.tsx}` and tests
- Modify: `useRequirements.ts`, `RequirementDetailPage.tsx`

**Interfaces:**
- Produces: `useStories`, `useGenerateStories`, `useUpdateStory`, `useApproveStory`, `useRejectStory`, `useCreateTasksFromStories`.

- [ ] **Step 1:** Write failing tests:

```ts
it("generates and lists story drafts");
it("edits a story with its version");
it("requires a comment before rejecting");
it("shows approve only to an eligible reviewer");
it("keeps typed text and offers reload on 409");
it("creates tasks only from approved stories");
it("does not offer task creation twice for a converted story");
```

- [ ] **Step 2:** Run `npm test`. Expected: FAIL.
- [ ] **Step 3:** Implement the working-model screens.
- [ ] **Step 4:** Run `npm test && npm run build && npm run lint`. Expected: PASS.
- [ ] **Step 5:** After developer review, commit `feat: add story review screens`.

### Task 10: Phase 2 acceptance

**Files:**
- Create: `docs/decisions/0003-ai-service-boundary.md`, `docs/test-data/dummy-prd.md`
- Modify: `docs/status.md`

- [ ] **Step 1:** Run `cd ai-service && .venv/bin/pytest -q`. Expected: PASS.
- [ ] **Step 2:** Run `cd backend && ./mvnw -q verify`. Expected: BUILD SUCCESS.
- [ ] **Step 3:** Run `cd frontend && npm test && npm run build && npm run lint`. Expected: PASS.
- [ ] **Step 4 [YOU]:** Add a free-tier `GEMINI_API_KEY` and a random `AI_SERVICE_TOKEN` to the root `.env`.
- [ ] **Step 5:** Get developer confirmation before applying `V4` to Supabase, then start all services.
- [ ] **Step 6 [YOU]:** With `docs/test-data/dummy-prd.md`, upload, analyze, answer a question, generate stories, reject one, approve another, create tasks, and check audit and token records.
- [ ] **Step 7:** Write the short ADR, mark Phase 2 complete as a working model, and commit after developer review.
