'use client';

import { Suspense, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { useRouter, useSearchParams } from 'next/navigation';
import { ArrowRight, CheckCircle2, KeyRound, Loader2, ShieldCheck } from 'lucide-react';
import { Button, Card, CardContent, Input, cn } from '@nubase/ui';
import { apiFetch, fetchAllProjects, type ApiError } from '@/lib/api';
import { useSession } from '@/lib/session';

interface ProjectSummary {
  ref: string;
  name: string;
  description?: string | null;
  initStatus?: string | null;
  healthStatus?: string | null;
  enabled: boolean;
  apikey: string;
}

type Status = 'idle' | 'posting' | 'done';

export default function CliAuthorizePage() {
  return (
    <Suspense
      fallback={
        <main className="mx-auto flex min-h-screen max-w-3xl items-center justify-center px-6">
          <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" />
        </main>
      }
    >
      <CliAuthorizeContent />
    </Suspense>
  );
}

function CliAuthorizeContent() {
  const params = useSearchParams();
  const router = useRouter();
  const { platformKey, user, hasHydrated, signOut } = useSession();
  const callback = params.get('callback') ?? '';
  const state = params.get('state') ?? '';
  const nubaseUrl = params.get('nubase_url') ?? undefined;
  const agentId = params.get('agent_id') ?? undefined;
  const sessionId = params.get('session_id') ?? undefined;

  const [projects, setProjects] = useState<ProjectSummary[]>([]);
  const [selectedRef, setSelectedRef] = useState<string | null>(null);
  const [query, setQuery] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [status, setStatus] = useState<Status>('idle');

  useEffect(() => {
    if (!hasHydrated) return;
    if (!platformKey) {
      const next = `/cli/authorize?${params.toString()}`;
      router.replace(`/login?next=${encodeURIComponent(next)}`);
      return;
    }

    let cancelled = false;
    setLoading(true);
    setError(null);
    fetchAllProjects<ProjectSummary>(platformKey)
      .then((res) => {
        if (cancelled) return;
        setProjects(res);
        setSelectedRef((current) => current ?? res[0]?.ref ?? null);
      })
      .catch((err: ApiError) => {
        if (cancelled) return;
        if (err.status === 401) {
          signOut();
          router.replace(`/login?next=${encodeURIComponent(`/cli/authorize?${params.toString()}`)}`);
          return;
        }
        setError(parseError(err) ?? 'Failed to load projects.');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [hasHydrated, params, platformKey, router, signOut]);

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return projects;
    return projects.filter(
      (p) =>
        p.ref.toLowerCase().includes(q) ||
        p.name.toLowerCase().includes(q) ||
        (p.description?.toLowerCase().includes(q) ?? false)
    );
  }, [projects, query]);

  const selected = projects.find((p) => p.ref === selectedRef) ?? null;
  const invalidRequest = !isLocalCallback(callback) || !state;

  async function authorize() {
    if (!selected || invalidRequest) return;
    setStatus('posting');
    setError(null);
    try {
      // Fetch the anon/authenticated key so the CLI can hand it to generated apps.
      // Best-effort: the project list omits it, and authorization still succeeds
      // with just the service_role key if this lookup fails.
      let anonKey: string | undefined;
      if (platformKey) {
        try {
          const keys = await apiFetch<{ authenticated_token?: string | null }>(
            `/auth/v1/admin/projects/${encodeURIComponent(selected.ref)}/keys`,
            { apikey: platformKey }
          );
          anonKey = keys.authenticated_token ?? undefined;
        } catch {
          // ignore — anon key stays undefined
        }
      }
      const res = await fetch(callback, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          state,
          nubaseUrl,
          projectKey: selected.apikey,
          projectRef: selected.ref,
          projectName: selected.name,
          anonKey,
          userId: user?.id,
          userEmail: user?.email,
        }),
      });
      if (!res.ok) {
        const text = await res.text();
        throw new Error(text || `Local callback failed with ${res.status}`);
      }
      setStatus('done');
    } catch (err) {
      setStatus('idle');
      setError((err as Error).message || 'Failed to authorize the CLI.');
    }
  }

  if (!hasHydrated || (!platformKey && !invalidRequest)) {
    return (
      <main className="mx-auto flex min-h-screen max-w-3xl items-center justify-center px-6">
        <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" />
      </main>
    );
  }

  return (
    <main className="mx-auto flex min-h-screen w-full max-w-4xl flex-col gap-5 px-6 py-8">
      <section className="rounded-lg border border-border bg-card p-5">
        <div className="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
          <div className="min-w-0">
            <div className="mb-3 inline-flex items-center gap-2 rounded-md border border-border bg-background px-2 py-1 text-xs text-muted-foreground">
              <KeyRound className="h-3.5 w-3.5" />
              CLI authorization
            </div>
            <h1 className="text-2xl font-semibold tracking-tight">Authorize Nubase CLI</h1>
            <p className="mt-2 max-w-2xl text-sm text-muted-foreground">
              Choose the project this local CLI should use for MCP tools and agent workflows.
            </p>
          </div>
          <div className="rounded-md border border-border bg-background px-3 py-2 text-xs text-muted-foreground">
            {sessionId ? `Session: ${sessionId}` : agentId ? `Agent: ${agentId}` : 'Local agent'}
          </div>
        </div>
      </section>

      {invalidRequest ? (
        <Card className="rounded-lg border-destructive/30 bg-destructive/5 shadow-none">
          <CardContent className="p-5 text-sm text-destructive">
            This authorization request is invalid. Run the install command again to generate a fresh authorization URL.
          </CardContent>
        </Card>
      ) : status === 'done' ? (
        <Card className="rounded-lg border-emerald-500/30 bg-emerald-500/5 shadow-none">
          <CardContent className="flex items-start gap-3 p-5">
            <CheckCircle2 className="mt-0.5 h-5 w-5 text-emerald-500" />
            <div>
              <h2 className="text-sm font-semibold">CLI authorized</h2>
              <p className="mt-1 text-sm text-muted-foreground">You can close this tab and return to your terminal.</p>
            </div>
          </CardContent>
        </Card>
      ) : (
        <>
          <section className="rounded-lg border border-border bg-card p-4">
            <div className="flex flex-col gap-3 sm:flex-row sm:items-center">
              <Input
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                placeholder="Search projects"
                className="h-9 bg-background"
              />
              <Link href="/projects" className="shrink-0">
                <Button variant="outline" size="sm" type="button">
                  Manage projects
                </Button>
              </Link>
            </div>
          </section>

          {error ? (
            <div className="rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-sm text-destructive">
              {error}
            </div>
          ) : null}

          <section className="grid gap-3">
            {loading ? (
              <div className="rounded-lg border border-border bg-card p-5 text-sm text-muted-foreground">Loading projects...</div>
            ) : filtered.length === 0 ? (
              <div className="rounded-lg border border-border bg-card p-5 text-sm text-muted-foreground">
                No accessible projects found.
              </div>
            ) : (
              filtered.map((project) => (
                <button
                  key={project.ref}
                  type="button"
                  onClick={() => setSelectedRef(project.ref)}
                  className={cn(
                    'rounded-lg border bg-card p-4 text-left transition hover:border-foreground/30',
                    selectedRef === project.ref ? 'border-brand ring-1 ring-brand/40' : 'border-border'
                  )}
                >
                  <div className="flex items-start justify-between gap-3">
                    <div className="min-w-0">
                      <div className="truncate text-sm font-semibold">{project.name}</div>
                      <div className="mt-1 font-mono text-xs text-muted-foreground">{project.ref}</div>
                      {project.description ? (
                        <p className="mt-2 line-clamp-2 text-xs text-muted-foreground">{project.description}</p>
                      ) : null}
                    </div>
                    {selectedRef === project.ref ? <CheckCircle2 className="h-4 w-4 shrink-0 text-brand" /> : null}
                  </div>
                </button>
              ))
            )}
          </section>

          <section className="sticky bottom-0 -mx-6 border-t border-border bg-background/95 px-6 py-4 backdrop-blur">
            <div className="mx-auto flex max-w-4xl flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
              <div className="flex items-start gap-2 text-xs text-muted-foreground">
                <ShieldCheck className="mt-0.5 h-4 w-4 shrink-0" />
                <span>The CLI stores this project key locally and uses it for future MCP requests.</span>
              </div>
              <Button onClick={authorize} disabled={!selected || status === 'posting'} className="shrink-0">
                {status === 'posting' ? <Loader2 className="h-4 w-4 animate-spin" /> : <ArrowRight className="h-4 w-4" />}
                Authorize CLI
              </Button>
            </div>
          </section>
        </>
      )}
    </main>
  );
}

function isLocalCallback(value: string) {
  if (!value) return false;
  try {
    const url = new URL(value);
    return url.protocol === 'http:' && (url.hostname === '127.0.0.1' || url.hostname === 'localhost');
  } catch {
    return false;
  }
}

function parseError(err: ApiError): string | null {
  try {
    const parsed = JSON.parse(err.message);
    return parsed?.message ?? parsed?.error ?? null;
  } catch {
    return err.message;
  }
}
