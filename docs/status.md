# Project Status

Branch: phase-1-foundation
Finished: Tasks 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12 and 13
Current task: 14
Blockers: none
Note: Task 12 passed automated tests and a browser check against a mock API. Its full manual test against the running backend is not confirmed yet, so cover filters, 409 reload and archived read-only in Task 14.
Note: The Task 13 audit screen passed its manual test as Owner against the running backend. Member and Viewer blocking has automated tests only.
Note: The backend audits organization create and update, project create, update and archive, and task delete. Task create and update are not audited.
Note: Sign up, login, sign out, password reset, organization onboarding and project screens passed manually against Supabase and the running backend. Refreshing an expired token and the Member and Viewer project controls have automated tests only, so check them against the running backend before Phase 1 acceptance.
Note: The project screen has no restore action yet, although the API can set an archived project back to `ACTIVE`.
Note: Supabase's built-in email service is rate limited. For manual tests, create users in the Dashboard with Auto Confirm User. Production needs custom SMTP.
Note: The Supabase bootstrap is done, and the Phase 1 Postman collection passed against the running backend.
Note: Database tests need Docker Desktop running. If `docker` is not on `PATH`, use `~/.docker/bin` with `DOCKER_HOST=unix://$HOME/.docker/run/docker.sock` and `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`.

## Rulings

- Organization PATCH changes `name` only, so the slug stays stable.
- `size` above 100 is capped at 100 rather than rejected.
- `Organization` includes the caller's `role`, so the UI can show the right controls without another request.
- Commits are made by the developer after review.
- Generated Spring test boilerplate was removed, because `PostgresTestBase` replaces it and the default context test would need a real database.
- Real `.env` values load only with the `local` Spring profile, so tests cannot reach Supabase.
