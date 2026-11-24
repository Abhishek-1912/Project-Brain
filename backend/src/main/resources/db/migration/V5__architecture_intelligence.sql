-- Phase 3: architecture intelligence. One versioned architecture per project, generated in three ordered steps.

-- An architecture run belongs to a project, so only that run type has no document.
ALTER TABLE brain.ai_runs DROP CONSTRAINT ai_runs_run_type_check;
ALTER TABLE brain.ai_runs ADD CONSTRAINT ai_runs_run_type_check
    CHECK (run_type IN ('ANALYSIS', 'STORIES', 'ARCHITECTURE'));
ALTER TABLE brain.ai_runs ALTER COLUMN document_id DROP NOT NULL;
ALTER TABLE brain.ai_runs ADD CONSTRAINT ai_runs_document_matches_type
    CHECK ((run_type = 'ARCHITECTURE') = (document_id IS NULL));
ALTER TABLE brain.ai_runs ADD COLUMN current_step text
    CHECK (current_step IN ('OVERVIEW', 'DATA_MODEL', 'API_DESIGN'));
ALTER TABLE brain.ai_runs ADD COLUMN input_fingerprint text CHECK (input_fingerprint ~ '^[0-9a-f]{64}$');

CREATE UNIQUE INDEX ai_runs_one_active_architecture_per_project
    ON brain.ai_runs (organization_id, project_id)
    WHERE run_type = 'ARCHITECTURE' AND status IN ('QUEUED', 'RUNNING');
CREATE INDEX ai_runs_project_architecture_created_idx
    ON brain.ai_runs (organization_id, project_id, created_at DESC)
    WHERE run_type = 'ARCHITECTURE';

CREATE TABLE brain.architecture_versions (
    id                   uuid PRIMARY KEY,
    organization_id      uuid NOT NULL REFERENCES brain.organizations (id),
    project_id           uuid NOT NULL,
    version_number       integer NOT NULL CHECK (version_number > 0),
    status               text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'APPROVED', 'REJECTED', 'SUPERSEDED')),
    ai_run_id            uuid NOT NULL,
    based_on_version_id  uuid,
    approver_job_role_id uuid,
    overview             jsonb NOT NULL CHECK (jsonb_typeof(overview) = 'object'),
    data_model           jsonb NOT NULL CHECK (jsonb_typeof(data_model) = 'object'),
    api_design           jsonb NOT NULL CHECK (jsonb_typeof(api_design) = 'object'),
    story_ids            jsonb NOT NULL CHECK (jsonb_typeof(story_ids) = 'array'),
    generated_by         uuid REFERENCES brain.profiles (id),
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    version              bigint NOT NULL DEFAULT 0,
    CONSTRAINT architecture_versions_organization_id_id_key UNIQUE (organization_id, id),
    CONSTRAINT architecture_versions_number_key UNIQUE (organization_id, project_id, version_number),
    CONSTRAINT architecture_versions_run_key UNIQUE (organization_id, ai_run_id),
    CONSTRAINT architecture_versions_project_fkey FOREIGN KEY (organization_id, project_id)
        REFERENCES brain.projects (organization_id, id),
    CONSTRAINT architecture_versions_run_fkey FOREIGN KEY (organization_id, ai_run_id)
        REFERENCES brain.ai_runs (organization_id, id),
    CONSTRAINT architecture_versions_based_on_fkey FOREIGN KEY (organization_id, based_on_version_id)
        REFERENCES brain.architecture_versions (organization_id, id),
    CONSTRAINT architecture_versions_approver_role_fkey FOREIGN KEY (organization_id, approver_job_role_id)
        REFERENCES brain.job_roles (organization_id, id)
);

-- At most one draft waiting for review and one current approved version per project.
CREATE UNIQUE INDEX architecture_versions_one_draft_key
    ON brain.architecture_versions (organization_id, project_id) WHERE status = 'DRAFT';
CREATE UNIQUE INDEX architecture_versions_one_approved_key
    ON brain.architecture_versions (organization_id, project_id) WHERE status = 'APPROVED';
CREATE INDEX architecture_versions_project_number_idx
    ON brain.architecture_versions (organization_id, project_id, version_number DESC);

