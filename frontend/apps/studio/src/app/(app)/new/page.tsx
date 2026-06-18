'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import {
  Button,
  Input,
  Label,
  Card,
  CardHeader,
  CardTitle,
  CardDescription,
  CardContent,
  useToast,
} from '@nubase/ui';
import { apiFetch, type ApiError } from '@/lib/api';
import { useSession } from '@/lib/session';
import { useI18n } from '@/lib/i18n';

const REGIONS = [
  { value: 'us-east-1', label: 'US East (N. Virginia)' },
  { value: 'eu-west-1', label: 'EU West (Ireland)' },
  { value: 'ap-southeast-1', label: 'Asia Pacific (Singapore)' },
];

interface InitDatabaseResponse {
  success: boolean;
  jwtSecret?: string;
  serviceRoleToken?: string;
  authenticatedToken?: string;
  initStatus?: string;
  error?: string;
}

/** Lower-case, alphanum+underscore, max 40 chars — usable as appCode / dbKey / dbName. */
function deriveRef(name: string): string {
  const base = name
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '_')
    .replace(/^_+|_+$/g, '')
    .slice(0, 32);
  return base || 'project';
}

export default function NewProjectPage() {
  const router = useRouter();
  const { toast } = useToast();
  const { platformKey, hasHydrated, setProject } = useSession();
  const { tr } = useI18n();

  const [name, setName] = useState('');
  const [ref, setRef] = useState('');
  const [refTouched, setRefTouched] = useState(false);
  const [description, setDescription] = useState('');
  const [region, setRegion] = useState(REGIONS[0]!.value); // UI only, no backend support yet
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const effectiveRef = refTouched ? ref : deriveRef(name);

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!platformKey) return;
    setLoading(true);
    setError(null);
    try {
      const dbKey = effectiveRef;
      const dbName = effectiveRef;
      const appCode = effectiveRef;
      const res = await apiFetch<InitDatabaseResponse>('/auth/v1/admin/init/database_config', {
        method: 'POST',
        body: {
          dbKey,
          dbName,
          appCode,
          appName: name.trim() || appCode,
          description: description.trim() || null,
        },
        apikey: platformKey,
      });
      setProject({
        ref: appCode,
        apikey: res.serviceRoleToken ?? '',
        name: name.trim() || appCode,
        initStatus: res.initStatus ?? 'PENDING_INIT',
        healthStatus: null,
      });
      // Config created (PENDING_INIT). Land on the project page, which auto-starts
      // provisioning (phase 2) on mount — the user doesn't have to click anything.
      // Keeping create and provision as separate calls keeps the UI responsive and
      // lets the same auto-provision path recover historical stuck projects.
      toast({ variant: 'success', title: tr('newProject.created'), message: tr('newProject.initializing') });
      router.push(`/project/${appCode}`);
    } catch (err) {
      const msg = parseError(err as ApiError) ?? tr('newProject.failed');
      setError(msg);
      toast({ variant: 'error', title: tr('newProject.createFailed'), message: msg });
      setLoading(false);
    }
  }

  if (!hasHydrated) return null;
  if (!platformKey) {
    router.replace('/login');
    return null;
  }

  return (
    <div className="w-full max-w-2xl p-8">
      <Card>
        <CardHeader>
          <CardTitle>{tr('newProject.title')}</CardTitle>
          <CardDescription>{tr('newProject.subtitle')}</CardDescription>
        </CardHeader>
        <CardContent>
          <form onSubmit={onSubmit} className="space-y-4">
            <div className="space-y-2">
              <Label htmlFor="name">{tr('newProject.name')}</Label>
              <Input
                id="name"
                required
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder={tr('newProject.namePlaceholder')}
              />
              <p className="text-xs text-muted-foreground">{tr('newProject.nameHelp')}</p>
            </div>
            <div className="space-y-2">
              <Label htmlFor="ref">{tr('newProject.ref')}</Label>
              <Input
                id="ref"
                required
                pattern="[a-z0-9_]{1,40}"
                value={effectiveRef}
                onChange={(e) => {
                  setRef(e.target.value);
                  setRefTouched(true);
                }}
                placeholder="my_crm"
                className="font-mono"
              />
              <p className="text-xs text-muted-foreground">
                {tr('newProject.refHelp')}
              </p>
            </div>
            <div className="space-y-2">
              <Label htmlFor="description">{tr('newProject.description')}</Label>
              <Input
                id="description"
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                placeholder={tr('newProject.descriptionPlaceholder')}
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="region">{tr('newProject.region')}</Label>
              <select
                id="region"
                value={region}
                onChange={(e) => setRegion(e.target.value)}
                className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 text-sm shadow-sm"
              >
                {REGIONS.map((r) => (
                  <option key={r.value} value={r.value}>
                    {r.label}
                  </option>
                ))}
              </select>
              <p className="text-xs text-muted-foreground">
                {tr('newProject.regionHelp')}
              </p>
            </div>
            {error ? <p className="text-xs text-destructive">{error}</p> : null}
            <div className="flex justify-end gap-2 pt-2">
              <Button type="button" variant="outline" onClick={() => router.back()} disabled={loading}>
                {tr('newProject.cancel')}
              </Button>
              <Button type="submit" disabled={loading || !effectiveRef}>
                {loading ? tr('newProject.creating') : tr('newProject.create')}
              </Button>
            </div>
          </form>
        </CardContent>
      </Card>
    </div>
  );
}

/** The backend returns errors as JSON ({ error, message, ... }); fall back to the raw text. */
function parseError(err: ApiError): string | null {
  try {
    const parsed = JSON.parse(err.message);
    return parsed?.error ?? parsed?.message ?? null;
  } catch {
    return err.message;
  }
}
