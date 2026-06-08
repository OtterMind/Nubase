import Link from 'next/link';
import {
  ArrowRight,
  Boxes,
  Brain,
  Database,
  Github,
  HardDrive,
  KeyRound,
  Layers,
  type LucideIcon,
  Network,
  ShieldCheck,
  Sparkles,
  Star,
  Terminal,
  Workflow,
  Zap,
} from 'lucide-react';
import { Button } from '@nubase/ui';

const GH = 'https://github.com/OtterMind/Nubase';

interface Feature {
  icon: LucideIcon;
  title: string;
  body: string;
  points: string[];
  tag?: string;
  className?: string;
}

const FEATURES: Feature[] = [
  {
    icon: Brain,
    title: 'Memory',
    tag: '★ first-class',
    body: 'A real memory layer for AI apps — not a bolted-on vector script. Facts are extracted, embedded and retrieved with a hybrid engine.',
    points: [
      'LLM extraction with ADD / UPDATE / DELETE / NONE decisions',
      'Hybrid recall: pgvector cosine + Postgres full-text + entity boost',
      'Entity store with linked memories & append-only history',
    ],
    className: 'lg:col-span-2 nb-ring',
  },
  {
    icon: Database,
    title: 'Database',
    body: 'An isolated PostgreSQL per project with a PostgREST-compatible REST API.',
    points: [
      'Select / filter / order / paginate / insert / upsert / delete',
      'Row Level Security with JWT claims',
      'Per-project JWT secret, roles & schema cache',
    ],
  },
  {
    icon: KeyRound,
    title: 'Auth',
    body: 'Supabase-style auth with the full surface area, per project.',
    points: [
      'Email, OAuth (Google / GitHub / WeChat), magic links',
      'MFA / TOTP, OTP, anonymous sign-in, SAML SSO',
      'anon / authenticated / service_role tokens',
    ],
  },
  {
    icon: HardDrive,
    title: 'Storage',
    body: 'S3 / R2-compatible object storage with policy-aware access.',
    points: [
      'Cloudflare R2 · AWS S3 · MinIO',
      'Public & private buckets, signed URLs',
      'Size & MIME controls, optional S3 Vectors',
    ],
  },
  {
    icon: Sparkles,
    title: 'AI Gateway',
    body: 'Bring your own model. One gateway, OpenAI- and Anthropic-compatible.',
    points: [
      'OpenAI & Anthropic compatible endpoints',
      'Per-project keys',
      'Token & cost usage tracking',
    ],
  },
  {
    icon: Network,
    title: 'MCP for agents',
    tag: 'one command',
    body: 'Claude Code & Codex operate Nubase natively over MCP.',
    points: [
      'Inspect schema, run SQL, export RLS',
      'Manage auth & storage, read & write memory',
      'Provision and initialize projects',
    ],
    className: 'lg:col-span-2',
  },
];

const STATS: [string, string][] = [
  ['6', 'primitives in one service'],
  ['1', 'Docker image — Postgres + Redis + API + Studio'],
  ['∞', 'isolated projects per control plane'],
  ['0', 'vendor lock-in · Apache-2.0'],
];

const OPEN_POINTS: [string, string][] = [
  ['Free forever', 'The full backend is open source. No seats, no metering, no paywalled core.'],
  ['Self-host anywhere', 'One image bundles Postgres + Redis + backend + Studio. Your data stays on your box.'],
  ['Apache-2.0', 'Permissive license. Fork it, run it in production, build a business on it.'],
];

const WORKS_WITH = [
  'Claude Code', 'Codex', 'Cursor', 'OpenAI', 'Anthropic',
  'PostgreSQL', 'pgvector', 'Cloudflare R2', 'AWS S3', 'Docker', 'MCP',
];

