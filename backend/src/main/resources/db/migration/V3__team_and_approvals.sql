ALTER TABLE brain.organizations
    ADD COLUMN allow_self_approval boolean NOT NULL DEFAULT true;

CREATE TABLE brain.job_roles (
    id              uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES brain.organizations (id),
    name            text NOT NULL CHECK (char_length(btrim(name)) BETWEEN 1 AND 60),
    active          boolean NOT NULL DEFAULT true,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    version         bigint NOT NULL DEFAULT 0,
    CONSTRAINT job_roles_organization_id_id_key UNIQUE (organization_id, id)
);

CREATE UNIQUE INDEX job_roles_organization_lower_name_key ON brain.job_roles (organization_id, lower(name));

CREATE TABLE brain.project_job_roles (
    organization_id uuid NOT NULL,
    project_id      uuid NOT NULL,
    user_id         uuid NOT NULL,
    job_role_id     uuid NOT NULL,
    created_by      uuid NOT NULL REFERENCES brain.profiles (id),
    created_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, project_id, user_id, job_role_id),
    CONSTRAINT project_job_roles_project_fkey FOREIGN KEY (organization_id, project_id)
        REFERENCES brain.projects (organization_id, id),
    CONSTRAINT project_job_roles_member_fkey FOREIGN KEY (organization_id, user_id)
        REFERENCES brain.organization_members (organization_id, user_id),
    CONSTRAINT project_job_roles_job_role_fkey FOREIGN KEY (organization_id, job_role_id)
        REFERENCES brain.job_roles (organization_id, id)
);

CREATE INDEX project_job_roles_role_idx ON brain.project_job_roles (organization_id, project_id, job_role_id);
CREATE INDEX project_job_roles_user_idx ON brain.project_job_roles (organization_id, user_id);

CREATE TABLE brain.organization_invites (
    id              uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES brain.organizations (id),
    email           text NOT NULL CHECK (char_length(btrim(email)) BETWEEN 3 AND 320),
    role            text NOT NULL CHECK (role IN ('ADMIN', 'MEMBER', 'VIEWER')),
    token_hash      text NOT NULL UNIQUE CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    created_by      uuid NOT NULL REFERENCES brain.profiles (id),
    expires_at      timestamptz NOT NULL,
    accepted_by     uuid REFERENCES brain.profiles (id),
    accepted_at     timestamptz,
    cancelled_by    uuid REFERENCES brain.profiles (id),
    cancelled_at    timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT organization_invites_accepted_pair CHECK ((accepted_by IS NULL) = (accepted_at IS NULL)),
    CONSTRAINT organization_invites_cancelled_pair CHECK ((cancelled_by IS NULL) = (cancelled_at IS NULL)),
    CONSTRAINT organization_invites_one_outcome CHECK (accepted_at IS NULL OR cancelled_at IS NULL)
);

-- An expired invite stays pending here, so creating a replacement must cancel it first.
CREATE UNIQUE INDEX organization_invites_pending_email_key ON brain.organization_invites (organization_id, lower(email))
    WHERE accepted_at IS NULL AND cancelled_at IS NULL;
CREATE INDEX organization_invites_org_created_idx ON brain.organization_invites (organization_id, created_at DESC);

ALTER TABLE brain.tasks ADD COLUMN responsible_job_role_id uuid;
ALTER TABLE brain.tasks ADD CONSTRAINT tasks_responsible_job_role_fkey
    FOREIGN KEY (organization_id, responsible_job_role_id) REFERENCES brain.job_roles (organization_id, id);
CREATE INDEX tasks_org_job_role_status_idx ON brain.tasks (organization_id, responsible_job_role_id, status);

CREATE TABLE brain.task_reviews (
    id              uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES brain.organizations (id),
    project_id      uuid NOT NULL,
    -- A historical ID with no foreign key, so reviews remain after a task is deleted.
    task_id         uuid NOT NULL,
    task_version    bigint NOT NULL CHECK (task_version >= 0),
    action          text NOT NULL CHECK (action IN ('SUBMITTED', 'APPROVED', 'REJECTED', 'WITHDRAWN', 'REOPENED')),
    actor_id        uuid NOT NULL REFERENCES brain.profiles (id),
    job_role_id     uuid NOT NULL,
    approval_basis  text CHECK (approval_basis IN ('ROLE_HOLDER', 'OWNER_FALLBACK')),
    self_approved   boolean NOT NULL DEFAULT false,
    comment         text CHECK (comment IS NULL OR char_length(comment) <= 2000),
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT task_reviews_project_fkey FOREIGN KEY (organization_id, project_id)
        REFERENCES brain.projects (organization_id, id),
    CONSTRAINT task_reviews_job_role_fkey FOREIGN KEY (organization_id, job_role_id)
        REFERENCES brain.job_roles (organization_id, id),
    CONSTRAINT task_reviews_decision_basis CHECK ((action IN ('APPROVED', 'REJECTED')) = (approval_basis IS NOT NULL)),
    CONSTRAINT task_reviews_self_approved_action CHECK (NOT self_approved OR action = 'APPROVED'),
    CONSTRAINT task_reviews_reject_comment CHECK (action <> 'REJECTED' OR char_length(btrim(comment)) > 0)
);

