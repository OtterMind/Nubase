import Link from 'next/link';
import { Star } from 'lucide-react';
import { HeaderAuth } from '@/components/header-auth';

const NAV = [
  { href: '/features', label: 'Features' },
  { href: '/compare', label: 'Compare' },
  { href: '/docs', label: 'Docs' },
  { href: '/blog', label: 'Blog' },
  { href: '/news', label: 'News' },
];

const GH = 'https://github.com/OtterMind/Nubase';

function Logo({ className = 'h-8 w-8' }: { className?: string }) {
  return (
    <span className="inline-flex items-center justify-center rounded-xl bg-gradient-to-b from-[var(--nb-mint)] to-[var(--nb-mint-deep)] p-1.5 shadow-[0_8px_20px_-10px_rgba(20,180,137,0.85),0_2px_4px_-1px_rgba(7,121,90,0.3),inset_0_1px_0_rgba(255,255,255,0.55),inset_0_-2px_5px_rgba(7,121,90,0.4)]">
      <svg viewBox="0 0 320 320" className={className} fill="none" aria-hidden="true">
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
  );
}

export function SiteHeader() {
  return (
    <header className="sticky top-0 z-40 w-full border-b border-[var(--nb-line)] bg-white/85 backdrop-blur-md">
      <div className="container flex h-16 items-center justify-between gap-4">
        <Link href="/" className="flex items-center gap-2.5 text-[17px] font-display font-semibold tracking-tight text-[var(--nb-ink)]">
          <Logo className="h-5 w-5" />
          nubase
        </Link>

        <nav className="hidden items-center gap-1 md:flex">
          {NAV.map((item) => (
            <Link
              key={item.href}
              href={item.href}
              className="rounded-full px-3.5 py-2 text-sm font-medium text-[var(--nb-dim)] transition-colors hover:bg-[var(--nb-mint-soft)] hover:text-[var(--nb-ink)]"
            >
              {item.label}
            </Link>
          ))}
        </nav>

        <div className="flex items-center gap-2">
          <Link
            href={GH}
            target="_blank"
            rel="noreferrer"
            className="hidden items-center gap-1.5 rounded-full border border-[var(--nb-line)] bg-white px-3.5 py-2 text-sm font-semibold text-[var(--nb-ink)] transition-colors hover:border-[var(--nb-mint)] hover:bg-[var(--nb-mint-soft)] sm:inline-flex"
          >
            <Star className="h-4 w-4" /> Star
          </Link>
          <HeaderAuth />
        </div>
      </div>
    </header>
  );
}
