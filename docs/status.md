# Project Status

Branch: phase-1-foundation
Finished: Tasks 0, 1 and 2 (Tasks 1 and 2 awaiting commit)
Current task: 3
Blockers: none
Note: PostgresTestBase compiles but first runs in Task 3, which adds the role bootstrap script.

## Rulings

- Organization PATCH changes `name` only, so the slug stays stable.
- `size` above 100 is capped at 100 rather than rejected.
- `Organization` includes the caller's `role`, so the UI can show the right controls without another request.
- Commits are made by the developer after review.
- Generated Spring test boilerplate was removed, because `PostgresTestBase` replaces it and the default context test would need a real database.
- Real `.env` values load only with the `local` Spring profile, so tests cannot reach Supabase.
