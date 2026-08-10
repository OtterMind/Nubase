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

These checks do not read `.env`, `.nubase`, the HiClaw workspace or a real Nubase project key. The smoke test starts the stdio bridge with an isolated environment and performs local SQL risk classification only.

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

An applied Team does not prove MCP authorization. Before assigning a task, separately verify Team/Worker readiness, all four persistent and runtime Skill tree digests, exact allow/deny discovery, consumer authorization and a sanitized trace carrying the same `taskId`, `runId` and `agentId`.

## Evidence and Safety

The committed trace and approval files are synthetic fixtures, not deployment claims. A real demonstration must create a reviewed run directory under `evidence/`; generated evidence remains ignored until deliberately sanitized and promoted. Never include credentials, raw headers, user data, prompts with private content, database dumps or AgentTeams runtime state.

The remote Java `/mcp` endpoint does not currently expose `deploy_app` or `deployment_promote`. The local stdio bridge owns `deploy_app`; rollback remains partial and must be reported as compensation when SQL, Functions, Secrets or Memory cannot be restored automatically.
