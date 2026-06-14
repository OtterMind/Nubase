# Connect Agents to Nubase

Use this guide to connect Codex, Claude Code, Cursor, IDEA, or another MCP-capable agent to Nubase.

Nubase exposes two separate surfaces:

- **Tools / MCP**: lets agents operate Nubase Memory, Database, Auth, and Storage, and **deploy** generated apps — publish frontends (Assets), deploy backend logic (Functions), and schedule jobs (cron).
- **Model Gateway**: lets agents route model calls through Nubase AI Gateway.

Some clients support both. Some support only MCP tools or only custom model base URLs.

## Required Values

Local defaults:

```bash
NUBASE_URL=http://localhost:9999
NUBASE_PROJECT_KEY=YOUR_NUBASE_PROJECT_KEY
NUBASE_AI_GATEWAY_KEY=YOUR_NUBASE_AI_GATEWAY_KEY
```

For trusted local agent workflows, `NUBASE_PROJECT_KEY` is usually the project `service_role` key. Do not place service-role keys in generated frontend code.

## Generic MCP

Install:

```bash
npx nubase_cli
```

Install bundled agent skills into the current repo:

```bash
npx nubase_cli install-skills --target both --project-dir .
```

After installing the skill files, the command prints an authorization URL and waits for approval. Open the URL, sign in to Studio, choose a project, and approve. The URL carries a per-session UUID plus a localhost callback address. Studio posts the selected project credentials back to that temporary `127.0.0.1` listener, the CLI writes `~/.nubase/config.json`, then the listener shuts down.

