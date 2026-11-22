# ADR 0001: Auth and Tenant Isolation

Status: Accepted
Date: 2026-11-23

## Context

Project Brain stores work for many organizations in one PostgreSQL database. A bug in one query must not expose another organization's data. The React app runs in the browser, so it cannot hold a privileged database or Supabase key.

## Decision

**Authentication**

- Supabase Auth handles sign-up, login, password reset and token refresh.
- React uses only the Supabase publishable key and sends its access token to the Spring Boot API. It does not query business tables directly.
- Spring Boot is a stateless resource server. It verifies each token's signature through JWKS (`ES256` or `RS256`), expiry, issuer, the `authenticated` audience, a canonical UUID subject and a usable email.
- After a `401`, the client refreshes once and retries. A second `401` signs the user out of that device.

**Authorization**

- Access roles are `OWNER`, `ADMIN`, `MEMBER` and `VIEWER`. `Role.java` is the source of truth for their permissions.
- The API enforces every permission. The UI hides controls for clarity only.
- A non-member gets `404`, so the API does not reveal that an organization exists. A member without permission gets `403`.

**Tenant isolation**

- `OrgTx` runs each organization request in one transaction. It checks membership and role, then sets `app.current_user_id` and `app.current_org_id` with `set_config(..., true)`, so the context ends with the transaction.
- Every table in schema `brain` uses `ENABLE` and `FORCE ROW LEVEL SECURITY`. Missing context matches no rows.
- The runtime role `brain_app` owns no tables and has no `BYPASSRLS`. Schema changes run through Flyway as `brain_migrator`.
- `brain_app` can only read and insert audit events, so the application cannot edit or delete audit history.

## Consequences

- Isolation has two independent layers: application checks and database policies.
- All data access must go through the API and `OrgTx`, which adds a small cost to each request.
- New tenant tables must add RLS policies and grants in a Flyway migration.
- The frontend role map mirrors `Role.java` and must change with it.

## Alternatives rejected

- **React querying Supabase directly:** this would spread business rules across the browser and database policies.
- **Application filtering only:** one missed `organization_id` filter could leak data.
- **One schema or database per tenant:** this adds migration and operating cost that Phase 1 does not need.
