CREATE TABLE brain.profiles (
    id           uuid PRIMARY KEY,
    email        text NOT NULL,
    display_name text,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    version      bigint NOT NULL DEFAULT 0
);

CREATE TABLE brain.organizations (
    id         uuid PRIMARY KEY,
    name       text NOT NULL,
    slug       text NOT NULL UNIQUE,
    created_by uuid NOT NULL REFERENCES brain.profiles (id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version    bigint NOT NULL DEFAULT 0
);

CREATE TABLE brain.organization_members (
    organization_id uuid NOT NULL REFERENCES brain.organizations (id),
    user_id         uuid NOT NULL REFERENCES brain.profiles (id),
    role            text NOT NULL CHECK (role IN ('OWNER', 'ADMIN', 'MEMBER', 'VIEWER')),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    version         bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (organization_id, user_id)
);

CREATE INDEX organization_members_user_idx ON brain.organization_members (user_id);

CREATE TABLE brain.projects (
    id              uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES brain.organizations (id),
    name            text NOT NULL,
    description     text,
    status          text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    created_by      uuid NOT NULL REFERENCES brain.profiles (id),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    version         bigint NOT NULL DEFAULT 0,
    CONSTRAINT projects_organization_id_id_key UNIQUE (organization_id, id)
);

CREATE UNIQUE INDEX projects_organization_lower_name_key ON brain.projects (organization_id, lower(name));

CREATE TABLE brain.tasks (
    id              uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES brain.organizations (id),
    project_id      uuid NOT NULL,
    title           text NOT NULL,
    description     text,
    status          text NOT NULL DEFAULT 'BACKLOG'
                    CHECK (status IN ('BACKLOG', 'TODO', 'IN_PROGRESS', 'IN_REVIEW', 'DONE')),
    priority        text NOT NULL DEFAULT 'MEDIUM' CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH', 'URGENT')),
    assignee_id     uuid,
    due_date        date,
    created_by      uuid NOT NULL REFERENCES brain.profiles (id),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    version         bigint NOT NULL DEFAULT 0,
    CONSTRAINT tasks_project_same_org_fkey FOREIGN KEY (organization_id, project_id)
        REFERENCES brain.projects (organization_id, id),
    CONSTRAINT tasks_assignee_member_fkey FOREIGN KEY (organization_id, assignee_id)
        REFERENCES brain.organization_members (organization_id, user_id)
);

CREATE INDEX tasks_project_status_idx ON brain.tasks (organization_id, project_id, status);
CREATE INDEX tasks_org_status_idx ON brain.tasks (organization_id, status);
CREATE INDEX tasks_org_priority_idx ON brain.tasks (organization_id, priority);
CREATE INDEX tasks_org_assignee_idx ON brain.tasks (organization_id, assignee_id);
CREATE INDEX tasks_org_due_date_idx ON brain.tasks (organization_id, due_date);

CREATE TABLE brain.audit_events (
    id              uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES brain.organizations (id),
    actor_id        uuid NOT NULL REFERENCES brain.profiles (id),
    action          text NOT NULL,
    entity_type     text NOT NULL,
    entity_id       uuid NOT NULL,
    metadata        jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX audit_events_org_created_idx ON brain.audit_events (organization_id, created_at DESC);
