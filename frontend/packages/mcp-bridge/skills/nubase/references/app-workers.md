# Nubase App Workers Reference

Use this reference when shipping or managing a **full app worker** — a generated app deployed as a single Cloudflare Worker (server module + bundled static assets) onto Nubase's Workers-for-Platforms dispatch namespace, reachable at its own preview host.

App workers are the **server-rendered / full-stack** deploy target. Unlike `assets_*` (static frontend only) or `functions_*` (individual edge handlers), one app worker bundles a server entry module **and** its client `dist/` into a single isolated deployment per project.

## When to use which deploy target

- Static frontend only (HTML/CSS/JS) → **Assets** (`assets_*`, `references/assets.md`).
- A few backend handlers → **Functions** (`functions_*`, `references/functions.md`).
- A whole app shipped as one server worker (SSR / API + bundled client) → **App worker** (this doc).

## Deploy

Deploying an app worker uploads a server bundle plus optional client assets. Because it is a multipart file upload, it runs through the CLI / deploy pipeline rather than a single MCP argument:

```http
POST /deployments/admin/v1/app-workers/deploy   (multipart/form-data, service_role)
  metadata   = JSON  { appCode, version, workerName?, mainModule, serverEntrypointPath?,
                       clientDistPath?, previewHost?, compatibilityDate?, compatibilityFlags?,
                       envBindings?, plainTextBindings?, secretTextBindings? }
  serverFile = one or more JS module files (must include mainModule)
  assetFile  = zero or more static asset files (served via the worker's ASSETS binding)
```

- `workerName` defaults to `appCode`; `previewHost` defaults to `<workerName>.ottermind.app`.
- `metadata.appCode` must match the calling project context (enforced server-side).
- A custom `workerName` must equal `appCode` or be namespaced under it (`<appCode>-<suffix>`, e.g. `appabc-preview`). The dispatch namespace is shared across tenants, so a worker name outside your appCode is rejected with 403.
- `NUBASE_PROJECT_REF` and `NUBASE_APP_VERSION` are injected as bindings automatically and cannot be overridden; pass app config via `plainTextBindings` and secrets via `secretTextBindings`.
- The deploy is recorded in the project's deployment history (`deployments_list` / `deployment_status`).

## Manage (after deploy)

Management is **scoped to workers this project has deployed** — the dispatch namespace is shared across all tenants, so list/status/delete only ever resolve workers found in *this* project's deployment history. You cannot read or delete another project's worker.

Read (always available):

- `app_workers_list()` — list this project's deployed app workers, each with `workerName`, latest `version`, `previewHost`, `publicUrl`, `lastDeploymentStatus`, `lastDeploymentId`, and timestamps.
- `app_worker_status({ workerName })` — one worker enriched with **live provider state**: returns `{ worker, existsOnProvider, provider }`, where `existsOnProvider:false` means the worker is no longer present on Cloudflare (e.g. deleted out-of-band).

Write (gated by `NUBASE_ALLOW_ADMIN_WRITE=true` and the project's service_role key; otherwise returns `{ success: false, ... }` without touching the backend):

- `app_worker_delete({ workerName })` — undeploy one app worker. Idempotent (deleting an already-absent worker still succeeds) and records an `app_worker_delete` entry in deployment history for audit. Returns `{ workerName, deleted, auditDeploymentId }`.

Equivalent control-plane HTTP endpoints (service_role):

```http
GET    /deployments/admin/v1/app-workers
GET    /deployments/admin/v1/app-workers/{workerName}
DELETE /deployments/admin/v1/app-workers/{workerName}
```

## Worked Example: inspect then retire a worker

```text
app_workers_list()
# → [{ workerName: "my-app", version: "2", publicUrl: "https://my-app.ottermind.app", lastDeploymentStatus: "succeeded", ... }]

app_worker_status({ "workerName": "my-app" })
# → { worker: {...}, existsOnProvider: true, provider: { ...live Cloudflare script details... } }

app_worker_delete({ "workerName": "my-app" })   # needs NUBASE_ALLOW_ADMIN_WRITE=true + service_role
# → { workerName: "my-app", deleted: true, auditDeploymentId: "..." }
```

## Safety

- Deleting an app worker takes the live app offline immediately — ask the user before calling `app_worker_delete`, the same as any destructive deploy op.
- Never put service_role keys or other secrets in `plainTextBindings` (those are plain text) or in bundled `assetFile` contents (assets are public). Use `secretTextBindings` for secrets.
- After deploying or retiring an app worker, `memory_write` the durable facts (worker name, preview host, version).
