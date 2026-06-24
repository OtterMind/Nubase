'use client';

import { useEffect, useRef, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { CloudOff, Loader2, Zap } from 'lucide-react';
import { Button, Card, CardContent } from '@nubase/ui';
import { apiFetch, fetchAllProjects, type ApiError } from '@/lib/api';
import { useSession, type ProjectContext } from '@/lib/session';
import { useProjectRef } from '@/lib/route-params';

interface NotProvisionedProps {
  projectRef: string;
  initStatus?: string | null;
}

/**
 * Shown on data pages while the project's database isn't initialised yet. The user
 * shouldn't have to click anything: provisioning starts automatically on mount and
 * this just reports progress, falling back to a manual retry if it fails.
 */
export function NotProvisioned({ projectRef, initStatus }: NotProvisionedProps) {
  const router = useRouter();
  const { platformKey, project, setProject } = useSession();
  const resolvedProjectRef = useProjectRef(projectRef);
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const autoStarted = useRef(false);

  async function provision() {
    if (!platformKey || !resolvedProjectRef) return;
    setRunning(true);
    setError(null);
    try {
      await apiFetch(`/auth/v1/admin/projects/${encodeURIComponent(resolvedProjectRef)}/provision`, {
        method: 'POST',
        apikey: platformKey,
      });
      const refreshed = await fetchProject(platformKey, resolvedProjectRef);
      if (refreshed) {
        setProject(refreshed);
      } else if (project && project.ref === resolvedProjectRef) {
        setProject({ ...project, initStatus: 'INITIALIZED' });
      }
      router.refresh();
    } catch (err) {
      setError((err as ApiError).message ?? 'Provision failed.');
    } finally {
      setRunning(false);
    }
  }

  // Kick off provisioning automatically the moment this lands — no manual click.
  // Fires once per mount; on failure the manual retry button below takes over.
  useEffect(() => {
    if (autoStarted.current) return;
    if (!platformKey || !resolvedProjectRef) return;
    autoStarted.current = true;
    void provision();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [platformKey, resolvedProjectRef]);

  const failed = !!error && !running;

  return (
    <div className="p-8">
      <Card>
        <CardContent className="flex flex-col items-center gap-3 py-16 text-center">
          {failed ? (
            <>
              <CloudOff className="h-8 w-8 text-muted-foreground" />
              <h2 className="text-lg font-semibold">Database provisioning failed</h2>
              <p className="max-w-md text-sm text-muted-foreground">
                This project is in state{' '}
                <code className="font-mono text-xs">{initStatus ?? 'unknown'}</code>. The underlying Postgres
                database couldn&apos;t be initialised.
              </p>
              <p className="max-w-md text-xs text-destructive">{error}</p>
              <div className="flex gap-2 pt-2">
                <Button size="sm" onClick={provision} disabled={running}>
                  <Zap className="h-3.5 w-3.5" /> Retry
                </Button>
                <Link href={`/project/${resolvedProjectRef}/settings`}>
                  <Button variant="outline" size="sm">
                    Open settings
                  </Button>
                </Link>
              </div>
            </>
          ) : (
            <>
              <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
              <h2 className="text-lg font-semibold">Initializing database…</h2>
              <p className="max-w-md text-sm text-muted-foreground">
                Provisioning the Postgres database and running the auth/storage schema for{' '}
                <code className="font-mono text-xs">{resolvedProjectRef}</code>. This takes ~30 seconds — the
                Database, Auth and Storage pages will populate automatically once it&apos;s done.
              </p>
            </>
          )}
        </CardContent>
      </Card>
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

async function fetchProject(platformKey: string, projectRef: string): Promise<ProjectContext | null> {
  const projects = await fetchAllProjects<ProjectSummary>(platformKey);
  const project = projects.find((p) => p.ref === projectRef);
  if (!project) return null;
  return {
    ref: project.ref,
    apikey: project.apikey ?? '',
    name: project.name ?? project.ref,
    initStatus: project.initStatus ?? null,
    healthStatus: project.healthStatus ?? null,
  };
}
