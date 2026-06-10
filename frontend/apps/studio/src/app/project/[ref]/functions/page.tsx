'use client';

import { useCallback, useEffect, useState } from 'react';
import { Activity, CloudCog, Play, Plus, RefreshCw, Rocket, ShieldCheck, ShieldOff } from 'lucide-react';
import { Badge, Button, Card, CardContent, CardHeader, CardTitle, Input, Label } from '@nubase/ui';
import { apiFetch, API_BASE, type ApiError } from '@/lib/api';
import { isProjectReady, useSession } from '@/lib/session';
import { NotProvisioned } from '@/components/not-provisioned';
import { useProjectRef } from '@/lib/route-params';

interface EdgeFunction {
  id: string;
  name: string;
  slug: string;
  description?: string | null;
  verifyJwt: boolean;
  enabled: boolean;
  privileged: boolean;
  entrypoint: string;
  activeVersion?: EdgeFunctionVersion | null;
  updatedAt?: string | null;
}

interface EdgeFunctionVersion {
  versionNo: number;
  sourceHash: string;
  provider: string;
  providerDeploymentId?: string | null;
  status: string;
  errorMessage?: string | null;
  deployedByPlatformUserId?: string | null;
  createdAt?: string | null;
}

interface InvocationLog {
  id: string;
  requestId: string;
  functionSlug: string;
  method: string;
  path: string;
  statusCode?: number | null;
  durationMs?: number | null;
  executorProvider?: string | null;
  callerType?: string | null;
  callerUserId?: string | null;
  errorCode?: string | null;
  errorMessage?: string | null;
  createdAt?: string | null;
}

interface FunctionSecret {
  name: string;
  createdByPlatformUserId?: string | null;
  updatedByPlatformUserId?: string | null;
  createdAt?: string | null;
  updatedAt?: string | null;
}

export default function FunctionsPage({ params }: { params: { ref: string } }) {
  const { project } = useSession();
  const projectRef = useProjectRef(params.ref);
  const ready = isProjectReady(project);
  if (!ready) {
    return <NotProvisioned projectRef={project?.ref ?? projectRef} initStatus={project?.initStatus} />;
  }
  return <FunctionsInner projectRef={projectRef} />;
}

