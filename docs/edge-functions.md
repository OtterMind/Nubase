# Edge Functions

Nubase Edge Functions provide Supabase-style function endpoints with Nubase Spring Boot as the public gateway:

```http
POST /functions/v1/{functionName}
apikey: <project anon/authenticated/service_role key>
Authorization: Bearer <user jwt>
```

The Spring gateway resolves the project, validates the project key, applies `verify_jwt`, records invocation logs, and delegates execution to a configured provider.

## Architecture

```text
Client
  -> Nubase Spring /functions/v1/{name}
  -> EdgeFunctionExecutor
  -> local executor or Cloudflare private dispatcher
  -> user function
```

Function metadata lives in the metadata database, not in each project database. Tables include:

- `edge_functions`
- `edge_function_versions`
- `edge_function_secrets`
- `edge_function_invocations`

## CLI Usage

Create a function:

```bash
nubase_cli functions new hello
```

Deploy it:

```bash
NUBASE_ALLOW_ADMIN_WRITE=true nubase_cli functions deploy hello
```

TypeScript entrypoints (`index.ts`) are bundled with esbuild automatically; pass
`--bundle` to force bundling for plain-JS import graphs, or `--no-bundle` to upload
the directory as-is (the server rejects uncompiled TypeScript with
`TYPESCRIPT_REQUIRES_BUNDLE`):

```bash
NUBASE_ALLOW_ADMIN_WRITE=true nubase_cli functions deploy hello --bundle
```

Invoke it:

```bash
nubase_cli functions invoke hello --method POST --body '{"ok":true}'
```

Manage logs and secrets:

```bash
nubase_cli functions logs hello --limit 50
nubase_cli functions secrets list hello
NUBASE_ALLOW_ADMIN_WRITE=true nubase_cli functions secrets set hello API_KEY=value
NUBASE_ALLOW_ADMIN_WRITE=true nubase_cli functions delete hello
```

Admin APIs expose deployment state directly:

- `GET /functions/admin/v1/functions/{slug}` returns the active version.
- `GET /functions/admin/v1/functions/{slug}/versions` returns all deploy attempts, including failed versions.

## Runtime Providers

Default local executor:

```yaml
nubase:
  functions:
    executor:
      provider: local
      local:
        base-url: http://localhost:8787
```

Cloudflare dispatcher mode:

```yaml
nubase:
  functions:
    executor:
        provider: cloudflare
      per-project-rpm: ${NUBASE_FUNCTIONS_PER_PROJECT_RPM:600}
      per-function-rpm: ${NUBASE_FUNCTIONS_PER_FUNCTION_RPM:120}
      invocation-log-retention-days: ${NUBASE_FUNCTIONS_INVOCATION_LOG_RETENTION_DAYS:30}
      cloudflare:
        account-id: ${CLOUDFLARE_ACCOUNT_ID}
        dispatch-namespace: ${CLOUDFLARE_WORKERS_DISPATCH_NAMESPACE}
        dispatcher-url: ${NUBASE_FUNCTIONS_CLOUDFLARE_DISPATCHER_URL}
        dispatcher-secret: ${NUBASE_FUNCTIONS_CLOUDFLARE_DISPATCHER_SECRET}
```

In this mode Nubase remains the public gateway. The Cloudflare dispatcher is private and receives signed requests from Nubase.

The dispatcher template lives in `cloudflare/functions-dispatcher/`. Deploy it with Wrangler after creating a Workers for Platforms dispatch namespace:

```bash
cd cloudflare/functions-dispatcher
cp wrangler.toml.example wrangler.toml
wrangler secret put NUBASE_DISPATCHER_SECRET
wrangler deploy
```

Set `NUBASE_FUNCTIONS_CLOUDFLARE_DISPATCHER_URL` to the deployed dispatcher URL and use the same `NUBASE_DISPATCHER_SECRET` value in `NUBASE_FUNCTIONS_CLOUDFLARE_DISPATCHER_SECRET`.

## Security

- `verify_jwt=true` requires a valid user JWT or service-role caller.
- `service_role` is not injected into functions by default.
- Function secrets are encrypted in the metadata database.
- Cloudflare deployments inject function secrets as Worker `secret_text` bindings.
- Invocation logs store caller type and user id, but not raw `Authorization` or `apikey` values.
- Invocation rate limits are enforced per project and per function. Redis is used when configured, otherwise Nubase falls back to per-process Caffeine counters for single-node installs. Set `NUBASE_FUNCTIONS_PER_PROJECT_RPM=0` or `NUBASE_FUNCTIONS_PER_FUNCTION_RPM=0` to disable either limiter.
- Invocation logs are pruned by a scheduled retention job. Set `NUBASE_FUNCTIONS_INVOCATION_LOG_RETENTION_DAYS=0` to disable pruning.

## Current Limitations

- Default deploy uploads the function directory as-is, except TypeScript entrypoints which are esbuild-bundled automatically. Use `--bundle` for plain-JS import graphs that need resolving.
- Cloudflare Workers for Platforms script upload is handled synchronously. Nubase records each deploy attempt as a version and retries transient `429` and `5xx` upload responses.
- Invocation log retention is implemented; table partitioning is not.
