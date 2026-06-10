'use client';

import { useEffect } from 'react';

/**
 * Google One Tap sign-in for the marketing homepage.
 *
 * Auto-hides unless the backend reports Google is configured
 * (GET /auth/v1/platform/config → { google_enabled, google_client_id }).
 * On success we verify the credential server-side, then hand the resulting
 * platform token to the Studio login page via its #access_token fragment,
 * which establishes the session and routes the developer into their projects.
 *
 * www and Studio are served from the same origin (nubase.ai) behind APISIX,
 * so all requests here are same-origin relative paths.
 */

interface PublicConfig {
  google_enabled?: boolean;
  google_client_id?: string;
}

interface GoogleCredentialResponse {
  credential?: string;
}

declare global {
  interface Window {
    google?: {
      accounts: {
        id: {
          initialize: (cfg: {
            client_id: string;
            callback: (resp: GoogleCredentialResponse) => void;
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

function loadGis(): Promise<void> {
  return new Promise((resolve, reject) => {
    if (window.google?.accounts?.id) return resolve();
    const existing = document.querySelector<HTMLScriptElement>(`script[src="${GIS_SRC}"]`);
    if (existing) {
      existing.addEventListener('load', () => resolve());
      existing.addEventListener('error', () => reject(new Error('gis')));
      if (window.google?.accounts?.id) resolve();
      return;
    }
    const s = document.createElement('script');
    s.src = GIS_SRC;
    s.async = true;
    s.defer = true;
    s.onload = () => resolve();
    s.onerror = () => reject(new Error('gis'));
    document.head.appendChild(s);
  });
}

/**
 * Read the platform token from the shared `nubase.session` localStorage (written by Studio's zustand
 * persist store) and validate it. Returns true when the visitor already has a live session.
 */
async function hasValidSession(): Promise<boolean> {
  let token: string | null = null;
  try {
    const raw = localStorage.getItem('nubase.session');
    if (raw) token = (JSON.parse(raw)?.state?.platformKey as string | undefined) ?? null;
  } catch {
    token = null;
  }
  if (!token) return false;
  try {
    const res = await fetch('/auth/v1/platform/me', {
      headers: { Authorization: `Bearer ${token}` },
      cache: 'no-store',
    });
    return res.ok;
  } catch {
    return false;
  }
}

export function GoogleOneTap() {
  useEffect(() => {
    let cancelled = false;

    async function init() {
      let cfg: PublicConfig;
      try {
        const res = await fetch('/auth/v1/platform/config', { cache: 'no-store' });
        if (!res.ok) return;
        cfg = (await res.json()) as PublicConfig;
      } catch {
        return;
      }
      if (cancelled || !cfg.google_enabled || !cfg.google_client_id) return;

      // Already signed in? www and Studio share the nubase.ai origin (and the `nubase.session`
      // localStorage), so check for a valid existing session first and skip One Tap if found.
      if (await hasValidSession()) return;
      if (cancelled) return;

      try {
        await loadGis();
      } catch {
        return; // GIS unreachable — silently skip One Tap.
      }
      if (cancelled || !window.google) return;

      window.google.accounts.id.initialize({
        client_id: cfg.google_client_id,
        auto_select: false,
        cancel_on_tap_outside: true,
        context: 'signin',
        callback: (resp) => {
          if (!resp.credential) return;
          fetch('/auth/v1/platform/oauth/google', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ credential: resp.credential }),
          })
            .then((r) => (r.ok ? r.json() : Promise.reject(new Error('login failed'))))
            .then((auth: { access_token: string; expires_in?: number }) => {
              const frag =
                `#access_token=${encodeURIComponent(auth.access_token)}` +
                `&token_type=Bearer&expires_in=${auth.expires_in ?? 0}`;
              // replace() — not assign() — so the bearer token isn't left in this origin's history.
              window.location.replace(`/studio/login${frag}`);
            })
            .catch(() => {
              /* Verification failed — leave the page untouched. */
            });
        },
      });
      window.google.accounts.id.prompt();
    }

    void init();
    return () => {
      cancelled = true;
    };
  }, []);

  return null;
}
