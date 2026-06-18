'use client';

import Link from 'next/link';
import { usePathname, useRouter } from 'next/navigation';
import { useEffect, useRef, useState } from 'react';
import {
  FolderGit2,
  Box,
  Plus,
  User,
  Home,
  Table2,
  Terminal,
  Users,
  HardDrive,
  Activity,
  Brain,
  Settings,
  Bot,
  Cable,
  CalendarClock,
  CloudCog,
  FileBox,
  ChevronDown,
  Check,
  AlertTriangle,
  PanelLeftClose,
  PanelLeftOpen,
} from 'lucide-react';
import { cn } from '@nubase/ui';
import { useSession, isProjectReady } from '@/lib/session';
import { apiFetch } from '@/lib/api';
import { useProjectRef } from '@/lib/route-params';
import { type MessageKey, useI18n } from '@/lib/i18n';
import { UserMenu } from './user-menu';

interface NavItem {
  labelKey: MessageKey;
  href: string;
  icon: React.ComponentType<{ className?: string }>;
  /** Exact match (no startsWith) — used for the workspace root link so it doesn't match /projects/foo. */
  exact?: boolean;
}

const WORKSPACE_NAV: NavItem[] = [
  { labelKey: 'shell.nav.allProjects', href: '/projects', icon: FolderGit2 },
  { labelKey: 'shell.nav.newProject', href: '/new', icon: Plus },
  { labelKey: 'shell.nav.account', href: '/account', icon: User },
];

function projectNav(ref: string): NavItem[] {
  return [
    { labelKey: 'shell.nav.home', href: `/project/${ref}`, icon: Home, exact: true },
    { labelKey: 'shell.nav.tableEditor', href: `/project/${ref}/editor`, icon: Table2 },
    { labelKey: 'shell.nav.sqlEditor', href: `/project/${ref}/sql`, icon: Terminal },
    { labelKey: 'shell.nav.authentication', href: `/project/${ref}/auth`, icon: Users },
    { labelKey: 'shell.nav.storage', href: `/project/${ref}/storage`, icon: HardDrive },
    { labelKey: 'shell.nav.assets', href: `/project/${ref}/assets`, icon: FileBox },
    { labelKey: 'shell.nav.memory', href: `/project/${ref}/memory`, icon: Brain },
    { labelKey: 'shell.nav.aiGateway', href: `/project/${ref}/ai-gateway`, icon: Bot },
    { labelKey: 'shell.nav.functions', href: `/project/${ref}/functions`, icon: CloudCog },
    { labelKey: 'shell.nav.cron', href: `/project/${ref}/cron`, icon: CalendarClock },
    { labelKey: 'shell.nav.connectAgent', href: `/project/${ref}/connect-agent`, icon: Cable },
    { labelKey: 'shell.nav.logs', href: `/project/${ref}/logs`, icon: Activity },
    { labelKey: 'shell.nav.settings', href: `/project/${ref}/settings`, icon: Settings },
  ];
}

/** localStorage key for the collapsed/expanded preference. */
const COLLAPSE_KEY = 'nubase.studio.sidebar.collapsed';

/**
 * Single shell used by both workspace pages (Projects/New/Account) and project-internal pages.
 *
 * <p>Sidebar is expanded by default and can collapse to icons-only (w-14, 56px).
 * Preference is persisted to localStorage so the choice sticks
 * across pages and reloads. Collapsed mode uses native {@code title} tooltips for label
 * disclosure — no popover library required.
 */
