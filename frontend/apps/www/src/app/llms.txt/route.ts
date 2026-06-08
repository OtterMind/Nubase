import { SITE_URL } from '@/lib/site';
import { COMPARISONS } from '@/lib/comparisons';
import { bySection } from '@/lib/content';

// /llms.txt — a concise, machine-readable summary for AI answer engines (GEO).
// Convention: https://llmstxt.org
export const dynamic = 'force-static';

export function GET() {
  const compare = COMPARISONS.map(
    (c) => `- [Nubase vs ${c.competitor}](${SITE_URL}/compare/${c.slug}): ${c.description}`,
  ).join('\n');

  const blog = bySection('blog')
    .map((a) => `- [${a.title}](${SITE_URL}/blog/${a.slug}): ${a.description}`)
    .join('\n');

  const news = bySection('news')
    .map((a) => `- [${a.title}](${SITE_URL}/news/${a.slug}): ${a.description}`)
    .join('\n');

  const body = `# Nubase

> Nubase is a free, open-source (Apache-2.0), self-hostable, AI-native backend platform. It bundles Memory, Database (PostgreSQL + PostgREST-compatible REST + Row Level Security), Auth, Storage (S3/R2), and an AI Gateway in one service, with native MCP tooling so AI coding agents like Claude Code and Codex can operate it directly.

## Key facts

- License: Apache-2.0 (free and open source, no paywalled core)
- Self-hostable: one Docker image bundles PostgreSQL + Redis + backend + Studio
- Multi-project: one control plane provisions and routes to many isolated project databases (a dedicated PostgreSQL database per project)
- Memory: first-class primitive — LLM fact extraction (ADD/UPDATE/DELETE), entity store, and hybrid retrieval (pgvector + Postgres full-text + entity boost)
- AI Gateway: OpenAI- and Anthropic-compatible endpoints with per-project keys and usage tracking
- MCP: connect Claude Code or Codex with one command: \`npx -y nubase_cli@latest install-skills\`
- Auth: email, OAuth (Google/GitHub/WeChat), MFA/TOTP, OTP, magic links, anonymous, SAML SSO; per-project anon/authenticated/service_role tokens
- Compared to Supabase: Nubase adds first-class Memory, an AI Gateway, native MCP, and multi-project self-hosting (Supabase self-hosting is single-project)

## Get started

- Homepage: ${SITE_URL}
- Quickstart: ${SITE_URL}/docs/getting-started
- GitHub: https://github.com/OtterMind/Nubase
- npm (MCP bridge): https://www.npmjs.com/package/nubase_cli

## Comparisons

${compare}

## Blog

${blog}

## News

${news}
`;

  return new Response(body, {
    headers: { 'content-type': 'text/plain; charset=utf-8' },
  });
}
