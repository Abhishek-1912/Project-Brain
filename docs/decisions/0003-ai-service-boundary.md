# ADR 0003: AI Service Boundary

Status: Accepted for dummy data only
Date: 2026-11-23
Builds on: ADR 0001, ADR 0002, `docs/superpowers/specs/2026-11-23-phase-2-requirement-intelligence-design.md`

## Context

Phase 2 turns a PRD into an analysis and user stories with AI. Document text is untrusted, AI output can be wrong, and the product must stay safe across organizations. The working model uses the Google Gemini Flash free tier, where people may review content and Google may use it to improve its products.

## Decision

**Spring owns data and decisions**

- Spring is the only service that reads or writes the database, checks permissions, and writes audit records.
- The Python AI service is stateless. It extracts text and calls Gemini. It has no database access, no user tokens, and no tools.
- Spring calls Python over a private connection with a service token. Python returns safe error codes and never echoes document text.

**AI runs**

- A run is a database row processed by a bounded in-process worker. A document can have one active run.
- No database transaction is held during an AI call. The worker rechecks the requesting user's access and the project's state before saving.
- A result is saved completely or not at all.
- On startup, runs left active are marked `ABANDONED`. This assumes one backend instance.

**AI output is a draft**

- Spring validates every result and checks source quotes against the document itself.
- AI can only create draft stories. Only an eligible person can approve or reject them, using the rules in ADR 0002.
- An unknown job role from AI is saved as no role.
- Approved stories become `BACKLOG` tasks once, enforced by row locks and a unique database constraint.

## Consequences

- Prompt injection in a document cannot trigger an action, because the AI has no tools and no path to approval.
- AI providers can change without moving data or permission logic out of Spring.
- Running more than one backend needs a different recovery approach and a shared job queue.

## Before real client documents

This decision does not approve real documents. First add a paid Gemini tier, private file storage, spending limits, retention and deletion rules, and client consent.
