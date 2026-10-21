-- DOO Phase 1 Initial Schema
-- V1__init.sql

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'task_status') THEN
        CREATE TYPE task_status AS ENUM (
            'QUEUED', 'RUNNING', 'WAITING_FOR_USER', 'VERIFYING',
            'COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED'
        );
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'workflow_type') THEN
        CREATE TYPE workflow_type AS ENUM (
            'WEB_RESEARCH', 'PRODUCT_COMPARISON', 'UI_INTERACTION', 'UNKNOWN'
        );
    END IF;
END $$;

-- Main tasks table
CREATE TABLE tasks (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_request    TEXT NOT NULL,
    status          VARCHAR(32) NOT NULL DEFAULT 'QUEUED',
    workflow_type   VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
    plan            TEXT,
    current_step    INTEGER NOT NULL DEFAULT 0,
    success_criteria TEXT,
    failure_reason  TEXT,
    idempotency_key VARCHAR(255) UNIQUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ
);

CREATE INDEX idx_tasks_status ON tasks(status);
CREATE INDEX idx_tasks_created_at ON tasks(created_at DESC);
CREATE INDEX idx_tasks_idempotency ON tasks(idempotency_key) WHERE idempotency_key IS NOT NULL;

-- Task execution steps / events
CREATE TABLE task_steps (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id     UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    step_index  INTEGER NOT NULL,
    action_type VARCHAR(64) NOT NULL,
    action_args JSONB,
    result      TEXT,
    status      VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    error       TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ
);

CREATE INDEX idx_task_steps_task_id ON task_steps(task_id, step_index);

-- Evidence artifacts (screenshots, URLs, text snippets)
CREATE TABLE evidence_artifacts (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id     UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    artifact_type VARCHAR(32) NOT NULL, -- 'SCREENSHOT', 'URL_VISITED', 'TEXT_SNIPPET', 'VERIFICATION_RESULT'
    label       VARCHAR(255),
    content     TEXT,            -- base64 for screenshots, text for others
    source_url  TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_evidence_task_id ON evidence_artifacts(task_id, created_at);

-- Approval / user-input requests
CREATE TABLE approval_requests (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id         UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    prompt          TEXT NOT NULL,
    proposed_action TEXT,
    parameters      JSONB,
    status          VARCHAR(32) NOT NULL DEFAULT 'PENDING', -- PENDING, APPROVED, REJECTED, EXPIRED
    user_response   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ NOT NULL DEFAULT (now() + interval '10 minutes'),
    resolved_at     TIMESTAMPTZ
);

CREATE INDEX idx_approvals_task_id ON approval_requests(task_id);
CREATE INDEX idx_approvals_status ON approval_requests(status) WHERE status = 'PENDING';

-- Task results (final summary)
CREATE TABLE task_results (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id         UUID NOT NULL UNIQUE REFERENCES tasks(id) ON DELETE CASCADE,
    summary         TEXT,
    verification_passed BOOLEAN,
    verification_notes TEXT,
    sources         JSONB,       -- Array of {url, title} objects
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Function to auto-update updated_at
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ language 'plpgsql';

CREATE TRIGGER update_tasks_updated_at
    BEFORE UPDATE ON tasks
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();
