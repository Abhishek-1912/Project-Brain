CREATE TABLE brain.requirement_documents (
    id                   uuid PRIMARY KEY,
    organization_id      uuid NOT NULL REFERENCES brain.organizations (id),
    project_id           uuid NOT NULL,
    name                 text NOT NULL CHECK (char_length(btrim(name)) BETWEEN 1 AND 255),
    media_type           text NOT NULL CHECK (char_length(media_type) BETWEEN 1 AND 150),
    size_bytes           integer NOT NULL CHECK (size_bytes BETWEEN 1 AND 10485760),
    sha256               text NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    extracted_text       text NOT NULL CHECK (char_length(extracted_text) BETWEEN 1 AND 200000),
    -- Phase 2 is approved for dummy documents only.
    dummy_data_confirmed boolean NOT NULL CHECK (dummy_data_confirmed),
    approver_job_role_id uuid,
    uploaded_by          uuid NOT NULL REFERENCES brain.profiles (id),
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    version              bigint NOT NULL DEFAULT 0,
    CONSTRAINT requirement_documents_organization_id_id_key UNIQUE (organization_id, id),
    CONSTRAINT requirement_documents_project_fkey FOREIGN KEY (organization_id, project_id)
        REFERENCES brain.projects (organization_id, id),
    CONSTRAINT requirement_documents_approver_role_fkey FOREIGN KEY (organization_id, approver_job_role_id)
        REFERENCES brain.job_roles (organization_id, id)
);

CREATE INDEX requirement_documents_project_idx
    ON brain.requirement_documents (organization_id, project_id, created_at DESC);

CREATE TABLE brain.requirement_document_files (
    document_id     uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    content         bytea NOT NULL CHECK (octet_length(content) BETWEEN 1 AND 10485760),
    CONSTRAINT requirement_document_files_document_fkey FOREIGN KEY (organization_id, document_id)
        REFERENCES brain.requirement_documents (organization_id, id)
);

CREATE TABLE brain.ai_runs (
    id              uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES brain.organizations (id),
    project_id      uuid NOT NULL,
    document_id     uuid NOT NULL,
    run_type        text NOT NULL CHECK (run_type IN ('ANALYSIS', 'STORIES')),
    status          text NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    provider        text,
    model           text,
    prompt_version  text,
    input_tokens    integer NOT NULL DEFAULT 0 CHECK (input_tokens >= 0),
    output_tokens   integer NOT NULL DEFAULT 0 CHECK (output_tokens >= 0),
    billing_tier    text NOT NULL DEFAULT 'FREE' CHECK (billing_tier IN ('FREE', 'PAID')),
    cost_usd        numeric(12, 6) NOT NULL DEFAULT 0 CHECK (cost_usd >= 0),
    error_code      text CHECK (error_code ~ '^[A-Z_]{1,40}$'),
    requested_by    uuid NOT NULL REFERENCES brain.profiles (id),
    created_at      timestamptz NOT NULL DEFAULT now(),
    started_at      timestamptz,
    finished_at     timestamptz,
    CONSTRAINT ai_runs_organization_id_id_key UNIQUE (organization_id, id),
    CONSTRAINT ai_runs_project_fkey FOREIGN KEY (organization_id, project_id)
        REFERENCES brain.projects (organization_id, id),
    CONSTRAINT ai_runs_document_fkey FOREIGN KEY (organization_id, document_id)
        REFERENCES brain.requirement_documents (organization_id, id)
);

CREATE UNIQUE INDEX ai_runs_one_active_per_document_key
    ON brain.ai_runs (organization_id, document_id)
    WHERE status IN ('QUEUED', 'RUNNING');
CREATE INDEX ai_runs_active_status_idx ON brain.ai_runs (status) WHERE status IN ('QUEUED', 'RUNNING');
CREATE INDEX ai_runs_document_created_idx ON brain.ai_runs (organization_id, document_id, created_at DESC);

