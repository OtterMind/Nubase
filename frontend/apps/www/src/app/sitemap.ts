import type { MetadataRoute } from 'next';
import { SITE_URL } from '@/lib/site';
import { COMPARISONS } from '@/lib/comparisons';
import { bySection } from '@/lib/content';

export default function sitemap(): MetadataRoute.Sitemap {
  const now = new Date();

  const staticPages = [
    { path: '/', priority: 1, freq: 'weekly' as const },
    { path: '/features', priority: 0.8, freq: 'monthly' as const },
    { path: '/compare', priority: 0.9, freq: 'weekly' as const },
    { path: '/blog', priority: 0.8, freq: 'weekly' as const },
    { path: '/news', priority: 0.8, freq: 'weekly' as const },
    { path: '/docs', priority: 0.7, freq: 'weekly' as const },
    { path: '/docs/getting-started', priority: 0.7, freq: 'monthly' as const },
    { path: '/docs/concepts', priority: 0.6, freq: 'monthly' as const },
    { path: '/docs/memory', priority: 0.6, freq: 'monthly' as const },
    { path: '/docs/memory/quickstart', priority: 0.6, freq: 'monthly' as const },
  ];

  const entries: MetadataRoute.Sitemap = staticPages.map((p) => ({
    url: `${SITE_URL}${p.path}`,
    lastModified: now,
    changeFrequency: p.freq,
    priority: p.priority,
  }));

  for (const c of COMPARISONS) {
    entries.push({
      url: `${SITE_URL}/compare/${c.slug}`,
      lastModified: now,
      changeFrequency: 'monthly',
      priority: 0.8,
    });
  }

  for (const a of [...bySection('blog'), ...bySection('news')]) {
    entries.push({
      url: `${SITE_URL}/${a.section}/${a.slug}`,
      lastModified: new Date(a.date),
      changeFrequency: 'monthly',
      priority: 0.6,
    });
  }

  return entries;
}
