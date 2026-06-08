export interface CompareRow {
  feature: string;
  them: string;
  nubase: string;
  win?: boolean; // Nubase advantage
}

export interface Comparison {
  slug: string;
  competitor: string;
  category: string; // e.g. "Backend-as-a-Service"
  description: string;
  intro: string;
  rows: CompareRow[];
  whyNubase: string[];
  whenThem: string;
  faqs: { q: string; a: string }[];
}

export const COMPARISONS: Comparison[] = [
  {
    slug: 'supabase',
    competitor: 'Supabase',
    category: 'Open-source Firebase alternative',
    description:
      'Nubase vs Supabase: an open-source, AI-native backend with first-class Memory, multi-project '
      + 'self-hosting and native MCP for AI coding agents — compared with Supabase.',
    intro:
      'Supabase is an excellent open-source backend, but its self-hosted stack is built around a single '
      + 'project and has no built-in memory layer. Nubase keeps the parts that work — Postgres, REST, JWTs, '
      + 'Row Level Security, Storage and a Studio — and adds first-class Memory, an AI Gateway, native MCP '
      + 'tooling and many isolated projects from one self-hosted control plane.',
    rows: [
      { feature: 'License', them: 'Apache-2.0', nubase: 'Apache-2.0' },
      { feature: 'Multi-project self-hosting', them: 'Single project (self-hosted)', nubase: 'Many isolated projects, one Studio', win: true },
      { feature: 'Project isolation', them: 'One local project', nubase: 'Dedicated Postgres database per project', win: true },
      { feature: 'Database API', them: 'PostgREST', nubase: 'PostgREST-compatible' },
      { feature: 'Auth', them: 'Yes', nubase: 'Email, OAuth, MFA, OTP, SSO' },
      { feature: 'Storage', them: 'Yes (S3)', nubase: 'S3 / R2-compatible' },
      { feature: 'Built-in AI memory', them: 'No', nubase: 'First-class Memory pillar', win: true },
      { feature: 'AI Gateway (OpenAI/Anthropic)', them: 'No', nubase: 'Per-project keys + usage', win: true },
      { feature: 'MCP for coding agents', them: 'No', nubase: 'Native — Claude & Codex', win: true },
      { feature: 'Realtime / Edge Functions', them: 'Yes', nubase: 'Not yet' },
    ],
    whyNubase: [
      'Memory is a first-class primitive — extraction, entity graph and hybrid retrieval, not a separate vector script.',
      'One control plane provisions and routes to many isolated project databases.',
      'Coding agents operate it over MCP: schema, SQL, auth, storage and memory.',
    ],
    whenThem: 'Pick Supabase if you need Realtime or Edge Functions today, or want a managed cloud with a single dedicated project.',
    faqs: [
      { q: 'Is Nubase a drop-in Supabase replacement?', a: 'Nubase follows the Supabase developer model (apikey + Bearer JWT, PostgREST-style REST, RLS), so Supabase-style clients feel at home — but it is not byte-for-byte API compatible and does not yet implement Realtime or Edge Functions.' },
      { q: 'Can I self-host many projects?', a: 'Yes. Unlike single-project self-hosted Supabase, one Nubase control plane manages many projects, each with its own database, JWT secret and roles.' },
    ],
  },
  {
    slug: 'firebase',
    competitor: 'Firebase',
    category: 'Managed app backend',
    description:
      'Nubase vs Firebase: a free, open-source, self-hostable backend on Postgres with built-in AI '
      + 'memory and SQL — compared with Google Firebase.',
    intro:
      'Firebase is a fully managed backend, but it is proprietary, NoSQL-first and runs only on Google’s '
      + 'cloud. Nubase is open source, self-hostable, built on PostgreSQL with a real REST API and Row Level '
      + 'Security — and adds an AI memory layer Firebase has no equivalent for.',
    rows: [
      { feature: 'Open source', them: 'No (proprietary)', nubase: 'Yes — Apache-2.0', win: true },
      { feature: 'Self-host / own your data', them: 'No (Google Cloud only)', nubase: 'Yes — one Docker image', win: true },
      { feature: 'Database', them: 'Firestore (NoSQL)', nubase: 'PostgreSQL + SQL + REST', win: true },
      { feature: 'Row Level Security', them: 'Security rules', nubase: 'Postgres RLS with JWT claims' },
      { feature: 'Auth', them: 'Yes', nubase: 'Email, OAuth, MFA, OTP, SSO' },
      { feature: 'Storage', them: 'Yes', nubase: 'S3 / R2-compatible' },
      { feature: 'Built-in AI memory', them: 'No', nubase: 'First-class Memory pillar', win: true },
      { feature: 'Pricing model', them: 'Usage-metered', nubase: 'Free, self-hosted', win: true },
    ],
    whyNubase: [
      'Own your data: self-host the whole stack with no vendor lock-in.',
      'Relational Postgres + SQL instead of NoSQL-only documents.',
      'Built-in Memory and an AI Gateway for AI-native apps.',
    ],
    whenThem: 'Pick Firebase if you want a fully managed, serverless platform with Realtime and mobile SDKs and are happy on Google Cloud.',
    faqs: [
      { q: 'Does Nubase have a NoSQL document store?', a: 'No — Nubase is PostgreSQL-based. You get relational tables, SQL and a PostgREST-style REST API, plus JSON columns when you need schemaless fields.' },
      { q: 'Can I migrate from Firebase?', a: 'There is no automatic importer, but Nubase’s SQL + REST model maps cleanly from Firestore collections to Postgres tables.' },
    ],
  },
  {
    slug: 'appwrite',
    competitor: 'Appwrite',
    category: 'Open-source backend server',
    description:
      'Nubase vs Appwrite: a Postgres-based, AI-native open-source backend with built-in memory and MCP '
      + 'for coding agents — compared with Appwrite.',
    intro:
      'Appwrite is a popular open-source backend server. Nubase shares the open-source, self-hostable spirit '
      + 'but is built on PostgreSQL with a PostgREST-compatible API, adds first-class AI Memory and an AI '
      + 'Gateway, and is designed for AI coding agents through native MCP tooling.',
    rows: [
      { feature: 'Open source', them: 'Yes (BSD-3)', nubase: 'Yes — Apache-2.0' },
      { feature: 'Database engine', them: 'MariaDB (abstracted)', nubase: 'PostgreSQL + pgvector', win: true },
      { feature: 'SQL / PostgREST REST API', them: 'Custom REST/SDK', nubase: 'PostgREST-compatible /rest/v1', win: true },
      { feature: 'Auth', them: 'Yes', nubase: 'Email, OAuth, MFA, OTP, SSO' },
      { feature: 'Storage', them: 'Yes', nubase: 'S3 / R2-compatible' },
      { feature: 'Built-in AI memory', them: 'No', nubase: 'First-class Memory pillar', win: true },
      { feature: 'AI Gateway (OpenAI/Anthropic)', them: 'No', nubase: 'Per-project keys + usage', win: true },
      { feature: 'MCP for coding agents', them: 'No', nubase: 'Native — Claude & Codex', win: true },
    ],
    whyNubase: [
      'PostgreSQL + pgvector under the hood — SQL, RLS and vector search in one engine.',
      'Memory and AI Gateway make it AI-native, not just a CRUD backend.',
      'Agents target it over MCP for schema, SQL, auth, storage and memory.',
    ],
    whenThem: 'Pick Appwrite if you want its mature multi-language SDKs and console and do not need a Postgres/SQL foundation or AI memory.',
    faqs: [
      { q: 'Is Nubase built on Postgres?', a: 'Yes. Every project gets its own PostgreSQL database with pgvector, exposed through a PostgREST-compatible REST API with Row Level Security.' },
    ],
  },
  {
    slug: 'pocketbase',
    competitor: 'PocketBase',
    category: 'Single-binary backend',
    description:
      'Nubase vs PocketBase: a Postgres-based, multi-project AI-native backend with built-in memory — '
      + 'compared with the single-file SQLite backend PocketBase.',
    intro:
      'PocketBase is a delightful single-file backend built on SQLite — perfect for small apps. Nubase '
      + 'targets AI-native, multi-project workloads: PostgreSQL per project, a real REST + RLS surface, AI '
      + 'Memory and an AI Gateway, with one Docker image bundling everything.',
    rows: [
      { feature: 'Open source', them: 'Yes (MIT)', nubase: 'Yes — Apache-2.0' },
      { feature: 'Database', them: 'SQLite (single file)', nubase: 'PostgreSQL per project', win: true },
      { feature: 'Multi-project isolation', them: 'No', nubase: 'Dedicated DB per project', win: true },
      { feature: 'Auth', them: 'Yes', nubase: 'Email, OAuth, MFA, OTP, SSO' },
      { feature: 'Storage', them: 'Local / S3', nubase: 'S3 / R2-compatible' },
      { feature: 'Built-in AI memory', them: 'No', nubase: 'First-class Memory pillar', win: true },
      { feature: 'AI Gateway + MCP', them: 'No', nubase: 'Yes', win: true },
      { feature: 'Footprint', them: 'Single binary', nubase: 'One Docker image (PG + Redis + API + Studio)' },
    ],
    whyNubase: [
      'PostgreSQL scales past SQLite’s single-writer limits and adds pgvector.',
      'Run many isolated projects from one control plane.',
      'AI Memory + Gateway + MCP for AI-native apps and agents.',
    ],
    whenThem: 'Pick PocketBase for a tiny, single-binary app where SQLite is plenty and you do not need multi-project isolation or AI memory.',
    faqs: [
      { q: 'Is Nubase as lightweight as PocketBase?', a: 'Nubase bundles Postgres + Redis + API + Studio, so it is heavier than a single SQLite binary — but it still runs from one Docker image with auto-generated secrets.' },
    ],
  },
  {
    slug: 'convex',
    competitor: 'Convex',
    category: 'Reactive backend platform',
    description:
      'Nubase vs Convex: an open-source, self-hostable Postgres backend with built-in AI memory — '
      + 'compared with the reactive backend platform Convex.',
    intro:
      'Convex is a reactive backend with a TypeScript function model. Nubase takes a different path: an '
      + 'open-source, self-hostable PostgreSQL backend with a standard REST + RLS surface, first-class AI '
      + 'Memory and an AI Gateway — owned and run on your own infrastructure.',
    rows: [
      { feature: 'Open source & self-host', them: 'Limited', nubase: 'Yes — Apache-2.0, one image', win: true },
      { feature: 'Database', them: 'Document + reactive', nubase: 'PostgreSQL + SQL + REST', win: true },
      { feature: 'Standard REST API', them: 'Functions / TS client', nubase: 'PostgREST-compatible /rest/v1', win: true },
      { feature: 'Auth', them: 'Yes', nubase: 'Email, OAuth, MFA, OTP, SSO' },
      { feature: 'Built-in AI memory', them: 'No (DIY vectors)', nubase: 'First-class Memory pillar', win: true },
      { feature: 'AI Gateway + MCP', them: 'No', nubase: 'Yes', win: true },
    ],
    whyNubase: [
      'Standard Postgres + REST instead of a proprietary function runtime.',
      'Self-host with full data ownership under Apache-2.0.',
      'Memory, AI Gateway and MCP built for AI-native products.',
    ],
    whenThem: 'Pick Convex if you want its end-to-end reactive TypeScript model and managed hosting, and don’t need Postgres/SQL or self-hosting.',
    faqs: [
      { q: 'Does Nubase support realtime subscriptions?', a: 'Not yet — realtime is on the roadmap. Today Nubase focuses on REST, Auth, Storage and Memory over Postgres.' },
    ],
  },
];

export function getComparison(slug: string): Comparison | undefined {
  return COMPARISONS.find((c) => c.slug === slug);
}