This follows the native-app loopback callback pattern from [RFC 8252](https://www.rfc-editor.org/rfc/rfc8252), similar to browser-based CLI login flows such as [`gh auth login`](https://cli.github.com/manual/gh_auth_login). Environment variables still override the saved config.

For automation, skip the authorization prompt:

```bash
npx nubase_cli install-skills --target both --project-dir . --no-authorize
```

This writes one Nubase skill directory under `.claude/skills/nubase` and `.codex/skills/nubase`. The skill is the instruction layer; the MCP bridge is the tool layer.

Installed structure:

- `SKILL.md`
- `references/memory.md`
- `references/database.md`
- `references/auth-storage.md`
- `references/assets.md` — publish the generated frontend
- `references/functions.md` — deploy backend logic
- `references/cron.md` — schedule recurring jobs
- `references/ai-gateway.md`
- `references/security.md`

Recommended stdio bridge:

```json
{
  "mcpServers": {
    "nubase": {
      "command": "npx",
      "args": ["nubase_cli"],
      "env": {
        "NUBASE_AGENT_ID": "agent-name"
      }
    }
  }
}
```

For remote or non-default installs, pass the URLs during install:

```bash
npx nubase_cli install-skills --target both --project-dir . \
  --studio-url https://studio.example.com \
  --nubase-url https://api.example.com
```

You can also start a standalone authorization session:

```bash
npx nubase_cli authorize
```

Manual configuration is still supported:

```json
{
  "env": {
    "NUBASE_URL": "http://localhost:9999",
    "NUBASE_PROJECT_KEY": "YOUR_NUBASE_PROJECT_KEY",
    "NUBASE_AGENT_ID": "agent-name"
  }
}
```

Local workspace development:

```bash
cd frontend
pnpm --filter nubase_cli build
NUBASE_URL=http://localhost:9999 \
NUBASE_PROJECT_KEY=YOUR_NUBASE_PROJECT_KEY \
NUBASE_AGENT_ID=codex \
node packages/mcp-bridge/dist/src/index.js
```

Remote MCP shape, for clients that support URL-based MCP:

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

If the client supports user-scoped headers, add:

```json
{
  "Authorization": "Bearer USER_JWT"
}
```

## User and Session Injection

The bridge injects user/session context from environment variables:

```bash
NUBASE_USER_JWT=USER_ACCESS_TOKEN
NUBASE_USER_ID=USER_UUID
NUBASE_AGENT_ID=codex
NUBASE_RUN_ID=feature-123
```

Tool arguments can override `userId`, `agentId`, and `runId` for a single call. API keys and JWTs are never passed as tool arguments, so the model does not need to see them.

SQL execution is disabled by default:

```bash
NUBASE_ALLOW_SQL_EXECUTE=true
NUBASE_ALLOW_DANGEROUS_SQL=false
```

Dangerous SQL such as `drop` or `truncate` remains blocked unless `NUBASE_ALLOW_DANGEROUS_SQL=true`.

Every successful schema-changing `sql_execute` is recorded to an append-only `nubase.migrations` audit table (timestamp, risk, SQL text, agent/run/user). Review it with the `db_list_migrations` tool, or disable the trail with `NUBASE_RECORD_MIGRATIONS=false`.

## Codex

Use the stdio bridge when Codex expects a local MCP command:

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

Recommended first prompt:

```text
Use the nubase MCP server. First call nubase_overview, then memory_context with this task.
Before changing database schema, inspect existing tables and policies.
To deploy: functions_deploy for backend logic, assets_upload to publish the frontend, cron_create to schedule jobs.
Write durable project decisions with memory_write.
```

## Claude Code

Use the stdio bridge:

```json
{
  "mcpServers": {
    "nubase": {
      "command": "npx",
      "args": ["nubase_cli"],
      "env": {
        "NUBASE_URL": "http://localhost:9999",
        "NUBASE_PROJECT_KEY": "YOUR_NUBASE_PROJECT_KEY",
        "NUBASE_AGENT_ID": "claude-code"
      }
    }
  }
}
```

For local workspace development before the package is published:

```json
{
  "mcpServers": {
    "nubase": {
      "command": "node",
      "args": ["frontend/packages/mcp-bridge/dist/src/index.js"],
      "env": {
        "NUBASE_URL": "http://localhost:9999",
        "NUBASE_PROJECT_KEY": "YOUR_NUBASE_PROJECT_KEY",
        "NUBASE_AGENT_ID": "claude-code"
      }
    }
  }
}
```

## Cursor

Use the stdio bridge in Cursor's MCP configuration:

```json
{
  "mcpServers": {
    "nubase": {
      "command": "npx",
      "args": ["nubase_cli"],
      "env": {
        "NUBASE_URL": "http://localhost:9999",
        "NUBASE_PROJECT_KEY": "YOUR_NUBASE_PROJECT_KEY",
        "NUBASE_AGENT_ID": "cursor"
      }
    }
  }
}
```

Recommended workspace rule:

```text
Use Nubase as the backend. Call nubase_overview, then memory_context before planning. Use REST /rest/v1 for app data, deploy logic with functions_deploy, publish the frontend with assets_upload, and never expose service_role keys in client code.
```

## IDEA / JetBrains

For MCP-capable JetBrains agent integrations, use the bridge when stdio is supported:

```json
{
  "mcpServers": {
    "nubase": {
      "command": "npx",
      "args": ["nubase_cli"],
      "env": {
        "NUBASE_URL": "http://localhost:9999",
        "NUBASE_PROJECT_KEY": "YOUR_NUBASE_PROJECT_KEY",
        "NUBASE_AGENT_ID": "idea"
      }
    }
  }
}
```

## AI Gateway

OpenAI-compatible clients:

```bash
OPENAI_BASE_URL=http://localhost:9999/v1
OPENAI_API_KEY=YOUR_NUBASE_AI_GATEWAY_KEY
```

Anthropic-compatible clients:

```bash
ANTHROPIC_BASE_URL=http://localhost:9999
ANTHROPIC_AUTH_TOKEN=YOUR_NUBASE_AI_GATEWAY_KEY
```

AI Gateway config is independent from MCP. Configure both when the same client should use Nubase tools and route model calls through Nubase.

## Supabase-Style App APIs

Nubase implements Supabase-compatible API subsets for common app-building workflows:

- Auth: `/auth/v1`
- REST/PostgREST-style data API: `/rest/v1`
- Storage: `/storage/v1`

Common request pattern:

```http
apikey: YOUR_NUBASE_PROJECT_KEY
Authorization: Bearer USER_JWT
```

For generated frontend apps, prefer the `authenticated` or `anon` project token plus user JWT. Keep `service_role` on trusted servers and local agent tooling only.

## Recommended Agent Startup Flow

1. Call the `nubase_overview` MCP tool first. One call returns capabilities, database schema, storage buckets, auth users, AI Gateway keys, the active permission gates, and suggested next steps — no need to call `capabilities` + schema + buckets + users + keys separately. (Without the MCP bridge, fall back to `GET /agent/v1/capabilities`.)
2. Call `memory_context({ task })`.
3. Inspect the schema returned by `nubase_overview` (or `db_export_schema`) before writing SQL; `sql_dry_run` before `sql_execute`.
4. Use `/rest/v1`, `/auth/v1`, and `/storage/v1` for generated app code.

When the task is to **deploy** a generated app, continue (deploy tools need `NUBASE_ALLOW_ADMIN_WRITE=true` and the service_role key):

5. Deploy backend logic with `functions_new` → `functions_deploy` → `functions_invoke`.
6. Publish the generated frontend with `assets_upload`; open the returned `publicUrl`.
7. Schedule recurring work with `cron_create` (after verifying the function).
8. Route any LLM calls through the AI Gateway env vars.
9. Write durable decisions (schema, deployed functions, asset paths, cron jobs) with `memory_write`.

See [Deploy an AI-generated app](deploy-ai-generated-apps.md) for the full walkthrough.

