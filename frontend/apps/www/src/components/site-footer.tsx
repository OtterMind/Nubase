import Link from 'next/link';
import { Github, Twitter } from 'lucide-react';

const COLS: { title: string; links: { href: string; label: string }[] }[] = [
  {
    title: 'Product',
    links: [
      { href: '/features', label: 'Features' },
      { href: '/compare', label: 'Compare' },
      { href: '/docs', label: 'Documentation' },
    ],
  },
  {
    title: 'Developers',
    links: [
      { href: '/docs/getting-started', label: 'Quickstart' },
      { href: '/docs/concepts', label: 'Architecture' },
      { href: '/docs/memory', label: 'Memory' },
    ],
  },
  {
    title: 'Resources',
    links: [
      { href: '/blog', label: 'Blog' },
      { href: '/news', label: 'News' },
      { href: 'https://github.com/OtterMind/Nubase', label: 'GitHub' },
    ],
  },
  {
    title: 'Legal',
    links: [
      { href: '/legal/privacy', label: 'Privacy' },
      { href: '/legal/terms', label: 'Terms' },
    ],
  },
];

export function SiteFooter() {
  return (
    <footer className="nb-charcoal-band relative overflow-hidden">
      {/* soft mint glow accent in a corner, echoing the hero */}
      <div className="pointer-events-none absolute -left-24 -top-24 h-72 w-72 rounded-full bg-[var(--nb-mint)]/20 blur-3xl" />
      <div className="container relative grid gap-10 py-16 md:grid-cols-[1.5fr_repeat(4,1fr)]">
        <div className="max-w-xs">
          <div className="mb-3 flex items-center gap-2.5">
            <span className="inline-flex items-center justify-center rounded-xl bg-gradient-to-b from-[var(--nb-mint)] to-[var(--nb-mint-deep)] p-1.5 shadow-[0_4px_12px_-4px_rgba(0,0,0,0.5),inset_0_1px_0_rgba(255,255,255,0.5),inset_0_-2px_5px_rgba(7,121,90,0.4)]">
              <svg viewBox="0 0 320 320" className="h-5 w-5" fill="none" aria-hidden="true">
                <path
                  d="M104 240 V80 L216 240 V80"
                  fill="none"
                  stroke="#07382c"
                  strokeWidth="40"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                />
                <circle cx="216" cy="80" r="21" fill="none" stroke="#07382c" strokeWidth="11" />
                <circle cx="104" cy="240" r="12" fill="#ffffff" />
              </svg>
            </span>
            <span className="font-display text-lg font-semibold text-white">nubase</span>
          </div>
          <p className="text-sm leading-6 text-white/55">
            The open-source, AI-native backend with real memory. Self-host the whole stack in one
            Docker image.
          </p>
          <div className="mt-5 flex gap-3">
            <Link
              href="https://github.com/OtterMind/Nubase"
              target="_blank"
              rel="noreferrer"
              aria-label="GitHub"
              className="inline-flex h-9 w-9 items-center justify-center rounded-full bg-white/10 text-white/80 transition-colors hover:bg-[var(--nb-mint)] hover:text-[#07382c]"
            >
              <Github className="h-4 w-4" />
            </Link>
            <Link
              href="https://x.com"
              target="_blank"
              rel="noreferrer"
              aria-label="X / Twitter"
              className="inline-flex h-9 w-9 items-center justify-center rounded-full bg-white/10 text-white/80 transition-colors hover:bg-[var(--nb-mint)] hover:text-[#07382c]"
            >
              <Twitter className="h-4 w-4" />
            </Link>
          </div>
        </div>

        {COLS.map((col) => (
          <div key={col.title}>
            <p className="mb-3 text-xs font-semibold uppercase tracking-[0.14em] text-white/45">
              {col.title}
            </p>
            <ul className="space-y-2.5">
              {col.links.map((link) => (
                <li key={link.href}>
                  <Link
                    href={link.href}
                    className="text-sm text-white/65 transition-colors hover:text-[var(--nb-mint)]"
                  >
                    {link.label}
                  </Link>
                </li>
              ))}
            </ul>
          </div>
        ))}
      </div>

      <div className="border-t border-white/10">
        <div className="container flex flex-col items-center justify-between gap-2 py-6 text-xs text-white/45 sm:flex-row">
          <p>&copy; {new Date().getFullYear()} Nubase. Apache-2.0. Built for AI-native apps.</p>
          <p>Made with care by the Nubase team.</p>
        </div>
      </div>
    </footer>
  );
}
