'use client';

import { useCallback, useEffect, useState } from 'react';
import { CalendarClock, Database, Play, Plus, RefreshCw, Trash2, Zap } from 'lucide-react';
import { Badge, Button, Card, CardContent, CardHeader, CardTitle, Input, Label } from '@nubase/ui';
import { apiFetch, API_BASE, type ApiError } from '@/lib/api';
import { isProjectReady, useSession } from '@/lib/session';
import { NotProvisioned } from '@/components/not-provisioned';
import { useProjectRef } from '@/lib/route-params';

interface ScheduledJob {
  id: string;
  projectRef: string;
  name: string;
  description?: string | null;
  cronExpression: string;
  targetType: 'edge_function' | 'db_function';
  functionSlug?: string | null;
  httpMethod?: string | null;
  requestPath?: string | null;
  requestBody?: string | null;
  dbFunctionName?: string | null;
  dbFunctionArgs?: string | null;
  timeoutSeconds?: number | null;
  enabled: boolean;
  nextRunAt?: string | null;
  lastRunAt?: string | null;
  lastStatus?: string | null;
  createdAt?: string | null;
  updatedAt?: string | null;
}

interface ScheduledJobRun {
  id: string;
  jobId: string;
  jobName: string;
  targetType: string;
  scheduledFor?: string | null;
  startedAt?: string | null;
  finishedAt?: string | null;
  status: 'success' | 'failed';
  result?: string | null;
  errorMessage?: string | null;
}

interface JobDraft {
  name: string;
  description: string;
  cronExpression: string;
  targetType: 'edge_function' | 'db_function';
  functionSlug: string;
  httpMethod: string;
  requestPath: string;
  requestBody: string;
  dbFunctionName: string;
  dbFunctionArgs: string;
  timeoutSeconds: string;
}

const EMPTY_DRAFT: JobDraft = {
  name: '',
  description: '',
  cronExpression: '',
  targetType: 'edge_function',
  functionSlug: '',
  httpMethod: 'POST',
  requestPath: '',
  requestBody: '',
  dbFunctionName: '',
  dbFunctionArgs: '',
  timeoutSeconds: '',
};

export default function CronPage({ params }: { params: { ref: string } }) {
  const { project } = useSession();
  const projectRef = useProjectRef(params.ref);
  const ready = isProjectReady(project);
  if (!ready) {
    return <NotProvisioned projectRef={project?.ref ?? projectRef} initStatus={project?.initStatus} />;
  }
  return <CronInner projectRef={projectRef} />;
}

