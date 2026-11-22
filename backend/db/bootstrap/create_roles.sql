-- Run once per database by an administrator, before Flyway:
--   psql "$ADMIN_URL" -v ON_ERROR_STOP=1 \
--     -v migrator_password="$DB_MIGRATOR_PASSWORD" \
--     -v app_password="$DB_APP_PASSWORD" \
--     -f db/bootstrap/create_roles.sql

SELECT format('CREATE ROLE brain_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS PASSWORD %L',
              :'migrator_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'brain_migrator')
\gexec

SELECT format('CREATE ROLE brain_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS PASSWORD %L',
              :'app_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'brain_app')
\gexec

-- A non-superuser administrator must be able to act as the role to assign it the schema.
GRANT brain_migrator TO CURRENT_USER;

CREATE SCHEMA IF NOT EXISTS brain AUTHORIZATION brain_migrator;

REVOKE ALL ON SCHEMA brain FROM PUBLIC;
GRANT USAGE ON SCHEMA brain TO brain_app;
