import type { Metadata } from 'next';
import { bySection } from '@/lib/content';
import { ArticleList } from '@/components/article-list';
import { SITE, url } from '@/lib/site';

export const metadata: Metadata = {
  title: 'Blog — Nubase',
  description: 'Engineering notes and guides on AI memory, self-hosting and building AI-native backends with Nubase.',
  alternates: { canonical: url('/blog') },
  openGraph: { title: 'Nubase Blog', description: 'AI memory, self-hosting and AI-native backends.', url: url('/blog'), images: [SITE.ogImage] },
};

export default function BlogIndex() {
  return (
    <main className="container py-20 lg:py-24">
      <p className="font-mono text-xs uppercase tracking-[0.2em] text-green">Blog</p>
      <h1 className="mt-3 text-4xl font-bold sm:text-5xl">Notes on building AI-native backends</h1>
      <p className="mt-4 max-w-2xl text-[var(--nb-dim)]">
        Guides and deep dives on memory, self-hosting, and connecting AI coding agents to a real backend.
      </p>
      <ArticleList items={bySection('blog')} />
    </main>
  );
}
