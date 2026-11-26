-- Phase 4: knowledge engine. Needs db/bootstrap/enable_vector.sql run once beforehand.
-- pgvector lives in the extensions schema, and every reference to it names that schema.

CREATE TABLE brain.knowledge_sources (
    id              uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES brain.organizations (id),
    project_id      uuid NOT NULL,
    source_type     text NOT NULL CHECK (source_type IN ('DOCUMENT', 'CLARIFICATION', 'STORY', 'ARCHITECTURE', 'NOTE')),
    source_id       uuid NOT NULL,
    title           text NOT NULL CHECK (char_length(title) BETWEEN 1 AND 500),
    content_hash    text CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    embedding_model text CHECK (char_length(embedding_model) BETWEEN 1 AND 200),
    status          text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'INDEXING', 'READY', 'FAILED')),
    error_code      text CHECK (error_code ~ '^[A-Z_]{1,40}$'),
    queued_by       uuid NOT NULL REFERENCES brain.profiles (id),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    indexed_at      timestamptz,
    CONSTRAINT knowledge_sources_organization_id_id_key UNIQUE (organization_id, id),
    CONSTRAINT knowledge_sources_item_key UNIQUE (organization_id, project_id, source_type, source_id),
    CONSTRAINT knowledge_sources_project_fkey FOREIGN KEY (organization_id, project_id)
        REFERENCES brain.projects (organization_id, id)
);
CREATE INDEX knowledge_sources_project_idx ON brain.knowledge_sources (organization_id, project_id, updated_at DESC);
CREATE INDEX knowledge_sources_waiting_idx ON brain.knowledge_sources (status) WHERE status IN ('PENDING', 'INDEXING');

CREATE TABLE brain.knowledge_chunks (
    id              uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES brain.organizations (id),
    project_id      uuid NOT NULL,
    source_row_id   uuid NOT NULL,
    position        integer NOT NULL CHECK (position >= 0),
    content         text NOT NULL CHECK (char_length(content) BETWEEN 1 AND 4000),
    search_vector   tsvector GENERATED ALWAYS AS (to_tsvector('english'::regconfig, content)) STORED,
    embedding       extensions.vector(384) NOT NULL,
    CONSTRAINT knowledge_chunks_position_key UNIQUE (source_row_id, position),
    CONSTRAINT knowledge_chunks_source_fkey FOREIGN KEY (organization_id, source_row_id)
        REFERENCES brain.knowledge_sources (organization_id, id) ON DELETE CASCADE,
    CONSTRAINT knowledge_chunks_project_fkey FOREIGN KEY (organization_id, project_id)
        REFERENCES brain.projects (organization_id, id)
);
CREATE INDEX knowledge_chunks_project_idx ON brain.knowledge_chunks (organization_id, project_id);
CREATE INDEX knowledge_chunks_search_idx ON brain.knowledge_chunks USING gin (search_vector);
CREATE INDEX knowledge_chunks_embedding_idx ON brain.knowledge_chunks
    USING hnsw (embedding extensions.vector_cosine_ops);

CREATE TABLE brain.knowledge_notes (
    id              uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES brain.organizations (id),
    project_id      uuid NOT NULL,
    title           text NOT NULL CHECK (char_length(btrim(title)) BETWEEN 1 AND 200),
    body            text NOT NULL CHECK (char_length(btrim(body)) BETWEEN 1 AND 20000),
    created_by      uuid NOT NULL REFERENCES brain.profiles (id),
    updated_by      uuid NOT NULL REFERENCES brain.profiles (id),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    version         bigint NOT NULL DEFAULT 0,
    CONSTRAINT knowledge_notes_organization_id_id_key UNIQUE (organization_id, id),
    CONSTRAINT knowledge_notes_project_fkey FOREIGN KEY (organization_id, project_id)
        REFERENCES brain.projects (organization_id, id)
);
CREATE INDEX knowledge_notes_project_idx ON brain.knowledge_notes (organization_id, project_id, updated_at DESC);

