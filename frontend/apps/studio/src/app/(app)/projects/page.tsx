'use client';

import Link from 'next/link';
import { useEffect, useMemo, useState } from 'react';
import { useRouter } from 'next/navigation';
import {
  AlertCircle,
  Plus,
  Database,
  ArrowRight,
  Search,
  LayoutGrid,
  List,
  ChevronLeft,
  ChevronRight,
  CheckCircle2,
  Clock3,
} from 'lucide-react';
import {
  Button,
  Card,
  CardContent,
  Input,
  Skeleton,
  cn,
} from '@nubase/ui';
import { apiFetch, type ApiError } from '@/lib/api';
import { useSession, isSuperAdmin } from '@/lib/session';
import { useI18n } from '@/lib/i18n';

interface ProjectSummary {
  ref: string;
  name: string;
  description?: string | null;
  schemaName?: string | null;
  initStatus?: string | null;
  healthStatus?: string | null;
  enabled: boolean;
  apikey: string;
  createdAt?: string | null;
}

type ViewMode = 'grid' | 'list';
const PAGE_SIZE = 24;
const VIEW_KEY = 'nubase.projects.view';

/**
 * Treat the backend's auto-generated description placeholder as "no description".
 *
 * <p>{@code DatabaseInitService.buildDatabaseConfig} populates the description with
 * {@code "Auto-created database for <appCode>"} when the user didn't supply one, so
 * blindly rendering {@code p.description} would show that boilerplate on every untouched
 * project. We treat it as empty here instead of changing the backend default (other
 * places might rely on a non-null description).
 */
/** Epoch millis for a project's createdAt; 0 (sorts last) when missing or unparseable. */
function createdTime(p: { createdAt?: string | null }): number {
  if (!p.createdAt) return 0;
  const t = Date.parse(p.createdAt);
  return Number.isNaN(t) ? 0 : t;
}

function meaningfulDescription(p: { description?: string | null; ref: string; name?: string | null }): string | null {
  const d = (p.description ?? '').trim();
  if (!d) return null;
  // Backend default: "Auto-created database for <appCode>" (appCode == ref).
  if (/^auto-?created (database )?for /i.test(d)) return null;
  // Defensive: any "<name>-description" placeholder pattern.
  if (p.name && d.toLowerCase() === `${p.name.toLowerCase()}-description`) return null;
  return d;
}

