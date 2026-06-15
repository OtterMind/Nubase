
![AI demo to real app with Nubase](brand/ai-demo-to-real-app-en.png)

# Nubase

**English** · [简体中文](README.zh-CN.md)

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-10A074.svg)](LICENSE)
[![npm](https://img.shields.io/npm/v/nubase_cli?logo=npm&label=nubase_cli&color=cb3837)](https://www.npmjs.com/package/nubase_cli)
[![GitHub stars](https://img.shields.io/github/stars/OtterMind/Nubase?style=social)](https://github.com/OtterMind/Nubase)

**Turn AI-written code into real apps.** Nubase is an open-source, AI-native backend **and deploy layer** that a coding agent drives directly — so a generated app goes live in minutes. Eight capability modules in one self-hostable service: **Database, Auth, Storage, Assets, Functions, AI Gateway, Memory, and cron**.

> An agent can model the data (Database + Auth), deploy backend logic (**Functions**), publish the generated frontend to a public CDN (**Assets**), and schedule recurring work (**cron**) — all through MCP tools, with no separate hosting account. Supabase-style where it makes sense (Postgres, REST, JWTs, RLS, object storage, a Studio dashboard), plus first-class **Memory** and an **MCP** surface built for AI coding agents.

---

## ⚡ Quick Start

### 1. Use Nubase in Claude Code or Codex — one command

From your project folder, run:

```bash
npx -y nubase_cli@latest install-skills
```

That single command:

- 📚 installs the **Nubase skills** for **both Claude Code and Codex**,
- 🔌 wires up the **MCP server** config, and
- 🔐 opens a browser to **authorize** and pick your project.

Then:

- **Claude Code** — restart it in this folder, run `/mcp`, and confirm `nubase` is connected.
- **Codex** — it's added to `~/.codex/config.toml`; just start Codex.

> This connects your agent to a Nubase instance (a hosted one, or your own — spin one up in step 2). Point the CLI at any instance with:
> ```bash
> npx -y nubase_cli@latest install-skills \
>   --studio-url https://studio.example.com \
>   --nubase-url https://api.example.com
> ```

### 2. Run your own Nubase — one command

The all-in-one Docker image bundles **PostgreSQL + Redis + the backend + Studio**:

```bash
docker run -d --name nubase \
  -p 9999:9999 -p 5432:5432 \
  -v nubase_data:/data \
  <your-namespace>/nubase:latest
```

- **Studio** → http://localhost:9999/studio — create an account, create a project, click **Provision** to initialize its database.
- **API** → http://localhost:9999 (the Studio UI is bundled into the backend and served on the same port)

> First-boot secrets are generated into the `/data` volume; keep the volume to retain your projects. For a real deployment with stable secrets, see [Self-host with Docker](#-self-host-with-docker).

### 3. Build with your agent

Your agent can now operate Nubase directly through MCP tools — inspect schema, create tables, run SQL, manage auth & storage, **deploy edge functions, publish a frontend to the public CDN, schedule cron jobs**, and read/write durable **memory**. Try asking:

> "Create a `todos` table with RLS, deploy an edge function that returns the open count, publish a one-page UI to Assets that calls it, and remember the deployment."

See [Deploy an AI-generated app](docs/deploy-ai-generated-apps.md) for the full generate → live walkthrough.

---

## 🚀 Self-host with Docker

The single all-in-one image is everything you need to run Nubase on your own box — **one line, no compose file, no external services**.

**Try it (auto-generated secrets, kept in the volume):**

```bash
docker run -d --name nubase -p 9999:9999 -p 5432:5432 \
  -v nubase_data:/data <your-namespace>/nubase:latest
```

**Production (pin stable secrets so encrypted project credentials survive restarts):**

```bash
docker run -d --name nubase -p 9999:9999 -p 5432:5432 \
  -v nubase_data:/data \
  -e PGRST_ENCRYPTION_MASTER_KEY="$(openssl rand -base64 32)" \
  -e METADATA_SERVICE_ROLE_KEY="$(openssl rand -base64 48)" \
  <your-namespace>/nubase:latest
```

Everything else is configured via environment variables — Postgres, Redis, S3/R2 storage, SMTP, OAuth, and LLM providers. See [docs/docker-all-in-one.md](docs/docker-all-in-one.md) for the full list and a multi-architecture (`amd64` + `arm64`) note.

> Replace `<your-namespace>` with the Docker Hub namespace the image is published under.

---

## Why Nubase

AI-native applications need more than CRUD. They need user memory, retrieval, auth, storage, database APIs, and project isolation from day one. Without that backend layer, every AI coding session produces another demo that still needs weeks of infrastructure work.

Supabase is excellent, but its open-source self-hosted stack is designed around a **single** project. Nubase is built for AI teams and self-hosters who want **one Studio, one backend service, and many isolated AI projects** on their own infrastructure — with three opinionated additions:

1. **Memory is a first-class primitive** — durable memory, entity extraction, history, and hybrid retrieval are built in, not bolted on as a separate vector-store script.
2. **AI coding gets a real backend target** — agents create tables, call REST APIs, write memory, and inspect schema through MCP-friendly tools.
3. **Self-hosting supports many projects** — a single control plane provisions and routes to multiple isolated project databases.

## Core Features

- **🗄️ Database** — one isolated PostgreSQL per project; a PostgREST-compatible `/rest/v1` API (select/filter/order/paginate/insert/update/upsert/delete); per-project JWT secrets, roles, and schema cache; Row Level Security with JWT claims.
- **🔐 Auth** — Supabase-style signup/login and refresh-token rotation; MFA/TOTP, OTP & magic links, anonymous sign-in; OAuth (Google / GitHub / WeChat) and SAML SSO; per-project `anon` / `authenticated` / `service_role` tokens.
- **📦 Storage** — S3-compatible (Cloudflare R2 / AWS S3 / MinIO); public/private buckets, signed URLs, size & MIME controls; optional S3 Vectors for large document/asset workloads.
- **🌐 Assets (static CDN)** — publish a generated frontend: per-project public static assets served at `/assets/v1/**` with Cache-Control/ETag/304 semantics; per-project default cache policy and custom CDN domain; agents publish directly over MCP (`assets_upload`).
- **⚡ Functions** — deploy backend logic as edge functions served at `/functions/v1/**`; per-function secrets, invocation logs, rate limits, `verify_jwt`; local executor or Cloudflare Workers for Platforms; agents scaffold/deploy/invoke over MCP (`functions_deploy`).
- **🤖 AI Gateway** — OpenAI- and Anthropic-compatible endpoints with per-project keys and token/cost usage tracking.
- **🧠 Memory** — Mem0-style memory API; LLM-powered fact extraction (ADD/UPDATE/DELETE/NONE); hybrid retrieval over pgvector + Postgres full-text + entity boost; entity store and append-only history. Works with OpenAI, Anthropic, and OpenAI-compatible providers.
- **⏰ Scheduled Jobs (cron)** — recurring jobs that invoke an edge function or a named database function on a crontab schedule, run by the control plane with run history; managed over MCP (`cron_create`).
- **🧰 AI Coding & Agents** — an MCP bridge (`nubase_cli`) for schema inspection, SQL execution, RLS export, project init, and memory; one consistent project-token model across Auth, REST, Storage, and Memory.
- **🎛️ Studio** — a Next.js dashboard for projects, SQL (with execution history), users, storage, and the memory explorer.

## Nubase vs Supabase

![Nubase vs Supabase — an AI-native, self-hostable backend with built-in Memory and MCP for AI coding](brand/nubase-vs-supabase.png)

<details>
<summary>Full comparison as a table (including Supabase Cloud)</summary>

| Area | Supabase Cloud | Supabase self-hosted | Nubase |
| --- | --- | --- | --- |
| Multi-project dashboard | Yes | No (mimics one project) | **Yes** |
| Project isolation | Dedicated instance | One local project | **Dedicated Postgres DB per project** |
| Database API | PostgREST | PostgREST | PostgREST-compatible (Java) |
| Auth | Yes | Yes | Supabase-style Auth |
| Storage | Yes | Yes | S3/R2-compatible |
| AI memory | Not a core primitive | Not a core primitive | **Built-in Memory pillar** |
| AI coding backend target | General primitives | General primitives | **Memory + REST + MCP + Studio** |
| Deploy a generated app | App + separate hosting/Functions/cron | Self-managed stack | **Frontend (Assets) + backend (Functions) + cron, one platform** |
| Edge Functions | Yes | Available in stack | **Gateway + executor (local / Cloudflare WfP)** |
| Realtime | Yes | Available in stack | Not yet |

</details>

## Architecture

Nubase has two database layers:

- **Metadata database** — platform users, project configs, encrypted project credentials, ownership, platform settings, SQL snippets, and execution records.
- **Project databases** — each project gets its own PostgreSQL database with auth tables, storage metadata, memory tables, and application tables.

Requests use a two-token model: `apikey` identifies the project + role (`anon` / `authenticated` / `service_role`), and `Authorization: Bearer <jwt>` identifies the end user for RLS and memory ownership. A request filter resolves the project from the `apikey`, routes JDBC to the correct project database, and sets the request context.

## Run from source (development)

Requirements: Java 17, Maven, Docker, Node.js + pnpm.

```bash
# 1. Start Postgres (15 + pgvector)
docker compose -f pg-docker-compose.yml up -d

# 2. Required secrets
export PGRST_ENCRYPTION_MASTER_KEY="$(openssl rand -base64 32)"
export METADATA_SERVICE_ROLE_KEY="replace-with-a-long-random-admin-token"
export OPENAI_API_KEY="sk-..."   # optional, only for LLM-powered Memory

# 3. Backend → http://localhost:9999
mvn spring-boot:run

# 4. Studio → http://localhost:3000
cd frontend && pnpm install && pnpm dev:studio
```

To build the all-in-one image yourself: `docker build -f Dockerfile.all-in-one -t nubase:local .`

## Examples

**Write and search memory:**

```bash
curl -X POST http://localhost:9999/mem/v1/memories \
  -H "apikey: $NUBASE_SERVICE_KEY" -H "Content-Type: application/json" \
  -d '{"userId":"user-42","messages":[{"role":"user","content":"I prefer steak over sushi and my dog is named Mochi."}]}'

curl -X POST http://localhost:9999/mem/v1/search \
  -H "apikey: $NUBASE_SERVICE_KEY" -H "Content-Type: application/json" \
  -d '{"userId":"user-42","query":"what food do they like?"}'
```

**Use the REST API** (after creating a `todos` table):

```bash
curl "http://localhost:9999/rest/v1/todos?select=*" -H "apikey: $NUBASE_ANON_KEY"

curl -X POST "http://localhost:9999/rest/v1/todos" \
  -H "apikey: $NUBASE_SERVICE_KEY" -H "Content-Type: application/json" \
  -d '{"text":"Ship the first open-source release"}'
```

## Documentation

- [Getting started](docs/getting-started.md)
- [Deploy an AI-generated app (generate → live)](docs/deploy-ai-generated-apps.md)
- [Connect agents (Claude / Codex / Cursor)](docs/agent-connect.md)
- [MCP & agent guide](docs/mcp.md)
- [Edge Functions](docs/edge-functions.md) · [Assets (static CDN)](docs/assets.md) · [Scheduled Jobs (cron)](docs/scheduled-jobs.md)
- [nubase_cli usage](docs/nubase-cli-usage.md)
- [All-in-one Docker image](docs/docker-all-in-one.md)
- [Architecture](docs/architecture.md)
- [Product overview](docs/product-overview.md)
- [Supabase comparison](docs/supabase-comparison.md)

## Status & roadmap

Nubase is early-stage but all eight modules (Database, Auth, Storage, Assets, Functions, AI Gateway, Memory, cron) plus Studio and the MCP bridge are in place. Not yet implemented: **Realtime** and operational extras like backups/PITR, HA, and enterprise SSO/SCIM. Review the admin/management endpoints before exposing a server to the public internet.

## Contributing

Contributions and issues are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md) and [SECURITY.md](SECURITY.md). This is an early public release, so feedback shapes what comes next. 🙌

## License

[Apache-2.0](LICENSE).