CREATE TABLE brain.knowledge_answers (
    id              uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES brain.organizations (id),
    project_id      uuid NOT NULL,
    asked_by        uuid NOT NULL REFERENCES brain.profiles (id),
    question        text NOT NULL CHECK (char_length(question) BETWEEN 1 AND 500),
    answer          text CHECK (char_length(answer) BETWEEN 1 AND 4000),
    found           boolean NOT NULL,
    citations       jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(citations) = 'array'),
    provider        text,
    model           text,
    prompt_version  text,
    input_tokens    integer NOT NULL DEFAULT 0 CHECK (input_tokens >= 0),
    output_tokens   integer NOT NULL DEFAULT 0 CHECK (output_tokens >= 0),
    error_code      text CHECK (error_code ~ '^[A-Z_]{1,40}$'),
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT knowledge_answers_project_fkey FOREIGN KEY (organization_id, project_id)
        REFERENCES brain.projects (organization_id, id)
);
CREATE INDEX knowledge_answers_project_idx ON brain.knowledge_answers (organization_id, project_id, created_at DESC);

ALTER TABLE brain.knowledge_sources ENABLE ROW LEVEL SECURITY;
ALTER TABLE brain.knowledge_sources FORCE ROW LEVEL SECURITY;
ALTER TABLE brain.knowledge_chunks ENABLE ROW LEVEL SECURITY;
ALTER TABLE brain.knowledge_chunks FORCE ROW LEVEL SECURITY;
ALTER TABLE brain.knowledge_notes ENABLE ROW LEVEL SECURITY;
ALTER TABLE brain.knowledge_notes FORCE ROW LEVEL SECURITY;
ALTER TABLE brain.knowledge_answers ENABLE ROW LEVEL SECURITY;
ALTER TABLE brain.knowledge_answers FORCE ROW LEVEL SECURITY;

CREATE POLICY knowledge_sources_tenant ON brain.knowledge_sources FOR ALL TO brain_app
    USING (organization_id = brain.current_org_id())
    WITH CHECK (organization_id = brain.current_org_id());
CREATE POLICY knowledge_chunks_tenant ON brain.knowledge_chunks FOR ALL TO brain_app
    USING (organization_id = brain.current_org_id())
    WITH CHECK (organization_id = brain.current_org_id());
CREATE POLICY knowledge_notes_tenant ON brain.knowledge_notes FOR ALL TO brain_app
    USING (organization_id = brain.current_org_id())
    WITH CHECK (organization_id = brain.current_org_id());

-- knowledge_answers: append-only for brain_app.
CREATE POLICY knowledge_answers_select ON brain.knowledge_answers FOR SELECT TO brain_app
    USING (organization_id = brain.current_org_id());
CREATE POLICY knowledge_answers_insert ON brain.knowledge_answers FOR INSERT TO brain_app
    WITH CHECK (organization_id = brain.current_org_id());

-- Startup recovery crosses tenants, so it may only move interrupted sources back to the queue.
CREATE POLICY knowledge_sources_recovery_select ON brain.knowledge_sources FOR SELECT TO brain_migrator
    USING (status IN ('PENDING', 'INDEXING'));
CREATE POLICY knowledge_sources_recovery_update ON brain.knowledge_sources FOR UPDATE TO brain_migrator
    USING (status = 'INDEXING')
    WITH CHECK (status = 'PENDING');

CREATE FUNCTION brain.requeue_interrupted_knowledge()
    RETURNS TABLE (org_id uuid, source_row_id uuid, queued_by_user uuid)
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
BEGIN
    UPDATE brain.knowledge_sources SET status = 'PENDING', updated_at = now() WHERE status = 'INDEXING';
    RETURN QUERY
        SELECT s.organization_id, s.id, s.queued_by
        FROM brain.knowledge_sources s
        WHERE s.status = 'PENDING'
        ORDER BY s.updated_at, s.id;
END;
$$;
REVOKE ALL ON FUNCTION brain.requeue_interrupted_knowledge() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION brain.requeue_interrupted_knowledge() TO brain_app;

GRANT SELECT, INSERT, UPDATE, DELETE ON brain.knowledge_sources, brain.knowledge_notes TO brain_app;
GRANT SELECT, INSERT, DELETE ON brain.knowledge_chunks TO brain_app;
GRANT SELECT, INSERT ON brain.knowledge_answers TO brain_app;
