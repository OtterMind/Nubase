ALTER TABLE automation_project_operations
    ADD COLUMN token_version BIGINT,
    -- attempt_count tracks consecutive recovery failures and resets after dispatch.
    ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at TIMESTAMPTZ,
    -- Claim fields are nullable so a rolling deployment can add them before recovery starts.
    ADD COLUMN claim_token UUID,
    ADD COLUMN claimed_until TIMESTAMPTZ;

-- The Platform MCP endpoint is default-disabled and unreleased. This forward-only
-- migration establishes recovery fencing before the endpoint can be enabled.

-- A pre-migration pending row did not capture the issuing token version. Requiring
-- explicit reauthorization is safer than attributing it to the grant's current version.
UPDATE automation_project_operations
SET status = 'FAILED',
    response_json = '{"error":"OPERATION_REAUTH_REQUIRED"}',
    error_code = 'OPERATION_REAUTH_REQUIRED',
    updated_at = NOW()
WHERE action = 'platformProjectProvision'
  AND status = 'PENDING';

UPDATE automation_project_operations operation
SET token_version = grant_row.token_version
FROM automation_grants grant_row
WHERE operation.grant_id = grant_row.id;

UPDATE automation_project_operations
SET next_attempt_at = created_at;

ALTER TABLE automation_project_operations
    ALTER COLUMN token_version SET NOT NULL,
    ALTER COLUMN next_attempt_at SET NOT NULL,
    ALTER COLUMN next_attempt_at SET DEFAULT NOW(),
    ADD CONSTRAINT chk_automation_project_operation_token_version
        CHECK (token_version >= 0),
    ADD CONSTRAINT chk_automation_project_operation_attempt_count
        CHECK (attempt_count BETWEEN 0 AND 5),
    ADD CONSTRAINT chk_automation_project_operation_claim_pair
        CHECK ((claim_token IS NULL) = (claimed_until IS NULL));

CREATE INDEX idx_automation_project_operations_provision_due
    ON automation_project_operations (next_attempt_at, claimed_until, created_at, id)
    WHERE action = 'platformProjectProvision' AND status = 'PENDING';
