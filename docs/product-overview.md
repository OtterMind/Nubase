# Product Overview

Nubase turns AI-written code into real apps. It is a self-hostable backend **and deploy layer** for AI-native applications and AI Coding workflows.

AI Coding tools can generate product surfaces quickly, but a generated app still needs to *go live*: data, auth, backend logic, a place to serve the frontend, scheduled work, secure APIs, project isolation, and a dashboard where humans can inspect and fix the system. Nubase is designed to be that target — an agent generates code and ships it online without a separate hosting account.

It combines eight capability modules:

- **Database**: PostgreSQL with PostgREST-style REST APIs and Row Level Security
- **Auth**: Supabase-style authentication, JWTs, OAuth/MFA, and refresh tokens
- **Storage**: S3/R2-compatible object storage with Postgres metadata
- **Assets**: a public static CDN for publishing the generated frontend
- **Functions**: edge functions for deploying backend logic
- **AI Gateway**: OpenAI/Anthropic-compatible LLM routing with usage tracking
- **Memory**: durable, searchable, evolving user/agent memory for LLM apps
- **cron**: scheduled recurring jobs (edge-function or db-function targets)

The goal is to give AI developers, coding agents, and product teams a backend that understands AI-native needs from the start — and lets an agent deploy the whole app from one place.

## The Problem

Traditional backend-as-a-service platforms give developers Database, Storage, and Auth. That works for CRUD applications, but AI-native applications and AI-generated apps also need Memory:

- facts about a user
- preferences
- entities
- long-term conversation context
- evolving knowledge
- audit trails for why a memory changed

Teams usually rebuild this with a vector table, prompt glue, and ad hoc retrieval logic. Nubase makes Memory a first-class platform primitive.

AI Coding adds another problem: code agents can create features faster than teams can prepare durable backend infrastructure. Generated apps need a consistent place to create tables, call APIs, store files, authenticate users, and persist memory. Nubase gives agents and humans the same backend surface.

There is also a self-hosting gap. Supabase Cloud supports organizations and projects, but the official self-hosted Supabase stack is single-project oriented. Nubase is designed so one self-hosted control plane can manage many projects, with each project isolated at the database level.

## Target Users

Nubase is for:

- developers building products with AI Coding tools
- agentic app builders who need a backend that agents can operate safely
- AI app developers who need persistent user memory
- teams that want Supabase-style APIs but prefer Java/Spring infrastructure
- self-hosters running multiple small or medium projects
- agencies or internal platforms managing many apps
- developers who want Postgres isolation without running many full Supabase stacks

It is not yet a complete replacement for Supabase Cloud because Realtime, managed backups, PITR, HA orchestration, and enterprise operations are not part of the current open-source core. (Edge Functions, Assets, and cron — historically gaps — are now implemented.)

## Deployment Flow (generate → live)

The modules compose into one path an agent follows to ship a generated app. See [deploy-ai-generated-apps.md](deploy-ai-generated-apps.md) for the worked walkthrough.

1. **Model data** — create tables with RLS (`sql_execute`); manage users (`auth_*`). → Database + Auth
2. **Deploy backend logic** — scaffold, deploy, and invoke edge functions (`functions_deploy`). → Functions
3. **Publish the frontend** — upload the generated HTML/CSS/JS; get a public URL (`assets_upload`). → Assets
4. **Schedule work** — wire recurring jobs (`cron_create`). → cron
5. **Route AI calls** — send LLM traffic through the gateway (`gateway_*`). → AI Gateway
6. **Persist decisions** — record what was deployed (`memory_write`). → Memory

The frontend uses the **anon** key + user JWTs; service_role stays server-side and in trusted local agent tooling.

## Eight Modules

The modules are designed to be used by both humans and AI Coding agents. Studio gives humans a review surface; REST APIs and MCP tools give agents a stable operational surface. They are ordered by how broadly an app needs them: data and identity first, then the deploy layer, then AI enhancements and scheduling.

### Database

Each project gets a dedicated PostgreSQL database.

Current capabilities:

