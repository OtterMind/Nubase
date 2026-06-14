# Nubase MCP and Agent Guide

Nubase exposes an MCP server for trusted AI coding agents. Agents use it to inspect schema, run controlled database operations, work with durable Memory, and **deploy generated apps** — publish frontends (Assets), deploy backend logic (Functions), and schedule jobs (cron). For the end-to-end deploy path, see [deploy-ai-generated-apps.md](deploy-ai-generated-apps.md).

## Endpoint

Remote HTTP MCP endpoint:

```text
http://localhost:9999/mcp
```

Most requests need:

```http
apikey: <project anon/authenticated/service_role key>
Authorization: Bearer <user JWT>
```

`Authorization` is optional for service-role workflows, but user-scoped Memory and RLS behavior should use a user JWT when available.

## Agent Metadata

Agents and setup UIs can discover Nubase capabilities through:

```text
GET /agent/v1/instructions
GET /agent/v1/capabilities
```

These endpoints intentionally do not return secrets.

## Local vs Remote MCP

Nubase supports two MCP delivery modes:

- **Local stdio bridge (`nubase_cli`)** — best for Codex / Claude Code. It can read the local workspace, bundle Edge Functions, upload a static `dist` directory, and run `deploy_app`.
- **Remote HTTP MCP (`/mcp`)** — best for hosted or URL-based clients. It exposes server-side tools for Memory, Database inspection/SQL, Assets, Auth admin, Storage bucket admin, AI Gateway keys, Edge Function metadata/bundle deploy/secrets/logs, and cron jobs.

Use the local bridge for operations that need local files: `functions_new`, `functions_deploy` from a source directory, `assets_upload` from a directory, and `deploy_app` from a manifest file. Use remote `/mcp` for already-materialized payloads and control-plane operations such as `functionsDeployBundle`, `assetsUpload`, `cronCreate`, `deploymentRollback`, `authCreateUser`, `storageCreateBucket`, and `gatewayIssueKey`.

Project lifecycle tools are platform-level, not tenant-level. Configure one of:

- `NUBASE_PLATFORM_JWT` — platform user session token.
- `NUBASE_PLATFORM_KEY` or `NUBASE_METADATA_SERVICE_ROLE_KEY` — metadata service-role key.

Then use:

- `projects_list()` — list visible projects.
- `project_keys_admin(ref)` — fetch service_role and authenticated keys for a project.
- `project_provision(ref)` — run pending/failed provisioning; requires `NUBASE_ALLOW_ADMIN_WRITE=true`.
- `project_update(ref, appName, description, enabled)` — rename/describe/pause/resume; requires `NUBASE_ALLOW_ADMIN_WRITE=true`.
- `project_select_instructions(ref, serviceRoleKey, anonKey)` — return env/config values needed to point an agent at that project. It does not write files.

## Start Here

- `nubase_overview(schema?)`: one-shot snapshot of the whole backend — capabilities, schema, buckets, auth users, gateway keys, permission gates, next steps. Call this first.
- `nubase_capabilities()` / `nubase_instructions()`: discover capabilities and safe-use guidance.
- `project_keys()`: the anon key (for browser/client code) and the service_role key (server-side only).
- `fetch_docs(topic)`: fetch bundled Nubase usage docs for agents.

## Auth, Storage, And AI Gateway Tools

- `auth_list_users(page?, perPage?, keyword?)`: list users.
- `auth_create_user(email, password?, phone?, role?)`, `auth_delete_user(userId, softDelete?)`: manage users. Write tools require `NUBASE_ALLOW_ADMIN_WRITE=true`.
- `auth_get_settings()`: read tenant auth settings.
- `auth_update_settings(settings)`, `auth_clear_settings()`: replace or clear tenant auth settings. Write tools require `NUBASE_ALLOW_ADMIN_WRITE=true`.
- `storage_list_buckets(search?, limit?, offset?)`: list buckets.
- `storage_create_bucket(name, public?, fileSizeLimit?)`, `storage_delete_bucket(bucketId)`: manage buckets. Write tools require `NUBASE_ALLOW_ADMIN_WRITE=true`.
- `storage_list_objects(bucketId, prefix?, search?, limit?, offset?, sortBy?)`: list objects in a bucket.
- `storage_create_signed_url(bucketId, path, expiresIn?)`, `storage_create_signed_urls(bucketId, paths, expiresIn?)`: create temporary download URLs.
- `storage_create_signed_upload_url(bucketId, path, upsert?)`: create a temporary upload URL. Requires `NUBASE_ALLOW_ADMIN_WRITE=true`.
- `gateway_list_keys()`, `gateway_usage(startDate?, endDate?)`: inspect AI Gateway keys and overview usage.
- `gateway_usage_daily(apiKeyId?, startDate?, endDate?)`, `gateway_usage_by_model(startDate?, endDate?)`, `gateway_usage_logs(apiKeyId?, startDate?, endDate?, page?, size?)`: usage detail.
- `gateway_pricing(all?)`: read model pricing rows.
- `gateway_issue_key(name?, description?, expiresAt?)`, `gateway_revoke_key(id)`: manage AI Gateway keys. Write tools require `NUBASE_ALLOW_ADMIN_WRITE=true`.

## Database Tools

- `rest_select(table, query?)`: query a table through `/rest/v1` with a PostgREST query string.
- `db_export_schema(schema?, tables?, includeDrop?)`: export table DDL to inspect structure (read-only).
- `sql_dry_run(sql)`: classify SQL risk and statement count without executing.
- `sql_execute(sql)`: execute SQL through the admin API. Gated by `NUBASE_ALLOW_SQL_EXECUTE=true`.
- `db_list_migrations(limit?)`: read the audit trail of schema changes applied via `sql_execute`.

