# Project Status

Branch: phase-1-foundation
Phase 1: Complete
Finished: Tasks 0 through 14
Current task: Plan Phase 1.5 (Team and Approvals)
Blockers: none
Acceptance: On 2026-11-23, 135 backend tests, 83 frontend tests and the production build passed. No privileged key was found in frontend history or the build. The developer confirmed the browser acceptance flow against Supabase and the running backend.
Decision: Auth and tenant isolation are recorded in `docs/decisions/0001-auth-and-tenant-isolation.md`.

## Known gaps

- Member and Viewer UI access has automated tests only, because the app has no invite screen yet.
- Refreshing an expired token has automated tests only.
- The project screen has no restore action, although the API can set an archived project back to `ACTIVE`.
- Task create and update are not audited. Organization create and update, project create, update and archive, and task delete are audited.
- Production needs custom SMTP, because Supabase's built-in email service is rate limited. For manual tests, create users in the Dashboard with Auto Confirm User.
- The production JavaScript bundle is above Vite's 500 kB warning limit.

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