CREATE TABLE brain.architecture_run_steps (
    organization_id    uuid NOT NULL REFERENCES brain.organizations (id),
    ai_run_id          uuid NOT NULL,
    step               text NOT NULL CHECK (step IN ('OVERVIEW', 'DATA_MODEL', 'API_DESIGN')),
    output             jsonb NOT NULL CHECK (jsonb_typeof(output) = 'object'),
    provider           text NOT NULL,
    model              text NOT NULL,
    prompt_version     text NOT NULL,
    input_tokens       integer NOT NULL DEFAULT 0 CHECK (input_tokens >= 0),
    output_tokens      integer NOT NULL DEFAULT 0 CHECK (output_tokens >= 0),
    reused_from_run_id uuid,
    created_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT architecture_run_steps_pkey PRIMARY KEY (organization_id, ai_run_id, step),
    CONSTRAINT architecture_run_steps_run_fkey FOREIGN KEY (organization_id, ai_run_id)
        REFERENCES brain.ai_runs (organization_id, id),
    CONSTRAINT architecture_run_steps_reused_fkey FOREIGN KEY (organization_id, reused_from_run_id)
        REFERENCES brain.ai_runs (organization_id, id)
);

CREATE TABLE brain.architecture_reviews (
    id              uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES brain.organizations (id),
    project_id      uuid NOT NULL,
    version_id      uuid NOT NULL,
    content_version bigint NOT NULL CHECK (content_version >= 0),
    action          text NOT NULL CHECK (action IN ('APPROVED', 'REJECTED')),
    actor_id        uuid NOT NULL REFERENCES brain.profiles (id),
    job_role_id     uuid,
    approval_basis  text NOT NULL CHECK (approval_basis IN ('ROLE_HOLDER', 'OWNER_FALLBACK')),
    self_approved   boolean NOT NULL DEFAULT false,
    comment         text CHECK (comment IS NULL OR char_length(comment) <= 2000),
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT architecture_reviews_project_fkey FOREIGN KEY (organization_id, project_id)
        REFERENCES brain.projects (organization_id, id),
    CONSTRAINT architecture_reviews_version_fkey FOREIGN KEY (organization_id, version_id)
        REFERENCES brain.architecture_versions (organization_id, id),
    CONSTRAINT architecture_reviews_job_role_fkey FOREIGN KEY (organization_id, job_role_id)
        REFERENCES brain.job_roles (organization_id, id),
    CONSTRAINT architecture_reviews_self_approved_action CHECK (NOT self_approved OR action = 'APPROVED'),
    CONSTRAINT architecture_reviews_reject_comment CHECK (action <> 'REJECTED' OR char_length(btrim(comment)) > 0)
);
CREATE INDEX architecture_reviews_version_created_idx
    ON brain.architecture_reviews (organization_id, version_id, created_at, id);

ALTER TABLE brain.architecture_versions ENABLE ROW LEVEL SECURITY;
ALTER TABLE brain.architecture_versions FORCE ROW LEVEL SECURITY;
ALTER TABLE brain.architecture_run_steps ENABLE ROW LEVEL SECURITY;
ALTER TABLE brain.architecture_run_steps FORCE ROW LEVEL SECURITY;
ALTER TABLE brain.architecture_reviews ENABLE ROW LEVEL SECURITY;
ALTER TABLE brain.architecture_reviews FORCE ROW LEVEL SECURITY;

CREATE POLICY architecture_versions_tenant ON brain.architecture_versions FOR ALL TO brain_app
    USING (organization_id = brain.current_org_id())
    WITH CHECK (organization_id = brain.current_org_id());

-- Step results and reviews are append-only for brain_app.
CREATE POLICY architecture_run_steps_select ON brain.architecture_run_steps FOR SELECT TO brain_app
    USING (organization_id = brain.current_org_id());
CREATE POLICY architecture_run_steps_insert ON brain.architecture_run_steps FOR INSERT TO brain_app
    WITH CHECK (organization_id = brain.current_org_id());
CREATE POLICY architecture_reviews_select ON brain.architecture_reviews FOR SELECT TO brain_app
    USING (organization_id = brain.current_org_id());
CREATE POLICY architecture_reviews_insert ON brain.architecture_reviews FOR INSERT TO brain_app
    WITH CHECK (organization_id = brain.current_org_id());

GRANT SELECT, INSERT, UPDATE ON brain.architecture_versions TO brain_app;
GRANT SELECT, INSERT ON brain.architecture_run_steps, brain.architecture_reviews TO brain_app;
