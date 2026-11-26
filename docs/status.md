# Project Status

Branch: phase-4-knowledge-engine
Phase 1: Complete
Phase 1.5: Complete and merged into `main` through PR #2
Phase 2: Working model complete for dummy data only, and merged into `main` through PR #4
Phase 3: Working model complete for dummy data only, and merged into `main` through PR #6
Phase 4: Working model built for dummy data only, on branch `phase-4-knowledge-engine`. The browser check has not been done yet, and the whole-branch review is next.
Finished: Phase 1 Tasks 0 through 14, Phase 1.5 Tasks 1 through 12, Phase 2 Tasks 1 through 10, Phase 3 Tasks 1 through 8, Phase 4 Tasks 1 through 9
Current task: Phase 4 whole-branch review
Blockers: none
Phase 1 acceptance: On 2026-11-23, 135 backend tests, 83 frontend tests and the production build passed. The developer confirmed the browser flow against Supabase and the running backend.
Phase 1.5 acceptance: On 2026-11-23, 236 backend tests, 134 frontend tests, the production build and lint passed. A fresh review found no Critical issues, and its Important findings were fixed. The developer confirmed the two-user browser flow against Supabase and the running backend.
Phase 2 acceptance: On 2026-11-23, the developer confirmed the browser flow with the dummy PRD, Gemini free tier, Supabase and the running services. A fresh review then found no Critical issues, and its six Important findings were fixed. After those fixes, 63 AI service tests, 341 backend tests, 156 frontend tests, the production build and lint passed. The fixes were not repeated in the live browser flow.
Phase 3 acceptance: On 2026-11-24, the developer confirmed the architecture browser flow (generate, edit, reject, regenerate, approve, Markdown download, audit) with Supabase, using Groq's `openai/gpt-oss-120b`. A fresh review then found no Critical issues, and its Important findings were fixed. After those fixes, 92 AI service tests, 399 backend tests, 171 frontend tests, the production build and lint passed. The fixes were not repeated in the live browser flow.
Phase 4 acceptance: On 2026-11-26, 107 AI service tests, 458 backend tests, 191 frontend tests, the production build and lint passed. The developer enabled pgvector on Supabase, `V6` was applied, and the real embedding route returned 384-dimension vectors. The browser check, Part 3 of `docs/testing/browser-acceptance.md`, has not been done yet. A fresh review then found two Critical and three Important issues. Both Critical issues and two Important ones were fixed, and the HNSW recall issue is deferred as a known gap. After the fixes, 107 AI service tests, 463 backend tests and 191 frontend tests passed.
AI provider: `AI_PROVIDER` chooses `groq` or `gemini` (default). Local development currently uses Groq's free tier.
Database: Migration `V6` and pgvector 0.8.2 are applied to Supabase. `V6` is on branch `phase-4-knowledge-engine`, not yet in `main`.
Embeddings: The AI service uses local `BAAI/bge-small-en-v1.5` through `fastembed`. Start it with `FASTEMBED_CACHE_PATH=$HOME/.cache/fastembed`, or it downloads the model again into a temporary folder.
Decisions: `docs/decisions/0001-auth-and-tenant-isolation.md`, `docs/decisions/0002-team-and-approval-model.md`, `docs/decisions/0003-ai-service-boundary.md` and `docs/decisions/0004-knowledge-engine.md`.

## Known gaps

- Refreshing an expired token has automated tests only.
- The project screen has no restore action, although the API can set an archived project back to `ACTIVE`.
- Task create and update are not audited as general edits. Review actions and organization, project, team, invite and task deletion changes are audited.
- Production needs custom SMTP for sign-up and password email, because Supabase's built-in email service is rate limited. For manual tests, create users in the Dashboard with Auto Confirm User.
- Supabase **Confirm email** must stay enabled wherever invites are used.
- The production JavaScript bundle is above Vite's 500 kB warning limit.
- Database policies do not independently stop arbitrary SQL as `brain_app` from changing roles inside the current organization.
- The My reviews badge can stay stale after a team change until it refetches, and can briefly show the previous organization's count.
- Editing a role and another field on an old `DONE` task without a role shows a confusing message.
- Invites created by a later-removed Admin stay valid until cancelled or expired.
- Phase 2 is for dummy data only. Real documents need a paid Gemini tier, private file storage, spending limits, retention and deletion rules, and client consent.
- AI run recovery assumes one backend instance. A second instance starting up would mark the first instance's active runs as failed.
- Regenerating stories replaces all drafts, including hand-edited drafts, after a confirmation.
- Ten minor Phase 2 review findings are deferred, including differing Python and Spring story limits, older answers on dismissed repeat questions, very short verified quotes, and story-run usage not shown on screen.
- The AI service has no committed start script yet. It must be started with only its own settings: `AI_PROVIDER`, the chosen provider's key and model, and `AI_SERVICE_TOKEN`.
- Groq's free tier allows about 8,000 tokens per minute for `openai/gpt-oss-120b`, so larger architecture runs can hit rate limits or be cut off. Try again reuses finished steps.
- Eleven minor Phase 3 review findings are deferred, including unknown fields kept in edited sections, Markdown paragraphs starting with "1. " rendering as lists, and the database not tying reviews, versions, and reused steps to the same project (a fix would need a `V6` migration).
- Phase 4 has not been checked in a browser. All pending browser checks are in `docs/testing/browser-acceptance.md`.
- Knowledge relevance uses a cosine similarity of at least 0.6, not yet checked against the ten dummy questions. An unrelated sentence pair scored 0.58 with the real model, so real content may need a higher threshold.
- Knowledge indexing recovery assumes one backend instance, like AI runs.
- The knowledge HNSW vector index filters by organization and project after its approximate scan, so recall could drop at multi-tenant volume. Check before real tenants.
- Eight minor Phase 4 review findings are deferred, including lists capped at 100 sources and notes, sources not polling right after a rebuild or note save, and a rebuild racing a fresh approval.
- The embedding model is English-first.
- Knowledge search has an API but no screen of its own. Ask uses it.
- Story and clarification citations link to the project's requirements list, not to the exact item.
- Test database pools are limited to 6 connections each, because cached Spring test contexts otherwise exhaust PostgreSQL's connection limit.
- Architecture output is a design document only. It does not generate code, migrations, or diagrams, and is not checked against real code.

## Development notes

- The Supabase bootstrap is done, and the Phase 1 Postman collection passed against the running backend.
- Database tests need Docker Desktop running. If `docker` is not on `PATH`, use `~/.docker/bin` with `DOCKER_HOST=unix://$HOME/.docker/run/docker.sock` and `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`.

## Rulings

- Organization PATCH changes `name` only, so the slug stays stable.
- `size` above 100 is capped at 100 rather than rejected.
- `Organization` includes the caller's `role`, so the UI can show the right controls without another request.
- Commits are made by the developer after review.
- Generated Spring test boilerplate was removed, because `PostgresTestBase` replaces it and the default context test would need a real database.
- Real `.env` values load only with the `local` Spring profile, so tests cannot reach Supabase.
