import type { Metadata, Viewport } from 'next';
// Self-hosted fonts (bundled via npm) — no runtime dependency on Google Fonts / gstatic.
import '@fontsource-variable/bricolage-grotesque';
import '@fontsource-variable/hanken-grotesk';
import '@fontsource-variable/jetbrains-mono';
import { SiteHeader } from '@/components/site-header';
import { SiteFooter } from '@/components/site-footer';
import { JsonLd } from '@/components/json-ld';
import { SITE, SITE_URL, url } from '@/lib/site';
import '@nubase/ui/styles.css';
import './globals.css';

export const metadata: Metadata = {
  metadataBase: new URL(SITE_URL),
  title: {
    default: `${SITE.name} — ${SITE.tagline}`,
    template: `%s · ${SITE.name}`,
  },
  description: SITE.description,
  keywords: [...SITE.keywords],
  applicationName: SITE.name,
  authors: [{ name: 'The Nubase team' }],
  alternates: { canonical: '/' },
  openGraph: {
    type: 'website',
    siteName: SITE.name,
    title: `${SITE.name} — ${SITE.tagline}`,
    description: SITE.description,
    url: SITE_URL,
    images: [{ url: SITE.ogImage, width: 2640, height: 1800, alt: 'Nubase vs Supabase comparison' }],
  },
  twitter: {
    card: 'summary_large_image',
    title: `${SITE.name} — ${SITE.tagline}`,
    description: SITE.description,
    images: [SITE.ogImage],
  },
  robots: {
    index: true,
    follow: true,
    googleBot: { index: true, follow: true, 'max-image-preview': 'large', 'max-snippet': -1 },
  },
};

export const viewport: Viewport = {
  themeColor: '#0B1222',
};

const orgLd = {
  '@context': 'https://schema.org',
  '@type': 'Organization',
  name: SITE.name,
  url: SITE_URL,
  logo: url('/icon.svg'),
  sameAs: [SITE.github, SITE.npm],
};

const appLd = {
  '@context': 'https://schema.org',
  '@type': 'SoftwareApplication',
  name: SITE.name,
  applicationCategory: 'DeveloperApplication',
  operatingSystem: 'Linux, macOS, Windows (Docker)',
  description: SITE.description,
  url: SITE_URL,
  license: 'https://www.apache.org/licenses/LICENSE-2.0',
  offers: { '@type': 'Offer', price: '0', priceCurrency: 'USD' },
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html
      lang="en"
      className="dark"
      suppressHydrationWarning
    >
      <body className="min-h-screen bg-background font-sans antialiased">
        <JsonLd data={[orgLd, appLd]} />
        <SiteHeader />
        {children}
        <SiteFooter />
      </body>
    </html>
  );
}
