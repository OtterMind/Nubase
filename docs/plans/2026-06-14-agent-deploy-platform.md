# Agent Deploy Platform Implementation Plan

**Goal:** Turn Nubase from a set of agent-callable backend modules into a deploy platform with observable deployments, rollback, project lifecycle tools, safer publishing, and richer remote MCP coverage.

**Architecture:** Build this incrementally on top of existing `nubase_cli` MCP bridge and Spring control-plane APIs. The local bridge remains responsible for reading workspace files; the server stores deployment records and exposes status/logs/rollback/control-plane tools.

**Tech Stack:** Spring Boot, JPA/Flyway metadata tables, TypeScript `nubase_cli`, Spring AI MCP tools, Node test runner, JUnit/Mockito.

---

## Implementation Order

1. **Deployment Records + Status/Logs** ✅
   - Add metadata tables/entities/repositories for `app_deployments` and `app_deployment_steps`.
   - Add Spring admin API and remote MCP tools for list/get/logs.
   - Update `nubase_cli deploy_app` to create/update deployment records around each step.

2. **Rollback Foundation** ✅
   - Add `deployment_rollback` API/tool for the supported subset.
   - Roll back successful `assets_upload` steps by deleting the recorded asset path.
   - Roll back successful `cron_create` steps by deleting the recorded job.
   - Record skipped rollback actions for SQL, Memory, function deploys, secret updates, verification, failed original steps, and unsupported step types.

3. **Assets Release Model** ✅
   - Add asset release id/prefix support in `deploy_app`.
   - Upload `__nubase_release.json` for release deployments.
   - Add `spaFallbackPath` asset setting and public serving fallback for SPA routes.
   - Expose `assets_update_settings` locally and `assetsUpdateSettings` remotely.

4. **Project Lifecycle Tools** ✅
   - Add bridge tools for existing project list, keys, provision, update, and select-instructions APIs.
   - Use platform-level auth (`NUBASE_PLATFORM_JWT` or `NUBASE_PLATFORM_KEY`/`NUBASE_METADATA_SERVICE_ROLE_KEY`) separately from tenant `NUBASE_PROJECT_KEY`.
   - Keep destructive project delete out of MCP tools.

5. **Security Scan Before Publish** ✅
   - Add local bridge scan for service_role-looking JWTs, private keys, `.env`/npmrc/pypirc files, and common API key patterns.
   - Run the scan before `deploy_app` uploads Assets and before `functions_deploy` uploads source bundles.
   - Allow explicit bypass with `securityScan: false` in deploy manifests or `--no-security-scan` for function deploys.

6. **AI Gateway + Auth/Storage Expansion** ✅
   - Add bridge tools for Auth tenant settings read/update/clear.
   - Add bridge tools for Storage object listing, signed download URLs, and signed upload URLs.
   - Add bridge tools for AI Gateway daily usage, usage by model, usage logs, and model pricing.
   - Keep writes gated by `NUBASE_ALLOW_ADMIN_WRITE=true`.

## Task 1 Acceptance

- `deploy_app` returns a `deploymentId`.
- The deployment record has status, timestamps, app name, public URL, manifest summary, and step rows.
- CLI/MCP exposes `deployments_list`, `deployment_status`, and `deployment_logs`.
- Existing tests pass; new tests cover record creation/update and read tools.

## Task 2 Acceptance

- CLI/MCP exposes `deployment_rollback`.
- Spring admin API exposes `POST /deployments/admin/v1/deployments/{id}/rollback`.
- Rollback actions are appended to deployment logs as `rollback:<stepName>` rows.
- Deployment status becomes `rolled_back` when rollback has no failed actions, or `rollback_failed` when at least one rollback action fails.
- Tests cover asset/cron rollback, skipped failed original steps, service-role gating, and bridge endpoint calls.

## Task 3 Acceptance

- `deploy_app` supports `assets.release`, `assets.releaseId`, `assets.releasePrefix`, and `assets.spaFallback`.
- Release deploys upload files under a stable release prefix and add `__nubase_release.json`.
- Asset settings include `spaFallbackPath`; public GET/HEAD falls back to that asset when a route path is missing.
- MCP exposes `assets_update_settings` for local bridge users and `assetsUpdateSettings` for remote Spring MCP users.
- Tests cover release manifest upload, SPA fallback setting, settings endpoint calls, and backend compile.

## Task 4 Acceptance

- MCP exposes `projects_list`, `project_keys_admin`, `project_provision`, `project_update`, and `project_select_instructions`.
- Platform lifecycle calls require `NUBASE_PLATFORM_JWT` or `NUBASE_PLATFORM_KEY`/`NUBASE_METADATA_SERVICE_ROLE_KEY`.
- Platform writes remain gated by `NUBASE_ALLOW_ADMIN_WRITE=true`.
- Project soft delete is intentionally not exposed to coding agents.
- Tests cover platform auth, write gating, request paths, and tool listing.

## Task 5 Acceptance

- `deploy_app` blocks suspicious asset content before upload.
- `functions_deploy` blocks suspicious bundle content before upload.
- Bypass is explicit and visible: `securityScan: false` or `--no-security-scan`.
- Tests cover blocked asset upload, blocked function deploy, and bypass behavior.

## Task 6 Acceptance

- MCP exposes `auth_get_settings`, `auth_update_settings`, and `auth_clear_settings`.
- MCP exposes `storage_list_objects`, `storage_create_signed_url`, `storage_create_signed_urls`, and `storage_create_signed_upload_url`.
- MCP exposes `gateway_usage_daily`, `gateway_usage_by_model`, `gateway_usage_logs`, and `gateway_pricing`.
- Auth settings and signed upload URL creation stay gated by `NUBASE_ALLOW_ADMIN_WRITE=true`.
- Tests cover request paths, request bodies, signed upload headers, usage/pricing endpoints, and tool listing.

## Remaining Gaps

- Remote Spring MCP still has narrower coverage than the local bridge for some local-file and object-signing flows. Codex/Claude Code should use the local `nubase_cli` bridge for generated-app deploys.
- AI Gateway upstream/provider configuration is not yet exposed as a stable MCP tool. The existing code has service-layer pieces, but it needs a reviewed admin API/tool contract before agents should mutate provider routing.
