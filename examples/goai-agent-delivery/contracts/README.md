# Contest Contracts

These contracts define the reviewable boundary of the GOAI Agent Delivery demo.

- `deployment-manifest.schema.json` accepts only file-backed SQL, a verified non-privileged Function, release-scoped Assets, and bounded cron jobs.
- `function-manifest.schema.json` prevents privileged or JWT-bypassing demo Functions.
- `verification-report.schema.json` defines the immutable independent verification input to approval.
- `approval.schema.json` binds the human decision to both manifest and verification-report digests without storing credentials or raw request headers.
- `trace-event.schema.json` records correlated, sanitized evidence events.
- `task-state.schema.json` defines the versioned AgentTeams shared state, assignments, artifact digests, and approval state.

The local `nubase_cli` stdio bridge owns `deploy_app`. Nubase remote HTTP MCP exposes materialized deployment primitives and deployment inspection/rollback tools; this package does not claim that remote `deploy_app` exists.
