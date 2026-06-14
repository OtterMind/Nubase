# Nubase Assets Reference

Use this reference when publishing a generated **frontend** or any static files: HTML/CSS/JS, images, fonts, `robots.txt`, the public asset CDN, `/assets/v1` delivery, or the `/assets/admin/v1` control plane.

Assets is **where the frontend an agent just generated goes live**. Upload the files, get back public URLs, and the app is reachable — no separate static host.

## Tools

Read (always available):

- `assets_list({ prefix?, search?, limit?, offset? })` — list published assets with their public URLs

Write (gated by `NUBASE_ALLOW_ADMIN_WRITE=true` and the project's service_role key; otherwise returns `{ success: false, ... }` without touching the backend):

- `assets_upload({ path, content | contentBase64, contentType?, cacheControl?, upsert? })` — publish one asset; returns the `AssetFileDTO` including the resolved `publicUrl`
- `assets_delete({ path })` — delete one asset

`assets_upload` takes **exactly one** of:

- `content` — UTF-8 text (html, css, js, svg, json, txt). Use this for generated source files.
- `contentBase64` — base64 bytes for binaries (png, jpg, woff2, …).

`contentType` is inferred from the asset path when omitted (`.html` → `text/html`, `.css` → `text/css`, …). `upsert` defaults to `true` (overwrite); set `false` to fail if the path already exists. `cacheControl` accepts plain seconds (`"31536000"` → `max-age=31536000`) or a full `Cache-Control` value; omit it to inherit the project default (`public, max-age=3600`).

## Public delivery

Published assets are served with cache headers and **no apikey on the read path**:

```http
GET  /assets/v1/{path}     # served with Cache-Control / ETag / Last-Modified
HEAD /assets/v1/{path}
```

The exact public origin is whatever `assets_upload` returns as `publicUrl` (it resolves the project's custom domain / CDN base when configured). Use that value in links and in generated HTML — don't hardcode a host.

## Worked Example: publish a one-page app

```text
assets_upload({
  "path": "index.html",
  "content": "<!doctype html><html><head><link rel=\"stylesheet\" href=\"/assets/v1/css/app.css\"></head><body><h1>Hi</h1><script src=\"/assets/v1/app.js\"></script></body></html>"
})
assets_upload({ "path": "css/app.css", "content": "body{font-family:system-ui;margin:2rem}", "cacheControl": "31536000" })
assets_upload({ "path": "app.js",      "content": "console.log('live')" })
```

Each call returns the public URL. Open the one for `index.html` to see the app. List what's published:

```text
assets_list({ "prefix": "css/" })
```

A binary asset (read the bytes, base64-encode, then upload):

```text
assets_upload({ "path": "img/logo.png", "contentBase64": "<base64>", "contentType": "image/png" })
```

## Patterns

1. **Reference assets by relative `/assets/v1/...` paths** inside generated HTML/CSS so the page is portable across domains.
2. **Long cache for fingerprinted files** (`app.abc123.js`): set a long `cacheControl`. Use a short/default cache for `index.html` so updates show up.
3. **Assets are fully public** — never publish secrets, `.env` files, or service_role keys. Static config that ships to the browser is fine; credentials are not.
4. **Frontend + backend together**: publish the UI to Assets, deploy backend logic as Functions (`functions.md`), and have the page call `/rest/v1` (with the anon key + user JWT) or `/functions/v1`. The frontend only ever uses the **anon key**.

> Asset paths are restricted to URL-safe segments; `.`/`..` segments are rejected, so a path can never escape the project's prefix. New projects get the `assets` schema automatically.