CREATE TABLE brain.requirement_analyses (
    id               uuid PRIMARY KEY,
    organization_id  uuid NOT NULL REFERENCES brain.organizations (id),
    document_id      uuid NOT NULL,
    analysis_version integer NOT NULL CHECK (analysis_version > 0),
    ai_run_id        uuid NOT NULL,
    result           jsonb NOT NULL,
    created_by       uuid NOT NULL REFERENCES brain.profiles (id),
    created_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT requirement_analyses_organization_id_id_key UNIQUE (organization_id, id),
    CONSTRAINT requirement_analyses_document_version_key UNIQUE (organization_id, document_id, analysis_version),
    CONSTRAINT requirement_analyses_run_key UNIQUE (organization_id, ai_run_id),
    CONSTRAINT requirement_analyses_document_fkey FOREIGN KEY (organization_id, document_id)
        REFERENCES brain.requirement_documents (organization_id, id),
    CONSTRAINT requirement_analyses_run_fkey FOREIGN KEY (organization_id, ai_run_id)
        REFERENCES brain.ai_runs (organization_id, id)
);

CREATE TABLE brain.clarification_questions (
    id              uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES brain.organizations (id),
    analysis_id     uuid NOT NULL,
    position        integer NOT NULL CHECK (position >= 0),
    question        text NOT NULL CHECK (char_length(question) BETWEEN 1 AND 1000),
    reason          text NOT NULL CHECK (char_length(reason) BETWEEN 1 AND 1000),
    status          text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'ANSWERED', 'DISMISSED')),
    answer          text CHECK (answer IS NULL OR char_length(btrim(answer)) BETWEEN 1 AND 5000),
    answered_by     uuid REFERENCES brain.profiles (id),
    answered_at     timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    version         bigint NOT NULL DEFAULT 0,
    CONSTRAINT clarification_questions_position_key UNIQUE (organization_id, analysis_id, position),
    CONSTRAINT clarification_questions_analysis_fkey FOREIGN KEY (organization_id, analysis_id)
        REFERENCES brain.requirement_analyses (organization_id, id),
    CONSTRAINT clarification_questions_answer_status CHECK ((status = 'ANSWERED') = (answer IS NOT NULL)),
    CONSTRAINT clarification_questions_decision_pair CHECK ((status = 'OPEN') = (answered_by IS NULL)),
    CONSTRAINT clarification_questions_decision_time CHECK ((answered_by IS NULL) = (answered_at IS NULL))
);

-- tasks has a single-column primary key, so composite references need this key.
ALTER TABLE brain.tasks ADD CONSTRAINT tasks_organization_id_id_key UNIQUE (organization_id, id);

CREATE TABLE brain.story_drafts (
    id                  uuid PRIMARY KEY,
    organization_id     uuid NOT NULL REFERENCES brain.organizations (id),
    project_id          uuid NOT NULL,
    document_id         uuid NOT NULL,
    analysis_id         uuid NOT NULL,
    title               text NOT NULL CHECK (char_length(btrim(title)) BETWEEN 1 AND 200),
    as_a                text NOT NULL CHECK (char_length(btrim(as_a)) BETWEEN 1 AND 300),
    i_want              text NOT NULL CHECK (char_length(btrim(i_want)) BETWEEN 1 AND 1000),
    so_that             text NOT NULL CHECK (char_length(btrim(so_that)) BETWEEN 1 AND 1000),
    acceptance_criteria jsonb NOT NULL CHECK (jsonb_typeof(acceptance_criteria) = 'array'),
    priority            text NOT NULL CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH', 'URGENT')),
    job_role_id         uuid,
    source_quotes       jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(source_quotes) = 'array'),
    status              text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'APPROVED', 'REJECTED', 'CONVERTED')),
    task_id             uuid,
    generated_by        uuid REFERENCES brain.profiles (id),
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    version             bigint NOT NULL DEFAULT 0,
    CONSTRAINT story_drafts_organization_id_id_key UNIQUE (organization_id, id),
    CONSTRAINT story_drafts_project_fkey FOREIGN KEY (organization_id, project_id)
        REFERENCES brain.projects (organization_id, id),
    CONSTRAINT story_drafts_document_fkey FOREIGN KEY (organization_id, document_id)
        REFERENCES brain.requirement_documents (organization_id, id),
    CONSTRAINT story_drafts_analysis_fkey FOREIGN KEY (organization_id, analysis_id)
        REFERENCES brain.requirement_analyses (organization_id, id),
    CONSTRAINT story_drafts_job_role_fkey FOREIGN KEY (organization_id, job_role_id)
        REFERENCES brain.job_roles (organization_id, id),
    -- A converted story stays converted after its task is deleted, so it cannot create a second task.
    CONSTRAINT story_drafts_task_fkey FOREIGN KEY (organization_id, task_id)
        REFERENCES brain.tasks (organization_id, id) ON DELETE SET NULL (task_id)
);

