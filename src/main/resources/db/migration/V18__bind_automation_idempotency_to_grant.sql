ALTER TABLE automation_project_operations
    DROP CONSTRAINT uq_automation_project_operation;

ALTER TABLE automation_project_operations
    ADD CONSTRAINT uq_automation_project_operation
        UNIQUE (grant_id, actor, action, idempotency_key);
