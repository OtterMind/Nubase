import Link from 'next/link';
import { Button } from '@nubase/ui';

const NAV = [
  { href: '/features', label: 'Features' },
  { href: '/compare', label: 'Compare' },
  { href: '/docs', label: 'Docs' },
  { href: '/blog', label: 'Blog' },
  { href: '/news', label: 'News' },
  { href: 'https://github.com/OtterMind/Nubase', label: 'GitHub' },
];

export function SiteHeader() {
  return (
    <header className="sticky top-0 z-40 w-full border-b border-border/60 bg-background/80 backdrop-blur">
      <div className="container flex h-14 items-center justify-between">
        <Link href="/" className="flex items-center gap-2 text-sm font-semibold tracking-tight">
          <svg viewBox="0 0 320 320" className="h-7 w-7" fill="none" aria-hidden="true">
            <defs>
              <linearGradient id="nbLogo" x1="96" y1="80" x2="224" y2="240" gradientUnits="userSpaceOnUse">
                <stop offset="0" stopColor="#3DE3AF" />
                <stop offset="1" stopColor="#10A074" />
              </linearGradient>
            </defs>
            <path
              d="M104 240 V80 L216 240 V80"
              fill="none"
              stroke="url(#nbLogo)"
              strokeWidth="40"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
            <circle cx="216" cy="80" r="21" fill="none" stroke="#3DE3AF" strokeWidth="11" />
            <circle cx="104" cy="240" r="12" fill="#10A074" />
          </svg>
          nubase
        </Link>
        <nav className="hidden gap-6 md:flex">
          {NAV.map((item) => (
            <Link
              key={item.href}
              href={item.href}
              className="text-sm text-muted-foreground transition-colors hover:text-foreground"
            >
              {item.label}
            </Link>
          ))}
        </nav>
        <div className="flex items-center gap-2">
          <Link href="/studio/projects">
            <Button size="sm" variant="brand">
              Open Studio
            </Button>
          </Link>
        </div>
      </div>
    </header>
  );
}
