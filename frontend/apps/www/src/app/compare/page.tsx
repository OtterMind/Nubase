import type { Metadata } from 'next';
import Link from 'next/link';
import { ArrowRight } from 'lucide-react';
import { COMPARISONS } from '@/lib/comparisons';
import { SITE, url } from '@/lib/site';

export const metadata: Metadata = {
  title: 'Nubase vs alternatives — open-source backend comparison',
  description:
    'How Nubase compares to Supabase, Firebase, Appwrite, PocketBase and Convex: an open-source, '
    + 'AI-native backend with first-class Memory, multi-project self-hosting and native MCP.',
  alternates: { canonical: url('/compare') },
  openGraph: {
    title: 'Nubase vs alternatives — open-source backend comparison',
    description: 'Compare Nubase with Supabase, Firebase, Appwrite, PocketBase and Convex.',
    url: url('/compare'),
    images: [SITE.ogImage],
  },
};

export default function ComparePage() {
  return (
    <main className="container py-20 lg:py-24">
      <p className="font-mono text-xs uppercase tracking-[0.2em] text-green">Comparisons</p>
      <h1 className="mt-3 text-4xl font-bold sm:text-5xl">Nubase vs the alternatives</h1>
      <p className="mt-4 max-w-2xl text-[var(--nb-dim)]">
        An honest look at how Nubase — a free, open-source, AI-native backend with first-class Memory
        — stacks up against popular backend and database services.
      </p>

      <div className="mt-12 grid gap-5 md:grid-cols-2">
        {COMPARISONS.map((c) => (
          <Link
            key={c.slug}
            href={`/compare/${c.slug}`}
            className="nb-card group flex flex-col p-7"
          >
            <span className="font-mono text-[11px] uppercase tracking-wider text-[var(--nb-dim)]">
              {c.category}
            </span>
            <h2 className="mt-2 flex items-center gap-2 font-display text-2xl font-bold">
              Nubase <span className="text-[var(--nb-dim)]">vs</span> {c.competitor}
            </h2>
            <p className="mt-2 flex-1 text-sm leading-6 text-[var(--nb-dim)]">{c.description}</p>
            <span className="mt-4 inline-flex items-center gap-1 text-sm text-green">
              Read the comparison
              <ArrowRight className="h-4 w-4 transition-transform group-hover:translate-x-0.5" />
            </span>
          </Link>
        ))}
      </div>
    </main>
  );
}
