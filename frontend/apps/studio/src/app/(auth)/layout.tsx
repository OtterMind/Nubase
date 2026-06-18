'use client';

import Link from 'next/link';
import { LanguageToggle, useI18n } from '@/lib/i18n';

export default function AuthLayout({ children }: { children: React.ReactNode }) {
  const { tr } = useI18n();
  const year = new Date().getFullYear();
  return (
    <div className="grid min-h-screen lg:grid-cols-2">
      <div className="flex flex-col p-8">
        <div className="flex items-center justify-between gap-3">
          <Link href="/" className="flex items-center gap-2 text-sm font-semibold tracking-tight">
            <span className="inline-block h-6 w-6 rounded-md bg-brand" />
            nubase
          </Link>
          <LanguageToggle />
        </div>
        <div className="mx-auto flex w-full max-w-sm flex-1 flex-col justify-center">
          {children}
        </div>
        <div className="text-xs text-muted-foreground">
          {tr('auth.layout.copyright', { year })}
        </div>
      </div>
      <div className="hidden bg-secondary lg:flex lg:flex-col lg:justify-end lg:p-12">
        <blockquote className="space-y-2">
          <p className="text-lg leading-relaxed">
            {tr('auth.layout.quote')}
          </p>
          <footer className="text-sm text-muted-foreground">{tr('auth.layout.quoteBy')}</footer>
        </blockquote>
      </div>
    </div>
  );
}
