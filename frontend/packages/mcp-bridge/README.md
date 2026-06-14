# nubase_cli

stdio MCP bridge for Nubase. Use it when Codex, Claude Code, Cursor, IDEA, or another local MCP client needs Nubase tools.

## Install

```bash
npx -y nubase_cli@latest
```

## Browser Authorization

Installing skills starts a one-time browser authorization session and prints an authorization URL:

```bash
npx -y nubase_cli@latest install-skills
```

This installs the bundled Claude/Codex skills into your user skill directories, writes a local project MCP bridge under `.nubase/mcp-bridge`, writes project MCP config for Claude Code, and starts browser authorization. Open the printed URL, sign in to Studio, choose a project, and approve. The URL includes a per-session UUID and points back to the temporary localhost callback started by the install command. After approval, the CLI writes project-local `.nubase/config.json` and closes the localhost callback server.

Restart Claude Code in the project after installing, then run `/mcp` and confirm `nubase` is connected.

For automation, skip the prompt:

```bash
npx -y nubase_cli@latest install-skills --no-authorize
```

To install project-local skill files instead of user-level skill files:

```bash
npx -y nubase_cli@latest install-skills --skills-scope project
```

To skip MCP config registration:

```bash
npx -y nubase_cli@latest install-skills --no-mcp
```

You can also start a standalone authorization session:

```bash
npx -y nubase_cli@latest authorize
```

Future `nubase_cli` runs read this file when `NUBASE_PROJECT_KEY` is not set.

Options:

```bash
npx -y nubase_cli@latest authorize \
  --studio-url http://localhost:3000 \
  --nubase-url http://localhost:9999 \
  --agent-id codex
```

For local development inside the Nubase repository:

```bash
cd frontend
pnpm --filter nubase_cli build
node packages/mcp-bridge/dist/src/index.js
```

## MCP Config

```json
{
  "mcpServers": {
    "nubase": {
      "type": "stdio",
      "command": "npx",
      "args": ["-y", "nubase_cli@latest"],
      "env": {
        "NUBASE_AGENT_ID": "claude-code",
        "NUBASE_CONFIG": "/absolute/project/path/.nubase/config.json"
      }
    }
  }
}
```

