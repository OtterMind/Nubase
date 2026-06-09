'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { ArrowRight, LayoutDashboard } from 'lucide-react';

interface SessionUser {
  email: string;
  full_name?: string | null;
}

/**
 * Login-aware header controls. www and Studio share the nubase.ai origin (and the `nubase.session`
 * localStorage written by Studio), so we read the platform token, validate it via /me, and show
 * either the signed-in user + a Dashboard link, or a "Get started" entrance to Studio.
 *
 * SSR renders the signed-out state; the effect upgrades to signed-in after mount (no hydration
 * mismatch, since the first client render also starts signed-out).
 */
export function HeaderAuth() {
  const [user, setUser] = useState<SessionUser | null>(null);

  useEffect(() => {
    let cancelled = false;

    async function load() {
      let token: string | null = null;
      try {
        const raw = localStorage.getItem('nubase.session');
        if (raw) token = (JSON.parse(raw)?.state?.platformKey as string | undefined) ?? null;
      } catch {
        token = null;
      }
      if (!token) return;
      try {
        const res = await fetch('/auth/v1/platform/me', {
          headers: { Authorization: `Bearer ${token}` },
          cache: 'no-store',
        });
        if (!res.ok || cancelled) return;
        const me = (await res.json()) as SessionUser;
        if (!cancelled) setUser(me);
      } catch {
        /* network/invalid token — stay signed-out */
      }
    }

    void load();
    return () => {
      cancelled = true;
    };
  }, []);

  if (user) {
    const label = user.full_name?.trim() || user.email;
    const initial = (label?.[0] ?? '?').toUpperCase();
    return (
      <div className="flex items-center gap-2">
        <Link
          href="/studio/projects"
          className="inline-flex items-center gap-1.5 rounded-full bg-[var(--nb-ink)] px-4 py-2 text-sm font-semibold text-white transition-transform hover:-translate-y-0.5 hover:bg-[var(--nb-green-deep)]"
        >
          <LayoutDashboard className="h-4 w-4" /> Dashboard
        </Link>
        <Link
          href="/studio/account"
          title={user.email}
          aria-label="Account"
          className="flex h-8 w-8 items-center justify-center rounded-full bg-[var(--nb-mint)] text-xs font-semibold text-[#07382c] shadow-sm transition-opacity hover:opacity-90"
        >
          {initial}
        </Link>
      </div>
    );
  }

  return (
    <Link
      href="/studio/login"
      className="inline-flex items-center gap-1.5 rounded-full bg-[var(--nb-ink)] px-4 py-2 text-sm font-semibold text-white transition-transform hover:-translate-y-0.5 hover:bg-[var(--nb-green-deep)]"
    >
      Get started <ArrowRight className="h-4 w-4" />
    </Link>
  );
}
