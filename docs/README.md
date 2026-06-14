# Nubase Documentation

Nubase turns AI-written code into real apps: the backend **and deploy layer** that a coding agent drives directly to ship a generated app online. It gives generated apps and agent-built products a real target instead of a pile of one-off scripts.

It combines eight capability modules in one self-hostable platform:

- Database — Postgres + PostgREST (`/rest/v1`) with RLS
- Auth — Supabase-style users, sessions, OAuth/MFA (`/auth/v1`)
- Storage — buckets + signed URLs for user files (`/storage/v1`)
- Assets — publish the generated frontend to a public CDN (`/assets/v1`)
- Functions — deploy backend logic as edge functions (`/functions/v1`)
- AI Gateway — OpenAI/Anthropic-compatible LLM routing + usage (`/v1`)
- Memory — durable agent/user/project context (`/mem/v1`)
- cron — schedule recurring jobs

The project is Supabase-inspired, but it is built around three core differences:

- a complete generate → live deploy surface (frontend + backend + cron) in one platform
- first-class AI memory as a platform primitive
- self-hosted multi-project support through database-per-project isolation

## Start Here

- [Product overview](product-overview.md)
- [Deploy an AI-generated app (generate → live)](deploy-ai-generated-apps.md)
- [Getting started](getting-started.md)
- [Connect agents](agent-connect.md)
- [MCP and agent guide](mcp.md)
- [Supabase comparison](supabase-comparison.md)
- [Architecture](architecture.md)
- Capability deep-dives: [Edge Functions](edge-functions.md) · [Assets (static CDN)](assets.md) · [Scheduled Jobs (cron)](scheduled-jobs.md)
- [Documentation plan](documentation-plan.md)

## Current Scope

Implemented or partially implemented:

- Platform users and Studio login
- Project creation and provisioning
- Per-project Postgres routing (Database) + PostgREST-compatible REST API
- Supabase-style Auth
- S3/R2-compatible Storage
- Assets static CDN (`/assets/v1`) for publishing generated frontends
- Edge Functions gateway + executor provider (local / Cloudflare WfP)
- AI Gateway (OpenAI/Anthropic-compatible) with usage tracking
- AI Memory API
- Scheduled Jobs (cron) for edge-function and db-function targets
- SQL editor and SQL history
- MCP tools across Database, Auth, Storage, Assets, Functions, AI Gateway, Memory, and cron

Not implemented yet:

- Realtime
- Managed backups and PITR
- Production-grade HA orchestration
- Billing
- Enterprise SSO

## External References

- Supabase self-hosting: https://supabase.com/docs/guides/self-hosting
- Supabase product docs: https://supabase.com/docs
- Supabase organizations and projects: https://supabase.com/docs/guides/platform/billing-faq
