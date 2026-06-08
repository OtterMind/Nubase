export type Block =
  | { h2: string }
  | { p: string }
  | { code: string }
  | { ul: string[] };

export interface Article {
  section: 'blog' | 'news';
  slug: string;
  title: string;
  description: string;
  date: string; // ISO yyyy-mm-dd
  author: string;
  read: string; // e.g. "4 min read"
  tag: string;
  body: Block[];
}

export const ARTICLES: Article[] = [
  // --------------------------------------------------------------- NEWS
  {
    section: 'news',
    slug: 'nubase-is-now-open-source',
    title: 'Nubase is now open source',
    description:
      'The free, AI-native backend — Memory, Database, Auth, Storage and an AI Gateway — is now open '
      + 'source under Apache-2.0.',
    date: '2026-06-09',
    author: 'The Nubase team',
    read: '2 min read',
    tag: 'Release',
    body: [
      { p: 'Nubase is now open source under Apache-2.0. The entire backend — Memory, Database, Auth, Storage and the AI Gateway — is free to self-host, with no paywalled core and no metering.' },
      { h2: 'What you get' },
      { ul: [
        'One Docker image bundling PostgreSQL + Redis + the backend + Studio',
        'A first-class Memory layer with extraction and hybrid retrieval',
        'Native MCP tooling so Claude Code and Codex can operate it directly',
      ] },
      { h2: 'Get started in one line' },
      { code: 'npx -y nubase_cli@latest install-skills' },
      { p: 'Star the project on GitHub, open an issue, and tell us what you build.' },
    ],
  },
  {
    section: 'news',
    slug: 'all-in-one-docker-image',
    title: 'Self-host the whole stack with one Docker image',
    description:
      'A single multi-architecture image runs Postgres, Redis, the API and Studio — your data, your box.',
    date: '2026-06-08',
    author: 'The Nubase team',
    read: '2 min read',
    tag: 'Release',
    body: [
      { p: 'You can now run all of Nubase from a single Docker image — Postgres + pgvector, Redis, the backend API and the Studio dashboard — with secrets auto-generated into a persistent volume.' },
      { code: 'docker run -p 3000:3000 -p 9999:9999 -v nubase_data:/data ottermind/nubase' },
      { p: 'The image is multi-architecture (amd64 + arm64) and configured entirely through environment variables.' },
    ],
  },
  // --------------------------------------------------------------- BLOG
  {
    section: 'blog',
    slug: 'why-ai-apps-need-a-memory-layer',
    title: 'Why AI apps need a real memory layer',
    description:
      'Stuffing context into a prompt is not memory. Here is what durable, retrievable memory looks like '
      + 'as a backend primitive.',
    date: '2026-06-07',
    author: 'The Nubase team',
    read: '6 min read',
    tag: 'Memory',
    body: [
      { p: 'Most “AI memory” is a prompt-stuffing trick: cram recent messages into the context window and hope. That breaks the moment a conversation ends, a session rotates, or a user comes back next week.' },
      { h2: 'Memory is a backend primitive, not a prompt' },
      { p: 'Durable memory means turning conversation into structured, retrievable knowledge: extract the facts, resolve entities, store history, and recall the right thing at the right time — across sessions and devices.' },
      { h2: 'How Nubase models it' },
      { ul: [
        'Extraction: an LLM decides to ADD, UPDATE, DELETE or do NOTHING with each fact.',
        'Entity store: facts link to people, places and things for better recall.',
        'Hybrid retrieval: pgvector cosine similarity + Postgres full-text + entity boost.',
        'Append-only history: every change is auditable and reversible.',
      ] },
      { h2: 'A single API call' },
      { code: 'POST /mem/v1/memories\n{ "userId": "user-42", "messages": [ ... ] }' },
      { p: 'The result is extracted, embedded and searchable forever — owned by you, on your own infrastructure.' },
    ],
  },
  {
    section: 'blog',
    slug: 'connect-claude-and-codex-to-your-backend',
    title: 'Connect Claude Code and Codex to your backend in one command',
    description:
      'How MCP turns Nubase into a backend your AI coding agent can actually operate — inspect schema, '
      + 'run SQL, manage auth and write memory.',
    date: '2026-06-05',
    author: 'The Nubase team',
    read: '5 min read',
    tag: 'AI Coding',
    body: [
      { p: 'AI coding tools generate UI fast, but they need a real backend to target. The Model Context Protocol (MCP) lets an agent operate one directly — no copy-pasting API docs.' },
      { h2: 'One command' },
      { code: 'npx -y nubase_cli@latest install-skills' },
      { p: 'That installs the Nubase skills for both Claude Code and Codex, wires up the MCP server and authorizes your project.' },
      { h2: 'What the agent can do' },
      { ul: [
        'Inspect schema and export Row Level Security policies',
        'Run SQL and provision project databases',
        'Manage auth users and storage buckets',
        'Read and write durable memory',
      ] },
      { p: 'The result: your agent builds against a stable, isolated backend — and you review everything in Studio.' },
    ],
  },
];

export function bySection(section: Article['section']): Article[] {
  return ARTICLES.filter((a) => a.section === section).sort((a, b) => (a.date < b.date ? 1 : -1));
}

export function getArticle(section: Article['section'], slug: string): Article | undefined {
  return ARTICLES.find((a) => a.section === section && a.slug === slug);
}
