# Nubase Agentic Delivery Team Package

本目录是 GOAI Agent Infra 的可审阅工程包。正式规范以 AgentTeams v1.2.2 为准，本机 HiClaw v1.1.2 仅用于兼容运行验证。

## Package Layout

- `agentteams-v1.2.2/`: four standalone Workers, one Team and the reviewed MCP policy.
- `compat/hiclaw-v1.1.2/`: legacy inline Team manifest for the local runtime.
- `identities/`: the four Agent Identity contracts.
- `skills/`: four versioned, reusable Skills.
- `contracts/`: strict deployment, approval, trace and shared-state schemas.
- `scenario/`: a credential-free example application and synthetic review fixtures.
- `scripts/`: package validation, negative tests and stdio MCP smoke.
- `evidence/`: the reviewed boundary for generated runtime evidence.

## Credential-Free Verification

Requirements: Node.js 22, pnpm 10, Java 17 and Ruby with its standard YAML library. Build the stdio bridge before running the smoke test:

```bash
cd frontend
pnpm install --frozen-lockfile
pnpm --filter nubase_cli build
pnpm --filter nubase_cli test

cd ..
node examples/goai-agent-delivery/scripts/validate-package.mjs
node examples/goai-agent-delivery/scripts/test-validator.mjs
node examples/goai-agent-delivery/scripts/mcp-smoke.mjs
```

These checks do not read `.env`, `.nubase`, the HiClaw workspace or a real Nubase project key. The validator extracts all 65 stdio tools and all 44 Java `@Tool` methods from source, then requires every role policy to classify its transport inventory exactly. The smoke test starts the stdio bridge with an isolated environment and performs local SQL risk classification only.

## AgentTeams Runtime

With a local AgentTeams or HiClaw Manager running, static validation and runtime detection are read-only by default:

```bash
bash script/goai/install-agentteams.sh
```

After the three reviewed sandbox MCP routes are configured with runtime-only credentials, create a dedicated sanitized directory below `evidence/`, mount that exact directory at `/host-share`, and apply the detected manifest explicitly:

```bash
mkdir -p examples/goai-agent-delivery/evidence/demo-workspace
bash script/goai/install-agentteams.sh \
  --apply \
  --host-share-root "$(pwd)/examples/goai-agent-delivery/evidence/demo-workspace"
```

The installer rejects any `/host-share` that does not exactly match the explicit directory, is outside `evidence/`, contains symlinks, or contains common credential material. Mount the dedicated demo directory read-only whenever the runtime permits it.

An applied Team does not prove MCP authorization. The local Java HTTP routes must use each route's `javaHttpPolicy.allowTools` as the top-level Higress MCP `allowTools` field; it is not nested under `server`. A separately deployed stdio wrapper must instead use `stdioBridgePolicy` and its bridge guards. The transports use different tool names and their policies are not interchangeable.

HiClaw v1.1.2 also requires a top-level `tools: []` compatibility marker in proxy `rawConfigurations`; without the literal `tools:` field its Console SDK saves the MCP plugin instance as disabled. The empty marker does not define the proxied tools. The top-level `allowTools` list remains authoritative, and `tools/list` must equal it exactly after the route is applied.

For Console API consumer authorization, use the policy's `higressConsumers` values (`worker-nubase-delivery-lead`, `worker-nubase-builder`, `worker-nubase-verifier`, and `worker-nubase-release-governor`). The unprefixed `agentTeamsWorkers` values are manifest resource names, not Higress authorization identities. Before assigning a task, separately verify Team/Worker readiness, all four persistent and runtime Skill tree digests, exact allow/deny discovery, consumer authorization and a sanitized trace carrying the same `taskId`, `runId` and `agentId`.

On HiClaw v1.1.2 the Worker-facing runtime path is the enabled `mcporter` Skill. Use `mcporter` server discovery, tool discovery, one allowed call and one denied call as the runtime MCP evidence. An applied manifest, a reachable URL, the CoPaw native MCP registry count or an expected route declaration does not prove Worker authorization.

The Java Builder route remains `PARTIAL`: both `executeSql` and `executeSqlDryRun` are denied, so this route exposes no SQL execution or dry-run capability. Database schema apply and database writes are outside the contest profile.