export default function ProjectsPage() {
  const router = useRouter();
  const { tr } = useI18n();
  const { platformKey, user, setProject, signOut, hasHydrated } = useSession();
  const superAdmin = isSuperAdmin(user);
  const [projects, setProjects] = useState<ProjectSummary[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [view, setView] = useState<ViewMode>('grid');
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(1);

  useEffect(() => {
    if (typeof window !== 'undefined') {
      const stored = window.localStorage.getItem(VIEW_KEY);
      if (stored === 'list' || stored === 'grid') setView(stored);
    }
  }, []);

  useEffect(() => {
    if (typeof window !== 'undefined') {
      window.localStorage.setItem(VIEW_KEY, view);
    }
  }, [view]);

  useEffect(() => {
    if (!hasHydrated) return; // wait for persisted session to load before deciding
    if (!platformKey) {
      router.replace('/login');
      return;
    }
    let cancelled = false;
    setLoading(true);
    setError(null);
    apiFetch<ProjectSummary[]>('/auth/v1/admin/projects', { apikey: platformKey })
      .then((res) => {
        if (!cancelled) setProjects(res);
      })
      .catch((err: ApiError) => {
        if (cancelled) return;
        if (err.status === 401) {
          signOut();
          router.replace('/login');
          return;
        }
        setError(err.message ?? tr('projects.loadError'));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [hasHydrated, platformKey, router, signOut, tr]);

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    const base = !q
      ? projects
      : projects.filter(
          (p) =>
            p.ref.toLowerCase().includes(q) ||
            (p.name?.toLowerCase().includes(q) ?? false) ||
            (p.description?.toLowerCase().includes(q) ?? false)
        );
    // Newest first; projects without a parseable createdAt sort to the end.
    return [...base].sort((a, b) => createdTime(b) - createdTime(a));
  }, [projects, query]);

  const stats = useMemo(() => summarizeProjects(projects), [projects]);
  const pageCount = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE));
  const safePage = Math.min(page, pageCount);
  const paged = filtered.slice((safePage - 1) * PAGE_SIZE, safePage * PAGE_SIZE);

  useEffect(() => {
    if (page > pageCount) setPage(1);
  }, [pageCount, page]);

  function open(p: ProjectSummary) {
    setProject({
      ref: p.ref,
      apikey: p.apikey,
      name: p.name,
      initStatus: p.initStatus ?? null,
      healthStatus: p.healthStatus ?? null,
    });
    router.push(`/project/${p.ref}`);
  }

  return (
    <div className="flex w-full max-w-7xl flex-1 flex-col gap-5 px-6 py-6 lg:px-8">
      <section className="overflow-hidden rounded-lg border border-border bg-card">
        <div className="flex flex-col gap-5 border-b border-border px-5 py-5 lg:flex-row lg:items-end lg:justify-between">
          <div className="min-w-0">
            <div className="mb-2 flex items-center gap-2">
              <span className="rounded-md border border-border bg-background px-2 py-1 font-mono text-[11px] text-muted-foreground">
                {tr('projects.scope')}
              </span>
              {superAdmin ? (
                <span className="rounded-md bg-brand/10 px-2 py-1 text-[11px] font-medium text-brand">
                  {tr('projects.superAdmin')}
                </span>
              ) : null}
            </div>
            <h1 className="text-2xl font-semibold tracking-tight">
              {superAdmin ? tr('projects.titleAll') : tr('projects.titleOwn')}
            </h1>
            <p className="mt-1 max-w-2xl text-sm text-muted-foreground">
              {tr('projects.subtitle')}
            </p>
          </div>
          <Link href="/new" className="shrink-0">
            <Button size="sm" className="w-full sm:w-auto">
              <Plus className="h-3.5 w-3.5" /> {tr('projects.new')}
            </Button>
          </Link>
        </div>

        <div className="grid grid-cols-2 divide-x divide-y divide-border sm:grid-cols-4 sm:divide-y-0">
          <StatTile label={tr('projects.total')} value={projects.length} icon={Database} />
          <StatTile label={tr('projects.ready')} value={stats.ready} icon={CheckCircle2} tone="success" />
          <StatTile label={tr('projects.pending')} value={stats.pending} icon={Clock3} tone="warning" />
          <StatTile label={tr('projects.failed')} value={stats.failed} icon={AlertCircle} tone="danger" />
        </div>
      </section>

      <section className="flex flex-col gap-3 rounded-lg border border-border bg-card p-3 sm:flex-row sm:items-center">
        <div className="relative min-w-[220px] flex-1">
          <Search className="pointer-events-none absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-muted-foreground" />
          <Input
            placeholder={tr('projects.search')}
            value={query}
            onChange={(e) => {
              setQuery(e.target.value);
              setPage(1);
            }}
            className="h-8 border-border bg-background pl-8 text-xs shadow-none"
          />
        </div>
        <div className="flex h-8 shrink-0 overflow-hidden rounded-md border border-border bg-background">
          <button
            onClick={() => setView('grid')}
            className={cn(
              'flex items-center gap-1.5 border-r border-border px-3 text-xs',
              view === 'grid' ? 'bg-accent text-accent-foreground' : 'text-muted-foreground hover:text-foreground'
            )}
            aria-pressed={view === 'grid'}
            aria-label={tr('projects.gridView')}
          >
            <LayoutGrid className="h-3.5 w-3.5" /> {tr('projects.grid')}
          </button>
          <button
            onClick={() => setView('list')}
            className={cn(
              'flex items-center gap-1.5 px-3 text-xs',
              view === 'list' ? 'bg-accent text-accent-foreground' : 'text-muted-foreground hover:text-foreground'
            )}
            aria-pressed={view === 'list'}
            aria-label={tr('projects.listView')}
          >
            <List className="h-3.5 w-3.5" /> {tr('projects.list')}
          </button>
        </div>
      </section>

      {error ? (
        <div className="rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-sm text-destructive">
          {error}
        </div>
      ) : null}

      {loading ? (
        <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
          {Array.from({ length: 6 }).map((_, i) => (
            <Card key={i} className="min-h-[142px] rounded-lg border-border p-4 shadow-none">
              <div className="flex items-start justify-between gap-2">
                <Skeleton className="h-3.5 w-32" />
                <Skeleton className="h-5 w-20" />
              </div>
              <Skeleton className="mt-5 h-3 w-full" />
              <Skeleton className="mt-2 h-3 w-2/3" />
            </Card>
          ))}
        </div>
      ) : filtered.length === 0 && !error ? (
        <Card className="rounded-lg border-border shadow-none">
          <CardContent className="flex flex-col items-start gap-3 px-5 py-16 text-left">
            <div className="flex h-10 w-10 items-center justify-center rounded-lg border border-border bg-muted/30">
              <Database className="h-5 w-5 text-muted-foreground" />
            </div>
            <div>
              <p className="text-sm font-medium">
                {projects.length === 0 ? tr('projects.emptyTitle') : tr('projects.emptySearchTitle')}
              </p>
              <p className="mt-1 text-xs text-muted-foreground">
                {projects.length === 0
                  ? tr('projects.emptyBody')
                  : tr('projects.emptySearchBody')}
              </p>
            </div>
            {projects.length === 0 ? (
              <Link href="/new">
                <Button size="sm">{tr('projects.createFirst')}</Button>
              </Link>
            ) : null}
          </CardContent>
        </Card>
      ) : view === 'grid' ? (
        <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
          {paged.map((p) => (
            <button key={p.ref} onClick={() => open(p)} className="group h-full text-left">
              <Card className="flex h-full min-h-[142px] flex-col overflow-hidden rounded-lg border-border bg-card shadow-none transition hover:-translate-y-px hover:border-foreground/25 hover:shadow-sm">
                <div className="flex flex-1 flex-col p-4">
                  <div className="flex items-start justify-between gap-3">
                    <div className="min-w-0">
                      <h2
                        className="truncate text-[15px] font-semibold leading-5 tracking-tight"
                        title={p.name}
                      >
                        {p.name}
                      </h2>
                      <p className="mt-1 truncate font-mono text-[11px] text-muted-foreground">
                        {p.ref}
                      </p>
                    </div>
                    <StatusBadge initStatus={p.initStatus} healthStatus={p.healthStatus} />
                  </div>
                  <p className="mt-3 line-clamp-2 min-h-[32px] text-xs leading-4 text-muted-foreground">
                    {meaningfulDescription(p) ?? tr('projects.noDescription')}
                  </p>
                </div>
                <div className="flex h-10 items-center justify-between border-t border-border bg-muted/20 px-4">
                  <span className="truncate text-[11px] text-muted-foreground">
                    {tr('projects.schema')} <span className="font-mono text-foreground/80">{p.schemaName ?? 'public'}</span>
                  </span>
                  <ArrowRight className="h-3.5 w-3.5 text-muted-foreground transition group-hover:translate-x-0.5 group-hover:text-foreground" />
                </div>
              </Card>
            </button>
          ))}
        </div>
      ) : (
        <Card className="overflow-hidden rounded-lg border-border shadow-none">
          <div className="overflow-x-auto">
            <table className="w-full min-w-[760px] text-sm">
              <thead className="border-b border-border bg-muted/30 text-[11px] uppercase tracking-wide text-muted-foreground">
                <tr>
                  <th className="px-4 py-2.5 text-left font-medium">{tr('projects.project')}</th>
                  <th className="px-4 py-2.5 text-left font-medium">{tr('projects.reference')}</th>
                  <th className="px-4 py-2.5 text-left font-medium">{tr('projects.schema')}</th>
                  <th className="px-4 py-2.5 text-left font-medium">{tr('projects.status')}</th>
                  <th className="px-4 py-2.5" />
                </tr>
              </thead>
              <tbody>
                {paged.map((p) => (
                  <tr
                    key={p.ref}
                    onClick={() => open(p)}
                    className="group cursor-pointer border-b border-border/60 last:border-b-0 hover:bg-accent/35"
                  >
                    <td className="max-w-[340px] px-4 py-3">
                      <div className="truncate font-medium">{p.name}</div>
                      <div className="mt-0.5 truncate text-xs text-muted-foreground">
                        {meaningfulDescription(p) ?? tr('projects.noDescription')}
                      </div>
                    </td>
                    <td className="px-4 py-3 font-mono text-xs text-muted-foreground">{p.ref}</td>
                    <td className="px-4 py-3 font-mono text-xs text-muted-foreground">
                      {p.schemaName ?? 'public'}
                    </td>
                    <td className="px-4 py-3">
                      <StatusBadge initStatus={p.initStatus} healthStatus={p.healthStatus} />
                    </td>
                    <td className="px-4 py-3 text-right">
                      <ArrowRight className="ml-auto h-3.5 w-3.5 text-muted-foreground opacity-0 transition group-hover:translate-x-0.5 group-hover:opacity-100" />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      )}

      {filtered.length > PAGE_SIZE ? (
        <div className="flex flex-col gap-3 rounded-lg border border-border bg-card px-3 py-3 text-xs text-muted-foreground sm:flex-row sm:items-center sm:justify-between">
          <span>
            {tr('projects.showing', {
              start: (safePage - 1) * PAGE_SIZE + 1,
              end: Math.min(safePage * PAGE_SIZE, filtered.length),
              total: filtered.length,
            })}
          </span>
          <div className="flex items-center gap-1">
            <Button
              size="sm"
              variant="outline"
              onClick={() => setPage((p) => Math.max(1, p - 1))}
              disabled={safePage <= 1}
            >
              <ChevronLeft className="h-3.5 w-3.5" /> {tr('projects.prev')}
            </Button>
            <span className="px-2">
              {tr('projects.page', { page: safePage, total: pageCount })}
            </span>
            <Button
              size="sm"
              variant="outline"
              onClick={() => setPage((p) => Math.min(pageCount, p + 1))}
              disabled={safePage >= pageCount}
            >
              {tr('projects.next')} <ChevronRight className="h-3.5 w-3.5" />
            </Button>
          </div>
        </div>
      ) : null}
    </div>
  );
}

function StatusBadge({
  initStatus,
  healthStatus,
}: {
  initStatus?: string | null;
  healthStatus?: string | null;
}) {
  const state = getProjectState(initStatus, healthStatus);

  return (
    <span
      className={cn(
        'inline-flex shrink-0 items-center gap-1.5 rounded-full border px-2 py-0.5 text-[11px] font-medium',
        state.className
      )}
    >
      <span className={cn('h-1.5 w-1.5 rounded-full', state.dotClassName)} />
      {state.label}
    </span>
  );
}

function StatTile({
  label,
  value,
  icon: Icon,
  tone = 'neutral',
}: {
  label: string;
  value: number;
  icon: React.ComponentType<{ className?: string }>;
  tone?: 'neutral' | 'success' | 'warning' | 'danger';
}) {
  const toneClass =
    tone === 'success'
      ? 'text-emerald-400'
      : tone === 'warning'
        ? 'text-amber-400'
        : tone === 'danger'
          ? 'text-destructive'
          : 'text-muted-foreground';

  return (
    <div className="flex items-center justify-between px-4 py-3">
      <div>
        <p className="text-[11px] uppercase tracking-wide text-muted-foreground">{label}</p>
        <p className="mt-1 text-xl font-semibold tracking-tight">{value}</p>
      </div>
      <Icon className={cn('h-4 w-4', toneClass)} />
    </div>
  );
}

function summarizeProjects(projects: ProjectSummary[]) {
  return projects.reduce(
    (acc, project) => {
      const state = getProjectState(project.initStatus, project.healthStatus).key;
      if (state === 'ready') acc.ready += 1;
      else if (state === 'failed') acc.failed += 1;
      else acc.pending += 1;
      return acc;
    },
    { ready: 0, pending: 0, failed: 0 }
  );
}

function getProjectState(initStatus?: string | null, healthStatus?: string | null) {
  const init = (initStatus ?? '').toUpperCase();

  if (init === 'INITIALIZED') {
    return {
      key: 'ready',
      label: healthStatus === 'healthy' || !healthStatus ? 'ready' : healthStatus,
      className: 'border-emerald-500/20 bg-emerald-500/10 text-emerald-400',
      dotClassName: healthStatus === 'healthy' || !healthStatus ? 'bg-emerald-400' : 'bg-emerald-400/50',
    } as const;
  }

  if (init === 'INIT_FAILED') {
    return {
      key: 'failed',
      label: 'failed',
      className: 'border-destructive/25 bg-destructive/10 text-destructive',
      dotClassName: 'bg-destructive',
    } as const;
  }

  if (init === 'INITIALIZING') {
    return {
      key: 'pending',
      label: 'initializing',
      className: 'border-amber-500/25 bg-amber-500/10 text-amber-400',
      dotClassName: 'bg-amber-400',
    } as const;
  }

  return {
    key: 'pending',
    label: init === 'PENDING_INIT' ? 'pending' : 'unknown',
    className: 'border-border bg-muted/40 text-muted-foreground',
    dotClassName: 'bg-muted-foreground/60',
  } as const;
}
