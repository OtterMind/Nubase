# Nubase Functions Reference

Use this reference when deploying backend logic for a generated app: Edge Functions, serverless handlers, the `/functions/v1` invoke path, `/functions/admin/v1` control plane, function secrets, invocation logs, or scheduling a function to run on a cron.

Functions are **how AI-written backend code goes live**. You scaffold a function locally, deploy it, and Nubase serves it at a stable public URL — no separate hosting account.

## Tools

Read (always available):

- `functions_list()` — list deployed functions
- `functions_logs({ name?, limit? })` — invocation logs, optionally for one function
- `functions_secrets_list({ name })` — secret names only (values are never returned)

Write (gated by `NUBASE_ALLOW_ADMIN_WRITE=true`; otherwise returns `{ success: false, ... }` without touching the backend):

- `functions_new({ name })` — scaffold `nubase/functions/<name>/` locally (writes local files only; not gated, but listed here as the first deploy step)
- `functions_deploy({ name, dir?, bundle?, noBundle?, noVerifyJwt? })` — bundle and deploy the local function
- `functions_invoke({ name, method?, path?, body?, contentType? })` — invoke a deployed function and return the `{status, headers, body}` envelope (function-level 4xx/5xx are returned, not thrown)
- `functions_secrets_set({ name, secrets })` — set secrets from a `{ KEY: value }` object
- `functions_delete({ name })` — delete a function

## Invoke path

Deployed functions are public at:

```http
POST /functions/v1/{slug}
apikey: <project anon/authenticated/service_role key>
Authorization: Bearer <user jwt>   # required when verify_jwt is on
```

Generated frontend code calls this with the **anon key** (+ the user JWT) — never the service_role key.

## Workflow: scaffold → deploy → invoke

**1. Scaffold** (writes `index.js` + `nubase-function.json` under `nubase/functions/hello`):

```text
functions_new({ "name": "hello" })
```

The default entrypoint is plain `index.js` (uploadable as-is). Write the handler — a standard Worker-style fetch handler:

```js
export default {
  async fetch(request) {
    const { name = "world" } = await request.json().catch(() => ({}));
    return Response.json({ greeting: `hello ${name}` });
  },
};
```

**2. Deploy** (needs `NUBASE_ALLOW_ADMIN_WRITE=true`). TypeScript entrypoints (`index.ts`) are esbuild-bundled automatically; pass `bundle: true` to resolve a plain-JS import graph, or `noBundle: true` to upload the directory as-is:

```text
functions_deploy({ "name": "hello" })
functions_deploy({ "name": "hello", "bundle": true })
```

Each deploy is recorded as a version; failed deploys are kept too. Inspect with `GET /functions/admin/v1/functions/{slug}/versions`.

**3. Invoke** to verify (gated — invoking runs code with the service_role key):

```text
functions_invoke({ "name": "hello", "method": "POST", "body": "{\"name\":\"ada\"}" })
# -> { "status": 200, "headers": {...}, "body": "{\"greeting\":\"hello ada\"}" }
```

**4. Read logs:**

```text
functions_logs({ "name": "hello", "limit": 50 })
```

## Secrets

Set per-function secrets (encrypted in the metadata DB; injected as env to the function, as Worker `secret_text` bindings in Cloudflare mode):

```text
functions_secrets_set({ "name": "hello", "secrets": { "API_KEY": "sk-..." } })
functions_secrets_list({ "name": "hello" })   # names only
```

Never echo secret values back to the user or write them to Memory.

## verify_jwt and auth

- `verify_jwt=true` (default) requires a valid user JWT or a service-role caller. Set `noVerifyJwt: true` on deploy for a public, unauthenticated endpoint (e.g. a webhook receiver) — only when the function does its own auth.
- `service_role` is **not** injected into functions by default.
- Invocation logs record caller type and user id, but not raw `Authorization`/`apikey` values.

## Schedule a function

To run a deployed function on a recurring schedule, create a cron job with `targetType: "edge_function"` and `functionSlug: "<name>"` — see `cron.md`. Scheduled calls run with `callerRole=cron` (bypasses `verify_jwt`) and still get rate limits, secrets, and invocation logging.

## Limits

- Invocation rate limits are enforced per project and per function (`NUBASE_FUNCTIONS_PER_PROJECT_RPM` / `PER_FUNCTION_RPM`; `0` disables).
- Invocation logs are pruned on a retention schedule (`NUBASE_FUNCTIONS_INVOCATION_LOG_RETENTION_DAYS`).
- Runtime is a Worker-style provider (local executor or Cloudflare Workers for Platforms). Nubase is always the public gateway.

> `/functions/v1` is Supabase-Edge-Functions-style; don't assume every Supabase runtime API exists without a test. For runtime/provider configuration (local vs Cloudflare Workers for Platforms), call `fetch_docs({ "topic": "overview" })` or read the project's `docs/edge-functions.md`.