CREATE INDEX story_drafts_document_status_idx ON brain.story_drafts (organization_id, document_id, status);

ALTER TABLE brain.tasks ADD COLUMN source_story_id uuid;
ALTER TABLE brain.tasks ADD CONSTRAINT tasks_source_story_key UNIQUE (source_story_id);
ALTER TABLE brain.tasks ADD CONSTRAINT tasks_source_story_fkey
    FOREIGN KEY (organization_id, source_story_id) REFERENCES brain.story_drafts (organization_id, id);

CREATE TABLE brain.story_reviews (
    id              uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES brain.organizations (id),
    project_id      uuid NOT NULL,
    document_id     uuid NOT NULL,
    -- A historical ID with no foreign key, so review history outlives replaced drafts.
    story_id        uuid NOT NULL,
    story_version   bigint NOT NULL CHECK (story_version >= 0),
    action          text NOT NULL CHECK (action IN ('SUBMITTED', 'APPROVED', 'REJECTED', 'REOPENED')),
    actor_id        uuid NOT NULL REFERENCES brain.profiles (id),
    job_role_id     uuid,
    approval_basis  text CHECK (approval_basis IN ('ROLE_HOLDER', 'OWNER_FALLBACK')),
    self_approved   boolean NOT NULL DEFAULT false,
    comment         text CHECK (comment IS NULL OR char_length(comment) <= 2000),
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT story_reviews_project_fkey FOREIGN KEY (organization_id, project_id)
        REFERENCES brain.projects (organization_id, id),
    CONSTRAINT story_reviews_document_fkey FOREIGN KEY (organization_id, document_id)
        REFERENCES brain.requirement_documents (organization_id, id),
    CONSTRAINT story_reviews_job_role_fkey FOREIGN KEY (organization_id, job_role_id)
        REFERENCES brain.job_roles (organization_id, id),
    CONSTRAINT story_reviews_decision_basis CHECK ((action IN ('APPROVED', 'REJECTED')) = (approval_basis IS NOT NULL)),
    CONSTRAINT story_reviews_self_approved_action CHECK (NOT self_approved OR action = 'APPROVED'),
    CONSTRAINT story_reviews_reject_comment CHECK (action <> 'REJECTED' OR char_length(btrim(comment)) > 0)
);

CREATE INDEX story_reviews_story_created_idx ON brain.story_reviews (organization_id, story_id, created_at, id);

