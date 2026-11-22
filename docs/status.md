# Project Status

Branch: phase-1-foundation
Finished: Tasks 0, 1, 2, 3, 4, 5, 6, 7, 8 and 9
Current task: 10
Blockers: none
Note: Sign up, login, sign out and password reset passed manually against Supabase. The API client's token refresh has automated tests only, so check it against the running backend in Task 10.
Note: The Supabase bootstrap is done, and the Phase 1 Postman collection passed against the running backend.
Note: Database tests need Docker Desktop running. If `docker` is not on `PATH`, use `~/.docker/bin` with `DOCKER_HOST=unix://$HOME/.docker/run/docker.sock` and `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`.

## Rulings

- Organization PATCH changes `name` only, so the slug stays stable.
- `size` above 100 is capped at 100 rather than rejected.
- `Organization` includes the caller's `role`, so the UI can show the right controls without another request.
- Commits are made by the developer after review.
- Generated Spring test boilerplate was removed, because `PostgresTestBase` replaces it and the default context test would need a real database.
- Real `.env` values load only with the `local` Spring profile, so tests cannot reach Supabase.