- database-per-project isolation
- PostgREST-compatible REST endpoints
- schema cache
- RLS with JWT claims
- project provisioning
- SQL execution through Studio
- per-project roles and JWT secrets

Main concepts:

- metadata database stores project routing
- project database stores tenant data
- `apikey` resolves project and role
- Bearer token resolves end user

### Auth

Auth provides Supabase-style identity flows and token issuance.

Current capabilities:

- email/password signup and login
- refresh token rotation
- email confirmation and recovery flows
- OAuth provider abstraction
- Google, GitHub, and WeChat OAuth providers
- admin user management
- per-project JWT secrets

### Storage

Storage stores object metadata in Postgres and object bytes in an S3-compatible backend.

Current capabilities:

- bucket create/list/update/delete
- public and private buckets
- object upload/download
- signed URLs
- R2/S3-compatible backend
- per-tenant object key layout
- optional S3 Vectors integration

### Assets

Assets is a public static CDN — where an agent publishes the frontend it just generated.

Current capabilities:

- upload/list/delete static files (HTML/CSS/JS, images, fonts) via `/assets/admin/v1` and MCP (`assets_upload`)
- public delivery at `/assets/v1/{path}` with Cache-Control / ETag / 304 semantics
- per-project default cache policy and an optional custom CDN domain
- CDN mode (dedicated R2 bucket) or backend mode (served by Nubase)

### Functions

Functions deploy AI-written backend logic as edge functions, with Nubase as the public gateway.

Current capabilities:

- scaffold/deploy/invoke via CLI and MCP (`functions_deploy`, `functions_invoke`)
- public invoke path `/functions/v1/{slug}` with `verify_jwt`
- per-function secrets, invocation logs, per-project/per-function rate limits
- local executor or Cloudflare Workers for Platforms dispatcher

### AI Gateway

The AI Gateway routes model calls through Nubase with per-project keys and usage tracking.

Current capabilities:

- OpenAI-compatible `/v1` and Anthropic-compatible `/v1/messages`
- per-project `nbk_` keys (issue/revoke) and token/request/cost usage analytics
- model routing across providers

### Memory

Memory turns user messages into durable facts.

Current capabilities:

- add memory from messages
- search memory
- update and delete memory
- inspect memory history
- extract entities
- retrieve with vector search, full-text search, and entity boost
- use OpenAI, Anthropic, or OpenAI-compatible providers

Main tables:

- `mem.memories`
- `mem.memory_history`
- `mem.entities`
- `mem.session_messages`
- `mem.config`

### cron

Scheduled Jobs run recurring work from the control plane.

Current capabilities:

- crontab schedules (UTC), evaluated by a control-plane scheduler safe across instances
- two target types: `edge_function` (invoke a deployed function) and `db_function` (call a named Postgres function)
- per-job timeout, run history, and retention
- managed via `/cron/admin/v1` and MCP (`cron_create`)

## Why Database-per-Project

Nubase uses a physical database boundary for each project.

Benefits:

- stronger tenant isolation than schema-only multi-tenancy
- independent database credentials per project
- independent JWT secrets per project
- easier backup/restore boundaries
- easier project export or migration
- clearer blast radius

Tradeoffs:

- more connections to manage
- more operational responsibility
- pool sizing matters
- provisioning requires database-level privileges

## Open-Source and Commercial Boundary

Recommended open-source core:

- project creation
- database-per-project isolation
- Auth
- Storage
- REST API
- Assets (static CDN)
- Functions (edge functions)
- AI Gateway
- Memory
- cron (scheduled jobs)
- local Studio
- local development docs

Recommended commercial layer:

- organizations and teams
- fine-grained RBAC
- SSO/SAML/SCIM
- audit logs
- quotas and billing
- managed backups and PITR
- high availability
- monitoring
- managed hosting
- enterprise support

This boundary keeps the open-source product useful while preserving a real business model.

## Current Status

Nubase is in active development. The core architecture is present, but the public open-source release should still complete:

- security hardening
- license selection
- better production deployment docs
- secret cleanup
- endpoint permission review
- CI setup
- contributor documentation
