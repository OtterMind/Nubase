'use client';

import { useCallback, useEffect, useState } from 'react';
import { Activity, CloudCog, FileCode, Play, Plus, RefreshCw, Rocket, ShieldCheck, ShieldOff, Upload } from 'lucide-react';
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
  callerRole?: string | null;
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

interface InvokeResult {
  status: number;
  headers: Record<string, string>;
  body: string;
}

const SAMPLE_SOURCE = `export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    const payload = req.method === 'GET'
      ? null
      : await req.json().catch(() => null);

    return Response.json({
      ok: true,
      message: 'Hello from Nubase Functions',
      method: req.method,
      path: url.pathname,
      query: Object.fromEntries(url.searchParams),
      payload,
      projectRef: env.NUBASE_PROJECT_REF,
      functionName: env.NUBASE_FUNCTION_NAME,
      hasApiKeySecret: Boolean(env.API_KEY),
    });
  },
};
`;

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
  const [sourceCode, setSourceCode] = useState(SAMPLE_SOURCE);
  const [sourceNote, setSourceNote] = useState<string | null>(null);
  const [invokeDraft, setInvokeDraft] = useState({ method: 'POST', path: '', body: '{\n  "name": "ji"\n}' });
  const [invokeResult, setInvokeResult] = useState<InvokeResult | null>(null);

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
      // Functional update keeps `selected` out of the deps, so changing the
      // sidebar selection does not refetch the whole list.
      setSelected((prev) => prev ?? fns[0]?.slug ?? null);
    } catch (err) {
      setError((err as ApiError).message ?? 'Failed to load functions.');
    } finally {
      setLoading(false);
    }
  }, [apikey]);

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

  async function deploySource(fn: EdgeFunction) {
    if (!sourceCode.trim()) {
      setError('Function source is required.');
      return;
    }
    setBusy(`deploy:${fn.slug}`);
    setError(null);
    setSourceNote(null);
    try {
      const bundle = await buildSourceBundle(sourceCode);
      await apiFetch(`/functions/admin/v1/functions/${encodeURIComponent(fn.slug)}/deploy`, {
        method: 'POST',
        apikey,
        authScope: 'tenant',
        body: {
          sourceHash: bundle.sourceHash,
          artifactType: 'source_bundle',
          sourceBundleBase64: bundle.sourceBundleBase64,
        },
      });
      setSourceNote(`Deployed index.js (${sourceCode.length} chars).`);
      await load();
    } catch (err) {
      setError((err as ApiError).message ?? 'Deploy failed.');
    } finally {
      setBusy(null);
    }
  }

  async function readSourceFile(file: File | null) {
    if (!file) return;
    setError(null);
    if (!/\.(js|mjs)$/.test(file.name)) {
      setError('Upload a JavaScript file such as index.js or index.mjs.');
      return;
    }
    const text = await file.text();
    setSourceCode(text);
    setSourceNote(`Loaded ${file.name} (${text.length} chars).`);
  }

  async function invokeFunction(e: React.FormEvent) {
    e.preventDefault();
    if (!current) return;
    setBusy(`invoke:${current.slug}`);
    setError(null);
    setInvokeResult(null);
    try {
      const path = invokeDraft.path.trim();
      const suffix = path ? `/${path.replace(/^\/+/, '')}` : '';
      const method = invokeDraft.method.toUpperCase();
      const hasBody = !['GET', 'HEAD'].includes(method);
      const res = await fetch(`${API_BASE}/functions/v1/${encodeURIComponent(current.slug)}${suffix}`, {
        method,
        headers: {
          apikey,
          ...(hasBody ? { 'Content-Type': 'application/json' } : {}),
        },
        body: hasBody && invokeDraft.body.trim() ? invokeDraft.body : undefined,
      });
      setInvokeResult({
        status: res.status,
        headers: Object.fromEntries(res.headers.entries()),
        body: await res.text(),
      });
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Invoke failed.');
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
                    <Button size="sm" onClick={() => deploySource(current)} disabled={busy === `deploy:${current.slug}`}>
                      <Rocket className="h-3.5 w-3.5" />
                      Deploy code
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
                    <div className="rounded-lg border border-border bg-background p-3">
                      <div className="text-xs text-muted-foreground">Entrypoint</div>
                      <div className="mt-2 font-mono text-sm">{current.entrypoint}</div>
                    </div>
                  </div>
                </CardContent>
              </Card>

              <Card>
                <CardHeader className="flex-row items-center justify-between space-y-0">
                  <div>
                    <CardTitle className="text-base">Source</CardTitle>
                    <p className="mt-1 text-xs text-muted-foreground">Deploys the editor as <span className="font-mono">index.js</span>.</p>
                  </div>
                  <div className="flex items-center gap-2">
                    <Button type="button" size="sm" variant="outline" onClick={() => { setSourceCode(SAMPLE_SOURCE); setSourceNote('Sample index.js loaded.'); }}>
                      <FileCode className="h-3.5 w-3.5" />
                      Sample
                    </Button>
                    <label className="inline-flex h-8 cursor-pointer items-center gap-1.5 rounded-md border border-border bg-background px-3 text-xs font-medium hover:bg-accent">
                      <Upload className="h-3.5 w-3.5" />
                      Upload
                      <input
                        type="file"
                        accept=".js,.mjs,application/javascript,text/javascript,text/plain"
                        className="sr-only"
                        onChange={(e) => { void readSourceFile(e.target.files?.[0] ?? null); e.currentTarget.value = ''; }}
                      />
                    </label>
                    <Button size="sm" onClick={() => deploySource(current)} disabled={busy === `deploy:${current.slug}`}>
                      <Rocket className="h-3.5 w-3.5" />
                      Deploy
                    </Button>
                  </div>
                </CardHeader>
                <CardContent>
                  <textarea
                    value={sourceCode}
                    onChange={(e) => setSourceCode(e.target.value)}
                    spellCheck={false}
                    className="h-80 w-full resize-y rounded-md border border-border bg-zinc-950 p-3 font-mono text-xs leading-5 text-zinc-100 outline-none ring-brand/40 placeholder:text-zinc-500 focus:ring-2"
                  />
                  {sourceNote ? <p className="mt-2 text-xs text-muted-foreground">{sourceNote}</p> : null}
                </CardContent>
              </Card>

              <Card>
                <CardHeader className="flex-row items-center justify-between space-y-0">
                  <div>
                    <CardTitle className="text-base">Test Invoke</CardTitle>
                    <p className="mt-1 font-mono text-xs text-muted-foreground">POST /functions/v1/{current.slug}</p>
                  </div>
                  <Button size="sm" onClick={invokeFunction} disabled={busy === `invoke:${current.slug}`}>
                    <Play className="h-3.5 w-3.5" />
                    Run
                  </Button>
                </CardHeader>
                <CardContent>
                  <form onSubmit={invokeFunction} className="space-y-3">
                    <div className="grid grid-cols-[120px_1fr] gap-3">
                      <div className="space-y-1.5">
                        <Label htmlFor="invoke-method" className="text-xs">Method</Label>
                        <select
                          id="invoke-method"
                          value={invokeDraft.method}
                          onChange={(e) => setInvokeDraft({ ...invokeDraft, method: e.target.value })}
                          className="h-9 w-full rounded-md border border-border bg-background px-2 text-sm"
                        >
                          {['GET', 'POST', 'PUT', 'PATCH', 'DELETE'].map((method) => <option key={method}>{method}</option>)}
                        </select>
                      </div>
                      <div className="space-y-1.5">
                        <Label htmlFor="invoke-path" className="text-xs">Path and query</Label>
                        <Input
                          id="invoke-path"
                          value={invokeDraft.path}
                          onChange={(e) => setInvokeDraft({ ...invokeDraft, path: e.target.value })}
                          placeholder="/demo?x=1"
                          className="font-mono text-xs"
                        />
                      </div>
                    </div>
                    <div className="space-y-1.5">
                      <Label htmlFor="invoke-body" className="text-xs">JSON body</Label>
                      <textarea
                        id="invoke-body"
                        value={invokeDraft.body}
                        onChange={(e) => setInvokeDraft({ ...invokeDraft, body: e.target.value })}
                        spellCheck={false}
                        className="h-28 w-full resize-y rounded-md border border-border bg-background p-3 font-mono text-xs leading-5 outline-none ring-brand/40 focus:ring-2"
                      />
                    </div>
                  </form>
                  {invokeResult ? (
                    <div className="mt-3 overflow-hidden rounded-md border border-border">
                      <div className="flex items-center justify-between border-b border-border bg-muted/60 px-3 py-2 text-xs">
                        <span>Status</span>
                        <Badge variant={invokeResult.status < 400 ? 'success' : 'warning'}>{invokeResult.status}</Badge>
                      </div>
                      <pre className="max-h-72 overflow-auto bg-background p-3 text-xs">{formatResponseBody(invokeResult.body)}</pre>
                    </div>
                  ) : null}
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
                            <td className="px-3 py-2">{log.callerRole ?? '-'}</td>
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

async function buildSourceBundle(source: string) {
  const payload = JSON.stringify({
    files: [{
      path: 'index.js',
      content: base64EncodeUtf8(source),
    }],
  });
  return {
    sourceHash: await sha256Hex(payload),
    sourceBundleBase64: base64EncodeUtf8(payload),
  };
}

function base64EncodeUtf8(value: string) {
  const bytes = new TextEncoder().encode(value);
  let binary = '';
  for (const byte of bytes) {
    binary += String.fromCharCode(byte);
  }
  return btoa(binary);
}

async function sha256Hex(value: string) {
  const bytes = new TextEncoder().encode(value);
  const digest = await crypto.subtle.digest('SHA-256', bytes);
  return Array.from(new Uint8Array(digest))
    .map((byte) => byte.toString(16).padStart(2, '0'))
    .join('');
}

function formatResponseBody(body: string) {
  try {
    return JSON.stringify(JSON.parse(body), null, 2);
  } catch {
    return body || '(empty response)';
  }
}