function CronInner({ projectRef }: { projectRef: string }) {
  const { project } = useSession();
  const apikey = project!.apikey;
  const [jobs, setJobs] = useState<ScheduledJob[]>([]);
  const [runs, setRuns] = useState<ScheduledJobRun[]>([]);
  const [selected, setSelected] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [draft, setDraft] = useState<JobDraft>(EMPTY_DRAFT);
  const [draftError, setDraftError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const list = await apiFetch<ScheduledJob[]>('/cron/admin/v1/jobs', { apikey, authScope: 'tenant' });
      setJobs(list);
      // Functional update keeps `selected` out of the deps, so changing the
      // sidebar selection does not refetch the whole list.
      setSelected((prev) => (prev && list.some((job) => job.name === prev) ? prev : list[0]?.name ?? null));
    } catch (err) {
      setError((err as ApiError).message ?? 'Failed to load scheduled jobs.');
    } finally {
      setLoading(false);
    }
  }, [apikey]);

  useEffect(() => { load(); }, [load]);

  const current = jobs.find((job) => job.name === selected) ?? jobs[0] ?? null;

  const loadRuns = useCallback(async (name: string) => {
    try {
      const res = await apiFetch<ScheduledJobRun[]>(
        `/cron/admin/v1/jobs/${encodeURIComponent(name)}/runs?limit=50`,
        { apikey, authScope: 'tenant' }
      );
      setRuns(res);
    } catch {
      setRuns([]);
    }
  }, [apikey]);

  useEffect(() => {
    if (current?.name) loadRuns(current.name);
    else setRuns([]);
  }, [current?.name, loadRuns]);

  async function createJob(e: React.FormEvent) {
    e.preventDefault();
    setDraftError(null);
    const name = draft.name.trim();
    if (!name) return;
    if (!/^[a-zA-Z0-9_-]{1,128}$/.test(name)) {
      setDraftError('Name may only contain letters, digits, "_" and "-" (max 128 chars).');
      return;
    }
    if (!draft.cronExpression.trim()) {
      setDraftError('Cron expression is required.');
      return;
    }
    let dbFunctionArgs: Record<string, unknown> | undefined;
    if (draft.targetType === 'db_function' && draft.dbFunctionArgs.trim()) {
      try {
        const parsed = JSON.parse(draft.dbFunctionArgs);
        if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error('not an object');
        dbFunctionArgs = parsed;
      } catch {
        setDraftError('Function args must be a JSON object, e.g. {"days": 7}.');
        return;
      }
    }
    const timeoutSeconds = draft.timeoutSeconds.trim() ? Number(draft.timeoutSeconds) : undefined;
    if (timeoutSeconds !== undefined && (!Number.isFinite(timeoutSeconds) || timeoutSeconds <= 0)) {
      setDraftError('Timeout must be a positive number of seconds.');
      return;
    }
    setBusy('create');
    setError(null);
    try {
      const job = await apiFetch<ScheduledJob>('/cron/admin/v1/jobs', {
        method: 'POST',
        apikey,
        authScope: 'tenant',
        body: {
          name,
          description: draft.description.trim() || undefined,
          cronExpression: draft.cronExpression.trim(),
          targetType: draft.targetType,
          functionSlug: draft.targetType === 'edge_function' ? draft.functionSlug.trim() || undefined : undefined,
          httpMethod: draft.targetType === 'edge_function' ? draft.httpMethod.trim() || undefined : undefined,
          requestPath: draft.targetType === 'edge_function' ? draft.requestPath.trim() || undefined : undefined,
          requestBody: draft.targetType === 'edge_function' ? draft.requestBody.trim() || undefined : undefined,
          dbFunctionName: draft.targetType === 'db_function' ? draft.dbFunctionName.trim() || undefined : undefined,
          dbFunctionArgs,
          timeoutSeconds,
        },
      });
      setDraft(EMPTY_DRAFT);
      setSelected(job.name);
      await load();
    } catch (err) {
      setError((err as ApiError).message ?? 'Create failed.');
    } finally {
      setBusy(null);
    }
  }

  async function toggleJob(job: ScheduledJob) {
    setBusy(job.name);
    setError(null);
    try {
      await apiFetch<ScheduledJob>(`/cron/admin/v1/jobs/${encodeURIComponent(job.name)}`, {
        method: 'PATCH',
        apikey,
        authScope: 'tenant',
        body: { enabled: !job.enabled },
      });
      await load();
    } catch (err) {
      setError((err as ApiError).message ?? 'Update failed.');
    } finally {
      setBusy(null);
    }
  }

  async function deleteJob(job: ScheduledJob) {
    if (!window.confirm(`Delete scheduled job "${job.name}"? This cannot be undone.`)) return;
    setBusy(`delete:${job.name}`);
    setError(null);
    try {
      await apiFetch(`/cron/admin/v1/jobs/${encodeURIComponent(job.name)}`, {
        method: 'DELETE',
        apikey,
        authScope: 'tenant',
      });
      setSelected(null);
      await load();
    } catch (err) {
      setError((err as ApiError).message ?? 'Delete failed.');
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
              <CalendarClock className="h-4 w-4 text-brand" />
            </div>
            <div>
              <h1 className="text-base font-semibold">Cron</h1>
              <p className="text-xs text-muted-foreground">
                Scheduled jobs for <span className="font-mono">{projectRef}</span>.
              </p>
            </div>
          </div>
          <div className="flex items-center gap-2">
            <code className="hidden rounded-md border border-border bg-card px-2 py-1 text-xs text-muted-foreground md:block">
              {API_BASE}/cron/admin/v1
            </code>
            <Button size="sm" variant="outline" onClick={load} disabled={loading}>
              <RefreshCw className={'h-3.5 w-3.5 ' + (loading ? 'animate-spin' : '')} />
              Refresh
            </Button>
          </div>
        </div>
      </header>

      <main className="grid min-h-0 flex-1 grid-cols-[340px_1fr] overflow-hidden">
        <aside className="overflow-y-auto border-r border-border bg-card/30 p-4">
          <form onSubmit={createJob} className="space-y-3 rounded-lg border border-border bg-card p-3">
            <div className="flex items-center gap-2 text-sm font-semibold">
              <Plus className="h-4 w-4" />
              New job
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="job-name" className="text-xs">Name</Label>
              <Input id="job-name" value={draft.name} onChange={(e) => setDraft({ ...draft, name: e.target.value })} placeholder="nightly-cleanup" />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="job-cron" className="text-xs">Cron expression</Label>
              <Input id="job-cron" value={draft.cronExpression} onChange={(e) => setDraft({ ...draft, cronExpression: e.target.value })} placeholder="0 3 * * *" className="font-mono" />
            </div>
            <div className="space-y-1.5">
              <Label className="text-xs">Target</Label>
              <div className="grid grid-cols-2 gap-2">
                <TargetButton
                  label="Edge function"
                  icon={Zap}
                  active={draft.targetType === 'edge_function'}
                  onClick={() => setDraft({ ...draft, targetType: 'edge_function' })}
                />
                <TargetButton
                  label="DB function"
                  icon={Database}
                  active={draft.targetType === 'db_function'}
                  onClick={() => setDraft({ ...draft, targetType: 'db_function' })}
                />
              </div>
            </div>
            {draft.targetType === 'edge_function' ? (
              <>
                <div className="space-y-1.5">
                  <Label htmlFor="job-fn" className="text-xs">Function slug</Label>
                  <Input id="job-fn" value={draft.functionSlug} onChange={(e) => setDraft({ ...draft, functionSlug: e.target.value })} placeholder="cleanup" className="font-mono" />
                </div>
                <div className="grid grid-cols-2 gap-2">
                  <div className="space-y-1.5">
                    <Label htmlFor="job-method" className="text-xs">Method</Label>
                    <Input id="job-method" value={draft.httpMethod} onChange={(e) => setDraft({ ...draft, httpMethod: e.target.value })} placeholder="POST" className="font-mono" />
                  </div>
                  <div className="space-y-1.5">
                    <Label htmlFor="job-path" className="text-xs">Path</Label>
                    <Input id="job-path" value={draft.requestPath} onChange={(e) => setDraft({ ...draft, requestPath: e.target.value })} placeholder="/run" className="font-mono" />
                  </div>
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="job-body" className="text-xs">Request body</Label>
                  <textarea
                    id="job-body"
                    value={draft.requestBody}
                    onChange={(e) => setDraft({ ...draft, requestBody: e.target.value })}
                    placeholder='{"a": 1}'
                    className="min-h-[56px] w-full rounded-md border border-input bg-background p-2 font-mono text-xs"
                  />
                </div>
              </>
            ) : (
              <>
                <div className="space-y-1.5">
                  <Label htmlFor="job-dbfn" className="text-xs">Database function</Label>
                  <Input id="job-dbfn" value={draft.dbFunctionName} onChange={(e) => setDraft({ ...draft, dbFunctionName: e.target.value })} placeholder="purge_old_rows" className="font-mono" />
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="job-args" className="text-xs">Function args (JSON object)</Label>
                  <textarea
                    id="job-args"
                    value={draft.dbFunctionArgs}
                    onChange={(e) => setDraft({ ...draft, dbFunctionArgs: e.target.value })}
                    placeholder='{"days": 7}'
                    className="min-h-[56px] w-full rounded-md border border-input bg-background p-2 font-mono text-xs"
                  />
                </div>
              </>
            )}
            <div className="grid grid-cols-2 gap-2">
              <div className="space-y-1.5">
                <Label htmlFor="job-timeout" className="text-xs">Timeout (s)</Label>
                <Input id="job-timeout" value={draft.timeoutSeconds} onChange={(e) => setDraft({ ...draft, timeoutSeconds: e.target.value })} placeholder="60" />
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="job-desc" className="text-xs">Description</Label>
                <Input id="job-desc" value={draft.description} onChange={(e) => setDraft({ ...draft, description: e.target.value })} placeholder="optional" />
              </div>
            </div>
            {draftError ? <p className="rounded-md border border-destructive/30 bg-destructive/10 p-2 text-xs text-destructive">{draftError}</p> : null}
            <Button size="sm" className="w-full" disabled={busy === 'create'}>
              <Plus className="h-3.5 w-3.5" />
              Create job
            </Button>
          </form>

          {error ? <p className="mt-3 rounded-md border border-destructive/30 bg-destructive/10 p-2 text-xs text-destructive">{error}</p> : null}

          <div className="mt-4 space-y-2">
            {jobs.map((job) => (
              <button
                key={job.id}
                onClick={() => setSelected(job.name)}
                className={
                  'w-full rounded-lg border px-3 py-2 text-left transition-colors ' +
                  (current?.name === job.name ? 'border-brand bg-brand/10' : 'border-border bg-card hover:bg-accent')
                }
              >
                <div className="flex items-center justify-between gap-2">
                  <span className="truncate text-sm font-semibold">{job.name}</span>
                  <Badge variant={job.enabled ? 'success' : 'warning'}>{job.enabled ? 'on' : 'off'}</Badge>
                </div>
                <div className="mt-1 flex items-center justify-between gap-2 text-xs text-muted-foreground">
                  <span className="flex items-center gap-1">
                    <Badge variant="outline" className="font-mono">{job.targetType === 'edge_function' ? 'edge' : 'db'}</Badge>
                    <span className="font-mono">{job.cronExpression}</span>
                  </span>
                  <StatusBadge status={job.lastStatus} />
                </div>
                <div className="mt-1 text-xs text-muted-foreground">
                  next: {formatDate(job.nextRunAt)}
                </div>
              </button>
            ))}
            {!loading && jobs.length === 0 ? (
              <p className="rounded-md border border-dashed border-border p-3 text-xs text-muted-foreground">
                No scheduled jobs yet. Create one here or with <span className="font-mono">nubase_cli cron create</span>.
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
                    <p className="mt-1 text-xs text-muted-foreground">{current.description || 'No description.'}</p>
                  </div>
                  <div className="flex items-center gap-2">
                    <Button size="sm" variant="outline" onClick={() => toggleJob(current)} disabled={busy === current.name}>
                      <Play className="h-3.5 w-3.5" />
                      {current.enabled ? 'Disable' : 'Enable'}
                    </Button>
                    <Button size="sm" variant="destructive" onClick={() => deleteJob(current)} disabled={busy === `delete:${current.name}`}>
                      <Trash2 className="h-3.5 w-3.5" />
                      Delete
                    </Button>
                  </div>
                </CardHeader>
                <CardContent>
                  <div className="grid grid-cols-2 gap-3 text-sm lg:grid-cols-4">
                    <Info label="Schedule" value={current.cronExpression} mono />
                    <Info label="Next run" value={formatDate(current.nextRunAt)} />
                    <Info label="Last run" value={formatDate(current.lastRunAt)} />
                    <Info label="Timeout" value={current.timeoutSeconds != null ? `${current.timeoutSeconds}s` : '-'} />
                  </div>
                </CardContent>
              </Card>

              <Card>
                <CardHeader>
                  <CardTitle className="text-base">Target</CardTitle>
                </CardHeader>
                <CardContent>
                  {current.targetType === 'edge_function' ? (
                    <div className="grid grid-cols-2 gap-3 text-sm lg:grid-cols-4">
                      <Info label="Type" value="Edge function" />
                      <Info label="Function" value={current.functionSlug ?? '-'} mono />
                      <Info label="Request" value={`${current.httpMethod ?? 'POST'} ${current.requestPath || '/'}`} mono />
                      <Info label="Body" value={current.requestBody || '-'} mono />
                    </div>
                  ) : (
                    <div className="grid grid-cols-2 gap-3 text-sm lg:grid-cols-4">
                      <Info label="Type" value="Database function" />
                      <Info label="Function" value={current.dbFunctionName ?? '-'} mono />
                      <Info label="Args" value={current.dbFunctionArgs || '-'} mono />
                    </div>
                  )}
                </CardContent>
              </Card>

              <Card>
                <CardHeader className="flex-row items-center justify-between space-y-0">
                  <CardTitle className="text-base">Recent Runs</CardTitle>
                  <StatusBadge status={current.lastStatus} />
                </CardHeader>
                <CardContent>
                  <div className="overflow-hidden rounded-md border border-border">
                    <table className="w-full text-left text-xs">
                      <thead className="bg-muted/60 text-muted-foreground">
                        <tr>
                          <th className="px-3 py-2">Status</th>
                          <th className="px-3 py-2">Started</th>
                          <th className="px-3 py-2">Duration</th>
                          <th className="px-3 py-2">Result / Error</th>
                        </tr>
                      </thead>
                      <tbody>
                        {runs.map((run) => (
                          <tr key={run.id} className="border-t border-border">
                            <td className="px-3 py-2"><StatusBadge status={run.status} /></td>
                            <td className="px-3 py-2 text-muted-foreground">{formatDate(run.startedAt ?? run.scheduledFor)}</td>
                            <td className="px-3 py-2">{formatDuration(run.startedAt, run.finishedAt)}</td>
                            <td className="max-w-[320px] truncate px-3 py-2 font-mono">
                              {run.status === 'failed' ? (run.errorMessage ?? '-') : (run.result ?? '-')}
                            </td>
                          </tr>
                        ))}
                        {runs.length === 0 ? (
                          <tr className="border-t border-border">
                            <td colSpan={4} className="px-3 py-4 text-center text-muted-foreground">No runs yet.</td>
                          </tr>
                        ) : null}
                      </tbody>
                    </table>
                  </div>
                </CardContent>
              </Card>
            </div>
          ) : (
            <div className="flex h-full items-center justify-center text-sm text-muted-foreground">
              Select or create a scheduled job.
            </div>
          )}
        </section>
      </main>
    </div>
  );
}

function TargetButton({
  label,
  icon: Icon,
  active,
  onClick,
}: {
  label: string;
  icon: React.ComponentType<{ className?: string }>;
  active: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={
        'flex items-center justify-center gap-1.5 rounded-md border px-2 py-1.5 text-xs transition-colors ' +
        (active ? 'border-brand bg-brand/10 font-semibold' : 'border-border bg-background hover:bg-accent')
      }
    >
      <Icon className="h-3.5 w-3.5" />
      {label}
    </button>
  );
}

function StatusBadge({ status }: { status?: string | null }) {
  if (!status) return <Badge variant="outline">never run</Badge>;
  if (status === 'success') return <Badge variant="success">{status}</Badge>;
  return <Badge variant="outline" className="border-destructive/30 bg-destructive/10 text-destructive">{status}</Badge>;
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

function formatDuration(start?: string | null, end?: string | null) {
  if (!start || !end) return '-';
  const ms = new Date(end).getTime() - new Date(start).getTime();
  if (!Number.isFinite(ms) || ms < 0) return '-';
  return ms < 1000 ? `${ms}ms` : `${(ms / 1000).toFixed(1)}s`;
}
