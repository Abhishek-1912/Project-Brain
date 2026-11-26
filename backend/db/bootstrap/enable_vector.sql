-- Run once per database by an administrator, after create_roles.sql and before Flyway applies V6:
--   psql "$ADMIN_URL" -v ON_ERROR_STOP=1 -f db/bootstrap/enable_vector.sql
-- Supabase already has an extensions schema. Migrations always name it, so no search_path change is needed.

CREATE SCHEMA IF NOT EXISTS extensions;
CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA extensions;

GRANT USAGE ON SCHEMA extensions TO brain_migrator, brain_app;