## Bounded Local Closure

The Java HTTP MCP tool `deploymentStageAsset` provides one deliberately narrow local rollback drill. It accepts a reviewed `appName`, `taskId`, unique `runId` and lowercase `sha256:` `manifestDigest`. The server generates a non-secret marker with a fresh ownership nonce at `__goai_e2e/{runId}/marker.json`, disables overwrite, records the truthful `assets_upload` deployment step, and returns sanitized deployment metadata including the non-secret `ownershipVersionId`. Because the nonce is server generated, clients validate the artifact digest by strict SHA-256 format and equality across the stage response, deployment summary and step instead of precomputing marker bytes. The marker and its deployment record never carry a public URL.

The runnable role sequence is:

1. Delivery Lead fixes the IDs, digest, exact marker path, acceptance checks, drill mode and human approval boundary.
2. Builder proves `nubase-build` through `mcporter` and calls `deploymentStageAsset` once.
3. Verifier uses its service-role route to prove the staged marker, deployment evidence and exact ownership version through the controlled `assetsList` metadata view. Anonymous/authenticated metadata queries and public GET, HEAD and SPA fallback paths must continue to hide the reserved namespace.
4. In an explicitly planned rollback drill, Verifier records `ROLLBACK_DRILL_INJECTED`; Release Governor verifies the bounded approval and calls `deploymentRollback` once.
5. Verifier independently proves marker absence, `rolled_back` status and one successful `asset_version_deleted` action whose `ownershipVersionId` matches the stage response and `assets_upload` step.
6. Release Governor emits `REJECTED_RECOVERED`. A non-drill path may emit `APPROVED_FOR_PROMOTION` readiness after independent `PASS` and human approval.

Neither terminal decision means released. Remote Java MCP still has no `deploy_app` or `deployment_promote`, and the bounded marker flow does not exercise SQL, Functions, Cron, Secrets, Memory or an application promotion.

The safety switch `NUBASE_ASSETS_BOUNDED_PRIVATE_STORAGE_ENABLED` defaults to `false`. A local drill must use a dedicated private global bucket, explicitly set that switch to `true`, leave `NUBASE_ASSETS_BUCKET`, `NUBASE_ASSETS_PUBLIC_BASE_URL` and `R2_PUBLIC_URL` empty, and have bucket versioning enabled by an operator before the run. Nubase verifies these preconditions and fails closed when private storage or versioning cannot be proven. Production CDN mode therefore cannot stage bounded markers. The application never changes a bucket's public access or versioning configuration. The harness also fails with `ASSET_UPLOAD_FAILED` instead of substituting a metadata-only marker when R2/S3 is unavailable.

After Nubase is running on port `9999`, the three reviewed routes are refreshed, and all four Workers report ready, run the complete recovery branch with each Worker's own consumer identity. The approval flag authorizes only this reversible local marker rollback:

```bash
node examples/goai-agent-delivery/scripts/run-local-closure.mjs \
  --evidence-root "$(pwd)/examples/goai-agent-delivery/evidence/local-sandbox" \
  --approve-local-rollback
```

The command runs the canonical package validator and fails before staging if any runtime tool inventory differs from policy. It uses each role's real consumer identity but records the executor as `role-scoped-harness`; it does not claim that an LLM autonomously produced the evidence. Uncertain stage results are reconciled by `runId` and digest before bounded compensation. `build-evidence.json` and `rollback-report.json` retain the non-secret ownership version so the exact-version deletion remains auditable. Evidence remains under `.incomplete-*` until schema, checksum, permission and recovery checks pass, then it is atomically published as the final run directory.

## Evidence and Safety

The committed trace and approval files are synthetic fixtures, not deployment claims. A real demonstration must create a reviewed run directory under `evidence/`; generated evidence remains ignored until deliberately sanitized and promoted into a reviewed submission artifact. Never include credentials, raw headers, user data, prompts with private content, database dumps or AgentTeams runtime state.

The remote Java `/mcp` endpoint does not expose `deploy_app` or `deployment_promote`. The local stdio bridge owns `deploy_app`. `deploymentStageAsset` only creates the bounded marker deployment described above. Rollback outside that profile remains partial and must be reported as compensation when SQL, Functions, Secrets or Memory cannot be restored automatically.
