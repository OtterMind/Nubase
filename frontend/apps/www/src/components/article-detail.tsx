import Link from 'next/link';
import { ArrowRight, Star } from 'lucide-react';
import { Button } from '@nubase/ui';
import type { Article } from '@/lib/content';
import { SITE, url } from '@/lib/site';
import { ArticleBody } from '@/components/article-body';
import { JsonLd } from '@/components/json-ld';

const SECTION_LABEL: Record<Article['section'], string> = { blog: 'Blog', news: 'News' };

function fmt(date: string) {
  return new Date(date).toLocaleDateString('en-US', { year: 'numeric', month: 'long', day: 'numeric' });
}

export function ArticleDetail({ article }: { article: Article }) {
  const label = SECTION_LABEL[article.section];
  const ld = {
    '@context': 'https://schema.org',
    '@type': article.section === 'news' ? 'NewsArticle' : 'BlogPosting',
    headline: article.title,
    description: article.description,
    datePublished: article.date,
    dateModified: article.date,
    author: { '@type': 'Organization', name: article.author },
    publisher: { '@type': 'Organization', name: SITE.name },
    mainEntityOfPage: url(`/${article.section}/${article.slug}`),
    image: url(SITE.ogImage),
  };

  return (
    <main className="container py-16 lg:py-20">
      <JsonLd data={ld} />
      <article className="mx-auto max-w-2xl">
        <nav className="font-mono text-xs text-[var(--nb-dim)]">
          <Link href={`/${article.section}`} className="hover:text-green">{label}</Link> / {article.tag}
        </nav>
        <h1 className="mt-4 text-4xl font-bold leading-tight sm:text-5xl">{article.title}</h1>
        <div className="mt-4 flex items-center gap-3 font-mono text-[12px] text-[var(--nb-dim)]">
          <span>{fmt(article.date)}</span>
          <span>·</span>
          <span>{article.read}</span>
          <span>·</span>
          <span>{article.author}</span>
        </div>

        <div className="mt-10">
          <ArticleBody body={article.body} />
        </div>

        <div className="mt-14 flex flex-wrap items-center gap-3 border-t border-faint pt-8">
          <Link href="/docs/getting-started">
            <Button size="lg" variant="brand">Get started free <ArrowRight className="h-4 w-4" /></Button>
          </Link>
          <Link href={SITE.github} target="_blank" rel="noreferrer">
            <Button size="lg" variant="outline"><Star className="h-4 w-4" /> Star on GitHub</Button>
          </Link>
        </div>
      </article>
    </main>
  );
}
