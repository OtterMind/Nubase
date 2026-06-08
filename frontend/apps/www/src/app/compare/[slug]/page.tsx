import type { Metadata } from 'next';
import Link from 'next/link';
import { notFound } from 'next/navigation';
import { ArrowRight, Check, Minus, Star } from 'lucide-react';
import { Button } from '@nubase/ui';
import { COMPARISONS, getComparison } from '@/lib/comparisons';
import { SITE, url } from '@/lib/site';
import { JsonLd } from '@/components/json-ld';

export function generateStaticParams() {
  return COMPARISONS.map((c) => ({ slug: c.slug }));
}

export function generateMetadata({ params }: { params: { slug: string } }): Metadata {
  const c = getComparison(params.slug);
  if (!c) return {};
  const title = `Nubase vs ${c.competitor} — open-source, AI-native backend`;
  return {
    title,
    description: c.description,
    keywords: [`Nubase vs ${c.competitor}`, `${c.competitor} alternative`, 'open source backend', 'AI memory'],
    alternates: { canonical: url(`/compare/${c.slug}`) },
    openGraph: {
      title,
      description: c.description,
      url: url(`/compare/${c.slug}`),
      type: 'article',
      images: [SITE.ogImage],
    },
    twitter: { card: 'summary_large_image', title, description: c.description },
  };
}

export default function ComparePage({ params }: { params: { slug: string } }) {
  const c = getComparison(params.slug);
  if (!c) notFound();

  const faqLd = {
    '@context': 'https://schema.org',
    '@type': 'FAQPage',
    mainEntity: c.faqs.map((f) => ({
      '@type': 'Question',
      name: f.q,
      acceptedAnswer: { '@type': 'Answer', text: f.a },
    })),
  };
  const breadcrumbLd = {
    '@context': 'https://schema.org',
    '@type': 'BreadcrumbList',
    itemListElement: [
      { '@type': 'ListItem', position: 1, name: 'Compare', item: url('/compare') },
      { '@type': 'ListItem', position: 2, name: `Nubase vs ${c.competitor}`, item: url(`/compare/${c.slug}`) },
    ],
  };

  return (
    <main className="container py-16 lg:py-20">
      <JsonLd data={[faqLd, breadcrumbLd]} />

      <nav className="font-mono text-xs text-[var(--nb-dim)]">
        <Link href="/compare" className="hover:text-green">Compare</Link> / Nubase vs {c.competitor}
      </nav>

      <h1 className="mt-4 max-w-3xl text-4xl font-bold sm:text-5xl">
        Nubase <span className="text-[var(--nb-dim)]">vs</span> {c.competitor}
      </h1>
      <p className="mt-5 max-w-2xl text-lg leading-8 text-[var(--nb-dim)]">{c.intro}</p>

      {/* comparison table */}
      <div className="mt-10 overflow-hidden rounded-2xl border border-faint">
        <table className="w-full text-left text-sm">
          <thead>
            <tr className="border-b border-faint bg-white/[0.02] font-mono text-[11px] uppercase tracking-wider text-[var(--nb-dim)]">
              <th className="px-5 py-3 font-medium">Capability</th>
              <th className="px-5 py-3 font-medium">{c.competitor}</th>
              <th className="px-5 py-3 font-medium text-green">Nubase</th>
            </tr>
          </thead>
          <tbody>
            {c.rows.map((r) => (
              <tr key={r.feature} className="border-b border-faint last:border-0">
                <td className="px-5 py-3.5 font-medium text-[var(--nb-ink)]">{r.feature}</td>
                <td className="px-5 py-3.5 text-[var(--nb-dim)]">
                  <span className="inline-flex items-center gap-2">
                    <Minus className="h-3.5 w-3.5 opacity-50" />
                    {r.them}
                  </span>
                </td>
                <td className="px-5 py-3.5">
                  <span className={`inline-flex items-center gap-2 ${r.win ? 'text-green' : 'text-[var(--nb-ink)]'}`}>
                    {r.win ? <Star className="h-3.5 w-3.5" /> : <Check className="h-3.5 w-3.5 text-green" />}
                    {r.nubase}
                  </span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="mt-12 grid gap-8 lg:grid-cols-[1.1fr_0.9fr]">
        <div>
          <h2 className="font-display text-2xl font-bold">Why teams choose Nubase</h2>
          <ul className="mt-4 space-y-3 text-[var(--nb-dim)]">
            {c.whyNubase.map((w) => (
              <li key={w} className="flex items-start gap-3">
                <Check className="mt-1 h-4 w-4 shrink-0 text-green" />
                {w}
              </li>
            ))}
          </ul>
        </div>
        <div className="rounded-2xl border border-faint bg-[var(--nb-bg-2)]/40 p-6">
          <h2 className="font-display text-lg font-bold">When {c.competitor} is the better pick</h2>
          <p className="mt-3 text-sm leading-6 text-[var(--nb-dim)]">{c.whenThem}</p>
        </div>
      </div>

      {/* FAQ */}
      <div className="mt-14">
        <h2 className="font-display text-2xl font-bold">FAQ</h2>
        <div className="mt-5 grid gap-4 md:grid-cols-2">
          {c.faqs.map((f) => (
            <div key={f.q} className="rounded-2xl border border-faint bg-[var(--nb-bg-2)]/40 p-6">
              <h3 className="font-semibold">{f.q}</h3>
              <p className="mt-2 text-sm leading-6 text-[var(--nb-dim)]">{f.a}</p>
            </div>
          ))}
        </div>
      </div>

      {/* CTA */}
      <div className="mt-14 flex flex-wrap items-center gap-3">
        <Link href="/docs/getting-started">
          <Button size="lg" variant="brand">Try Nubase free <ArrowRight className="h-4 w-4" /></Button>
        </Link>
        <Link href={SITE.github} target="_blank" rel="noreferrer">
          <Button size="lg" variant="outline"><Star className="h-4 w-4" /> Star on GitHub</Button>
        </Link>
      </div>
    </main>
  );
}