export function WorkspaceShell({
  projectRef,
  children,
}: {
  /** Set when rendering inside /project/[ref]/* — drives which nav item is highlighted. */
  projectRef?: string;
  children: React.ReactNode;
}) {
  const { tr } = useI18n();
  const router = useRouter();
  const pathname = usePathname();
  const project = useSession((s) => s.project);
  const platformKey = useSession((s) => s.platformKey);
  const setProject = useSession((s) => s.setProject);
  const resolvedProjectRef = useProjectRef(projectRef);
  const activeProjectRef = resolvedProjectRef || null;
  const [projects, setProjects] = useState<ProjectSummary[]>([]);
  const ready = isProjectReady(project);
  const showWarn = Boolean(resolvedProjectRef) && !ready && project?.initStatus;

  useEffect(() => {
    if (!platformKey) {
      setProjects([]);
      return;
    }
    let cancelled = false;
    apiFetch<ProjectSummary[]>('/auth/v1/admin/projects', { apikey: platformKey })
      .then((projects) => {
        if (cancelled) return;
        setProjects(projects);
        if (!resolvedProjectRef) return;
        const next = projects.find((p) => p.ref === resolvedProjectRef);
        if (!next) return;
        setProject({
          ref: next.ref,
          apikey: next.apikey ?? '',
          name: next.name ?? next.ref,
          initStatus: next.initStatus ?? null,
          healthStatus: next.healthStatus ?? null,
        });
      })
      .catch(() => {
        // Individual pages surface auth/load errors; the shell only fills missing context.
      });
    return () => {
      cancelled = true;
    };
  }, [platformKey, resolvedProjectRef, setProject]);

  const activeProject = activeProjectRef
    ? projects.find((p) => p.ref === activeProjectRef) ??
      (project?.ref === activeProjectRef ? project : null)
    : null;

  function switchProject(next: ProjectSummary) {
    setProject({
      ref: next.ref,
      apikey: next.apikey ?? '',
      name: next.name ?? next.ref,
      initStatus: next.initStatus ?? null,
      healthStatus: next.healthStatus ?? null,
    });
    router.push(`/project/${next.ref}`);
  }

  const [collapsed, setCollapsed] = useState(false);
  useEffect(() => {
    if (typeof window === 'undefined') return;
    const stored = window.localStorage.getItem(COLLAPSE_KEY);
    if (stored === 'true') setCollapsed(true);
  }, []);
  const toggle = () => {
    setCollapsed((prev) => {
      const next = !prev;
      try {
        window.localStorage.setItem(COLLAPSE_KEY, String(next));
      } catch {
        // localStorage disabled (private mode etc.) — fine, state survives the session.
      }
      return next;
    });
  };

  return (
    <div className="flex h-screen w-full bg-background text-[0.95rem]">
      <aside
        className={cn(
          'flex flex-col border-r border-border/80 bg-card transition-[width] duration-200 ease-out',
          collapsed ? 'w-14' : 'w-[244px]'
        )}
      >
        {/* Brand row: logo always visible, wordmark hides when collapsed */}
        <div
          className={cn(
            'flex h-[54px] items-center gap-2 px-3',
            collapsed && 'justify-center px-0'
          )}
        >
          <span className="inline-flex h-7 w-7 shrink-0 items-center justify-center rounded-lg bg-gradient-to-br from-brand to-emerald-700 text-xs font-bold text-brand-foreground shadow-sm">
            N
          </span>
          {!collapsed && (
            <div className="flex min-w-0 flex-1 items-center gap-2">
              <Link href="/projects" className="truncate text-[15px] font-bold">
                nubase
              </Link>
              <span className="rounded-full bg-muted px-2 py-0.5 text-[10px] font-semibold text-muted-foreground">
                Studio
              </span>
            </div>
          )}
        </div>

        <nav className="flex-1 space-y-1 overflow-y-auto px-3 py-4">
          {activeProjectRef ? (
            <>
              {!collapsed && (
                <div className="mb-1 px-2 py-1 text-[10px] font-bold uppercase tracking-[0.14em] text-muted-foreground">
                  {tr('shell.section.project')}
                </div>
              )}
              {projectNav(activeProjectRef).map((item) => (
                <SidebarLink key={item.href} item={item} pathname={pathname} collapsed={collapsed} />
              ))}
              {!collapsed && (
                <div className="mb-1 mt-5 px-2 py-1 text-[10px] font-bold uppercase tracking-[0.14em] text-muted-foreground">
                  {tr('shell.section.workspace')}
                </div>
              )}
            </>
          ) : (
            !collapsed && (
              <div className="mb-1 px-2 py-1 text-[10px] font-bold uppercase tracking-[0.14em] text-muted-foreground">
                {tr('shell.section.workspace')}
              </div>
            )
          )}

          {WORKSPACE_NAV.map((item) => (
            <SidebarLink key={item.href} item={item} pathname={pathname} collapsed={collapsed} />
          ))}
        </nav>

        {/* Footer: collapse/expand toggle + (optionally) version. Always at the bottom so
            the toggle stays reachable regardless of how many nav items there are. */}
        <div
          className={cn(
            'flex items-center border-t border-border/80 px-3 py-3',
            collapsed ? 'justify-center' : 'justify-between'
          )}
        >
          {!collapsed && (
            <span className="text-[11px] font-medium text-muted-foreground">{tr('shell.version')}</span>
          )}
          <button
            type="button"
            onClick={toggle}
            className="rounded-md border border-transparent p-1.5 text-muted-foreground transition-colors hover:border-border hover:bg-accent hover:text-foreground"
            title={collapsed ? tr('shell.expand') : tr('shell.collapse')}
            aria-label={collapsed ? tr('shell.expand') : tr('shell.collapse')}
          >
            {collapsed ? (
              <PanelLeftOpen className="h-3.5 w-3.5" />
            ) : (
              <PanelLeftClose className="h-3.5 w-3.5" />
            )}
          </button>
        </div>
      </aside>

      <main className="flex flex-1 flex-col overflow-hidden">
        <header className="flex h-[54px] items-center justify-between gap-3 border-b border-border/80 bg-card px-6">
          <div className="min-w-0">
            {activeProjectRef ? (
              <ProjectSwitcher
                activeProjectRef={activeProjectRef}
                activeProjectName={activeProject?.name ?? activeProjectRef}
                projects={projects}
                onSelect={switchProject}
              />
            ) : null}
          </div>
          <UserMenu />
        </header>
        {showWarn ? (
          <div className="flex items-center gap-2 border-b border-amber-500/30 bg-amber-500/10 px-6 py-2 text-xs text-amber-700 dark:text-amber-300">
            <AlertTriangle className="h-3.5 w-3.5" />
            <span>
              {tr('shell.projectWarning', { status: project?.initStatus?.toLowerCase() ?? 'pending' })}
            </span>
          </div>
        ) : null}
        <div className="flex flex-1 flex-col overflow-y-auto bg-background">{children}</div>
      </main>
    </div>
  );
}

