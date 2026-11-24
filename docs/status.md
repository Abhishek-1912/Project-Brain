# Project Status

Branch: main
Phase 1: Complete
Phase 1.5: Complete and merged into `main` through PR #2
Phase 2: Working model complete for dummy data only, and merged into `main` through PR #4
Finished: Phase 1 Tasks 0 through 14, Phase 1.5 Tasks 1 through 12, Phase 2 Tasks 1 through 10
Current task: Plan Phase 3
Blockers: none
Phase 1 acceptance: On 2026-11-23, 135 backend tests, 83 frontend tests and the production build passed. The developer confirmed the browser flow against Supabase and the running backend.
Phase 1.5 acceptance: On 2026-11-23, 236 backend tests, 134 frontend tests, the production build and lint passed. A fresh review found no Critical issues, and its Important findings were fixed. The developer confirmed the two-user browser flow against Supabase and the running backend.
Phase 2 acceptance: On 2026-11-23, the developer confirmed the browser flow with the dummy PRD, Gemini free tier, Supabase and the running services. A fresh review then found no Critical issues, and its six Important findings were fixed. After those fixes, 63 AI service tests, 341 backend tests, 156 frontend tests, the production build and lint passed. The fixes were not repeated in the live browser flow.
Database: Migration `V4` is applied to Supabase and is included in `main`.
Decisions: `docs/decisions/0001-auth-and-tenant-isolation.md`, `docs/decisions/0002-team-and-approval-model.md` and `docs/decisions/0003-ai-service-boundary.md`.

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
- The AI service has no committed start script yet. It must be started with only `GEMINI_API_KEY`, `AI_SERVICE_TOKEN`, and optional `GEMINI_MODEL`.

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
