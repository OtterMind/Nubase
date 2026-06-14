# Nubase Scheduled Jobs (Cron) Reference

Use this reference when setting up recurring backend work: cron jobs, scheduled tasks, periodic syncs, nightly cleanup, polling, or anything that should run on a schedule without a separate worker service.

Cron is **how an agent wires recurring tasks into a deployed app**. A job either invokes an Edge Function or calls a named database function, on a crontab schedule, run by the Nubase control plane.

## Tools

Read (always available):

- `cron_list()` — list scheduled jobs
- `cron_get({ name })` — one job
- `cron_runs({ name?, limit? })` — run history (one job, or project-wide when `name` is omitted)

Write (gated by `NUBASE_ALLOW_ADMIN_WRITE=true` and the project's service_role key; otherwise returns `{ success: false, ... }` without touching the backend):

- `cron_create({ name, cronExpression, targetType, ... })`
- `cron_update({ name, ... })` — `targetType` is immutable after create
- `cron_delete({ name })`

## Target types

`targetType` is one of:

- **`edge_function`** — invoke a deployed function through the gateway. Set `functionSlug`; optional `httpMethod`, `requestPath`, `requestBody`. Scheduled calls run with `callerRole=cron` (bypasses `verify_jwt`) and still get rate limits, secrets, and invocation logging.
- **`db_function`** — call one named Postgres function in the tenant schema. Set `dbFunctionName`; optional `dbFunctionArgs` (a JSON object). A per-job `timeoutSeconds` is enforced server-side. (Raw SQL is intentionally not allowed — create the function first, then schedule it by name.)

Schedules accept 5-field crontab and Spring's 6-field form, and **evaluate in UTC**.

## Worked Examples

**Run a deployed function every five minutes:**

```text
cron_create({
  "name": "poll-upstream",
  "cronExpression": "*/5 * * * *",
  "targetType": "edge_function",
  "functionSlug": "poll-upstream",
  "httpMethod": "POST",
  "requestBody": "{\"source\":\"cron\"}"
})
```

**Nightly database maintenance** (`refresh_stats` must already exist via `CREATE FUNCTION`):

```text
cron_create({
  "name": "refresh-stats",
  "cronExpression": "30 3 * * *",
  "targetType": "db_function",
  "dbFunctionName": "refresh_stats",
  "dbFunctionArgs": { "days": 7 },
  "timeoutSeconds": 120
})
```

**Pause / resume / inspect:**

```text
cron_update({ "name": "poll-upstream", "enabled": false })   # pause (keeps the definition)
cron_update({ "name": "poll-upstream", "enabled": true })    # resume; re-anchors next run to the future
cron_runs({ "name": "poll-upstream", "limit": 20 })          # status, duration, result/error per run
```

## Semantics

- **Minimum resolution is the tick interval** (30s by default); sub-minute schedules fire on the next tick after they come due.
- **No overlap**: a job still running when its next occurrence is due is not re-entered; missed occurrences coalesce into one delayed run. A claim that waits past its lock window is recorded as `skipped` (`QUEUE_WAIT_EXCEEDED_LOCK`), not double-run.
- **edge_function jobs require the functions module**; with it disabled, runs fail with `FUNCTIONS_DISABLED`.
- Run history is retained on a schedule (`NUBASE_CRON_RUN_RETENTION_DAYS`).

## Patterns

1. Deploy a function (`functions.md`), verify it with `functions_invoke`, **then** schedule it — don't schedule an unverified handler.
2. For data jobs, prefer a named `db_function` over an edge function calling `/rest/v1`: it runs in-database with a real timeout.
3. Name jobs after what they do (`refresh-stats`, `purge-old-rows`) — the name is the stable handle for update/delete.
4. After creating durable scheduling, record it with `memory_write` so a later session knows the job exists.

> All cron tools require the project's service_role apikey on the MCP connection. Schedules live in the control plane (metadata DB), so any number of Nubase instances run them safely without overlap.