interface ProjectSummary {
  ref: string;
  name?: string | null;
  initStatus?: string | null;
  healthStatus?: string | null;
  apikey?: string | null;
}

function ProjectSwitcher({
  activeProjectRef,
  activeProjectName,
  projects,
  onSelect,
}: {
  activeProjectRef: string;
  activeProjectName: string;
  projects: ProjectSummary[];
  onSelect: (project: ProjectSummary) => void;
}) {
  const [open, setOpen] = useState(false);
  const wrapRef = useRef<HTMLDivElement>(null);
  const { tr } = useI18n();

  useEffect(() => {
    function onDocClick(e: MouseEvent) {
      if (!wrapRef.current?.contains(e.target as Node)) setOpen(false);
    }
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') setOpen(false);
    }
    if (open) {
      document.addEventListener('mousedown', onDocClick);
      document.addEventListener('keydown', onKey);
      return () => {
        document.removeEventListener('mousedown', onDocClick);
        document.removeEventListener('keydown', onKey);
      };
    }
  }, [open]);

  function select(project: ProjectSummary) {
    setOpen(false);
    if (project.ref !== activeProjectRef) onSelect(project);
  }

  return (
    <div ref={wrapRef} className="relative min-w-0">
      <div className="flex min-w-0 items-center gap-3 text-sm">
        <Link
          href="/projects"
          className="flex min-w-0 items-center gap-2 rounded-md px-1.5 py-1 font-semibold text-foreground transition-colors hover:bg-accent"
        >
          <FolderGit2 className="h-4 w-4 shrink-0 text-muted-foreground" />
          <span className="hidden truncate sm:inline">{tr('shell.workspace')}</span>
          <span className="rounded-full border border-border bg-background px-2 py-0.5 text-[10px] font-bold uppercase tracking-[0.12em] text-muted-foreground">
            {tr('shell.local')}
          </span>
        </Link>
        <span className="text-lg font-light text-muted-foreground/70">/</span>
        <button
          type="button"
          onClick={() => setOpen((v) => !v)}
          className="flex min-w-0 items-center gap-2 rounded-md px-1.5 py-1 text-left font-semibold text-foreground transition-colors hover:bg-accent"
          aria-haspopup="menu"
          aria-expanded={open}
        >
          <Box className="h-4 w-4 shrink-0 text-muted-foreground" />
          <span className="min-w-0 truncate text-[15px]">{activeProjectName}</span>
          <ChevronDown className="h-4 w-4 shrink-0 text-muted-foreground" />
        </button>
        <span className="hidden text-lg font-light text-muted-foreground/70 md:inline">/</span>
        <div className="hidden min-w-0 items-center gap-2 md:flex">
          <span className="truncate font-mono text-[13px] font-semibold text-foreground">main</span>
          <span className="rounded-full border border-emerald-600/25 bg-emerald-500/10 px-2.5 py-0.5 text-[10px] font-bold uppercase tracking-[0.14em] text-emerald-700 dark:text-emerald-300">
            {tr('shell.ready')}
          </span>
        </div>
      </div>

      {open ? (
        <div
          role="menu"
          className="absolute left-[120px] z-50 mt-3 max-h-96 w-80 overflow-y-auto rounded-lg border border-border bg-popover py-1 text-popover-foreground shadow-xl shadow-slate-950/10 sm:left-[168px]"
        >
          <div className="border-b border-border bg-muted/35 px-3 py-2.5">
            <div className="text-[10px] font-bold uppercase tracking-[0.14em] text-muted-foreground">
              {tr('shell.switchProject')}
            </div>
            <div className="mt-0.5 truncate text-xs text-muted-foreground">{activeProjectRef}</div>
          </div>
          {projects.length > 0 ? (
            projects.map((project) => {
              const active = project.ref === activeProjectRef;
              return (
                <button
                  key={project.ref}
                  type="button"
                  onClick={() => select(project)}
                  className={cn(
                    'flex w-full items-center gap-3 px-3 py-2.5 text-left text-sm hover:bg-accent',
                    active && 'bg-accent/70'
                  )}
                  role="menuitem"
                >
                  <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-md border border-border bg-background">
                    <Box className="h-3.5 w-3.5 text-muted-foreground" />
                  </span>
                  <span className="min-w-0 flex-1">
                    <span className="block truncate font-semibold">{project.name ?? project.ref}</span>
                    <span className="block truncate font-mono text-[10px] text-muted-foreground">
                      {project.ref}
                    </span>
                  </span>
                  {active ? <Check className="h-3.5 w-3.5 shrink-0 text-brand" /> : null}
                </button>
              );
            })
          ) : (
            <div className="px-3 py-3 text-xs text-muted-foreground">{tr('shell.noProjects')}</div>
          )}
        </div>
      ) : null}
    </div>
  );
}

function SidebarLink({
  item,
  pathname,
  collapsed,
}: {
  item: NavItem;
  pathname: string;
  collapsed: boolean;
}) {
  const { tr } = useI18n();
  const Icon = item.icon;
  const active = item.exact ? pathname === item.href : pathname === item.href || pathname.startsWith(item.href + '/');
  const label = tr(item.labelKey);
  return (
    <Link
      href={item.href}
      // When collapsed, center the icon and drop the label. Native title attribute gives
      // free hover tooltips so users don't lose their bearings in icon-only mode.
      title={collapsed ? label : undefined}
      className={cn(
        'flex items-center rounded-lg text-sm font-medium transition-colors',
        collapsed
          ? 'justify-center px-0 py-2.5'
          : 'gap-2.5 px-3 py-2.5',
        active
          ? 'bg-accent text-accent-foreground shadow-[inset_0_0_0_1px_hsl(var(--border))]'
          : 'text-muted-foreground hover:bg-accent/65 hover:text-foreground'
      )}
    >
      <Icon className="h-4 w-4 shrink-0" />
      {!collapsed && <span className="truncate">{label}</span>}
    </Link>
  );
}
