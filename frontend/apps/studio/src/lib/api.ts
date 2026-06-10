export const API_BASE = process.env.NEXT_PUBLIC_NUBASE_API_URL ?? 'http://localhost:9999';

export interface ApiError {
  status: number;
  message: string;
}

interface RequestOptions extends Omit<RequestInit, 'body'> {
  body?: unknown;
  apikey?: string;
  bearer?: string;
  /** Platform session requests may clear the Studio session and redirect on 401. */
  authScope?: 'platform' | 'tenant';
}

export async function apiFetch<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { body, apikey, bearer, authScope, headers, ...rest } = options;

  const finalHeaders: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(apikey ? { apikey } : {}),
    ...(bearer ? { Authorization: `Bearer ${bearer}` } : {}),
    ...(headers as Record<string, string>),
  };

  const res = await fetch(`${API_BASE}${path}`, {
    ...rest,
    headers: finalHeaders,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });

  if (!res.ok) {
    // Only platform-session requests should clear the Studio login. Tenant/project API keys can also
    // receive 401s, but those are project errors and must not sign the user out of Studio.
    if (res.status === 401 && shouldHandlePlatformUnauthorized(path, authScope) && typeof window !== 'undefined') {
      try {
        localStorage.removeItem('nubase.session');
      } catch {
        /* ignore */
      }
      window.location.replace(studioLoginPath());
    }
    const message = await res.text().catch(() => res.statusText);
    throw { status: res.status, message } satisfies ApiError;
  }

  if (res.status === 204) return undefined as T;
  const contentType = res.headers.get('content-type') ?? '';
  if (contentType.includes('application/json')) {
    return (await res.json()) as T;
  }
  return (await res.text()) as unknown as T;
}

function studioLoginPath(): string {
  if (typeof window === 'undefined') return '/login';
  const path = window.location.pathname;
  return path === '/studio' || path.startsWith('/studio/') ? '/studio/login' : '/login';
}

function shouldHandlePlatformUnauthorized(path: string, authScope?: 'platform' | 'tenant'): boolean {
  if (authScope === 'tenant') return false;
  if (authScope === 'platform') return true;
  return (
    path.startsWith('/auth/v1/platform/password') ||
    path.startsWith('/auth/v1/admin/projects') ||
    path.startsWith('/auth/v1/admin/init/') ||
    path.startsWith('/auth/v1/admin/platform/')
  );
}
