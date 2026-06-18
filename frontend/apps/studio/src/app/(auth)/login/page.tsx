'use client';

import { Suspense, useCallback, useEffect, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import Link from 'next/link';
import { Button, Input, Label } from '@nubase/ui';
import { API_BASE, apiFetch, type ApiError } from '@/lib/api';
import { useSession } from '@/lib/session';
import { useI18n } from '@/lib/i18n';

interface PlatformAuthResponse {
  access_token: string;
  token_type: string;
  expires_in: number;
  user: { id: string; email: string; full_name?: string | null; role?: string | null };
}

interface PendingResponse {
  verification_required: true;
  email: string;
}

type TokenResult = PlatformAuthResponse | PendingResponse;

function isPending(r: TokenResult): r is PendingResponse {
  return (r as PendingResponse).verification_required === true;
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
            auto_select?: boolean;
            cancel_on_tap_outside?: boolean;
            context?: string;
          }) => void;
          prompt: () => void;
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
  const { tr } = useI18n();
  const next = safeNext(searchParams.get('next'));

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [config, setConfig] = useState<PublicConfig | null>(null);

  // Set when an unverified account must confirm an emailed code before its first session.
  const [pendingEmail, setPendingEmail] = useState<string | null>(null);
  const [code, setCode] = useState('');
  const [notice, setNotice] = useState<string | null>(null);

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
      setError(tr('auth.error.thirdParty'));
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
        setError(tr('auth.error.session'));
        setLoading(false);
      });
  }, [completeLogin, tr]);

  // Google One Tap: auto-detect a signed-in Google account and float the prompt (top-right) so the
  // user can sign in with one click — no inline button next to the password form. Users not signed
  // into Google fall back to the "Continue with Google" redirect / GitHub / password.
  useEffect(() => {
    if (!config?.google_enabled || !config.google_client_id) return;
    // Don't pop One Tap while the email-verification step is showing.
    if (pendingEmail) return;
    let cancelled = false;
    loadGisScript()
      .then(() => {
        if (cancelled || !window.google) return;
        window.google.accounts.id.initialize({
          client_id: config.google_client_id!,
          auto_select: false,
          cancel_on_tap_outside: true,
          context: 'signin',
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
                setError(parseError(err as ApiError) ?? tr('auth.error.google'));
                setLoading(false);
              });
          },
        });
        window.google.accounts.id.prompt();
      })
      .catch(() => {
        /* GIS unreachable — One Tap is simply skipped; other sign-in options remain. */
      });
    return () => {
      cancelled = true;
    };
  }, [config, completeLogin, pendingEmail, tr]);

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setLoading(true);
    setError(null);
    try {
      const res = await apiFetch<TokenResult>('/auth/v1/platform/token', {
        method: 'POST',
        body: { email, password },
      });
      if (isPending(res)) {
        setPendingEmail(res.email);
        setNotice(tr('auth.confirmEmail.sent', { email: res.email }));
      } else {
        completeLogin(res);
      }
    } catch (err) {
      const e = err as ApiError;
      setError(parseError(e) ?? tr('auth.error.signIn'));
    } finally {
      setLoading(false);
    }
  }

  async function onVerify(e: React.FormEvent) {
    e.preventDefault();
    setLoading(true);
    setError(null);
    try {
      const res = await apiFetch<PlatformAuthResponse>('/auth/v1/platform/verify-email', {
        method: 'POST',
        body: { email: pendingEmail, code },
      });
      completeLogin(res);
    } catch (err) {
      setError(parseError(err as ApiError) ?? tr('auth.error.verify'));
      setLoading(false);
    }
  }

  async function onResend() {
    setError(null);
    try {
      await apiFetch('/auth/v1/platform/verify-email/resend', {
        method: 'POST',
        body: { email: pendingEmail },
      });
    } catch {
      /* always report sent — no account enumeration */
    }
    setNotice(tr('auth.confirmEmail.resent'));
  }

  // One Tap (google_enabled) floats as an overlay, so the inline button row only shows the
  // redirect-based providers.
  const showOAuth = Boolean(config?.google_code_enabled || config?.github_enabled);

  if (pendingEmail) {
    return (
      <div className="space-y-6">
        <div className="space-y-2">
          <h1 className="text-2xl font-semibold tracking-tight">{tr('auth.confirmEmail.title')}</h1>
          <p className="text-sm text-muted-foreground">
            {tr('auth.confirmEmail.body', { email: pendingEmail })}
          </p>
        </div>
        <form onSubmit={onVerify} className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor="code">{tr('auth.verificationCode')}</Label>
            <Input
              id="code"
              inputMode="numeric"
              autoComplete="one-time-code"
              maxLength={6}
              required
              placeholder="123456"
              value={code}
              onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))}
            />
          </div>
          {notice ? <p className="text-xs text-muted-foreground">{notice}</p> : null}
          {error ? <p className="text-xs text-destructive">{error}</p> : null}
          <Button type="submit" disabled={loading || code.length < 6} className="w-full">
            {loading ? tr('auth.confirmEmail.verifying') : tr('auth.confirmEmail.verify')}
          </Button>
        </form>
        <button
          type="button"
          onClick={onResend}
          className="block w-full text-center text-sm text-muted-foreground underline-offset-4 hover:underline"
        >
          {tr('auth.confirmEmail.resend')}
        </button>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <div className="space-y-2">
        <h1 className="text-2xl font-semibold tracking-tight">{tr('auth.login.title')}</h1>
        <p className="text-sm text-muted-foreground">{tr('auth.login.subtitle')}</p>
      </div>

      {showOAuth ? (
        <div className="space-y-3">
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
              {tr('auth.login.google')}
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
              {tr('auth.login.github')}
            </a>
          ) : null}
          <div className="flex items-center gap-3">
            <span className="h-px flex-1 bg-border" />
            <span className="text-xs text-muted-foreground">{tr('auth.login.or')}</span>
            <span className="h-px flex-1 bg-border" />
          </div>
        </div>
      ) : null}

      <form onSubmit={onSubmit} className="space-y-4">
        <div className="space-y-2">
          <Label htmlFor="email">{tr('auth.email')}</Label>
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
          <Label htmlFor="password">{tr('auth.password')}</Label>
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
          {loading ? tr('auth.login.submitting') : tr('auth.login.submit')}
        </Button>
      </form>
      <p className="text-center text-sm text-muted-foreground">
        {tr('auth.login.noAccount')}{' '}
        <Link href="/sign-up" className="font-medium text-foreground underline-offset-4 hover:underline">
          {tr('auth.login.signUp')}
        </Link>
      </p>
    </div>
  );
}

function safeNext(value: string | null): string | null {
  if (!value || !value.startsWith('/')) return null;
  // Block protocol-relative (//evil.com) and backslash tricks (/\evil.com, which browsers
  // normalise to //evil.com) — both would be open redirects to another origin.
  if (value[1] === '/' || value[1] === '\\') return null;
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
