# Nubase Documentation

Nubase is an open-source backend service born for AI-native applications and AI Coding workflows. It gives generated apps and agent-built products a real backend target instead of a pile of one-off scripts.

It combines four primitives in one self-hostable platform:

- Memory
- Database
- Storage
- Auth

The project is Supabase-inspired, but it is built around two core differences:

- first-class AI memory as a platform primitive
- AI Coding and agent workflows through stable REST APIs, Studio, and MCP-friendly database tools
- self-hosted multi-project support through database-per-project isolation

## Start Here

- [Product overview](product-overview.md)
- [Supabase comparison](supabase-comparison.md)
- [Getting started](getting-started.md)
- [Architecture](architecture.md)
- [Edge Functions](edge-functions.md)
- [MCP and agent guide](mcp.md)
- [Connect agents](agent-connect.md)
- [Documentation plan](documentation-plan.md)

## Current Scope

Implemented or partially implemented:

- Platform users and Studio login
- Project creation and provisioning
- Per-project Postgres routing
- Supabase-style Auth
- PostgREST-compatible REST API
- S3/R2-compatible Storage
- AI Memory API
- Edge Functions initial gateway and executor provider
- SQL editor and SQL history
- Database and Memory MCP tools

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