ALTER TABLE brain.requirement_documents ENABLE ROW LEVEL SECURITY;
ALTER TABLE brain.requirement_documents FORCE ROW LEVEL SECURITY;
ALTER TABLE brain.requirement_document_files ENABLE ROW LEVEL SECURITY;
ALTER TABLE brain.requirement_document_files FORCE ROW LEVEL SECURITY;
ALTER TABLE brain.ai_runs ENABLE ROW LEVEL SECURITY;
ALTER TABLE brain.ai_runs FORCE ROW LEVEL SECURITY;
ALTER TABLE brain.requirement_analyses ENABLE ROW LEVEL SECURITY;
ALTER TABLE brain.requirement_analyses FORCE ROW LEVEL SECURITY;
ALTER TABLE brain.clarification_questions ENABLE ROW LEVEL SECURITY;
ALTER TABLE brain.clarification_questions FORCE ROW LEVEL SECURITY;
ALTER TABLE brain.story_drafts ENABLE ROW LEVEL SECURITY;
ALTER TABLE brain.story_drafts FORCE ROW LEVEL SECURITY;
ALTER TABLE brain.story_reviews ENABLE ROW LEVEL SECURITY;
ALTER TABLE brain.story_reviews FORCE ROW LEVEL SECURITY;

CREATE POLICY requirement_documents_tenant ON brain.requirement_documents FOR ALL TO brain_app
    USING (organization_id = brain.current_org_id())
    WITH CHECK (organization_id = brain.current_org_id());
CREATE POLICY requirement_document_files_tenant ON brain.requirement_document_files FOR ALL TO brain_app
    USING (organization_id = brain.current_org_id())
    WITH CHECK (organization_id = brain.current_org_id());
CREATE POLICY ai_runs_tenant ON brain.ai_runs FOR ALL TO brain_app
    USING (organization_id = brain.current_org_id())
    WITH CHECK (organization_id = brain.current_org_id());
CREATE POLICY requirement_analyses_tenant ON brain.requirement_analyses FOR ALL TO brain_app
    USING (organization_id = brain.current_org_id())
    WITH CHECK (organization_id = brain.current_org_id());
CREATE POLICY clarification_questions_tenant ON brain.clarification_questions FOR ALL TO brain_app
    USING (organization_id = brain.current_org_id())
    WITH CHECK (organization_id = brain.current_org_id());
CREATE POLICY story_drafts_tenant ON brain.story_drafts FOR ALL TO brain_app
    USING (organization_id = brain.current_org_id())
    WITH CHECK (organization_id = brain.current_org_id());

-- story_reviews: append-only for brain_app.
CREATE POLICY story_reviews_select ON brain.story_reviews FOR SELECT TO brain_app
    USING (organization_id = brain.current_org_id());
CREATE POLICY story_reviews_insert ON brain.story_reviews FOR INSERT TO brain_app
    WITH CHECK (organization_id = brain.current_org_id());

-- Startup recovery crosses tenants, so it is limited to failing runs left active by a stopped server.
CREATE POLICY ai_runs_recovery_select ON brain.ai_runs FOR SELECT TO brain_migrator
    USING (status IN ('QUEUED', 'RUNNING') OR (status = 'FAILED' AND error_code = 'ABANDONED'));
CREATE POLICY ai_runs_recovery_update ON brain.ai_runs FOR UPDATE TO brain_migrator
    USING (status IN ('QUEUED', 'RUNNING'))
    WITH CHECK (status = 'FAILED' AND error_code = 'ABANDONED');

CREATE FUNCTION brain.fail_abandoned_ai_runs() RETURNS integer
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    affected integer;
BEGIN
    UPDATE brain.ai_runs
    SET status = 'FAILED', error_code = 'ABANDONED', finished_at = now()
    WHERE status IN ('QUEUED', 'RUNNING');
    GET DIAGNOSTICS affected = ROW_COUNT;
    RETURN affected;
END;
$$;
REVOKE ALL ON FUNCTION brain.fail_abandoned_ai_runs() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION brain.fail_abandoned_ai_runs() TO brain_app;

GRANT SELECT, INSERT, UPDATE ON brain.requirement_documents, brain.ai_runs, brain.clarification_questions TO brain_app;
GRANT SELECT, INSERT ON brain.requirement_document_files, brain.requirement_analyses, brain.story_reviews TO brain_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON brain.story_drafts TO brain_app;
