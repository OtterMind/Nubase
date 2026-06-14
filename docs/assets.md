# Assets (Static Asset CDN)

Nubase Assets gives every project a public static-asset endpoint: upload images, css, js, fonts or any other static file, and serve them with proper cache headers — no apikey required on the read path. The goal is that an AI coding agent (Claude Code, Codex, …) can build an app and publish its static resources through Nubase directly, alongside the database, auth, storage and functions it already gets.

```http
GET https://{projectRef}.{your-domain}/assets/v1/img/logo.png
```

## Architecture

Metadata (`assets.files`) and per-project delivery settings (`assets.settings`) live in each tenant database's `assets` schema. File bytes live in R2 in one of two delivery modes:

**CDN mode (recommended for production).** Set `nubase.assets.bucket` to a dedicated, publicly accessible R2 bucket with a custom domain in front (e.g. `assets.nubase.ai` on Cloudflare — nubase.ai production uses the `nubase-assets` bucket). Objects are keyed `{appCode}/{path}`, so public URLs are:

```text
{public-base-url}/{appCode}/{path}       e.g. https://assets.nubase.ai/app123/img/logo.png
```

Reads go straight to the CDN and never touch the Nubase backend. Cloudflare honors the `Cache-Control` and `Content-Type` written on the R2 object at upload time. Note: in this mode, changing the project's default Cache-Control only affects assets uploaded afterwards (the header is baked into the object), and deletes can stay cached on the CDN edge until the TTL expires.

**Backend mode (default for self-hosters).** Leave `nubase.assets.bucket` empty: bytes live in the global Storage bucket under the reserved `{appCode}/__assets__/{path}` prefix (Storage bucket names are limited to `[a-z0-9-]`, so the `__assets__` segment can never collide with a bucket), and the backend serves them at `/assets/v1/{path}` — the tenant is resolved from the request subdomain (same mechanism as public Storage downloads), responses carry `Cache-Control`/`ETag`/`Last-Modified` and answer conditional requests with `304 Not Modified`, so any CDN in front caches correctly.

## Public data plane

```http
GET  /assets/v1/{path}     # serve the asset
HEAD /assets/v1/{path}     # headers only
```

`Cache-Control` is the per-file override if one was set at upload time, otherwise the project default (`assets.settings.default_cache_control`, default `public, max-age=3600`).

## Control plane (service_role)

Base path `/assets/admin/v1`, authenticated with the project's service_role apikey:

```http
GET    /assets/admin/v1/files?prefix=&search=&limit=&offset=
POST   /assets/admin/v1/files/{path}      # create (409 if the path exists), raw request body
PUT    /assets/admin/v1/files/{path}      # upsert, raw request body
DELETE /assets/admin/v1/files/{path}
GET    /assets/admin/v1/settings
PATCH  /assets/admin/v1/settings
```

Upload example:

```bash
curl -X PUT "$NUBASE_URL/assets/admin/v1/files/img/logo.png?cacheControl=31536000" \
  -H "apikey: $SERVICE_ROLE_KEY" \
  -H "Content-Type: image/png" \
  --data-binary @logo.png
```

`cacheControl` accepts plain seconds (becomes `max-age=N`) or a full `Cache-Control` value. Omit it to inherit the project default.

Asset paths are restricted to URL-safe segments (`[A-Za-z0-9._-]`, separated by `/`); `.` / `..` segments are rejected, so a path can never escape the tenant prefix.

## Settings

`PATCH /assets/admin/v1/settings` accepts:

- `defaultCacheControl` — applied to assets without a per-file override.
- `customBaseUrl` — the project's own domain/CDN prefix; when set, public URLs become `{customBaseUrl}/{path}` (your CDN/domain mapping decides what sits behind the prefix). Empty string clears it.
- `maxFileSizeBytes` — per-project size cap (never above the platform cap `nubase.assets.max-file-size`, default 25MB). `0` clears the override.

## MCP tools

Agents connected over MCP can publish assets directly:

- `assets_upload(path, content | contentBase64, contentType, cacheControl, upsert)`
- `assets_list(prefix, search, limit, offset)`
- `assets_delete(path)`

`assets_upload` / `assets_delete` are gated by `NUBASE_ALLOW_ADMIN_WRITE=true` and require the service_role apikey on the MCP connection. There is also a `nubase_cli assets` CLI. See [mcp.md](mcp.md).

## Configuration

```yaml
nubase:
  assets:
    enabled: ${NUBASE_ASSETS_ENABLED:true}
    bucket: ${NUBASE_ASSETS_BUCKET:}                       # dedicated public bucket = CDN mode
    public-base-url: ${NUBASE_ASSETS_PUBLIC_BASE_URL:}     # e.g. https://assets.nubase.ai
    max-file-size: ${NUBASE_ASSETS_MAX_FILE_SIZE:26214400}  # 25MB
```

New projects get the `assets` schema automatically. Backfill projects provisioned before this module existed with:

```http
POST /auth/v1/admin/init/assets-schema           # all INITIALIZED tenants
POST /auth/v1/admin/init/assets-schema?dbKey=…   # a single tenant
```

(idempotent, service_role required — same shape as the mem-schema migration).