const FLOW: [LucideIcon, string, string][] = [
  [Workflow, 'Generate', 'An AI coding agent scaffolds a feature against a real backend target.'],
  [Database, 'Provision', 'Nubase spins up an isolated Postgres and exposes /rest/v1 + Auth + Storage.'],
  [Brain, 'Remember', 'Memory persists user facts and entities, searchable across sessions.'],
  [Layers, 'Operate', 'Humans review schema, users, files, memories & SQL history in Studio.'],
];

const FAQ: [string, string][] = [
  ['Is it really free?', 'Yes — the entire backend is open source under Apache-2.0. Self-host it and run unlimited projects at no cost.'],
  ['How is this different from Supabase?', 'Supabase self-hosting targets a single project. Nubase is multi-project from one control plane, adds first-class Memory, an AI Gateway, and native MCP tooling for coding agents.'],
  ['Do I need to change my app code?', 'No — it speaks the familiar apikey + Bearer JWT model with PostgREST-style REST, so Supabase-style clients and generated apps feel at home.'],
  ['Which models does Memory use?', 'OpenAI, Anthropic, or any OpenAI-compatible provider — configured per project through the AI Gateway.'],
];

export default function Home() {
  return (
    <main className="overflow-hidden">
      {/* ---------------------------------------------------------------- HERO */}
      <section className="relative nb-grain">
        <div className="pointer-events-none absolute inset-0 -z-10 nb-aurora" />
        <div className="pointer-events-none absolute inset-0 -z-10 nb-grid" />
        <div className="container py-20 lg:py-28">
          <div className="mx-auto max-w-4xl text-center">
            <div className="nb-reveal inline-flex items-center gap-2 rounded-full border border-faint bg-white/[0.03] px-3.5 py-1.5 font-mono text-[11px] uppercase tracking-[0.18em] text-green" style={{ animationDelay: '60ms' }}>
              <span className="relative flex h-1.5 w-1.5">
                <span className="nb-pulse absolute inline-flex h-full w-full rounded-full bg-[var(--nb-green)]" />
                <span className="relative inline-flex h-1.5 w-1.5 rounded-full bg-[var(--nb-green)]" />
              </span>
              Free · Open source · Apache-2.0
            </div>

            <h1 className="nb-reveal mt-7 text-balance text-5xl font-extrabold leading-[1.02] sm:text-6xl lg:text-[5.2rem]" style={{ animationDelay: '120ms' }}>
              The open-source, AI-native backend with{' '}
              <span className="nb-shift">real memory</span>.
            </h1>

            <p className="nb-reveal mx-auto mt-7 max-w-2xl text-pretty text-lg leading-8 text-[var(--nb-dim)]" style={{ animationDelay: '180ms' }}>
              Memory, Database, Auth, Storage and an AI&nbsp;Gateway in one free, self-hostable
              service — built for AI apps and coding agents. Connect{' '}
              <span className="text-[var(--nb-ink)]">Claude or Codex</span> in a single command.
            </p>

            <div className="nb-reveal mx-auto mt-9 max-w-2xl overflow-hidden rounded-2xl border border-faint bg-[var(--nb-bg-2)]/80 text-left shadow-2xl shadow-black/40 backdrop-blur" style={{ animationDelay: '240ms' }}>
              <div className="flex items-center gap-2 border-b border-faint px-4 py-2.5 font-mono text-[11px] text-[var(--nb-dim)]">
                <Terminal className="h-3.5 w-3.5 text-green" /> one command — free, instant
              </div>
              <pre className="overflow-x-auto px-5 py-4 font-mono text-[13px] leading-7">
<span className="text-[var(--nb-dim)]"># connect your AI coding agent (Claude Code / Codex)</span>{'\n'}
<span className="text-[var(--nb-ink)]">npx -y </span><span className="text-green">nubase_cli@latest</span><span className="text-[var(--nb-ink)]"> install-skills</span>{'\n\n'}
<span className="text-[var(--nb-dim)]"># or self-host the whole stack</span>{'\n'}
<span className="text-[var(--nb-ink)]">docker run -p 3000:3000 -p 9999:9999 </span><span className="text-green">ottermind/nubase</span>
              </pre>
            </div>

            <div className="nb-reveal mt-8 flex flex-wrap items-center justify-center gap-3" style={{ animationDelay: '300ms' }}>
              <Link href="/docs/getting-started">
                <Button size="lg" variant="brand">Get started free <ArrowRight className="h-4 w-4" /></Button>
              </Link>
              <Link href={GH} target="_blank" rel="noreferrer">
                <Button size="lg" variant="outline"><Star className="h-4 w-4" /> Star on GitHub</Button>
              </Link>
            </div>

            <div className="nb-reveal mt-10 flex flex-wrap items-center justify-center gap-x-5 gap-y-2 font-mono text-[12px] uppercase tracking-wider text-[var(--nb-dim)]" style={{ animationDelay: '360ms' }}>
              {['Memory', 'Database', 'Auth', 'Storage', 'AI Gateway', 'MCP'].map((t) => (
                <span key={t} className="flex items-center gap-2">
                  <span className="h-1 w-1 rounded-full bg-[var(--nb-green)]" />{t}
                </span>
              ))}
            </div>
          </div>
        </div>
      </section>

      {/* --------------------------------------------------------- WORKS WITH */}
      <section className="border-y border-faint py-8">
        <p className="container mb-6 text-center font-mono text-[11px] uppercase tracking-[0.2em] text-[var(--nb-dim)]">
          Plays well with your stack
        </p>
        <div className="nb-mask-x relative overflow-hidden">
          <div className="nb-marquee gap-10 px-5">
            {[...WORKS_WITH, ...WORKS_WITH].map((name, i) => (
              <span key={`${name}-${i}`} className="whitespace-nowrap font-display text-lg font-semibold text-[var(--nb-dim)]/80">
                {name}
              </span>
            ))}
          </div>
        </div>
      </section>

      {/* ------------------------------------------------------------- STATS */}
      <section className="container py-16">
        <div className="grid gap-px overflow-hidden rounded-2xl border border-faint bg-[var(--nb-line)] sm:grid-cols-2 lg:grid-cols-4">
          {STATS.map(([n, label]) => (
            <div key={label} className="bg-[var(--nb-bg)] p-7">
              <div className="font-display text-5xl font-extrabold nb-gradient-text">{n}</div>
              <div className="mt-2 text-sm text-[var(--nb-dim)]">{label}</div>
            </div>
          ))}
        </div>
      </section>

      {/* ----------------------------------------------------------- FEATURES */}
      <section className="container py-12 lg:py-20">
        <div className="max-w-2xl">
          <p className="font-mono text-xs uppercase tracking-[0.2em] text-green">Everything in one service</p>
          <h2 className="mt-3 text-4xl font-bold sm:text-5xl">
            One backend. <span className="text-[var(--nb-dim)]">Six primitives.</span>
          </h2>
          <p className="mt-4 text-[var(--nb-dim)]">
            Built for AI from the ground up — Memory as a first-class citizen, the same per-project
            token model across every primitive, and isolation by default.
          </p>
        </div>

        <div className="mt-12 grid gap-5 lg:grid-cols-4">
          {FEATURES.map((f, i) => {
            const Icon = f.icon;
            return (
              <div key={f.title} className={`nb-card nb-reveal flex flex-col p-7 ${f.className ?? ''}`} style={{ animationDelay: `${i * 60}ms` }}>
                <div className="flex items-center justify-between">
                  <div className="flex h-11 w-11 items-center justify-center rounded-xl border border-faint bg-[var(--nb-green)]/10">
                    <Icon className="h-5 w-5 text-green" />
                  </div>
                  {f.tag && <span className="font-mono text-[10px] uppercase tracking-wider text-green">{f.tag}</span>}
                </div>
                <h3 className="mt-5 font-display text-xl font-bold">{f.title}</h3>
                <p className="mt-2 text-sm leading-6 text-[var(--nb-dim)]">{f.body}</p>
                <ul className="mt-4 space-y-2 text-[13px] text-[var(--nb-dim)]">
                  {f.points.map((pt) => (
                    <li key={pt} className="flex items-start gap-2.5">
                      <span className="mt-[7px] h-1 w-1 shrink-0 rounded-full bg-[var(--nb-green)]" />{pt}
                    </li>
                  ))}
                </ul>
              </div>
            );
          })}
        </div>
      </section>

      {/* --------------------------------------------------------- BYO MODEL */}
      <section className="border-y border-faint bg-white/[0.015] py-20 lg:py-24">
        <div className="container grid items-center gap-12 lg:grid-cols-2">
          <div>
            <p className="font-mono text-xs uppercase tracking-[0.2em] text-green">Memory + LLMs</p>
            <h2 className="mt-3 text-4xl font-bold sm:text-5xl">Bring your own model.</h2>
            <p className="mt-4 text-[var(--nb-dim)]">
              Nubase speaks <span className="text-[var(--nb-ink)]">OpenAI</span> and{' '}
              <span className="text-[var(--nb-ink)]">Anthropic</span>. The AI Gateway routes calls
              with per-project keys and usage tracking, while Memory turns raw conversation into a
              durable, searchable knowledge graph.
            </p>
            <div className="mt-7 grid grid-cols-3 gap-3 text-center">
              {[[Zap, 'Extract'], [Boxes, 'Embed'], [Brain, 'Recall']].map(([Ic, t], idx) => {
                const I = Ic as LucideIcon;
                return (
                  <div key={t as string} className="rounded-xl border border-faint bg-[var(--nb-bg-2)]/60 p-4">
                    <I className="mx-auto h-5 w-5 text-green" />
                    <div className="mt-2 font-mono text-[11px] uppercase tracking-wide text-[var(--nb-dim)]">{`0${idx + 1} ${t}`}</div>
                  </div>
                );
              })}
            </div>
          </div>

          <div className="nb-card p-2">
            <div className="flex items-center gap-2 border-b border-faint px-4 py-2.5 font-mono text-[11px] text-[var(--nb-dim)]">
              <Terminal className="h-3.5 w-3.5 text-green" /> mem/v1/memories
            </div>
            <pre className="overflow-x-auto px-5 py-4 font-mono text-[12.5px] leading-7 text-[var(--nb-dim)]">
<span className="text-green">POST</span> /mem/v1/memories{'\n'}
{'{'}{'\n'}
{'  '}<span className="text-[var(--nb-ink)]">"userId"</span>: "user-42",{'\n'}
{'  '}<span className="text-[var(--nb-ink)]">"messages"</span>: [{'\n'}
{'    '}{'{'} "role": "user",{'\n'}
{'      '}"content": "I prefer steak, and my dog is Mochi." {'}'}{'\n'}
{'  '}]{'\n'}
{'}'}{'\n\n'}
<span className="text-green">→</span> extracted · embedded · searchable forever
            </pre>
          </div>
        </div>
      </section>

      {/* ------------------------------------------------------ MULTI-PROJECT */}
      <section className="container py-20 lg:py-24">
        <div className="grid gap-12 lg:grid-cols-[0.9fr_1.1fr] lg:items-center">
          <div>
            <p className="font-mono text-xs uppercase tracking-[0.2em] text-green">Self-host · multi-project</p>
            <h2 className="mt-3 text-4xl font-bold sm:text-5xl">One control plane. Many isolated projects.</h2>
            <p className="mt-4 text-[var(--nb-dim)]">
              Unlike single-project self-hosting, one Nubase control plane provisions and routes to
              many projects — each with its own PostgreSQL database, JWT secret, roles and schema
              cache. A two-token model keeps every request scoped and safe.
            </p>
            <div className="mt-6 grid gap-3 sm:grid-cols-2">
              {[
                [ShieldCheck, 'Isolation by default', 'A dedicated database per project — no accidental shared boundary.'],
                [KeyRound, 'Two-token model', 'apikey resolves project + role; Bearer JWT scopes the end user for RLS.'],
              ].map(([Ic, t, d]) => {
                const I = Ic as LucideIcon;
                return (
                  <div key={t as string} className="rounded-xl border border-faint bg-[var(--nb-bg-2)]/50 p-5">
                    <I className="h-5 w-5 text-green" />
                    <div className="mt-3 font-semibold">{t as string}</div>
                    <p className="mt-1 text-sm text-[var(--nb-dim)]">{d as string}</p>
                  </div>
                );
              })}
            </div>
          </div>

          <div className="grid gap-3">
            {FLOW.map(([Ic, title, body], idx) => {
              const I = Ic;
              return (
                <div key={title} className="nb-card flex items-start gap-4 p-5">
                  <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-[var(--nb-green)]/12 font-mono text-sm font-bold text-green">
                    {idx + 1}
                  </div>
                  <div>
                    <div className="flex items-center gap-2 font-semibold">
                      <I className="h-4 w-4 text-green" />{title}
                    </div>
                    <p className="mt-1 text-sm text-[var(--nb-dim)]">{body}</p>
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      </section>

      {/* --------------------------------------------------------- OPEN/FREE */}
      <section className="border-y border-faint bg-white/[0.015]">
        <div className="container grid gap-8 py-14 md:grid-cols-3">
          {OPEN_POINTS.map(([title, body]) => (
            <div key={title}>
              <h3 className="font-display text-xl font-bold text-green">{title}</h3>
              <p className="mt-2 text-sm leading-6 text-[var(--nb-dim)]">{body}</p>
            </div>
          ))}
        </div>
      </section>

      {/* --------------------------------------------------------------- FAQ */}
      <section className="container py-20 lg:py-24">
        <h2 className="text-4xl font-bold sm:text-5xl">Questions, answered.</h2>
        <div className="mt-10 grid gap-4 md:grid-cols-2">
          {FAQ.map(([q, a]) => (
            <div key={q} className="rounded-2xl border border-faint bg-[var(--nb-bg-2)]/40 p-6">
              <h3 className="font-display text-lg font-bold">{q}</h3>
              <p className="mt-2 text-sm leading-6 text-[var(--nb-dim)]">{a}</p>
            </div>
          ))}
        </div>
      </section>

      {/* --------------------------------------------------------- FINAL CTA */}
      <section className="container pb-24">
        <div className="relative overflow-hidden rounded-3xl border border-faint nb-grain">
          <div className="pointer-events-none absolute inset-0 -z-10 nb-aurora opacity-80" />
          <div className="px-8 py-16 text-center lg:px-16">
            <Github className="mx-auto h-7 w-7 text-green" />
            <h2 className="mx-auto mt-5 max-w-3xl text-balance text-4xl font-bold sm:text-5xl">
              Free, open, and ready in one line.
            </h2>
            <p className="mx-auto mt-4 max-w-xl text-[var(--nb-dim)]">
              Give your AI app durable memory and a real backend from day one — self-hosted, on your
              terms, under Apache-2.0.
            </p>
            <div className="mt-8 flex flex-wrap items-center justify-center gap-3">
              <Link href="/docs/getting-started">
                <Button size="lg" variant="brand">Read the quickstart <ArrowRight className="h-4 w-4" /></Button>
              </Link>
              <Link href={GH} target="_blank" rel="noreferrer">
                <Button size="lg" variant="outline"><Star className="h-4 w-4" /> Star on GitHub</Button>
              </Link>
            </div>
          </div>
        </div>
      </section>
    </main>
  );
}
