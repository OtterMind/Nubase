import type { Metadata } from 'next';
import { notFound } from 'next/navigation';
import { bySection, getArticle } from '@/lib/content';
import { ArticleDetail } from '@/components/article-detail';
import { SITE, url } from '@/lib/site';

export function generateStaticParams() {
  return bySection('blog').map((a) => ({ slug: a.slug }));
}

export function generateMetadata({ params }: { params: { slug: string } }): Metadata {
  const a = getArticle('blog', params.slug);
  if (!a) return {};
  return {
    title: `${a.title} — Nubase Blog`,
    description: a.description,
    alternates: { canonical: url(`/blog/${a.slug}`) },
    openGraph: {
      title: a.title,
      description: a.description,
      url: url(`/blog/${a.slug}`),
      type: 'article',
      publishedTime: a.date,
      images: [SITE.ogImage],
    },
    twitter: { card: 'summary_large_image', title: a.title, description: a.description },
  };
}

export default function BlogPost({ params }: { params: { slug: string } }) {
  const a = getArticle('blog', params.slug);
  if (!a) notFound();
  return <ArticleDetail article={a} />;
}
