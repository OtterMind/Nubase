-- Agent-driven app deployment records.
-- These rows make deploy_app observable across CLI/MCP sessions and provide the
-- basis for status, logs, and later rollback support.

CREATE TABLE IF NOT EXISTS app_deployments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_ref VARCHAR(128) NOT NULL,
    app_name VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'running',
    public_url TEXT,
    manifest_summary JSONB,
    error_message TEXT,
    agent_id VARCHAR(128),
    run_id VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS app_deployment_steps (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    deployment_id UUID NOT NULL REFERENCES app_deployments(id) ON DELETE CASCADE,
    step_order INTEGER NOT NULL,
    step_name VARCHAR(128) NOT NULL,
    target_name VARCHAR(255),
    status VARCHAR(32) NOT NULL,
    result JSONB,
    error_message TEXT,
    started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMPTZ,
    CONSTRAINT uq_app_deployment_step_order UNIQUE (deployment_id, step_order)
);

CREATE INDEX IF NOT EXISTS idx_app_deployments_project_created
    ON app_deployments (project_ref, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_app_deployment_steps_deployment_order
    ON app_deployment_steps (deployment_id, step_order ASC);
