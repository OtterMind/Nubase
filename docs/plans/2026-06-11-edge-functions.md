# Edge Functions Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add Supabase-style Edge Functions to Nubase with the public function gateway implemented in Spring Boot and a pluggable execution plane that can later target Cloudflare Workers for Platforms.

**Architecture:** Nubase Spring remains the public data-plane gateway for `/functions/v1/{name}/**`, reusing the existing `apikey`, project routing, user JWT, and `service_role` model. Function metadata, versions, secrets, deployments, and invocation logs live in the metadata database. Execution is abstracted behind a provider interface: MVP uses an HTTP/mock executor; production can use Cloudflare Workers for Platforms via a provider without moving public auth logic out of Nubase.

**Tech Stack:** Spring Boot 3.2, Flyway, metadata PostgreSQL, existing `UnifiedMultiTenancyFilter`, existing `@RequireServiceRole`, Java `HttpClient`/Spring `RestClient`, Next.js Studio, `nubase_cli`, optional Cloudflare Workers for Platforms.

---

## Requirements Summary

Functional requirements:

- Public invocation endpoint compatible with Supabase-style paths: `/{base}/functions/v1/{functionName}`.
- Admin control-plane APIs for listing, creating, updating, deploying, disabling, and deleting functions.
- Per-function `verify_jwt`, enabled flag, import map / entrypoint metadata, environment variable references, and version tracking.
- Invocation logs with status, duration, request id, function name, project ref, and error summary.
- Execution provider abstraction so Nubase can start with a local HTTP executor and later add Cloudflare Workers for Platforms.
- Studio page for function management and invocation/log inspection.
- CLI commands for function scaffold, deploy, list, and invoke.

Non-functional requirements:

- Do not expose project JWT secrets, DB credentials, platform encryption keys, or Cloudflare API tokens to user functions.
- Do not inject `service_role` by default. Privileged functions require explicit configuration.
- Spring gateway must remain horizontally scalable; all mutable function state lives in DB or external execution provider.
- Invocation path must fail closed when function metadata, deployment, or auth checks are ambiguous.
- Logs must redact `Authorization`, `apikey`, `service_role`, `secret`, `token`, and environment variable values.

## High-Level Design

```text
Client
  |
  | /functions/v1/:name/**
  | apikey + optional Authorization
  v
Nubase Spring Functions Gateway
  | UnifiedMultiTenancyFilter resolves project
  | FunctionsGatewayController checks function metadata + verify_jwt
  | EdgeFunctionExecutor invokes provider
  v
Execution Provider
  | MVP: local/mock HTTP executor
  | Later: Cloudflare Workers for Platforms
  v
User Function
  |
  | fetch Nubase REST/Auth/Storage/Memory with scoped env
  v
Nubase APIs
```

## Key Decisions

Decision 1: Spring is the public function gateway.

- Rationale: Nubase already centralizes `apikey`, tenant routing, JWT validation, and `service_role` checks in Spring.
- Trade-off: Spring stays in the invocation path and can become a data-plane scaling concern.
- Mitigation: Keep gateway stateless, cache function metadata, and scale horizontally. Later split `functions-gateway` as a Spring microservice using the same auth library if needed.

Decision 2: Execution provider is pluggable from day one.

- Rationale: It avoids coupling control-plane work to Cloudflare details and lets tests run without external infrastructure.
- Trade-off: Extra interface and provider config before Cloudflare is fully implemented.
- Mitigation: Keep provider contract small: deploy, delete, invoke, health.

Decision 3: User functions call Nubase APIs over HTTP.

- Rationale: RLS/Auth/Storage/Memory behavior remains consistent and no DB credentials are exposed.
- Trade-off: Extra internal HTTP hop.
- Mitigation: Use short timeouts and connection pooling; optimize later only with measured data.

Decision 4: `service_role` injection is opt-in.

- Rationale: A function with `service_role` bypasses RLS and is equivalent to trusted backend code.
- Trade-off: Some functions need explicit setup.
- Mitigation: Add Studio warnings and a `privileged` flag.

---

## Phase 0: Architecture And Baseline

### Task 1: Document The Architecture Decision

**Files:**

- Create: `docs/adr/0001-edge-functions-gateway.md`
- Modify: `docs/architecture.md`
- Modify: `docs/supabase-comparison.md`