function FunctionsInner({ projectRef }: { projectRef: string }) {
  const { project } = useSession();
  const apikey = project!.apikey;
  const [functions, setFunctions] = useState<EdgeFunction[]>([]);
  const [logs, setLogs] = useState<InvocationLog[]>([]);
  const [selected, setSelected] = useState<string | null>(null);
  const [secrets, setSecrets] = useState<FunctionSecret[]>([]);
  const [secretDraft, setSecretDraft] = useState('');
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [draft, setDraft] = useState({ name: '', slug: '', description: '' });

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [fns, invocations] = await Promise.all([
        apiFetch<EdgeFunction[]>('/functions/admin/v1/functions', { apikey, authScope: 'tenant' }),
        apiFetch<InvocationLog[]>('/functions/admin/v1/invocations?limit=50', { apikey, authScope: 'tenant' }),
      ]);
      setFunctions(fns);
      setLogs(invocations);
      if (!selected && fns[0]) setSelected(fns[0].slug);
    } catch (err) {
      setError((err as ApiError).message ?? 'Failed to load functions.');
    } finally {
      setLoading(false);
    }
  }, [apikey, selected]);

  useEffect(() => { load(); }, [load]);

  const current = functions.find((fn) => fn.slug === selected) ?? functions[0] ?? null;

  const loadSecrets = useCallback(async (slug: string) => {
    try {
      const res = await apiFetch<FunctionSecret[]>(`/functions/admin/v1/functions/${encodeURIComponent(slug)}/secrets`, {
        apikey,
        authScope: 'tenant',
      });
      setSecrets(res);
    } catch {
      setSecrets([]);
    }
  }, [apikey]);

  useEffect(() => {
    if (current?.slug) loadSecrets(current.slug);
  }, [current?.slug, loadSecrets]);

  async function createFunction(e: React.FormEvent) {
    e.preventDefault();
    if (!draft.name.trim()) return;
    setBusy('create');
    setError(null);
    try {
      const fn = await apiFetch<EdgeFunction>('/functions/admin/v1/functions', {
        method: 'POST',
        apikey,
        authScope: 'tenant',
        body: {
          name: draft.name.trim(),
          slug: draft.slug.trim() || undefined,
          description: draft.description.trim() || undefined,
        },
      });
      setDraft({ name: '', slug: '', description: '' });
      setSelected(fn.slug);
      await load();
    } catch (err) {
      setError((err as ApiError).message ?? 'Create failed.');
    } finally {
      setBusy(null);
    }
  }

  async function patchFunction(fn: EdgeFunction, patch: Partial<EdgeFunction>) {
    setBusy(fn.slug);
    setError(null);
    try {
      await apiFetch<EdgeFunction>(`/functions/admin/v1/functions/${encodeURIComponent(fn.slug)}`, {
        method: 'PATCH',
        apikey,
        authScope: 'tenant',
        body: patch,
      });
      await load();
    } catch (err) {
      setError((err as ApiError).message ?? 'Update failed.');
    } finally {
      setBusy(null);
    }
  }

  async function deployPlaceholder(fn: EdgeFunction) {
    setBusy(`deploy:${fn.slug}`);
    setError(null);
    try {
      await apiFetch(`/functions/admin/v1/functions/${encodeURIComponent(fn.slug)}/deploy`, {
        method: 'POST',
        apikey,
        authScope: 'tenant',
        body: {
          sourceHash: `studio-${Date.now().toString(16)}`,
          artifactType: 'studio_placeholder',
        },
      });
      await load();
    } catch (err) {
      setError((err as ApiError).message ?? 'Deploy failed.');
    } finally {
      setBusy(null);
    }
  }

  async function setSecret(e: React.FormEvent) {
    e.preventDefault();
    if (!current || !secretDraft.includes('=')) return;
    const eq = secretDraft.indexOf('=');
    const name = secretDraft.slice(0, eq).trim();
    const value = secretDraft.slice(eq + 1);
    if (!name || !value) return;
    setBusy(`secret:${current.slug}`);
    setError(null);
    try {
      await apiFetch(`/functions/admin/v1/functions/${encodeURIComponent(current.slug)}/secrets`, {
        method: 'POST',
        apikey,
        authScope: 'tenant',
        body: { secrets: { [name]: value } },
      });
      setSecretDraft('');
      await loadSecrets(current.slug);
    } catch (err) {
      setError((err as ApiError).message ?? 'Secret update failed.');
    } finally {
      setBusy(null);
    }
  }

  return (
    <div className="flex h-full flex-col bg-background">
      <header className="border-b border-border px-5 py-4">
        <div className="flex items-center justify-between gap-4">
          <div className="flex items-center gap-3">
            <div className="flex h-9 w-9 items-center justify-center rounded-md border border-border bg-card">
              <CloudCog className="h-4 w-4 text-brand" />
            </div>
            <div>
              <h1 className="text-base font-semibold">Functions</h1>
              <p className="text-xs text-muted-foreground">
                Project functions for <span className="font-mono">{projectRef}</span>.
              </p>
            </div>
          </div>
          <div className="flex items-center gap-2">
            <code className="hidden rounded-md border border-border bg-card px-2 py-1 text-xs text-muted-foreground md:block">
              {API_BASE}/functions/v1
            </code>
            <Button size="sm" variant="outline" onClick={load} disabled={loading}>
              <RefreshCw className={'h-3.5 w-3.5 ' + (loading ? 'animate-spin' : '')} />
              Refresh
            </Button>
          </div>
        </div>
      </header>

      <main className="grid min-h-0 flex-1 grid-cols-[320px_1fr] overflow-hidden">
        <aside className="border-r border-border bg-card/30 p-4">
          <form onSubmit={createFunction} className="space-y-3 rounded-lg border border-border bg-card p-3">
            <div className="flex items-center gap-2 text-sm font-semibold">
              <Plus className="h-4 w-4" />
              New function
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="fn-name" className="text-xs">Name</Label>
              <Input id="fn-name" value={draft.name} onChange={(e) => setDraft({ ...draft, name: e.target.value })} />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="fn-slug" className="text-xs">Slug</Label>
              <Input id="fn-slug" value={draft.slug} onChange={(e) => setDraft({ ...draft, slug: e.target.value })} placeholder="optional" />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="fn-desc" className="text-xs">Description</Label>
              <Input id="fn-desc" value={draft.description} onChange={(e) => setDraft({ ...draft, description: e.target.value })} placeholder="optional" />
            </div>
            <Button size="sm" className="w-full" disabled={busy === 'create'}>
              <Plus className="h-3.5 w-3.5" />
              Create
            </Button>
          </form>

          {error ? <p className="mt-3 rounded-md border border-destructive/30 bg-destructive/10 p-2 text-xs text-destructive">{error}</p> : null}

          <div className="mt-4 space-y-2">
            {functions.map((fn) => (
              <button
                key={fn.id}
                onClick={() => setSelected(fn.slug)}
                className={
                  'w-full rounded-lg border px-3 py-2 text-left transition-colors ' +
                  (current?.slug === fn.slug ? 'border-brand bg-brand/10' : 'border-border bg-card hover:bg-accent')
                }
              >
                <div className="flex items-center justify-between gap-2">
                  <span className="truncate text-sm font-semibold">{fn.name}</span>
                  <Badge variant={fn.enabled ? 'success' : 'warning'}>{fn.enabled ? 'on' : 'off'}</Badge>
                </div>
                <div className="mt-1 flex items-center gap-2 text-xs text-muted-foreground">
                  <span className="font-mono">{fn.slug}</span>
                  <span>v{fn.activeVersion?.versionNo ?? '-'}</span>
                </div>
              </button>
            ))}
            {!loading && functions.length === 0 ? (
              <p className="rounded-md border border-dashed border-border p-3 text-xs text-muted-foreground">
                No functions yet. Create one here or deploy with <span className="font-mono">nubase_cli functions deploy</span>.
              </p>
            ) : null}
          </div>
        </aside>

        <section className="min-w-0 overflow-auto p-5">
          {current ? (
            <div className="space-y-4">
              <Card>
                <CardHeader className="flex-row items-start justify-between space-y-0">
                  <div>
                    <CardTitle className="text-base">{current.name}</CardTitle>
                    <p className="mt-1 font-mono text-xs text-muted-foreground">/functions/v1/{current.slug}</p>
                  </div>
                  <div className="flex items-center gap-2">
                    <Button size="sm" variant="outline" onClick={() => patchFunction(current, { enabled: !current.enabled })} disabled={busy === current.slug}>
                      <Play className="h-3.5 w-3.5" />
                      {current.enabled ? 'Disable' : 'Enable'}
                    </Button>
                    <Button size="sm" onClick={() => deployPlaceholder(current)} disabled={busy === `deploy:${current.slug}`}>
                      <Rocket className="h-3.5 w-3.5" />
                      Deploy marker
                    </Button>
                  </div>
                </CardHeader>
                <CardContent>
                  <div className="grid grid-cols-3 gap-3 text-sm">
                    <ToggleTile
                      label="JWT verification"
                      active={current.verifyJwt}
                      icon={current.verifyJwt ? ShieldCheck : ShieldOff}
                      onClick={() => patchFunction(current, { verifyJwt: !current.verifyJwt })}
                    />
                    <ToggleTile
                      label="Privileged"
                      active={current.privileged}
                      icon={ShieldCheck}
                      onClick={() => patchFunction(current, { privileged: !current.privileged })}
                    />
                    <div className="rounded-lg border border-border bg-background p-3">
                      <div className="text-xs text-muted-foreground">Entrypoint</div>
                      <div className="mt-2 font-mono text-sm">{current.entrypoint}</div>
                    </div>
                  </div>
                </CardContent>
              </Card>

              <Card>
                <CardHeader>
                  <CardTitle className="text-base">Active Deployment</CardTitle>
                </CardHeader>
                <CardContent>
                  {current.activeVersion ? (
                    <div className="grid grid-cols-2 gap-3 text-sm lg:grid-cols-4">
                      <Info label="Version" value={`v${current.activeVersion.versionNo}`} />
                      <Info label="Provider" value={current.activeVersion.provider} />
                      <Info label="Status" value={current.activeVersion.status} />
                      <Info label="Source hash" value={current.activeVersion.sourceHash?.slice(0, 12) ?? '-'} mono />
                    </div>
                  ) : (
                    <p className="text-sm text-muted-foreground">No active deployment. Deploy from CLI or create a marker here.</p>
                  )}
                </CardContent>
              </Card>

              <Card>
                <CardHeader>
                  <CardTitle className="text-base">Secrets</CardTitle>
                </CardHeader>
                <CardContent>
                  <form onSubmit={setSecret} className="flex gap-2">
                    <Input
                      value={secretDraft}
                      onChange={(e) => setSecretDraft(e.target.value)}
                      placeholder="API_KEY=value"
                      className="font-mono text-xs"
                    />
                    <Button size="sm" disabled={busy === `secret:${current.slug}`}>Set</Button>
                  </form>
                  <div className="mt-3 flex flex-wrap gap-2">
                    {secrets.map((secret) => (
                      <Badge key={secret.name} variant="outline" className="font-mono">{secret.name}</Badge>
                    ))}
                    {secrets.length === 0 ? <span className="text-xs text-muted-foreground">No secrets configured.</span> : null}
                  </div>
                </CardContent>
              </Card>

              <Card>
                <CardHeader className="flex-row items-center justify-between space-y-0">
                  <CardTitle className="text-base">Recent Invocations</CardTitle>
                  <Activity className="h-4 w-4 text-muted-foreground" />
                </CardHeader>
                <CardContent>
                  <div className="overflow-hidden rounded-md border border-border">
                    <table className="w-full text-left text-xs">
                      <thead className="bg-muted/60 text-muted-foreground">
                        <tr>
                          <th className="px-3 py-2">Time</th>
                          <th className="px-3 py-2">Method</th>
                          <th className="px-3 py-2">Path</th>
                          <th className="px-3 py-2">Status</th>
                          <th className="px-3 py-2">Caller</th>
                          <th className="px-3 py-2">Duration</th>
                        </tr>
                      </thead>
                      <tbody>
                        {logs.filter((log) => log.functionSlug === current.slug).slice(0, 12).map((log) => (
                          <tr key={log.id} className="border-t border-border">
                            <td className="px-3 py-2 text-muted-foreground">{formatDate(log.createdAt)}</td>
                            <td className="px-3 py-2 font-mono">{log.method}</td>
                            <td className="px-3 py-2 font-mono">{log.path || '/'}</td>
                            <td className="px-3 py-2">{log.statusCode ?? log.errorCode ?? '-'}</td>
                            <td className="px-3 py-2">{log.callerType ?? '-'}</td>
                            <td className="px-3 py-2">{log.durationMs ?? 0}ms</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </CardContent>
              </Card>
            </div>
          ) : (
            <div className="flex h-full items-center justify-center text-sm text-muted-foreground">
              Select or create a function.
            </div>
          )}
        </section>
      </main>
    </div>
  );
}

function ToggleTile({
  label,
  active,
  icon: Icon,
  onClick,
}: {
  label: string;
  active: boolean;
  icon: React.ComponentType<{ className?: string }>;
  onClick: () => void;
}) {
  return (
    <button type="button" onClick={onClick} className="rounded-lg border border-border bg-background p-3 text-left hover:bg-accent">
      <div className="flex items-center justify-between gap-2">
        <span className="text-xs text-muted-foreground">{label}</span>
        <Icon className="h-3.5 w-3.5" />
      </div>
      <Badge className="mt-2" variant={active ? 'success' : 'outline'}>{active ? 'enabled' : 'disabled'}</Badge>
    </button>
  );
}

function Info({ label, value, mono }: { label: string; value: string; mono?: boolean }) {
  return (
    <div className="rounded-lg border border-border bg-background p-3">
      <div className="text-xs text-muted-foreground">{label}</div>
      <div className={'mt-2 truncate text-sm ' + (mono ? 'font-mono' : 'font-medium')}>{value}</div>
    </div>
  );
}

function formatDate(value?: string | null) {
  if (!value) return '-';
  return new Date(value).toLocaleString();
}
