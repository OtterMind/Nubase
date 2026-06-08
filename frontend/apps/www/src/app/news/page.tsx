import type { Metadata } from 'next';
import { bySection } from '@/lib/content';
import { ArticleList } from '@/components/article-list';
import { SITE, url } from '@/lib/site';

export const metadata: Metadata = {
  title: 'News — Nubase',
  description: 'Release notes and announcements from the Nubase open-source project.',
  alternates: { canonical: url('/news') },
  openGraph: { title: 'Nubase News', description: 'Releases and announcements.', url: url('/news'), images: [SITE.ogImage] },
};

export default function NewsIndex() {
  return (
    <main className="container py-20 lg:py-24">
      <p className="font-mono text-xs uppercase tracking-[0.2em] text-green">News</p>
      <h1 className="mt-3 text-4xl font-bold sm:text-5xl">Releases &amp; announcements</h1>
      <p className="mt-4 max-w-2xl text-[var(--nb-dim)]">
        What&apos;s new in Nubase — releases, milestones and project updates.
      </p>
      <ArticleList items={bySection('news')} />
    </main>
  );
}