CREATE INDEX task_reviews_task_created_idx ON brain.task_reviews (organization_id, task_id, created_at, id);

-- FORCE RLS also applies to this table's owner, so lift it only for the backfill in this transaction.
ALTER TABLE brain.organizations NO FORCE ROW LEVEL SECURITY;
INSERT INTO brain.job_roles (id, organization_id, name)
SELECT gen_random_uuid(), o.id, defaults.name
FROM brain.organizations o
CROSS JOIN (VALUES ('Project Manager'), ('Architect'), ('Backend'), ('Frontend'), ('QA'), ('DevOps')) AS defaults (name);
ALTER TABLE brain.organizations FORCE ROW LEVEL SECURITY;

CREATE FUNCTION brain.current_invite_hash() RETURNS text
    LANGUAGE sql STABLE
    SET search_path = ''
    AS $$ SELECT NULLIF(current_setting('app.current_invite_hash', true), '') $$;

REVOKE ALL ON FUNCTION brain.current_invite_hash() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION brain.current_invite_hash() TO brain_app;

ALTER TABLE brain.job_roles ENABLE ROW LEVEL SECURITY;
ALTER TABLE brain.job_roles FORCE ROW LEVEL SECURITY;
ALTER TABLE brain.project_job_roles ENABLE ROW LEVEL SECURITY;
ALTER TABLE brain.project_job_roles FORCE ROW LEVEL SECURITY;
ALTER TABLE brain.organization_invites ENABLE ROW LEVEL SECURITY;
ALTER TABLE brain.organization_invites FORCE ROW LEVEL SECURITY;
ALTER TABLE brain.task_reviews ENABLE ROW LEVEL SECURITY;
ALTER TABLE brain.task_reviews FORCE ROW LEVEL SECURITY;

CREATE POLICY job_roles_tenant ON brain.job_roles FOR ALL TO brain_app
    USING (organization_id = brain.current_org_id())
    WITH CHECK (organization_id = brain.current_org_id());

CREATE POLICY project_job_roles_tenant ON brain.project_job_roles FOR ALL TO brain_app
    USING (organization_id = brain.current_org_id())
    WITH CHECK (organization_id = brain.current_org_id());

CREATE POLICY organization_invites_select ON brain.organization_invites FOR SELECT TO brain_app
    USING (organization_id = brain.current_org_id() OR token_hash = brain.current_invite_hash());
CREATE POLICY organization_invites_insert ON brain.organization_invites FOR INSERT TO brain_app
    WITH CHECK (organization_id = brain.current_org_id());
CREATE POLICY organization_invites_update ON brain.organization_invites FOR UPDATE TO brain_app
    USING (organization_id = brain.current_org_id())
    WITH CHECK (organization_id = brain.current_org_id());
CREATE POLICY organization_invites_accept ON brain.organization_invites FOR UPDATE TO brain_app
    USING (
        token_hash = brain.current_invite_hash()
        AND accepted_at IS NULL
        AND cancelled_at IS NULL
        AND expires_at > now()
    )
    WITH CHECK (
        token_hash = brain.current_invite_hash()
        AND accepted_by = brain.current_user_id()
        AND accepted_at IS NOT NULL
    );

-- task_reviews: append-only for brain_app.
CREATE POLICY task_reviews_select ON brain.task_reviews FOR SELECT TO brain_app
    USING (organization_id = brain.current_org_id());
CREATE POLICY task_reviews_insert ON brain.task_reviews FOR INSERT TO brain_app
    WITH CHECK (organization_id = brain.current_org_id());

CREATE POLICY organization_members_invite_insert ON brain.organization_members FOR INSERT TO brain_app
    WITH CHECK (
        user_id = brain.current_user_id()
        AND role <> 'OWNER'
        AND EXISTS (
            SELECT 1
            FROM brain.organization_invites i
            JOIN brain.profiles p ON p.id = brain.current_user_id()
            WHERE i.token_hash = brain.current_invite_hash()
              AND i.organization_id = organization_members.organization_id
              AND i.role = organization_members.role
              AND i.accepted_at IS NULL
              AND i.cancelled_at IS NULL
              AND i.expires_at > now()
              AND lower(i.email) = lower(p.email)
        )
    );
CREATE POLICY organization_members_update ON brain.organization_members FOR UPDATE TO brain_app
    USING (organization_id = brain.current_org_id())
    WITH CHECK (organization_id = brain.current_org_id());
CREATE POLICY organization_members_delete ON brain.organization_members FOR DELETE TO brain_app
    USING (organization_id = brain.current_org_id());

GRANT SELECT, INSERT, UPDATE ON brain.job_roles, brain.organization_invites TO brain_app;
GRANT SELECT, INSERT, DELETE ON brain.project_job_roles TO brain_app;
GRANT SELECT, INSERT ON brain.task_reviews TO brain_app;
GRANT UPDATE, DELETE ON brain.organization_members TO brain_app;