The bridge code is shared across projects via the npm cache; only `.nubase/config.json`
(holding this project's `projectKey`) is project-specific, pointed to by `NUBASE_CONFIG`.

You may still set `NUBASE_URL` and `NUBASE_PROJECT_KEY` explicitly. Environment variables take precedence over the saved authorization config.

## Install Agent Skills

Install the bundled Nubase skills and project MCP config:

```bash
npx -y nubase_cli@latest install-skills
```

Targets:

- `claude`: installs `~/.claude/skills/nubase/**`
- `codex`: installs `~/.codex/skills/nubase/**`
- `both`: installs both

Use `--skills-scope project` to write `.claude/skills/nubase/**` and `.codex/skills/nubase/**` in the current project instead.

By default, when the target includes `claude`, the command creates or merges a project `.mcp.json` that runs the bridge via `npx` (shared across projects through the npm cache):

```json
{
  "mcpServers": {
    "nubase": {
      "type": "stdio",
      "command": "npx",
      "args": ["-y", "nubase_cli@<version>"],
      "env": {
        "NUBASE_AGENT_ID": "claude-code",
        "NUBASE_CONFIG": "/absolute/project/path/.nubase/config.json"
      }
    }
  }
}
```

The `npx` spec is pinned to the installed CLI version for reproducibility. Pass
`--mcp-delivery local` instead to copy a hermetic, version-pinned bridge into
`.nubase/mcp-bridge` and reference it with `command: "node"` (no npm dependency at runtime).

Use `--mcp both` to also write project `.codex/config.toml` for Codex. Use `--no-mcp` to skip MCP config.

### Permissions

The MCP config's `env` block gates what the agent may do. Reads are always allowed; these flags gate write/execute tools:

| Flag | Default | Unlocks |
| --- | --- | --- |
| `NUBASE_ALLOW_SQL_EXECUTE` | **on** | `sql_execute` (run SQL) |
| `NUBASE_ALLOW_ADMIN_WRITE` | **on** | create/delete buckets & users, issue/revoke gateway keys |
| `NUBASE_ALLOW_DANGEROUS_SQL` | **off** | SQL classified DANGEROUS (DROP/TRUNCATE/...) |

`install-skills` writes SQL-execute and admin-write into the config by default; dangerous SQL stays off. Opt out per install with `--no-sql-execute` / `--no-admin-write`, or opt into dangerous SQL with `--allow-dangerous-sql`. You can also edit the flags directly in `.mcp.json` (or `.codex/config.toml`) afterwards.

Installed structure:

- `nubase/SKILL.md`
- `nubase/references/memory.md`
- `nubase/references/database.md`
- `nubase/references/auth-storage.md`
- `nubase/references/ai-gateway.md`
- `nubase/references/security.md`

The top-level skill tells the agent when to use Nubase. The `references/` files split detailed guidance by capability and are easier to extend.

## Context Injection

The bridge injects user and session context from environment variables:

```bash
NUBASE_URL=https://nubase.ai
NUBASE_PROJECT_KEY=YOUR_NUBASE_PROJECT_KEY
NUBASE_CONFIG=.nubase/config.json
NUBASE_USER_JWT=USER_ACCESS_TOKEN
NUBASE_USER_ID=USER_UUID
NUBASE_AGENT_ID=codex
NUBASE_RUN_ID=feature-123
```

Tool arguments can override `userId`, `agentId`, and `runId` for one call. API keys and JWTs stay in the bridge process environment and are not exposed as tool arguments.

## SQL Safety

SQL execution is disabled by default:

```bash
NUBASE_ALLOW_SQL_EXECUTE=true
NUBASE_ALLOW_DANGEROUS_SQL=false
```

Use `sql_dry_run` before `sql_execute`. Dangerous SQL remains blocked unless `NUBASE_ALLOW_DANGEROUS_SQL=true`.

Every successful schema-changing `sql_execute` is recorded to an append-only `nubase.migrations` audit table; review it with `db_list_migrations`. Disable the trail with `NUBASE_RECORD_MIGRATIONS=false`.

## Backend Ops Safety

Backend management tools are split into read and write halves. Read tools (`*_list_*`, `db_export_schema`, `gateway_usage`) are always available. Write/destructive tools (create/delete bucket, create/delete user, issue/revoke gateway key) are disabled by default:

```bash
NUBASE_ALLOW_ADMIN_WRITE=true
```

When disabled, a write tool returns `{ "success": false, "error": "...NUBASE_ALLOW_ADMIN_WRITE..." }` without touching the backend. All admin tools require the project key to carry `service_role` privileges.

## Tools

Core:

- `nubase_overview` — one-shot backend snapshot (capabilities, schema, buckets, auth users, gateway keys, permissions, next steps). Call this first.
- `nubase_capabilities`
- `nubase_instructions`
- `fetch_docs`
- `deploy_app` — one-call generated-app deploy from a manifest object (SQL, Functions, Assets, cron, Memory)
- `memory_context`
- `memory_search`
- `memory_write`
- `rest_select`
- `sql_dry_run`
- `sql_execute`

Backend ops (read):

- `db_export_schema` — export table DDL for a schema (default `public`)
- `db_list_migrations` — audit trail of schema changes applied via `sql_execute` (most recent first)
- `auth_list_users`
- `storage_list_buckets`
- `gateway_list_keys` — list AI Gateway `nbk_` keys
- `gateway_usage` — token/request/cost overview

Deploy (read):

- `assets_list` — published static assets and their public URLs
- `functions_list` / `functions_logs` / `functions_secrets_list` — Edge Functions
- `cron_list` / `cron_get` / `cron_runs` — scheduled jobs and run history

Backend ops (write, gated by `NUBASE_ALLOW_ADMIN_WRITE`):

- `auth_create_user` / `auth_delete_user`
- `storage_create_bucket` / `storage_delete_bucket`
- `gateway_issue_key` / `gateway_revoke_key`

Deploy (write, gated by `NUBASE_ALLOW_ADMIN_WRITE`):

- `deploy_app` — orchestrates SQL migrations, function deploys, frontend asset upload, cron jobs, and optional deployment memory
- `assets_upload` / `assets_delete` — publish the generated frontend to the public CDN
- `functions_new` / `functions_deploy` / `functions_invoke` / `functions_delete` / `functions_secrets_set` — deploy backend logic
- `cron_create` / `cron_update` / `cron_delete` — schedule recurring jobs

## App Deploy CLI

Deploy a generated app from one manifest:

```bash
NUBASE_ALLOW_SQL_EXECUTE=true \
NUBASE_ALLOW_ADMIN_WRITE=true \
nubase_cli app deploy nubase.deploy.json
```

Example `nubase.deploy.json`:

```json
{
  "name": "notes",
  "migrations": [{ "name": "schema", "file": "schema.sql" }],
  "functions": [
    {
      "name": "api",
      "dir": "nubase/functions/api",
      "verify": { "method": "GET" }
    }
  ],
  "assets": {
    "dir": "dist",
    "cacheControl": "public, max-age=31536000"
  },
  "cron": [
    {
      "name": "nightly",
      "cronExpression": "0 3 * * *",
      "targetType": "edge_function",
      "functionSlug": "api"
    }
  ],
  "rememberDeployment": true
}
```

The result includes `deploymentId`, per-step status, and `publicUrl` for the uploaded frontend.

For static frontend releases, use:

```json
{
  "assets": {
    "dir": "dist",
    "release": true,
    "releaseId": "v1",
    "spaFallback": true
  }
}
```

This uploads files under `__releases/<app>/<releaseId>/`, writes `__nubase_release.json`, and points SPA fallback at that release's `index.html`.

`deploy_app` scans Assets and Function bundles before upload for obvious secrets (`.env`, private keys, service-role-looking JWTs, common API key formats). Set `"securityScan": false` only for a deliberate internal deploy where this is acceptable.
The generated frontend must use the anon key from `project_keys`; never publish
the service_role key in Assets.

Inspect deployment records later:

```text
deployments_list({ "limit": 20 })
deployment_status({ "id": "<deploymentId>" })
deployment_logs({ "id": "<deploymentId>" })
deployment_rollback({ "id": "<deploymentId>" })
```

`deployment_rollback` is a guarded write operation. It deletes recorded Assets and cron jobs, then appends rollback actions to the deployment logs. SQL migrations, Memory writes, function deploys, and secret updates are recorded as skipped because the bridge does not store enough prior state to reverse them safely.

### Project lifecycle

Project lifecycle tools use platform-level auth, not a tenant project key:

```bash
export NUBASE_PLATFORM_JWT=...
# or
export NUBASE_PLATFORM_KEY=...
```

Available tools:

- `projects_list`
- `project_keys_admin`
- `project_provision`
- `project_update`
- `project_select_instructions`

`project_provision` and `project_update` also require `NUBASE_ALLOW_ADMIN_WRITE=true`. Project delete is intentionally not exposed.

## Edge Functions CLI

Create, deploy, and invoke project functions:

```bash
nubase_cli functions new hello
NUBASE_ALLOW_ADMIN_WRITE=true nubase_cli functions deploy hello
NUBASE_ALLOW_ADMIN_WRITE=true nubase_cli functions deploy hello --bundle
nubase_cli functions invoke hello --method POST --body '{"ok":true}'
nubase_cli functions logs hello --limit 50
nubase_cli functions secrets list hello
NUBASE_ALLOW_ADMIN_WRITE=true nubase_cli functions secrets set hello API_KEY=value
NUBASE_ALLOW_ADMIN_WRITE=true nubase_cli functions delete hello
```

Function source is scaffolded under `nubase/functions/<name>`. Deploy packages that directory and sends it to `/functions/admin/v1`.
The default scaffold uses `index.js` so it can be uploaded directly to Cloudflare Workers for Platforms without a TypeScript build step.
Use `--bundle` to compile an `index.ts`/`index.js` entrypoint and its import graph into a single Worker module with esbuild.
Function secrets are set by name and only secret names are returned by list commands.

## Scheduled Jobs (Cron) CLI

Manage project cron jobs that invoke edge functions or database functions on a schedule (`/cron/admin/v1`):

```bash
nubase_cli cron list
nubase_cli cron get nightly-cleanup
NUBASE_ALLOW_ADMIN_WRITE=true nubase_cli cron create nightly-cleanup --cron "0 3 * * *" --target edge_function --function cleanup --method POST --body '{"a":1}'
NUBASE_ALLOW_ADMIN_WRITE=true nubase_cli cron create purge --cron "*/5 * * * *" --target db_function --db-function purge_old_rows --args '{"days":7}'
NUBASE_ALLOW_ADMIN_WRITE=true nubase_cli cron update nightly-cleanup --cron "0 4 * * *" --disable
NUBASE_ALLOW_ADMIN_WRITE=true nubase_cli cron delete nightly-cleanup
nubase_cli cron runs nightly-cleanup --limit 50
nubase_cli cron runs --limit 50
```

`--args` must be a JSON object; `name` and `--target` are immutable after create. Writes require `NUBASE_ALLOW_ADMIN_WRITE=true`.

## Assets CLI

Publish a generated frontend (HTML/CSS/JS, images, fonts) to the project's public CDN, served at `/assets/v1/<path>` (`/assets/admin/v1`):

```bash
nubase_cli assets list --prefix css/ --limit 100
NUBASE_ALLOW_ADMIN_WRITE=true nubase_cli assets upload index.html --file ./dist/index.html
NUBASE_ALLOW_ADMIN_WRITE=true nubase_cli assets upload css/app.css --file ./dist/css/app.css --cache-control "public, max-age=31536000"
NUBASE_ALLOW_ADMIN_WRITE=true nubase_cli assets upload robots.txt --content "User-agent: *"
NUBASE_ALLOW_ADMIN_WRITE=true nubase_cli assets delete css/old.css
```

Pass either `--file <localPath>` (any file type, sent as bytes) or `--content <text>` for inline text. `Content-Type` is inferred from the asset path when `--content-type` is omitted. Upload upserts by default; `--create` makes it fail if the path already exists. The response includes the resolved `publicUrl`. Writes require `NUBASE_ALLOW_ADMIN_WRITE=true` and the project's service_role key.

## Publish

```bash
pnpm --filter nubase_cli typecheck
pnpm --filter nubase_cli build
pnpm --filter nubase_cli test
pnpm --filter nubase_cli pack:check
cd packages/mcp-bridge
npm publish --access public
```
