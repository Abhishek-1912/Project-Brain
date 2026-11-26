# Phase 4: Knowledge Engine Design

Status: Draft for developer review
Date: 2026-11-26
Source: PRD roadmap ("Knowledge engine: RAG, embeddings, pgvector, Markdown") and `docs/superpowers/specs/2026-11-22-agent-training-evaluation-design.md` (agents first "retrieve relevant project knowledge"; client knowledge stays scoped to its organization and project; no unreviewed AI notes)
Builds on: ADR 0001, ADR 0002, ADR 0003, and the Phase 2 and Phase 3 designs

## 1. Intent

Each project collects reviewed knowledge across Phases 2 and 3: the PRD, clarification answers, approved stories, and the approved architecture. Phase 4 makes that knowledge searchable by meaning and by keyword, and lets a person ask a question and get a short answer that cites where it came from.

It serves two users:

- **People now.** A Knowledge screen in each project, with an Ask box and cited answers.
- **Agents and MCP later.** A search API that returns the same cited passages. Phases 5 and 6 build on it without changing it.

**Same test conditions as Phases 2 and 3.** Dummy data only, free tiers, no clients, and no spending limit.

**Build it as a working model first.** The flow in section 8 and every anti-goal in section 4 are required. Visual polish can come later.

## 2. Goals

- Index only reviewed project knowledge: requirement document text, answered clarification questions, approved stories, the current approved architecture version, and Markdown notes written by people.
- Index automatically, in the background, when knowledge is uploaded, answered, approved, or edited. Remove it from the index when it is superseded, rejected, or deleted.
- Search with both meaning (vector) and keywords, and merge the two result lists.
- Answer a question only from retrieved passages, with numbered citations. Say "not found in this project's knowledge" when the passages do not answer it.
- Let a manager rebuild a project's index, which also indexes knowledge approved before Phase 4.
- Record model, prompt version, tokens, and outcome for every answer.

## 3. Non-goals

- Agents, MCP, GitHub, or tool use (Phases 5 to 7).
- Lessons learned from reviews, playbooks, or shared company knowledge across organizations.
- Follow-up questions with memory, streaming answers, or re-ranking models.
- Languages other than English. The chosen embedding model is English-first.
- Searching code, uploaded images, or the web.
- Fixing deferred minor findings from earlier phases.

## 4. Anti-goals

The work has failed, even if screens function, if any of these is possible:

- A search or answer returns knowledge from another organization or another project.
- Draft, rejected, or superseded content is returned after it stops being reviewed knowledge.
- Deleted content stays searchable.
- An answer is shown as found without at least one valid citation, or cites a passage that was not retrieved for that question.
- Text in a document, note, or question causes the system to take an action.
- The Python service reads the database or receives a user's access token.
- A partly indexed source is searchable as if complete.

## 5. Constraints

- Keep the Spring modular monolith, Supabase PostgreSQL with RLS, Flyway, the OpenAPI contract, and the stateless Python AI service from ADR 0003.
- `V5` is applied to Supabase. All schema changes go in a new `V6` migration.
- Supabase runs PostgreSQL 17. The `vector` extension needs a privileged role, so enabling it is a one-time bootstrap step run by the developer, like `create_roles.sql`.
- Tests must never call Groq, Gemini, Supabase, or download an embedding model.
- AI text and notes are shown as plain text.

## 6. Approaches

| Approach | Trade-off |
|---|---|
| **A. Local embeddings plus keyword search (chosen)** | A small open-source embedding model runs inside the Python service. Free, no new key, and no text leaves the machine for indexing. Costs a heavier Python service and a one-time model download. |
| B. Gemini embeddings plus pgvector | Simplest code, but sends all project knowledge to Google's free tier and depends on Gemini rate limits while generation uses Groq. |
| C. PostgreSQL keyword search only | Cheapest, but misses matches with different wording, and skips the pgvector base agents need. |

The developer chose A on 2026-11-26.

## 7. Design

### 7.1 Ownership

A new Spring module, `knowledge`, owns the index, notes, search, and answers.

- The `requirement` module publishes a `KnowledgeChanged(orgId, projectId, sourceType, sourceId)` event from its `api` package after a relevant change commits. Phase 2 and Phase 3 code changes only to publish these events.
- The `knowledge` module reads the source content through a read-only `requirement.api` lookup. It never writes requirement data.
- ArchUnit's module rules stay in force.

### 7.2 What gets indexed

| Source type | Indexed when | Removed when |
|---|---|---|
| `DOCUMENT` | A requirement document is uploaded | Never in Phase 4, because documents cannot be deleted |
| `CLARIFICATION` | A question is answered | Its answer changes (re-indexed) or it is dismissed |
| `STORY` | A story is approved | Never, because approved stories are final |
| `ARCHITECTURE` | A version is approved | A newer version is approved. Only the current approved version is indexed |
| `NOTE` | A note is created or edited | The note is deleted |

Requirement documents count as reviewed because the uploader confirmed them as dummy test data. They are the project's source PRD.

### 7.3 Python AI service

Two new routes, behind the existing service token:

- `POST /v1/embed` takes up to 64 texts of up to 2,000 characters each, and returns one vector per text plus the model name and dimensions.
- `POST /v1/answer` takes a question and up to 8 numbered passages. It returns `{answer, citations, found}` and the usual usage block. The prompt treats passages as quoted data, never as instructions, and the model has no tools.

