// Central site config. Override the domain at build time with NEXT_PUBLIC_SITE_URL.
export const SITE_URL = (process.env.NEXT_PUBLIC_SITE_URL ?? 'https://nubase.ai').replace(/\/+$/, '');

export const SITE = {
  name: 'Nubase',
  tagline: 'The free, open-source, AI-native backend',
  description:
    'Free and open-source (Apache-2.0), self-hostable backend for AI apps and coding agents: '
    + 'first-class Memory, Database, Auth, Storage and an AI Gateway — with MCP for Claude & Codex.',
  github: 'https://github.com/OtterMind/Nubase',
  npm: 'https://www.npmjs.com/package/nubase_cli',
  ogImage: '/og.png',
  keywords: [
    'open source backend',
    'self-hosted backend',
    'AI-native backend',
    'AI memory',
    'vector memory',
    'Supabase alternative',
    'Firebase alternative',
    'PostgREST',
    'Row Level Security',
    'MCP',
    'Claude Code backend',
    'Codex backend',
    'BaaS',
    'backend as a service',
  ],
} as const;

export function url(path = '/'): string {
  return `${SITE_URL}${path.startsWith('/') ? path : `/${path}`}`;
}
