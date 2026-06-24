// Empty (relative) by default: the browser calls same-origin paths like `/auth/v1/...`,
// which the Next standalone server proxies to the backend (see next.config.mjs rewrites),
// or which the Java backend serves directly in static-export mode. This makes Studio work
// at any server IP/domain without rebuilding. Set NEXT_PUBLIC_NUBASE_API_URL to an ABSOLUTE
// URL only when serving Studio and the API on different origins without the built-in proxy.
export const API_BASE = process.env.NEXT_PUBLIC_NUBASE_API_URL ?? '';

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

/** Paginated envelope returned by GET /auth/v1/admin/projects. */
interface ProjectListEnvelope<T> {
  projects: T[];
  total: number;
  page: number;
  per_page: number;
}

/**
 * Fetch every project visible to the caller, flattening the backend's paginated response back
 * into a single array. Studio's project views (dashboard stats/search, switcher, lookups by ref)
 * operate over the full set, so this pages through with a large page size and accumulates.
 * Typically one request; only large (super-admin) accounts trigger follow-up pages.
 *
 * `T` is the per-project shape the caller cares about (mirrors the old `apiFetch<T[]>` usage).
 */
export async function fetchAllProjects<T>(platformKey: string): Promise<T[]> {
  const perPage = 200; // backend caps per_page at 200
  const first = await apiFetch<ProjectListEnvelope<T>>(
    `/auth/v1/admin/projects?page=1&per_page=${perPage}`,
    { apikey: platformKey }
  );
  const all = [...(first.projects ?? [])];
  const total = first.total ?? all.length;
  const pageCount = Math.ceil(total / perPage);
  for (let p = 2; p <= pageCount; p++) {
    const res = await apiFetch<ProjectListEnvelope<T>>(
      `/auth/v1/admin/projects?page=${p}&per_page=${perPage}`,
      { apikey: platformKey }
    );
    all.push(...(res.projects ?? []));
  }
  return all;
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
