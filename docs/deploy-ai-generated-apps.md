# Deploy an AI-Generated App

This is the end-to-end path an agent (Claude Code, Codex, Cursor) follows to turn generated code into a real, online app on Nubase — frontend, backend logic, data, auth, and scheduled work, all on one platform.

Nubase exposes eight capability modules; four of them are the **deploy moves**:

| Move | Module | Tool | What goes live |
| --- | --- | --- | --- |
| Model data | Database | `sql_execute`, `rest_select` | Tables + RLS |
| Authenticate | Auth | `auth_*`, `/auth/v1` | Users, sessions |
| Ship backend logic | **Functions** | `functions_deploy` | Edge functions at `/functions/v1` |
| Publish the frontend | **Assets** | `assets_upload` | Static site at `/assets/v1` |
| Schedule work | **cron** | `cron_create` | Recurring jobs |

The remaining modules support them: **AI Gateway** (`gateway_*`) routes LLM calls, **Memory** (`memory_*`) persists decisions, **Storage** (`storage_*`) holds user-uploaded files.

## Prerequisites

- The `nubase` MCP server connected (`/mcp` shows it), or `nubase_cli` configured.
- Deploy (write) tools are gated. The MCP bridge needs:
  - `NUBASE_ALLOW_ADMIN_WRITE=true` — enables `functions_deploy`, `assets_upload`, `cron_create`, etc.
  - `NUBASE_ALLOW_SQL_EXECUTE=true` — enables `sql_execute` for DDL.
  - the project's **service_role** key (admin planes require it).
- The frontend only ever uses the **anon** key + user JWTs. Never ship service_role to the browser or publish it as an asset.

Start every session with `nubase_overview()` (one-shot backend state) and `memory_context({ task })`.

## 1. Model the data (Database + Auth)

Inspect first, then create tables with RLS for user-owned data:

```text
sql_dry_run({ "sql": "<DDL>" })
sql_execute({ "sql": "<DDL>" })
```

```sql
create table public.notes (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users (id) on delete cascade,
  body text not null,
  created_at timestamptz not null default now()
);
alter table public.notes enable row level security;
create policy "owner reads"   on public.notes for select using (auth.uid() = user_id);
create policy "owner writes"  on public.notes for insert with check (auth.uid() = user_id);
```

The frontend signs users in via `/auth/v1` and reads/writes `/rest/v1/notes` with the anon key + the user JWT — RLS scopes rows automatically. For RLS and auth detail, see the `database.md` and `auth-storage.md` skill references.

## 2. Deploy backend logic (Functions)

For anything beyond CRUD (webhooks, server-side composition, calling third-party APIs with secrets):

```text
functions_new({ "name": "summarize" })          # scaffolds nubase/functions/summarize/
# ...write the handler in index.js...
functions_deploy({ "name": "summarize" })        # creates + deploys; needs NUBASE_ALLOW_ADMIN_WRITE=true
functions_secrets_set({ "name": "summarize", "secrets": { "OPENAI_API_KEY": "sk-..." } })
functions_invoke({ "name": "summarize", "method": "POST", "body": "{\"text\":\"...\"}" })
```

Deploy before setting secrets — secrets attach to an existing function (a `functions_secrets_set` on a never-deployed slug fails), and they take effect on the next invocation without a redeploy. The function is live at `POST /functions/v1/summarize`. The frontend calls it with the anon key (+ user JWT when `verify_jwt` is on).

## 3. Publish the frontend (Assets)

Upload the generated static files; each call returns the public URL:

```text
assets_upload({ "path": "index.html", "content": "<!doctype html>..." })
assets_upload({ "path": "css/app.css", "content": "body{...}", "cacheControl": "31536000" })
assets_upload({ "path": "app.js",      "content": "fetch('/functions/v1/summarize', ...)" })
```

Open the `publicUrl` returned for `index.html` — the app is online. Reference assets and APIs by relative paths (`/assets/v1/...`, `/rest/v1/...`, `/functions/v1/...`) so the page stays portable.

## 4. Schedule recurring work (cron)

Run a deployed function or a database function on a schedule:

```text
cron_create({
  "name": "nightly-digest",
  "cronExpression": "0 7 * * *",
  "targetType": "edge_function",
  "functionSlug": "summarize"
})
```

Verify the function with `functions_invoke` before scheduling it. Inspect runs with `cron_runs({ name })`.

## 5. Route AI calls (AI Gateway)

If the app calls an LLM, point it at the gateway instead of a provider directly, so usage and cost are tracked per project:

```bash
OPENAI_BASE_URL=$NUBASE_URL/v1
OPENAI_API_KEY=$NUBASE_AI_GATEWAY_KEY          # an nbk_ key from gateway_issue_key
ANTHROPIC_BASE_URL=$NUBASE_URL
ANTHROPIC_AUTH_TOKEN=$NUBASE_AI_GATEWAY_KEY
```

## 6. Remember the deployment (Memory)

Record durable facts so the next session doesn't re-derive them:

```text
memory_write({ "content": "App 'notes' deployed: frontend at /assets/v1/index.html, backend fn 'summarize', nightly-digest cron at 07:00 UTC, notes table uses RLS owner policies." })
```

## Deployment checklist

Before calling an app "done", confirm:

- [ ] Tables created; RLS policies on every user-owned table.
- [ ] Auth flow works (signup/login returns a JWT the frontend stores).
- [ ] Backend functions deployed and `functions_invoke` returns 200.
- [ ] Function secrets set (not hardcoded, not in Memory).
- [ ] Frontend published to Assets; the public `index.html` URL loads.
- [ ] No service_role key in any published asset or client code.
- [ ] Scheduled jobs created and `cron_runs` shows them firing (if used).
- [ ] AI calls routed through the gateway (if the app uses an LLM).
- [ ] Deployment facts written to Memory.

## Where this maps in the codebase

- Skill references: `functions.md`, `assets.md`, `cron.md`, `database.md`, `auth-storage.md`, `ai-gateway.md` (installed under `skills/nubase/references/`).
- Capability docs: [edge-functions.md](edge-functions.md), [assets.md](assets.md), [scheduled-jobs.md](scheduled-jobs.md), [mcp.md](mcp.md).
