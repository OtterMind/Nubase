-- Metadata-plane tables for Supabase-style Edge Functions.
-- Function execution is delegated to a pluggable executor; these tables only store
-- project-scoped control-plane state, deployments, secrets, and invocation summaries.

CREATE TABLE IF NOT EXISTS edge_functions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_ref VARCHAR(128) NOT NULL,
    name VARCHAR(128) NOT NULL,
    slug VARCHAR(128) NOT NULL,
    description TEXT,
    verify_jwt BOOLEAN NOT NULL DEFAULT TRUE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    privileged BOOLEAN NOT NULL DEFAULT FALSE,
    import_map JSONB,
    entrypoint VARCHAR(512) NOT NULL DEFAULT 'index.ts',
    active_version_id UUID,
    created_by VARCHAR(255),
    created_by_platform_user_id UUID REFERENCES platform_users(id) ON DELETE SET NULL,
    updated_by_platform_user_id UUID REFERENCES platform_users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_edge_functions_project_slug UNIQUE (project_ref, slug),
    CONSTRAINT chk_edge_function_slug CHECK (slug ~ '^[a-zA-Z0-9_-]{1,128}$')
);

CREATE TABLE IF NOT EXISTS edge_function_versions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    function_id UUID NOT NULL REFERENCES edge_functions(id) ON DELETE CASCADE,
    version_no INTEGER NOT NULL,
    source_hash VARCHAR(128) NOT NULL,
    artifact_uri TEXT,
    artifact_type VARCHAR(64) NOT NULL DEFAULT 'source_bundle',
    provider VARCHAR(64) NOT NULL DEFAULT 'local',
    provider_deployment_id TEXT,
    status VARCHAR(32) NOT NULL DEFAULT 'pending',
    error_message TEXT,
    deployed_by_platform_user_id UUID REFERENCES platform_users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    activated_at TIMESTAMPTZ,
    CONSTRAINT uq_edge_function_versions_no UNIQUE (function_id, version_no)
);

ALTER TABLE edge_functions
    ADD CONSTRAINT fk_edge_functions_active_version
    FOREIGN KEY (active_version_id) REFERENCES edge_function_versions(id)
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE IF NOT EXISTS edge_function_secrets (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    function_id UUID NOT NULL REFERENCES edge_functions(id) ON DELETE CASCADE,
    name VARCHAR(128) NOT NULL,
    encrypted_value TEXT NOT NULL,
    created_by_platform_user_id UUID REFERENCES platform_users(id) ON DELETE SET NULL,
    updated_by_platform_user_id UUID REFERENCES platform_users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_edge_function_secret_name UNIQUE (function_id, name),
    CONSTRAINT chk_edge_function_secret_name CHECK (name ~ '^[A-Z_][A-Z0-9_]{0,127}$')
);

CREATE TABLE IF NOT EXISTS edge_function_invocations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    request_id VARCHAR(128) NOT NULL,
    project_ref VARCHAR(128) NOT NULL,
    function_slug VARCHAR(128) NOT NULL,
    function_version_id UUID,
    method VARCHAR(16) NOT NULL,
    path TEXT NOT NULL,
    status_code INTEGER,
    duration_ms INTEGER,
    executor_provider VARCHAR(64),
    error_code VARCHAR(64),
    error_message TEXT,
    caller_type VARCHAR(32),
    caller_role VARCHAR(64),
    caller_user_id UUID,
    caller_platform_user_id UUID REFERENCES platform_users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_edge_functions_project_slug
    ON edge_functions (project_ref, slug);

CREATE INDEX IF NOT EXISTS idx_edge_invocations_project_created
    ON edge_function_invocations (project_ref, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_edge_functions_project_created_by
    ON edge_functions (project_ref, created_by_platform_user_id);

CREATE INDEX IF NOT EXISTS idx_edge_invocations_project_caller
    ON edge_function_invocations (project_ref, caller_user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_edge_invocations_project_function_caller
    ON edge_function_invocations (project_ref, function_slug, caller_user_id, created_at DESC);
