# Nubase Auth and Storage Reference

Use this reference when implementing Nubase Auth, users, sessions, JWTs, Supabase-style `/auth/v1`, object storage, buckets, signed URLs, `/storage/v1`, uploads, downloads, or file metadata.

## Admin Ops Tools

These tools run against admin endpoints and require the project key to carry `service_role`.

Read (always available):

- `auth_list_users({ page?, perPage?, keyword? })`
- `storage_list_buckets({ search?, limit?, offset? })`

Write (gated by `NUBASE_ALLOW_ADMIN_WRITE=true`; otherwise they return `{ success: false, error }` without calling the backend):

- `auth_create_user({ email, password?, phone?, role? })`
- `auth_delete_user({ userId, softDelete? })`
- `storage_create_bucket({ name, public?, fileSizeLimit? })`
- `storage_delete_bucket({ bucketId })`

Prefer the read tools for inspection. Before any write tool, confirm the user asked for it; bucket and user deletion are destructive and need explicit confirmation.

> Storage is for **user-uploaded files** (private, signed-URL access). To publish a generated **frontend** (public static HTML/CSS/JS/images), use Assets instead — see `assets.md`. To deploy backend logic, see `functions.md`.

## Auth

Auth base path:

```text
/auth/v1
```

Use Auth for:

- signup
- login/token exchange
- refresh tokens
- current user
- trusted admin user management

Generated frontend apps should use anon/authenticated keys plus user JWTs. Service-role keys must stay server-side or inside trusted local agent tooling.

Get the keys with the `project_keys` tool: it returns the `anonKey` (safe for browser/client code, where `<anon key>` appears below) and the `serviceRoleKey` (server-side only). The anon key is captured when you authorize the CLI; if it is unavailable, copy the authenticated key from the Studio project Settings page and set `NUBASE_ANON_KEY` in the bridge env.

### Worked Example: signup → login → current user

```http
POST /auth/v1/signup
apikey: <anon key>
Content-Type: application/json

{ "email": "ada@example.com", "password": "s3cret-pass" }
```

```http
POST /auth/v1/token?grant_type=password
apikey: <anon key>
Content-Type: application/json

{ "email": "ada@example.com", "password": "s3cret-pass" }
```

Response carries the JWT the app stores and replays on every user-scoped request:

```json
{ "access_token": "<JWT>", "token_type": "bearer", "expires_in": 3600,
  "refresh_token": "<refresh>", "user": { "id": "f3a...07", "email": "ada@example.com" } }
```

```http
GET /auth/v1/user
apikey: <anon key>
Authorization: Bearer <JWT>
```

Refresh with `POST /auth/v1/token?grant_type=refresh_token` and body `{ "refresh_token": "<refresh>" }`.

When implementing auth:

1. Keep API base configurable.
2. Do not hardcode service_role.
3. Store user-owned data with owner fields such as `user_id`.
4. Respect RLS assumptions.
5. Use user JWTs for user-scoped requests.

## Storage

Storage base path:

```text
/storage/v1
```

Use Storage for:

- buckets
- public objects
- private/authenticated objects
- signed URLs
- resumable uploads when supported by the app flow

### Worked Example: private upload via signed URL

```text
storage_create_bucket({ "name": "avatars", "public": false })   # gated by NUBASE_ALLOW_ADMIN_WRITE
```

Ask the backend for a one-time signed upload URL, then PUT the bytes to it:

```http
POST /storage/v1/object/upload/sign/avatars/f3a...07/photo.png
apikey: <anon or authenticated key>
Authorization: Bearer <user JWT>
```

```json
{ "signedUrl": "/storage/v1/object/upload/sign/avatars/...?token=<token>" }
```

```http
PUT <signedUrl>
Content-Type: image/png

<binary file bytes>
```

Serve it back later with a short-lived signed download URL, and record the path in an app table:

```http
POST /storage/v1/object/sign/avatars/f3a...07/photo.png    Body: { "expiresIn": 3600 }
```

```text
# then persist the reference
POST /rest/v1/profiles   Body: { "avatar_path": "avatars/f3a...07/photo.png" }
```

When generating file flows:

1. Validate file size and MIME type.
2. Use signed URLs or authenticated endpoints for private data.
3. Use public buckets only for intentionally public assets.
4. Avoid service_role in browser uploads.
5. Store file references in app tables through `/rest/v1` when needed.

> `/auth/v1` and `/storage/v1` are Supabase-style subsets; don't assume every Supabase SDK behavior exists without a compatibility test.