`sql_execute` is powerful. Only enable it in trusted local or server-side environments, and `sql_dry_run` first.

## Deploy Tools — Assets, Functions, Scheduled Jobs

These are how an agent ships a generated app online. Write tools are gated behind `NUBASE_ALLOW_ADMIN_WRITE=true` and require connecting MCP with the project's service_role key.

### Assets (publish the frontend)

Static asset CDN MCP tools (see [assets.md](assets.md)). This is where a generated frontend (HTML/CSS/JS) goes:

- `assets_upload(path, content | contentBase64, contentType, cacheControl, upsert)`: publish a static asset to the project's public CDN (`/assets/v1/<path>`). Pass `content` for UTF-8 text files (css/js/html/svg) or `contentBase64` for binaries (images/fonts). `contentType` is inferred from the path when omitted; `upsert` defaults to `true`. Returns the public URL.
- `assets_list(prefix, search, limit, offset)`: list assets with their public URLs.
- `assets_delete(path)`: delete an asset.
- `assets_update_settings(defaultCacheControl, customBaseUrl, spaFallbackPath, maxFileSizeBytes)`: update delivery settings, including SPA fallback.

Example — publish a stylesheet:

```json
{
  "tool": "assets_upload",
  "arguments": {
    "path": "css/app.css",
    "content": "body { margin: 0; }",
    "cacheControl": "public, max-age=31536000"
  }
}
```

### Functions (deploy backend logic)

Edge Function control-plane MCP tools (see [edge-functions.md](edge-functions.md)):

- `functions_new(name)`: scaffold a local function under `nubase/functions/<name>` (writes local files only).
- `functions_deploy(name, dir, bundle, noBundle, noVerifyJwt)`: bundle and deploy a local function.
- `functions_invoke(name, method, path, body, contentType)`: invoke a deployed function over `/functions/v1`; returns the `{status, headers, body}` envelope.
- `functions_list()`, `functions_logs(name, limit)`, `functions_delete(name)`: manage functions and read invocation logs.
- `functions_secrets_list(name)`, `functions_secrets_set(name, secrets)`: manage per-function secrets (values are never returned).

### Scheduled Jobs (cron)

Scheduled-job MCP tools (see [scheduled-jobs.md](scheduled-jobs.md)):

- `cron_list()`, `cron_get(name)`: inspect jobs.
- `cron_create(name, cronExpression, targetType, ...)`: create a job. `targetType` is `edge_function` (set `functionSlug`, optional `httpMethod`/`requestPath`/`requestBody`) or `db_function` (set `dbFunctionName`, optional `dbFunctionArgs`).
- `cron_update(name, ...)`: update an existing job (`targetType` is immutable).
- `cron_delete(name)`: delete a job.
- `cron_runs(name?, limit)`: run history for one job, or project-wide when `name` is omitted.

Example — run a deployed function nightly:

```json
{
  "tool": "cron_create",
  "arguments": {
    "name": "nightly-cleanup",
    "cronExpression": "0 3 * * *",
    "targetType": "edge_function",
    "functionSlug": "cleanup"
  }
}
```

## Memory Tools

- `memory_context(task, topK?, userId?, agentId?, runId?)`: return compact task context plus structured memory hits.
- `memory_search(query, topK?, userId?, agentId?, runId?)`: search durable Memory.
- `memory_write(content, infer?, userId?, agentId?, runId?)`: write durable Memory.

Return task context:

```json
{
  "tool": "memory_context",
  "arguments": {
    "agentId": "codex",
    "task": "Implement billing settings page"
  }
}
```

Write a project decision:

```json
{
  "tool": "memory_write",
  "arguments": {
    "agentId": "claude-code",
    "content": "Project convention: service role keys must never be placed in generated frontend code.",
    "infer": true
  }
}
```

## Risk Levels

Recommended client behavior:

- `read`: schema inspection, Memory search, capability discovery.
- `write_safe`: Memory write, creating test data.
- `write_schema`: table, index, policy, or migration changes.
- `dangerous`: `drop`, `truncate`, bulk delete, reset, or destructive admin actions.

Agents should call a dry-run or ask for confirmation before dangerous operations. Nubase will add explicit SQL risk classification and audit records in the Agent Enablement roadmap.

## Connect Examples

Generic MCP config shape:

```json
{
  "mcpServers": {
    "nubase": {
      "command": "npx",
      "args": ["nubase_cli"],
      "env": {
        "NUBASE_URL": "http://localhost:9999",
        "NUBASE_PROJECT_KEY": "YOUR_NUBASE_PROJECT_KEY",
        "NUBASE_AGENT_ID": "codex"
      }
    }
  }
}
```

Remote MCP config shape, for clients that support URL-based MCP:

```json
{
  "mcpServers": {
    "nubase": {
      "url": "http://localhost:9999/mcp",
      "headers": {
        "apikey": "YOUR_NUBASE_PROJECT_KEY"
      }
    }
  }
}
```

AI Gateway model config is separate from MCP tools:

```bash
OPENAI_BASE_URL=http://localhost:9999/v1
OPENAI_API_KEY=YOUR_NUBASE_AI_GATEWAY_KEY
ANTHROPIC_BASE_URL=http://localhost:9999
ANTHROPIC_AUTH_TOKEN=YOUR_NUBASE_AI_GATEWAY_KEY
```

Some clients support both MCP tools and custom model base URLs. Some support only one. The stable Nubase contracts are endpoint plus headers for MCP, and OpenAI-compatible `/v1/*` plus Anthropic-compatible `/v1/messages` for AI Gateway.
