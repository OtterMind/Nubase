'use client';

import { Suspense, useCallback, useEffect, useRef, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import Link from 'next/link';
import { Button, Input, Label } from '@nubase/ui';
import { API_BASE, apiFetch, type ApiError } from '@/lib/api';
import { useSession } from '@/lib/session';

interface PlatformAuthResponse {
  access_token: string;
  token_type: string;
  expires_in: number;
  user: { id: string; email: string; full_name?: string | null; role?: string | null };
}

interface PlatformUserPayload {
  id: string;
  email: string;
  full_name?: string | null;
  role?: string | null;
}

interface PublicConfig {
  signup_enabled?: boolean;
  google_enabled?: boolean;
  google_code_enabled?: boolean;
  github_enabled?: boolean;
  google_client_id?: string;
}

// Minimal Google Identity Services typings for the bits we use.
declare global {
  interface Window {
    google?: {
      accounts: {
        id: {
          initialize: (cfg: {
            client_id: string;
            callback: (resp: { credential?: string }) => void;
          }) => void;
          renderButton: (el: HTMLElement, opts: Record<string, unknown>) => void;
        };
      };
    };
  }
}

const GIS_SRC = 'https://accounts.google.com/gsi/client';

function loadGisScript(): Promise<void> {
  return new Promise((resolve, reject) => {
    if (typeof window === 'undefined') return reject(new Error('no window'));
    if (window.google?.accounts?.id) return resolve();
    const existing = document.querySelector<HTMLScriptElement>(`script[src="${GIS_SRC}"]`);
    if (existing) {
      existing.addEventListener('load', () => resolve());
      existing.addEventListener('error', () => reject(new Error('gis load failed')));
      if (window.google?.accounts?.id) resolve();
      return;
    }
    const s = document.createElement('script');
    s.src = GIS_SRC;
    s.async = true;
    s.defer = true;
    s.onload = () => resolve();
    s.onerror = () => reject(new Error('gis load failed'));
    document.head.appendChild(s);
  });
}

export default function LoginPage() {
  return (
    <Suspense fallback={null}>
      <LoginContent />
    </Suspense>
  );
}

function LoginContent() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const setAuth = useSession((s) => s.setAuth);
  const next = safeNext(searchParams.get('next'));

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [config, setConfig] = useState<PublicConfig | null>(null);
  const googleBtnRef = useRef<HTMLDivElement | null>(null);

  const completeLogin = useCallback(
    (res: PlatformAuthResponse) => {
      setAuth({
        platformKey: res.access_token,
        user: {
          id: res.user.id,
          email: res.user.email,
          fullName: res.user.full_name ?? null,
          role: res.user.role ?? null,
        },
      });
      router.push(next ?? '/projects');
    },
    [setAuth, router, next],
  );

  // Public config drives which OAuth providers are shown.
  useEffect(() => {
    let active = true;
    apiFetch<PublicConfig>('/auth/v1/platform/config')
      .then((cfg) => {
        if (active) setConfig(cfg);
      })
      .catch(() => {
        if (active) setConfig({});
      });
    return () => {
      active = false;
    };
  }, []);

  // GitHub redirects back with the token in the URL fragment (or ?oauth_error=…).
  useEffect(() => {
    if (typeof window === 'undefined') return;
    const params = new URLSearchParams(window.location.search);
    if (params.get('oauth_error')) {
      setError('Third-party sign in failed. Please try again.');
    }
    const hash = window.location.hash.startsWith('#') ? window.location.hash.slice(1) : '';
    if (!hash) return;
    const hp = new URLSearchParams(hash);
    const token = hp.get('access_token');
    if (!token) return;
    // Strip the fragment so the token never lingers in history.
    window.history.replaceState(null, '', window.location.pathname + window.location.search);
    setLoading(true);
    apiFetch<PlatformUserPayload>('/auth/v1/platform/me', { bearer: token })
      .then((user) => {
        completeLogin({
          access_token: token,
          token_type: hp.get('token_type') ?? 'Bearer',
          expires_in: Number(hp.get('expires_in') ?? '0'),
          user,
        });
      })
      .catch(() => {
        setError('Sign in session could not be established. Please try again.');
        setLoading(false);
      });
  }, [completeLogin]);

  // Render the Google Identity Services button when Google is enabled.
  useEffect(() => {
    if (!config?.google_enabled || !config.google_client_id) return;
    let cancelled = false;
    loadGisScript()
      .then(() => {
        if (cancelled || !window.google || !googleBtnRef.current) return;
        window.google.accounts.id.initialize({
          client_id: config.google_client_id!,
          callback: (resp) => {
            if (!resp.credential) return;
            setLoading(true);
            setError(null);
            apiFetch<PlatformAuthResponse>('/auth/v1/platform/oauth/google', {
              method: 'POST',
              body: { credential: resp.credential },
            })
              .then(completeLogin)
              .catch((err) => {
                setError(parseError(err as ApiError) ?? 'Google sign in failed.');
                setLoading(false);
              });
          },
        });
        const width = Math.min(googleBtnRef.current.offsetWidth || 320, 400);
        window.google.accounts.id.renderButton(googleBtnRef.current, {
          type: 'standard',
          theme: 'outline',
          size: 'large',
          text: 'continue_with',
          shape: 'rectangular',
          logo_alignment: 'center',
          width,
        });
      })
      .catch(() => {
        /* GIS unreachable — Google button is simply omitted. */
      });
    return () => {
      cancelled = true;
    };
  }, [config, completeLogin]);

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setLoading(true);
    setError(null);
    try {
      const res = await apiFetch<PlatformAuthResponse>('/auth/v1/platform/token', {
        method: 'POST',
        body: { email, password },
      });
      completeLogin(res);
    } catch (err) {
      const e = err as ApiError;
      setError(parseError(e) ?? 'Sign in failed.');
    } finally {
      setLoading(false);
    }
  }

  const showOAuth = Boolean(
    config?.google_enabled || config?.google_code_enabled || config?.github_enabled,
  );

  return (
    <div className="space-y-6">
      <div className="space-y-2">
        <h1 className="text-2xl font-semibold tracking-tight">Sign in to Studio</h1>
        <p className="text-sm text-muted-foreground">
          Manage your nubase projects, databases and tenants.
        </p>
      </div>

      {showOAuth ? (
        <div className="space-y-3">
          {config?.google_enabled ? (
            <div ref={googleBtnRef} className="flex min-h-[40px] w-full justify-center" />
          ) : null}
          {config?.google_code_enabled ? (
            <a
              href={`${API_BASE}/auth/v1/platform/oauth/google/start`}
              className="inline-flex h-10 w-full items-center justify-center gap-2 rounded-md border border-input bg-background text-sm font-medium transition-colors hover:bg-accent hover:text-accent-foreground"
            >
              <svg viewBox="0 0 48 48" aria-hidden="true" className="h-4 w-4">
                <path fill="#EA4335" d="M24 9.5c3.54 0 6.71 1.22 9.21 3.6l6.85-6.85C35.9 2.38 30.47 0 24 0 14.62 0 6.51 5.38 2.56 13.22l7.98 6.19C12.43 13.72 17.74 9.5 24 9.5z" />
                <path fill="#4285F4" d="M46.98 24.55c0-1.57-.15-3.09-.38-4.55H24v9.02h12.94c-.58 2.96-2.26 5.48-4.78 7.18l7.73 6c4.51-4.18 7.09-10.36 7.09-17.65z" />
                <path fill="#FBBC05" d="M10.53 28.59c-.48-1.45-.76-2.99-.76-4.59s.27-3.14.76-4.59l-7.98-6.19C.92 16.46 0 20.12 0 24c0 3.88.92 7.54 2.56 10.78l7.97-6.19z" />
                <path fill="#34A853" d="M24 48c6.48 0 11.93-2.13 15.89-5.81l-7.73-6c-2.15 1.45-4.92 2.3-8.16 2.3-6.26 0-11.57-4.22-13.47-9.91l-7.98 6.19C6.51 42.62 14.62 48 24 48z" />
              </svg>
              Continue with Google
            </a>
          ) : null}
          {config?.github_enabled ? (
            <a
              href={`${API_BASE}/auth/v1/platform/oauth/github/start`}
              className="inline-flex h-10 w-full items-center justify-center gap-2 rounded-md border border-input bg-background text-sm font-medium transition-colors hover:bg-accent hover:text-accent-foreground"
            >
              <svg viewBox="0 0 16 16" aria-hidden="true" className="h-4 w-4 fill-current">
                <path d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.01 8.01 0 0016 8c0-4.42-3.58-8-8-8z" />
              </svg>
              Continue with GitHub
            </a>
          ) : null}
          <div className="flex items-center gap-3">
            <span className="h-px flex-1 bg-border" />
            <span className="text-xs text-muted-foreground">or</span>
            <span className="h-px flex-1 bg-border" />
          </div>
        </div>
      ) : null}

      <form onSubmit={onSubmit} className="space-y-4">
        <div className="space-y-2">
          <Label htmlFor="email">Email</Label>
          <Input
            id="email"
            type="email"
            placeholder="you@example.com"
            required
            autoComplete="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
          />
        </div>
        <div className="space-y-2">
          <Label htmlFor="password">Password</Label>
          <Input
            id="password"
            type="password"
            required
            autoComplete="current-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
        </div>
        {error ? <p className="text-xs text-destructive">{error}</p> : null}
        <Button type="submit" disabled={loading} className="w-full">
          {loading ? 'Signing in…' : 'Sign in'}
        </Button>
      </form>
      <p className="text-center text-sm text-muted-foreground">
        Don&apos;t have an account?{' '}
        <Link href="/sign-up" className="font-medium text-foreground underline-offset-4 hover:underline">
          Sign up
        </Link>
      </p>
    </div>
  );
}

function safeNext(value: string | null): string | null {
  if (!value || !value.startsWith('/') || value.startsWith('//')) return null;
  return value;
}

function parseError(err: ApiError): string | null {
  try {
    const parsed = JSON.parse(err.message);
    return parsed?.message ?? null;
  } catch {
    return err.message;
  }
}
