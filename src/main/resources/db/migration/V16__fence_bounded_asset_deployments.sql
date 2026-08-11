-- A bounded rollback drill owns exactly one deployment record per project/run.
-- Generic deployment records keep their existing run-id semantics.
CREATE UNIQUE INDEX IF NOT EXISTS uq_app_deployments_bounded_project_run
    ON app_deployments (project_ref, run_id)
    WHERE run_id IS NOT NULL
      AND manifest_summary ->> 'profile' = 'bounded-asset-v1';
