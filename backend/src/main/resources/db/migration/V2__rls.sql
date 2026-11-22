-- Context helpers. An empty setting (left on a reused connection) reads as NULL, which matches no rows.
CREATE FUNCTION brain.current_user_id() RETURNS uuid
    LANGUAGE sql STABLE
    AS $$ SELECT NULLIF(current_setting('app.current_user_id', true), '')::uuid $$;

CREATE FUNCTION brain.current_org_id() RETURNS uuid
    LANGUAGE sql STABLE
    AS $$ SELECT NULLIF(current_setting('app.current_org_id', true), '')::uuid $$;

-- A function, because a direct subquery would make the organizations and
-- organization_members policies reference each other and raise a recursion error.
CREATE FUNCTION brain.current_user_created_org(org_id uuid) RETURNS boolean
    LANGUAGE sql STABLE
    SET search_path = ''
    AS $$
        SELECT EXISTS (
            SELECT 1 FROM brain.organizations o
            WHERE o.id = org_id AND o.created_by = brain.current_user_id()
        )
    $$;

REVOKE ALL ON FUNCTION brain.current_user_id() FROM PUBLIC;
REVOKE ALL ON FUNCTION brain.current_org_id() FROM PUBLIC;
REVOKE ALL ON FUNCTION brain.current_user_created_org(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION brain.current_user_id() TO brain_app;
GRANT EXECUTE ON FUNCTION brain.current_org_id() TO brain_app;
GRANT EXECUTE ON FUNCTION brain.current_user_created_org(uuid) TO brain_app;

ALTER TABLE brain.profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE brain.profiles FORCE ROW LEVEL SECURITY;
ALTER TABLE brain.organizations ENABLE ROW LEVEL SECURITY;
ALTER TABLE brain.organizations FORCE ROW LEVEL SECURITY;
ALTER TABLE brain.organization_members ENABLE ROW LEVEL SECURITY;
ALTER TABLE brain.organization_members FORCE ROW LEVEL SECURITY;
ALTER TABLE brain.projects ENABLE ROW LEVEL SECURITY;
ALTER TABLE brain.projects FORCE ROW LEVEL SECURITY;
ALTER TABLE brain.tasks ENABLE ROW LEVEL SECURITY;
ALTER TABLE brain.tasks FORCE ROW LEVEL SECURITY;
ALTER TABLE brain.audit_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE brain.audit_events FORCE ROW LEVEL SECURITY;

-- profiles
CREATE POLICY profiles_select ON brain.profiles FOR SELECT TO brain_app
    USING (
        id = brain.current_user_id()
        OR EXISTS (
            SELECT 1 FROM brain.organization_members m
            WHERE m.user_id = profiles.id AND m.organization_id = brain.current_org_id()
        )
    );
CREATE POLICY profiles_insert ON brain.profiles FOR INSERT TO brain_app
    WITH CHECK (id = brain.current_user_id());
CREATE POLICY profiles_update ON brain.profiles FOR UPDATE TO brain_app
    USING (id = brain.current_user_id())
    WITH CHECK (id = brain.current_user_id());

-- organizations
CREATE POLICY organizations_select ON brain.organizations FOR SELECT TO brain_app
    USING (
        id = brain.current_org_id()
        OR EXISTS (
            SELECT 1 FROM brain.organization_members m
            WHERE m.organization_id = organizations.id AND m.user_id = brain.current_user_id()
        )
    );
CREATE POLICY organizations_insert ON brain.organizations FOR INSERT TO brain_app
    WITH CHECK (id = brain.current_org_id() AND created_by = brain.current_user_id());
CREATE POLICY organizations_update ON brain.organizations FOR UPDATE TO brain_app
    USING (id = brain.current_org_id())
    WITH CHECK (id = brain.current_org_id());

-- organization_members: the only permitted insert is the creator's own OWNER row.
CREATE POLICY organization_members_select ON brain.organization_members FOR SELECT TO brain_app
    USING (user_id = brain.current_user_id() OR organization_id = brain.current_org_id());
CREATE POLICY organization_members_insert ON brain.organization_members FOR INSERT TO brain_app
    WITH CHECK (
        user_id = brain.current_user_id()
        AND organization_id = brain.current_org_id()
        AND role = 'OWNER'
        AND brain.current_user_created_org(organization_id)
    );

-- projects
CREATE POLICY projects_tenant ON brain.projects FOR ALL TO brain_app
    USING (organization_id = brain.current_org_id())
    WITH CHECK (organization_id = brain.current_org_id());

-- tasks
CREATE POLICY tasks_tenant ON brain.tasks FOR ALL TO brain_app
    USING (organization_id = brain.current_org_id())
    WITH CHECK (organization_id = brain.current_org_id());

-- audit_events: append-only for brain_app.
CREATE POLICY audit_events_select ON brain.audit_events FOR SELECT TO brain_app
    USING (organization_id = brain.current_org_id());
CREATE POLICY audit_events_insert ON brain.audit_events FOR INSERT TO brain_app
    WITH CHECK (organization_id = brain.current_org_id());

GRANT SELECT, INSERT, UPDATE ON brain.profiles, brain.organizations, brain.projects TO brain_app;
GRANT SELECT, INSERT ON brain.organization_members, brain.audit_events TO brain_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON brain.tasks TO brain_app;
