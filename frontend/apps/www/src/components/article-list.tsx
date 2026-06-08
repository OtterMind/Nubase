import Link from 'next/link';
import { ArrowRight } from 'lucide-react';
import type { Article } from '@/lib/content';

function fmt(date: string) {
  return new Date(date).toLocaleDateString('en-US', { year: 'numeric', month: 'short', day: 'numeric' });
}

export function ArticleList({ items }: { items: Article[] }) {
  return (
    <div className="mt-12 grid gap-5 md:grid-cols-2">
      {items.map((a) => (
        <Link key={a.slug} href={`/${a.section}/${a.slug}`} className="nb-card group flex flex-col p-7">
          <div className="flex items-center gap-3 font-mono text-[11px] uppercase tracking-wider text-[var(--nb-dim)]">
            <span className="text-green">{a.tag}</span>
            <span>·</span>
            <span>{fmt(a.date)}</span>
            <span>·</span>
            <span>{a.read}</span>
          </div>
          <h2 className="mt-3 font-display text-xl font-bold">{a.title}</h2>
          <p className="mt-2 flex-1 text-sm leading-6 text-[var(--nb-dim)]">{a.description}</p>
          <span className="mt-4 inline-flex items-center gap-1 text-sm text-green">
            Read more
            <ArrowRight className="h-4 w-4 transition-transform group-hover:translate-x-0.5" />
          </span>
        </Link>
      ))}
    </div>
  );
}
