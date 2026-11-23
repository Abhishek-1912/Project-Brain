# Project Status

Branch: phase-1-5-team-approvals
Phase 1: Complete
Phase 1.5: Complete on this branch, not merged into `main`
Finished: Phase 1 Tasks 0 through 14, Phase 1.5 Tasks 1 through 12
Current task: Merge Phase 1.5, then plan Phase 2
Blockers: none
Phase 1 acceptance: On 2026-11-23, 135 backend tests, 83 frontend tests and the production build passed. The developer confirmed the browser flow against Supabase and the running backend.
Phase 1.5 acceptance: On 2026-11-23, 236 backend tests, 134 frontend tests, the production build and lint passed. A fresh review found no Critical issues, and its Important findings were fixed. The developer confirmed the two-user browser flow against Supabase and the running backend.
Database: Migration `V3` is applied to Supabase. Run the backend from this branch until it is merged, because `main` does not contain `V3`.
Decisions: `docs/decisions/0001-auth-and-tenant-isolation.md` and `docs/decisions/0002-team-and-approval-model.md`.

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
