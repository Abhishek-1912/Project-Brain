# ADR 0004: Knowledge Engine

Status: Accepted for dummy data only
Date: 2026-11-26
Builds on: ADR 0001, ADR 0002, ADR 0003, `docs/superpowers/specs/2026-11-26-phase-4-knowledge-engine-design.md`

## Context

Phases 2 and 3 produce reviewed project knowledge: requirement documents, clarification answers, approved stories, and the approved architecture. People need to ask questions about it, and later agents and MCP tools need to retrieve it. Knowledge must never cross organizations or projects, and unreviewed AI output must never become knowledge.

## Decision

- A new `knowledge` module owns the index, notes, search, and answers. The `requirement` module publishes `KnowledgeChanged` events and offers a read-only `KnowledgeSourceLookup`. Neither module writes the other's data.
- Only reviewed knowledge is indexed: uploaded requirement documents, answered clarification questions, approved or converted stories, the current approved architecture version, and notes written by people. Draft and rejected content, rejection comments, and AI answers are never indexed.
- An architecture source is keyed by its project, so approving a new version replaces the old one in the index.
- Embeddings are created locally by the Python AI service with `BAAI/bge-small-en-v1.5` (384 dimensions) through `fastembed`. No text leaves the machine for indexing. The model is cached in the folder named by `FASTEMBED_CACHE_PATH`.
- Vectors live in pgvector in the `extensions` schema, and every reference names that schema. Enabling the extension is a one-time privileged bootstrap step, `backend/db/bootstrap/enable_vector.sql`.
- Indexing runs in a bounded background worker. No database transaction is held while embedding. A source's chunks are replaced as a complete set in one transaction, and each attempt saves only if its timestamp token still matches, so a later change or deletion always wins.
- Search merges the top 20 vector matches and the top 20 keyword matches with reciprocal rank fusion (k = 60), and returns only relevant results: a keyword match, or cosine similarity of at least 0.6. Every query filters by organization and project, on top of row-level security.
- Ask answers only from at most 8 retrieved passages. Spring keeps only citations of passages it sent. An answer without a valid citation is shown as "Not found in this project's knowledge." At most 2 answers run at once.
- Any project member can ask and search. Only Owners and Admins, who hold `MANAGE_PROJECTS`, can write notes or rebuild the index. The design's word "managers" means this permission. There is no Manager access role.
- Audit records never contain document, note, question, or answer text.
- On startup, interrupted indexing is queued again, because indexing is safe to repeat. Like ADR 0003, this assumes one backend instance.

## Consequences

- Agents and MCP tools in later phases can use the search route without changes.
- A failed re-index keeps the previous complete version searchable until a retry succeeds. Content that stops being reviewed knowledge is removed straight away.
- The 0.6 relevance threshold has not yet been checked against the ten dummy questions or real content, and may need tuning.
- The embedding model is English-first, and the AI service needs about 64 MB of model files and the ONNX runtime.
- Running more than one backend needs a shared job queue for indexing, as for AI runs.

## Before real client knowledge

- Everything listed in ADR 0003 for real documents.
- A paid, no-training AI tier for answers, because questions and passages are sent to the answer model.
- Retention and deletion rules for notes, indexed chunks, and saved answers.
- Re-tuning the relevance threshold on representative content.