**Step 1: Create ADR**

Add an ADR that records:

- Spring Boot is the public `/functions/v1/**` gateway.
- Execution provider is pluggable.
- Cloudflare Workers for Platforms is an optional execution provider, not the public gateway.
- User functions receive minimal env by default.

**Step 2: Update architecture docs**

Add an `Edge Functions` section to `docs/architecture.md` after the Storage/Memory sections:

```markdown
## Edge Functions

Nubase routes `/functions/v1/*` through the Spring Boot API so project resolution, apikey validation, user JWT validation, service-role policy, rate limiting, and audit logging stay in the same security boundary as Auth, REST, Storage, Memory, and AI Gateway.

Function execution is delegated to a provider behind `EdgeFunctionExecutor`. The first provider is local/HTTP for development and tests; production deployments may use Cloudflare Workers for Platforms.
```

**Step 3: Update comparison docs**

Change Edge Functions status in `docs/supabase-comparison.md` from `Not implemented yet` to `Planned / in progress` while implementation is incomplete.

**Step 4: Verify docs render**

Run:

```bash
rg -n "Edge Functions|functions/v1|Cloudflare" docs
```

Expected: the new ADR and docs sections are listed.

**Step 5: Commit**

```bash
git add docs/adr/0001-edge-functions-gateway.md docs/architecture.md docs/supabase-comparison.md
git commit -m "docs: record edge functions architecture"
```

---

## Phase 1: Metadata Schema

### Task 2: Add Metadata Tables

**Files:**

- Create: `src/main/resources/db/migration/V4__edge_functions.sql`
- Test: `src/test/java/ai/nubase/functions/EdgeFunctionSchemaTest.java`

**Step 1: Write the migration**

Create metadata tables:

```sql
CREATE TABLE IF NOT EXISTS edge_functions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_ref VARCHAR(128) NOT NULL,
    name VARCHAR(128) NOT NULL,
    slug VARCHAR(128) NOT NULL,
    description TEXT,
    verify_jwt BOOLEAN NOT NULL DEFAULT TRUE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    privileged BOOLEAN NOT NULL DEFAULT FALSE,
    import_map JSONB,
    entrypoint VARCHAR(512) NOT NULL DEFAULT 'index.ts',
    active_version_id UUID,
    created_by VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_edge_functions_project_slug UNIQUE (project_ref, slug),
    CONSTRAINT chk_edge_function_slug CHECK (slug ~ '^[a-zA-Z0-9_-]{1,128}$')
);

CREATE TABLE IF NOT EXISTS edge_function_versions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    function_id UUID NOT NULL REFERENCES edge_functions(id) ON DELETE CASCADE,
    version_no INTEGER NOT NULL,
    source_hash VARCHAR(128) NOT NULL,
    artifact_uri TEXT,
    artifact_type VARCHAR(64) NOT NULL DEFAULT 'source_bundle',
    provider VARCHAR(64) NOT NULL DEFAULT 'local',
    provider_deployment_id TEXT,
    status VARCHAR(32) NOT NULL DEFAULT 'pending',
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    activated_at TIMESTAMPTZ,
    CONSTRAINT uq_edge_function_versions_no UNIQUE (function_id, version_no)
);

ALTER TABLE edge_functions
    ADD CONSTRAINT fk_edge_functions_active_version
    FOREIGN KEY (active_version_id) REFERENCES edge_function_versions(id)
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE IF NOT EXISTS edge_function_secrets (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    function_id UUID NOT NULL REFERENCES edge_functions(id) ON DELETE CASCADE,
    name VARCHAR(128) NOT NULL,
    encrypted_value TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_edge_function_secret_name UNIQUE (function_id, name),
    CONSTRAINT chk_edge_function_secret_name CHECK (name ~ '^[A-Z_][A-Z0-9_]{0,127}$')
);

CREATE TABLE IF NOT EXISTS edge_function_invocations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    request_id VARCHAR(128) NOT NULL,
    project_ref VARCHAR(128) NOT NULL,
    function_slug VARCHAR(128) NOT NULL,
    function_version_id UUID,
    method VARCHAR(16) NOT NULL,
    path TEXT NOT NULL,
    status_code INTEGER,
    duration_ms INTEGER,
    executor_provider VARCHAR(64),
    error_code VARCHAR(64),
    error_message TEXT,
    caller_role VARCHAR(64),
    caller_user_id VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_edge_functions_project_slug
    ON edge_functions (project_ref, slug);

CREATE INDEX IF NOT EXISTS idx_edge_invocations_project_created
    ON edge_function_invocations (project_ref, created_at DESC);
```

**Step 2: Add schema smoke test**

Use the existing test style in `src/test/java/ai/nubase/postgrest/PostgRESTApplicationTests.java`. Add a test that starts Spring with Flyway and checks `edge_functions` exists in the metadata DB.

**Step 3: Run migration tests**

Run:

```bash
mvn -q test -Dtest=EdgeFunctionSchemaTest
```

Expected: PASS.

**Step 4: Commit**

```bash
git add src/main/resources/db/migration/V4__edge_functions.sql src/test/java/ai/nubase/functions/EdgeFunctionSchemaTest.java
git commit -m "feat: add edge functions metadata schema"
```

---

## Phase 2: Backend Domain And Repositories

### Task 3: Add Function Domain Models

**Files:**

- Create: `src/main/java/ai/nubase/functions/entity/EdgeFunction.java`
- Create: `src/main/java/ai/nubase/functions/entity/EdgeFunctionVersion.java`
- Create: `src/main/java/ai/nubase/functions/entity/EdgeFunctionSecret.java`
- Create: `src/main/java/ai/nubase/functions/entity/EdgeFunctionInvocation.java`
- Create: `src/main/java/ai/nubase/functions/dto/EdgeFunctionDtos.java`
- Test: `src/test/java/ai/nubase/functions/EdgeFunctionEntityTest.java`

**Step 1: Write DTO validation tests**

Test:

- valid slugs: `hello`, `hello_world`, `hello-world`, `v1`.
- invalid slugs: empty, `/x`, `../x`, `x y`.
- valid secret names: `API_KEY`, `NUBASE_CUSTOM_TOKEN`.
- invalid secret names: `api_key`, `1_KEY`, `A-B`.

**Step 2: Implement entities and DTOs**

Use JPA annotations consistent with metadata entities in `src/main/java/ai/nubase/metadata/entity`.

DTOs needed:

- `CreateFunctionRequest`
- `UpdateFunctionRequest`
- `DeployFunctionRequest`
- `EdgeFunctionResponse`
- `EdgeFunctionVersionResponse`
- `InvocationLogResponse`
- `SetFunctionSecretsRequest`

**Step 3: Run tests**

```bash
mvn -q test -Dtest=EdgeFunctionEntityTest
```

Expected: PASS.

**Step 4: Commit**

```bash
git add src/main/java/ai/nubase/functions src/test/java/ai/nubase/functions/EdgeFunctionEntityTest.java
git commit -m "feat: add edge function domain models"
```

### Task 4: Add Repositories

**Files:**

- Create: `src/main/java/ai/nubase/functions/repository/EdgeFunctionRepository.java`
- Create: `src/main/java/ai/nubase/functions/repository/EdgeFunctionVersionRepository.java`
- Create: `src/main/java/ai/nubase/functions/repository/EdgeFunctionSecretRepository.java`
- Create: `src/main/java/ai/nubase/functions/repository/EdgeFunctionInvocationRepository.java`
- Test: `src/test/java/ai/nubase/functions/EdgeFunctionRepositoryTest.java`

**Step 1: Write repository tests**

Test:

- lookup by `projectRef` + `slug`.
- unique slug per project.
- same slug allowed across different projects.
- newest invocations sorted by `createdAt DESC`.

**Step 2: Implement repositories**

Use Spring Data JPA:

```java
Optional<EdgeFunction> findByProjectRefAndSlug(String projectRef, String slug);
List<EdgeFunction> findByProjectRefOrderByCreatedAtDesc(String projectRef);
boolean existsByProjectRefAndSlug(String projectRef, String slug);
```

**Step 3: Run tests**

```bash
mvn -q test -Dtest=EdgeFunctionRepositoryTest
```

Expected: PASS.

**Step 4: Commit**

```bash
git add src/main/java/ai/nubase/functions/repository src/test/java/ai/nubase/functions/EdgeFunctionRepositoryTest.java
git commit -m "feat: add edge function repositories"
```

---

## Phase 3: Executor Abstraction

### Task 5: Add Executor Provider Interface

**Files:**

- Create: `src/main/java/ai/nubase/functions/executor/EdgeFunctionExecutor.java`
- Create: `src/main/java/ai/nubase/functions/executor/EdgeFunctionInvocationRequest.java`
- Create: `src/main/java/ai/nubase/functions/executor/EdgeFunctionInvocationResponse.java`
- Create: `src/main/java/ai/nubase/functions/executor/EdgeFunctionDeploymentRequest.java`
- Create: `src/main/java/ai/nubase/functions/executor/EdgeFunctionDeploymentResponse.java`
- Create: `src/main/java/ai/nubase/functions/executor/EdgeFunctionExecutorProperties.java`
- Test: `src/test/java/ai/nubase/functions/executor/EdgeFunctionExecutorContractTest.java`

**Step 1: Define the provider contract**

The interface should be intentionally small:

```java
public interface EdgeFunctionExecutor {
    String provider();
    EdgeFunctionDeploymentResponse deploy(EdgeFunctionDeploymentRequest request);
    void delete(String projectRef, String functionSlug, String providerDeploymentId);
    EdgeFunctionInvocationResponse invoke(EdgeFunctionInvocationRequest request);
}
```

**Step 2: Add properties**

Add to `application.yml`:

```yaml
nubase:
  functions:
    enabled: true
    executor:
      provider: local
      timeout-ms: 30000
      max-request-bytes: 10485760
      max-response-bytes: 10485760
```

**Step 3: Run compile**

```bash
mvn -q -DskipTests compile
```

Expected: compile succeeds.

**Step 4: Commit**

```bash
git add src/main/java/ai/nubase/functions/executor src/main/resources/application.yml
git commit -m "feat: add edge function executor contract"
```

### Task 6: Add Local HTTP Executor

**Files:**

- Create: `src/main/java/ai/nubase/functions/executor/LocalHttpEdgeFunctionExecutor.java`
- Test: `src/test/java/ai/nubase/functions/executor/LocalHttpEdgeFunctionExecutorTest.java`

**Step 1: Write invocation tests**

Use `MockWebServer` or Spring `MockRestServiceServer` if available; otherwise mock the executor at service level.

Test:

- request method, path, query, body, and safe headers are forwarded.
- `Authorization` is forwarded only when user token exists.
- `apikey` is never forwarded to local executor unless explicitly required.
- timeout returns a normalized executor error.

**Step 2: Implement local executor**

Use a configured base URL:

```yaml
nubase:
  functions:
    executor:
      local:
        base-url: http://localhost:8787
```

Map deployment id to path:

```text
/{projectRef}/{functionSlug}
```

**Step 3: Run tests**

```bash
mvn -q test -Dtest=LocalHttpEdgeFunctionExecutorTest
```

Expected: PASS.

**Step 4: Commit**

```bash
git add src/main/java/ai/nubase/functions/executor/LocalHttpEdgeFunctionExecutor.java src/test/java/ai/nubase/functions/executor/LocalHttpEdgeFunctionExecutorTest.java
git commit -m "feat: add local edge function executor"
```

---

## Phase 4: Control Plane Services And APIs

### Task 7: Add Function Admin Service

**Files:**

- Create: `src/main/java/ai/nubase/functions/service/EdgeFunctionAdminService.java`
- Create: `src/main/java/ai/nubase/functions/service/EdgeFunctionSecretService.java`
- Test: `src/test/java/ai/nubase/functions/service/EdgeFunctionAdminServiceTest.java`

**Step 1: Write service tests**

Test:

- create function normalizes name to slug.
- duplicate slug in same project fails.
- update can toggle `enabled`, `verifyJwt`, `privileged`.
- deploy increments `versionNo`.
- activating a version updates `edge_functions.active_version_id`.
- deleting a function deletes secrets and versions through cascade.

**Step 2: Implement service**

Read `projectRef` from `MultiTenancyContext.getContext().getAppCode()`.

Use existing encryption service for secret values:

- Store only encrypted secret values.
- Never return plaintext secret values.
- Return secret names and update timestamps only.

**Step 3: Run tests**

```bash
mvn -q test -Dtest=EdgeFunctionAdminServiceTest
```

Expected: PASS.

**Step 4: Commit**

```bash
git add src/main/java/ai/nubase/functions/service src/test/java/ai/nubase/functions/service/EdgeFunctionAdminServiceTest.java
git commit -m "feat: add edge function admin service"
```

### Task 8: Add Admin REST Controller

**Files:**

- Create: `src/main/java/ai/nubase/functions/controller/EdgeFunctionAdminController.java`
- Modify: `src/main/java/ai/nubase/common/config/SecurityConfig.java`
- Test: `src/test/java/ai/nubase/functions/controller/EdgeFunctionAdminControllerTest.java`

**Step 1: Add controller tests**

Test endpoints:

- `GET /functions/admin/v1/functions`
- `POST /functions/admin/v1/functions`
- `GET /functions/admin/v1/functions/{slug}`
- `PATCH /functions/admin/v1/functions/{slug}`
- `POST /functions/admin/v1/functions/{slug}/deploy`
- `POST /functions/admin/v1/functions/{slug}/secrets`
- `GET /functions/admin/v1/invocations`

Assert all admin endpoints require `service_role` via `@RequireServiceRole`.

**Step 2: Implement controller**

Add:

```java
@RestController
@RequestMapping("/functions/admin/v1")
@RequireServiceRole
public class EdgeFunctionAdminController {
}
```

**Step 3: Update security**

Permit the admin path because authorization is enforced by the tenant filter and service-role aspect:

```java
.requestMatchers("/functions/admin/v1/**").permitAll()
```

**Step 4: Run tests**

```bash
mvn -q test -Dtest=EdgeFunctionAdminControllerTest
```

Expected: PASS.

**Step 5: Commit**

```bash
git add src/main/java/ai/nubase/functions/controller/EdgeFunctionAdminController.java src/main/java/ai/nubase/common/config/SecurityConfig.java src/test/java/ai/nubase/functions/controller/EdgeFunctionAdminControllerTest.java
git commit -m "feat: add edge function admin api"
```

---

## Phase 5: Public Function Gateway

### Task 9: Add Invocation Service

**Files:**

- Create: `src/main/java/ai/nubase/functions/service/EdgeFunctionInvocationService.java`
- Create: `src/main/java/ai/nubase/functions/service/HeaderSanitizer.java`
- Test: `src/test/java/ai/nubase/functions/service/EdgeFunctionInvocationServiceTest.java`

**Step 1: Write tests**

Test:

- missing function returns 404.
- disabled function returns 404 or 410; choose 404 to avoid leaking names.
- function with `verify_jwt=true` rejects calls without valid user JWT unless caller is service role.
- function with `verify_jwt=false` accepts anon apikey calls.
- privileged function only runs when configured and never by accident.
- sensitive headers are redacted in logs.

**Step 2: Implement invocation policy**

Policy:

- Always require valid project `apikey`; this is already handled by `UnifiedMultiTenancyFilter`.
- If `verify_jwt=true`, require either authenticated user in `SecurityContextHolder` or service role.
- If `privileged=false`, do not pass `service_role` to executor env.
- If `privileged=true`, only include a short-lived internal function token or explicit service-role env if later implemented.

**Step 3: Persist invocation log**

Write one row for every attempted invocation after tenant context is known:

- success
- executor error
- auth rejection
- timeout

**Step 4: Run tests**

```bash
mvn -q test -Dtest=EdgeFunctionInvocationServiceTest
```

Expected: PASS.

**Step 5: Commit**

```bash
git add src/main/java/ai/nubase/functions/service src/test/java/ai/nubase/functions/service/EdgeFunctionInvocationServiceTest.java
git commit -m "feat: add edge function invocation service"
```

### Task 10: Add Public Gateway Controller

**Files:**

- Create: `src/main/java/ai/nubase/functions/controller/EdgeFunctionGatewayController.java`
- Modify: `src/main/java/ai/nubase/common/config/SecurityConfig.java`
- Test: `src/test/java/ai/nubase/functions/controller/EdgeFunctionGatewayControllerTest.java`

**Step 1: Write controller tests**

Test:

- `GET /functions/v1/hello`
- `POST /functions/v1/hello/path/to/resource?x=1`
- binary body passes through as bytes.
- response status and content-type are preserved.
- executor timeout returns `504`.

**Step 2: Implement catch-all mapping**

Use mappings:

```java
@RequestMapping("/functions/v1/{functionSlug}/**")
public ResponseEntity<byte[]> invoke(...)
```

Preserve:

- HTTP method.
- path suffix after function slug.
- query string.
- request body.
- safe request headers.
- response status.
- content type.

Do not preserve hop-by-hop headers:

- `connection`
- `keep-alive`
- `transfer-encoding`
- `upgrade`
- `proxy-authenticate`
- `proxy-authorization`

**Step 3: Update security**

Permit:

```java
.requestMatchers("/functions/v1/**").permitAll()
```

Do not add `/functions/v1/**` to `NON_FILTERED_PATHS`; it must go through `UnifiedMultiTenancyFilter`.

**Step 4: Run tests**

```bash
mvn -q test -Dtest=EdgeFunctionGatewayControllerTest
```

Expected: PASS.

**Step 5: Commit**

```bash
git add src/main/java/ai/nubase/functions/controller/EdgeFunctionGatewayController.java src/main/java/ai/nubase/common/config/SecurityConfig.java src/test/java/ai/nubase/functions/controller/EdgeFunctionGatewayControllerTest.java
git commit -m "feat: add public edge function gateway"
```

---

## Phase 6: Cloudflare Provider

### Task 11: Add Cloudflare Provider Config

**Files:**

- Create: `src/main/java/ai/nubase/functions/executor/cloudflare/CloudflareFunctionsProperties.java`
- Create: `src/main/java/ai/nubase/functions/executor/cloudflare/CloudflareApiClient.java`
- Test: `src/test/java/ai/nubase/functions/executor/cloudflare/CloudflareApiClientTest.java`

**Step 1: Add config**

Add disabled-by-default config:

```yaml
nubase:
  functions:
    executor:
      provider: local
      cloudflare:
        account-id: ""
        api-token: ""
        dispatch-namespace: ""
        dispatcher-url: ""
```

**Step 2: Write tests**

Mock Cloudflare API requests. Assert:

- Authorization uses `Bearer <api-token>`.
- token is never logged.
- account id and namespace are required when provider is `cloudflare`.

**Step 3: Implement API client**

Keep this client focused on:

- deploy script/source bundle.
- delete worker.
- read deployment status.

**Step 4: Run tests**

```bash
mvn -q test -Dtest=CloudflareApiClientTest
```

Expected: PASS.

**Step 5: Commit**

```bash
git add src/main/java/ai/nubase/functions/executor/cloudflare src/test/java/ai/nubase/functions/executor/cloudflare/CloudflareApiClientTest.java src/main/resources/application.yml
git commit -m "feat: add cloudflare edge function provider config"
```

### Task 12: Implement Cloudflare Executor

**Files:**

- Create: `src/main/java/ai/nubase/functions/executor/cloudflare/CloudflareEdgeFunctionExecutor.java`
- Test: `src/test/java/ai/nubase/functions/executor/cloudflare/CloudflareEdgeFunctionExecutorTest.java`

**Step 1: Write executor tests**

Test:

- deploy creates deterministic worker name: `nubase-{projectRef}-{slug}` with safe hashing if too long.
- invocation signs internal request from Nubase to dispatcher.
- dispatcher URL is not public client-facing API.
- Cloudflare errors normalize to Nubase executor errors.

**Step 2: Implement deploy**

Deployment request includes:

- project ref.
- function slug.
- source bundle or artifact URI.
- env names.
- `verify_jwt`, `privileged`.

**Step 3: Implement invoke**

Spring calls Cloudflare private dispatcher with:

- `x-nubase-project-ref`
- `x-nubase-function-slug`
- `x-nubase-request-id`
- `x-nubase-invocation-signature`

Do not send project JWT secret or DB credentials.

**Step 4: Run tests**

```bash
mvn -q test -Dtest=CloudflareEdgeFunctionExecutorTest
```

Expected: PASS.

**Step 5: Commit**

```bash
git add src/main/java/ai/nubase/functions/executor/cloudflare src/test/java/ai/nubase/functions/executor/cloudflare/CloudflareEdgeFunctionExecutorTest.java
git commit -m "feat: add cloudflare edge function executor"
```

---

## Phase 7: CLI

### Task 13: Add CLI Function Commands

**Files:**

- Create: `frontend/packages/mcp-bridge/src/functions.ts`
- Modify: `frontend/packages/mcp-bridge/src/index.ts`
- Modify: `frontend/packages/mcp-bridge/src/nubase-client.ts`
- Test: `frontend/packages/mcp-bridge/test/functions.test.ts`

**Step 1: Write CLI tests**

Test commands:

- `nubase_cli functions new hello`
- `nubase_cli functions list`
- `nubase_cli functions deploy hello`
- `nubase_cli functions invoke hello --method POST --body '{"ok":true}'`

**Step 2: Implement scaffold**

Create local files:

```text
nubase/functions/hello/index.ts
nubase/functions/hello/nubase-function.json
```

Default `index.ts`:

```ts
export default {
  async fetch(req: Request, env: Record<string, string>) {
    return Response.json({
      ok: true,
      method: req.method,
      url: req.url,
      projectUrl: env.NUBASE_URL,
    });
  },
};
```

**Step 3: Implement deploy**

Package source files into a JSON/base64 payload first. Avoid adding a bundler in the first pass unless Cloudflare requires it.

Call:

```http
POST /functions/admin/v1/functions/{slug}/deploy
```

**Step 4: Run tests**

```bash
cd frontend/packages/mcp-bridge
pnpm run build
pnpm test
```

Expected: PASS.

**Step 5: Commit**

```bash
git add frontend/packages/mcp-bridge/src/functions.ts frontend/packages/mcp-bridge/src/index.ts frontend/packages/mcp-bridge/src/nubase-client.ts frontend/packages/mcp-bridge/test/functions.test.ts
git commit -m "feat: add edge functions cli commands"
```

---

## Phase 8: Studio

### Task 14: Add Studio Functions Page

**Files:**

- Create: `frontend/apps/studio/src/app/project/[ref]/functions/page.tsx`
- Modify: `frontend/apps/studio/src/components/workspace-shell.tsx`
- Modify: `frontend/apps/studio/src/lib/api.ts`
- Test: `frontend/apps/studio/src/app/project/[ref]/functions/page.test.tsx`

**Step 1: Write page tests**

Test:

- functions list renders.
- disabled function shows disabled badge.
- `verify_jwt=false` shows public badge.
- privileged function shows service-role warning.
- create/update form calls admin API with service-role key.

**Step 2: Add navigation item**

Add `Functions` to `WorkspaceShell` near Database/Auth/Storage/Memory.

**Step 3: Implement page**

Controls:

- list functions.
- create function.
- enable/disable.
- toggle `verify_jwt`.
- toggle `privileged`.
- view latest deployment status.
- view last invocations.

Do not add an in-browser full code editor in the first pass; use CLI deploy for source.

**Step 4: Run frontend tests**

```bash
pnpm --filter @nubase/studio test
pnpm --filter @nubase/studio typecheck
```

Expected: PASS.

**Step 5: Commit**

```bash
git add frontend/apps/studio/src/app/project/[ref]/functions/page.tsx frontend/apps/studio/src/components/workspace-shell.tsx frontend/apps/studio/src/lib/api.ts frontend/apps/studio/src/app/project/[ref]/functions/page.test.tsx
git commit -m "feat: add studio edge functions page"
```

---

## Phase 9: Observability And Operations

### Task 15: Add Logging And Redaction

**Files:**

- Create: `src/main/java/ai/nubase/functions/service/FunctionLogSanitizer.java`
- Modify: `src/main/resources/logback-spring.xml`
- Test: `src/test/java/ai/nubase/functions/service/FunctionLogSanitizerTest.java`

**Step 1: Write sanitizer tests**

Assert these values are redacted:

- `Authorization`
- `apikey`
- `service_role`
- `NUBASE_SERVICE_ROLE_KEY`
- `secret`
- `token`

**Step 2: Implement sanitizer**

Sanitizer returns structured maps, not raw request dumps.

**Step 3: Run tests**

```bash
mvn -q test -Dtest=FunctionLogSanitizerTest
```

Expected: PASS.

**Step 4: Commit**

```bash
git add src/main/java/ai/nubase/functions/service/FunctionLogSanitizer.java src/test/java/ai/nubase/functions/service/FunctionLogSanitizerTest.java src/main/resources/logback-spring.xml
git commit -m "feat: redact edge function logs"
```

### Task 16: Add Rate Limits And Timeouts

**Files:**

- Create: `src/main/java/ai/nubase/functions/service/EdgeFunctionRateLimiter.java`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/ai/nubase/functions/service/EdgeFunctionRateLimiterTest.java`

**Step 1: Add config**

```yaml
nubase:
  functions:
    invocation:
      timeout-ms: 30000
      max-body-bytes: 10485760
      per-project-rpm: 600
      per-function-rpm: 120
```

**Step 2: Implement limiter**

Start with in-memory Caffeine for MVP. Add a TODO to switch to Redis when multi-node rate consistency is required.

**Step 3: Run tests**

```bash
mvn -q test -Dtest=EdgeFunctionRateLimiterTest
```

Expected: PASS.

**Step 4: Commit**

```bash
git add src/main/java/ai/nubase/functions/service/EdgeFunctionRateLimiter.java src/test/java/ai/nubase/functions/service/EdgeFunctionRateLimiterTest.java src/main/resources/application.yml
git commit -m "feat: add edge function rate limits"
```

---

## Phase 10: End-To-End And Documentation

### Task 17: Add E2E Smoke Test

**Files:**

- Create: `src/test/java/ai/nubase/functions/EdgeFunctionsIntegrationTest.java`
- Create: `src/test/resources/functions/local-hello-server.js`

**Step 1: Write integration test**

Flow:

1. create project config.
2. create function `hello`.
3. deploy version through admin service.
4. call `/functions/v1/hello`.
5. assert response body and invocation log.

**Step 2: Run integration test**

```bash
mvn -q test -Dtest=EdgeFunctionsIntegrationTest
```

Expected: PASS.

**Step 3: Commit**

```bash
git add src/test/java/ai/nubase/functions/EdgeFunctionsIntegrationTest.java src/test/resources/functions/local-hello-server.js
git commit -m "test: add edge functions integration smoke test"
```

### Task 18: Update User Docs

**Files:**

- Create: `docs/edge-functions.md`
- Modify: `docs/README.md`
- Modify: `README.md`
- Modify: `docs/docker-all-in-one.md`
- Modify: `frontend/packages/mcp-bridge/README.md`

**Step 1: Document usage**

Include:

- create function.
- deploy function.
- invoke function.
- `verify_jwt` behavior.
- privileged function warning.
- local executor config.
- Cloudflare executor config.

**Step 2: Add quick example**

```bash
nubase_cli functions new hello
nubase_cli functions deploy hello
curl "$NUBASE_URL/functions/v1/hello" -H "apikey: $NUBASE_ANON_KEY"
```

**Step 3: Update status**

In `README.md`, move Edge Functions out of `Not yet implemented` only after the MVP is actually merged.

**Step 4: Run docs checks**

```bash
rg -n "Edge Functions|functions/v1|verify_jwt" README.md docs frontend/packages/mcp-bridge/README.md
```

Expected: all new docs are discoverable.

**Step 5: Commit**

```bash
git add docs/edge-functions.md docs/README.md README.md docs/docker-all-in-one.md frontend/packages/mcp-bridge/README.md
git commit -m "docs: add edge functions guide"
```

---

## Release Checklist

Run backend checks:

```bash
mvn -q test
mvn -q -DskipTests compile
```

Run frontend/CLI checks:

```bash
cd frontend
pnpm --filter @nubase/studio typecheck
pnpm --filter @nubase/studio test
pnpm --filter nubase_cli build
pnpm --filter nubase_cli test
```

Manual smoke:

```bash
curl "$NUBASE_URL/functions/v1/hello" \
  -H "apikey: $NUBASE_ANON_KEY"
```

Security smoke:

- Call a `verify_jwt=true` function without user JWT; expect `401`.
- Call a disabled function; expect `404`.
- Confirm `edge_function_invocations` contains no raw `Authorization` or `apikey`.
- Confirm user function env does not contain DB credentials or platform keys.
- Confirm `service_role` env is absent unless `privileged=true`.

## Follow-Up Work

- Redis-backed rate limiting for multi-node consistency.
- Durable artifact storage in S3/R2 instead of metadata DB payloads.
- Cloudflare dispatcher Worker template and deployment automation.
- Per-function custom domains.
- Function schedules and background jobs.
- Realtime trigger integration.
- Function logs streaming to Studio.
