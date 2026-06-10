# Nubase and Supabase Comparison

Nubase is Supabase-inspired, but it is not trying to clone every Supabase product surface. It is an AI-native backend service focused on:

- a backend target for AI Coding and agent-built products
- self-hosted multi-project support
- database-per-project isolation
- AI memory as a core backend primitive
- a Java/Spring backend implementation

## Official Supabase Context

Supabase Cloud has organizations and projects. Supabase's billing FAQ says an organization may contain multiple projects and each project is a dedicated Supabase instance with sub-services including Storage, Auth, Functions, and Realtime:

https://supabase.com/docs/guides/platform/billing-faq

Supabase self-hosting is different. The official self-hosting docs state that self-hosted Supabase mimics a single project and Studio does not support multiple organizations or projects:

https://supabase.com/docs/guides/self-hosting

Supabase product docs list core products including Database, Auth, Storage, Realtime, and Edge Functions:

https://supabase.com/docs

## High-Level Comparison

| Area | Supabase Cloud | Supabase self-hosted | Nubase |
| --- | --- | --- | --- |
| Primary use case | Managed backend platform | Self-host one Supabase project | Self-host many isolated projects |
| Project model | Organizations with many projects | Single-project oriented | Many projects in one control plane |
| Database isolation | Dedicated project instance | One self-hosted project | One physical Postgres database per project |
| Dashboard | Supabase Dashboard | Studio for one project | Studio for many projects |
| Database API | PostgREST | PostgREST | Java PostgREST-compatible API |
| Auth | GoTrue | GoTrue | Supabase-style Auth implemented in Java |
| Storage | Supabase Storage | Supabase Storage | S3/R2-compatible storage service |
| Realtime | Yes | Yes in Supabase stack | Not implemented yet |
| Edge Functions | Yes | Yes in Supabase stack | Initial Spring gateway + pluggable executor |
| AI memory | Not a core product primitive | Not a core product primitive | Built-in Memory pillar |
| AI Coding workflow | General backend platform | General backend stack | REST + Memory + MCP tools + Studio |
| Implementation | Supabase services | Supabase Docker stack | Spring Boot backend + Next.js Studio |

## Where Nubase Is Stronger

### Multi-project self-hosting

Nubase is designed for a single self-hosted platform to manage many projects. Each project maps to a dedicated PostgreSQL database and has its own:

- database credentials
- JWT secret
- service role key
- authenticated key
- schema cache
- auth tables
- storage metadata
- memory tables

This is the central product difference.

### AI Memory

Supabase gives developers primitives to build memory: Postgres, pgvector, Edge Functions, and client libraries. Nubase ships Memory as a platform feature.

Nubase Memory includes:

- fact extraction
- memory update/delete decisions
- embeddings
- BM25
- entity boost
- memory history
- entity browser
- per-user authorization

### AI Coding backend surface

AI Coding tools are most useful when they have a stable backend contract to target. Nubase provides:

- generated table access through `/rest/v1/*`
- project keys and isolated project databases
- Memory APIs for user preferences and long-term context
- Studio for human inspection and repair
- MCP database tools for schema inspection and SQL operations

This makes Nubase useful not only as app infrastructure, but also as infrastructure that coding agents can operate against.

### Java-native backend

Nubase runs as a Spring Boot application. This may be useful for teams that prefer:

- JVM deployment
- Spring Security
- Java observability tooling
- a single backend process for Auth, REST, Storage, Memory, and MCP tools

## Where Supabase Is Stronger Today

Supabase is more mature in:

- Realtime
- Edge Functions runtime parity
- managed backups
- PITR
- branching
- production analytics
- ecosystem maturity
- client libraries
- integrations
- operational documentation
- managed hosting

Nubase should not claim parity in these areas until they are implemented and tested.

## Compatibility Philosophy

Nubase aims for practical compatibility, not perfect internal equivalence.

Compatible concepts:

- `apikey` header
- service role key
- anon/authenticated roles
- Bearer user JWT
- `auth.uid()` style RLS
- `/auth/v1/*` style auth endpoints
- `/rest/v1/*` table REST API
- `/storage/v1/*` style storage API

Different implementation:

- Auth is implemented in Java, not GoTrue.
- PostgREST behavior is implemented in Java, not the PostgREST Haskell server.
- Project routing uses a metadata database plus Spring routing data sources.
- Memory is a Nubase-specific pillar.

## When to Choose Nubase

Choose Nubase when:

- you need to self-host multiple projects
- you want stronger project isolation than shared schemas
- you are building AI applications that need persistent memory
- you prefer a Java/Spring backend
- you want Supabase-like APIs without running many Supabase stacks

## When to Choose Supabase

Choose Supabase when:

- you need mature Realtime today
- you need Edge Functions today
- you want managed backups and PITR
- you want the largest ecosystem and client-library support
- you prefer a fully managed platform
- you need production-grade platform features immediately

## Product Strategy

Nubase should compete on:

- self-hosted multi-project operations
- database-per-project isolation
- AI memory
- simple JVM deployment
- clean open-source core

Nubase should not initially compete on:

- being a full Supabase Cloud replacement
- having every Supabase dashboard feature
- Realtime parity
- Edge Functions parity
- enterprise compliance before the foundation is stable