The embedding model is `BAAI/bge-small-en-v1.5` (384 dimensions), run on CPU through `fastembed` with ONNX. It downloads once on first start and is cached locally. Tests replace it with a fake that returns fixed vectors.

### 7.4 Storage (`V6`)

- `knowledge_sources`: one row per indexed item, with organization, project, source type and id, title, content hash, embedding model, status (`PENDING`, `INDEXING`, `READY`, `FAILED`), error code, and timestamps. One row per source.
- `knowledge_chunks`: the source's text in order, a generated English `tsvector` with a GIN index, and a `vector(384)` embedding with an HNSW cosine index.
- `knowledge_notes`: title, Markdown body of up to 20,000 characters, author, timestamps, and an optimistic `version`.
- `knowledge_answers`: question, answer, found flag, citations, provider, model, prompt version, tokens, error code, asker, and time.

Every table has RLS with `ENABLE` and `FORCE`, scoped by organization, with grants to `brain_app` only.

### 7.5 Indexing

- A change marks the source `PENDING`. A bounded worker, following the Phase 2 worker pattern, indexes it.
- Text is split along headings and paragraphs into chunks of about 1,000 characters, with about 150 characters of overlap.
- No database transaction is held while embedding. The chunks replace the old ones in one transaction, so a source is searched either as its old complete version or its new complete version. Only `READY` sources are searched.
- If the content hash is unchanged, nothing is re-embedded.
- If the source no longer qualifies by the time it is saved (rejected, superseded, or deleted), it is removed instead.
- On startup, sources left `INDEXING` return to `PENDING`, because indexing is safe to repeat. This uses the same narrow cross-tenant database function pattern as Phase 2 and assumes one backend instance.

### 7.6 Search

- `GET /organizations/{orgId}/projects/{projectId}/knowledge/search?q=&limit=` needs `VIEW` permission. It returns up to 20 passages with text, source type, source id, title, and score.
- Spring embeds the query, takes the top 20 vector matches and the top 20 keyword matches within that project, and merges them with reciprocal rank fusion (k = 60).
- Every query filters by both organization and project, on top of RLS.

### 7.7 Ask

- `POST /organizations/{orgId}/projects/{projectId}/knowledge/ask` needs `VIEW` permission and takes a question of up to 500 characters.
- It runs synchronously, because the person is waiting. It searches first, and if nothing relevant is found it answers "not found" without calling the language model.
- Otherwise it sends the top 8 passages to `/v1/answer`. Spring drops any citation that is not one of those passages. If no valid citation remains, the answer is shown as not found.
- At most 2 answers run at once. Beyond that, the user gets "busy, try again".
- The answer is saved to `knowledge_answers`. The audit log records `KNOWLEDGE_ASKED` with the answer id and found flag, never the question or answer text.

### 7.8 Notes and rebuild

- Owners, Admins, and Managers (`MANAGE_PROJECTS`) can create, edit, and delete notes in active projects. Changes are audited without the note body.
- They can also rebuild a project's index. Rebuild queues every qualifying source and removes any that no longer qualify.

### 7.9 Screens

A **Knowledge** tab in each project with three parts:

- **Ask**: a question box, the answer, and numbered citations that link to the source item.
- **Sources**: each indexed item with its status, plus Rebuild index for managers.
- **Notes**: list, create, edit, and delete for managers. Read-only for others.

## 8. Main flow

1. A manager approves a story or architecture version, answers a question, uploads a document, or writes a note.
2. The source is queued and indexed in the background, then shows as Ready.
3. Any project member asks a question.
4. Spring searches the project's knowledge, asks the language model to answer only from the passages, and checks the citations.
5. The member sees the answer with citations, or "not found in this project's knowledge".
6. When an item stops being reviewed knowledge, it stops appearing in search and answers.

## 9. Testing and acceptance

- Python: the embed and answer routes, input limits, safe error codes, and prompt-injection text in passages, with the model and provider faked.
- Backend: tenant and project isolation, permissions, each index and removal trigger, chunk replacement, citation validation, "not found", busy limit, restart recovery, and audit content. The test database uses the `pgvector/pgvector:pg17` image.
- Frontend: Ask, Sources, and Notes, including empty, loading, error, and read-only states.
- Acceptance: the developer enables `vector` on Supabase, applies `V6` after confirming, rebuilds the index for the dummy project, and checks a set of 10 written questions about the dummy PRD. Each expected source should appear in the top 5 search results, and answers should cite it.
- ADR 0004 records the knowledge boundary and indexing rules.

## 10. Left to the builder

- Exact chunking code, score thresholds for "not found", and prompt wording.
- Table column names, indexes beyond those named here, and API field names, following existing conventions.
- Screen layout, following `docs/design/brand-theme.md`.

## 11. Calls made by Claude, for review

- Requirement document text counts as reviewed knowledge.
- Ask is synchronous rather than a background run.
- Any project member can ask and search, using `VIEW`. Only managers can write notes and rebuild.
- Human notes are indexed without a separate approval step.
- Interrupted indexing is retried on startup rather than marked failed.
- The embedding model is `BAAI/bge-small-en-v1.5` at 384 dimensions.
