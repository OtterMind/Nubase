-- Independent platform MCP automation grants and idempotent project operations.
-- The endpoint remains disabled by default; this schema only establishes the durable
-- authorization and exactly-once boundaries required when it is explicitly enabled.

CREATE TABLE automation_grants (
    id              UUID PRIMARY KEY,
    actor           VARCHAR(128) NOT NULL,
    actor_type      VARCHAR(32)  NOT NULL,
    scopes          TEXT         NOT NULL,
    token_version   BIGINT       NOT NULL,
    allowed_ref_prefix VARCHAR(40) NOT NULL,
    max_projects    INTEGER      NOT NULL,
    approval_binding VARCHAR(255),
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    valid_from      TIMESTAMPTZ,
    valid_until     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_automation_grants_actor_type CHECK (actor_type = 'automation'),
    CONSTRAINT chk_automation_grants_token_version CHECK (token_version >= 0),
    CONSTRAINT chk_automation_grants_max_projects CHECK (max_projects > 0),
    CONSTRAINT chk_automation_grants_ref_prefix CHECK (
        allowed_ref_prefix ~ '^[a-z][a-z0-9_]{0,39}$'
        AND RIGHT(allowed_ref_prefix, 1) = '_'
    ),
    CONSTRAINT chk_automation_grants_validity CHECK (
        valid_until IS NULL OR valid_from IS NULL OR valid_until > valid_from
    )
);

CREATE INDEX idx_automation_grants_actor ON automation_grants (actor);
CREATE INDEX idx_automation_grants_active ON automation_grants (active) WHERE active = TRUE;
CREATE UNIQUE INDEX uq_automation_grants_active_actor
    ON automation_grants (actor) WHERE active = TRUE;

CREATE TABLE automation_project_operations (
    id                UUID PRIMARY KEY,
    actor             VARCHAR(128) NOT NULL,
    action            VARCHAR(64)  NOT NULL,
    idempotency_key   VARCHAR(128) NOT NULL,
    request_hash      CHAR(64)     NOT NULL,
    status            VARCHAR(16)  NOT NULL,
    project_ref       VARCHAR(50),
    task_id           VARCHAR(128) NOT NULL,
    run_id            VARCHAR(128) NOT NULL,
    spec_digest       VARCHAR(71)  NOT NULL,
    approval_id       VARCHAR(128),
    response_json     TEXT,
    error_code        VARCHAR(64),
    grant_id          UUID         NOT NULL REFERENCES automation_grants (id),
    token_jti         VARCHAR(128) NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_automation_project_operation
        UNIQUE (actor, action, idempotency_key),
    CONSTRAINT chk_automation_project_operation_action CHECK (
        action IN ('platformProjectCreate', 'platformProjectProvision')
    ),
    CONSTRAINT chk_automation_project_operation_status CHECK (
        status IN ('PENDING', 'SUCCEEDED', 'FAILED', 'REJECTED')
    )
);

CREATE INDEX idx_automation_project_operations_project
    ON automation_project_operations (project_ref, created_at DESC);
