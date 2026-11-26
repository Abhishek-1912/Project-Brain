-- Phase 5: personal access tokens for the MCP endpoint. Only a SHA-256 hash of each secret is stored.

CREATE TABLE brain.access_tokens (
    id              uuid PRIMARY KEY,
    user_id         uuid NOT NULL REFERENCES brain.profiles (id),
    organization_id uuid NOT NULL REFERENCES brain.organizations (id),
    name            text NOT NULL CHECK (char_length(btrim(name)) BETWEEN 1 AND 100),
    token_hash      text NOT NULL UNIQUE CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    display_prefix  text NOT NULL CHECK (char_length(display_prefix) = 8),
    scope           text NOT NULL CHECK (scope IN ('READ', 'READ_WRITE')),
    expires_at      timestamptz NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    last_used_at    timestamptz,
    revoked_at      timestamptz,
    CONSTRAINT access_tokens_expiry_after_creation CHECK (expires_at > created_at)
);
CREATE INDEX access_tokens_owner_idx ON brain.access_tokens (organization_id, user_id, created_at DESC);

ALTER TABLE brain.access_tokens ENABLE ROW LEVEL SECURITY;
ALTER TABLE brain.access_tokens FORCE ROW LEVEL SECURITY;

-- A user sees and changes only their own tokens in the current organization.
CREATE POLICY access_tokens_owner ON brain.access_tokens FOR ALL TO brain_app
    USING (user_id = brain.current_user_id() AND organization_id = brain.current_org_id())
    WITH CHECK (user_id = brain.current_user_id() AND organization_id = brain.current_org_id());

-- Resolving a presented token happens before any user is known, so it runs as the table owner and sees active tokens only.
CREATE POLICY access_tokens_resolve_select ON brain.access_tokens FOR SELECT TO brain_migrator
    USING (revoked_at IS NULL AND expires_at > now());
CREATE POLICY access_tokens_resolve_update ON brain.access_tokens FOR UPDATE TO brain_migrator
    USING (revoked_at IS NULL AND expires_at > now())
    WITH CHECK (revoked_at IS NULL AND expires_at > now());

CREATE FUNCTION brain.resolve_access_token(presented_hash text)
    RETURNS TABLE (token_id uuid, user_id uuid, org_id uuid, scope text)
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
BEGIN
    UPDATE brain.access_tokens t SET last_used_at = now()
    WHERE t.token_hash = presented_hash AND t.revoked_at IS NULL AND t.expires_at > now()
      AND (t.last_used_at IS NULL OR t.last_used_at < now() - interval '1 minute');
    RETURN QUERY
        SELECT t.id, t.user_id, t.organization_id, t.scope
        FROM brain.access_tokens t
        WHERE t.token_hash = presented_hash AND t.revoked_at IS NULL AND t.expires_at > now();
END;
$$;
REVOKE ALL ON FUNCTION brain.resolve_access_token(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION brain.resolve_access_token(text) TO brain_app;

GRANT SELECT, INSERT, UPDATE ON brain.access_tokens TO brain_app;
