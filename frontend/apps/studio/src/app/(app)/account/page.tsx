'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import {
  Badge,
  Button,
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
  Input,
  Label,
} from '@nubase/ui';
import { apiFetch, type ApiError } from '@/lib/api';
import { useSession } from '@/lib/session';
import { useI18n } from '@/lib/i18n';

export default function AccountPage() {
  const router = useRouter();
  const { user, platformKey, hasHydrated } = useSession();
  const { tr } = useI18n();

  useEffect(() => {
    if (!hasHydrated) return;
    if (!platformKey) router.replace('/login');
  }, [hasHydrated, platformKey, router]);

  if (!hasHydrated) return null;
  if (!user) return null;

  return (
    <div className="w-full max-w-2xl space-y-6 p-8">
      <header>
        <h1 className="text-2xl font-semibold tracking-tight">{tr('account.title')}</h1>
        <p className="text-sm text-muted-foreground">{tr('account.subtitle')}</p>
      </header>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">{tr('account.profile')}</CardTitle>
          <CardDescription>{tr('account.profileDescription')}</CardDescription>
        </CardHeader>
        <CardContent className="space-y-3 text-sm">
          <Row label={tr('auth.email')} value={user.email} />
          <Row label={tr('account.fullName')} value={user.fullName || '—'} />
          <div className="grid grid-cols-[140px_1fr] gap-4">
            <span className="text-xs text-muted-foreground">{tr('account.role')}</span>
            <div>
              {user.role === 'super_admin' ? (
                <Badge variant="success">super admin</Badge>
              ) : (
                <Badge variant="outline">user</Badge>
              )}
              <p className="mt-1 text-xs text-muted-foreground">
                {user.role === 'super_admin'
                  ? tr('account.roleSuper')
                  : tr('account.roleUser')}
              </p>
            </div>
          </div>
          <Row label={tr('account.userId')} value={user.id} mono />
        </CardContent>
      </Card>

      <ChangePasswordCard bearer={platformKey} />
    </div>
  );
}

function ChangePasswordCard({ bearer }: { bearer: string | null }) {
  const { tr } = useI18n();
  // 'form' collects passwords; 'code' is shown after a code has been emailed.
  const [step, setStep] = useState<'form' | 'code'>('form');
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [code, setCode] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  async function requestCode(e: React.FormEvent) {
    e.preventDefault();
    setLoading(true);
    setError(null);
    setNotice(null);
    try {
      await apiFetch('/auth/v1/platform/password/otp', {
        method: 'POST',
        bearer: bearer ?? undefined,
        body: { currentPassword },
      });
      setStep('code');
      setNotice(tr('account.passwordOtpSent'));
    } catch (err) {
      setError(parseError(err as ApiError) ?? tr('account.passwordOtpFailed'));
    } finally {
      setLoading(false);
    }
  }

  async function confirmChange(e: React.FormEvent) {
    e.preventDefault();
    setLoading(true);
    setError(null);
    try {
      await apiFetch('/auth/v1/platform/password', {
        method: 'POST',
        bearer: bearer ?? undefined,
        body: { currentPassword, newPassword, code },
      });
      setStep('form');
      setCurrentPassword('');
      setNewPassword('');
      setCode('');
      setNotice(tr('account.passwordUpdated'));
    } catch (err) {
      setError(parseError(err as ApiError) ?? tr('account.passwordChangeFailed'));
    } finally {
      setLoading(false);
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">{tr('account.security')}</CardTitle>
        <CardDescription>{tr('account.securityDescription')}</CardDescription>
      </CardHeader>
      <CardContent>
        {step === 'form' ? (
          <form onSubmit={requestCode} className="space-y-4">
            <div className="space-y-2">
              <Label htmlFor="currentPassword">{tr('account.currentPassword')}</Label>
              <Input
                id="currentPassword"
                type="password"
                required
                autoComplete="current-password"
                value={currentPassword}
                onChange={(e) => setCurrentPassword(e.target.value)}
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="newPassword">{tr('account.newPassword')}</Label>
              <Input
                id="newPassword"
                type="password"
                required
                minLength={8}
                autoComplete="new-password"
                value={newPassword}
                onChange={(e) => setNewPassword(e.target.value)}
              />
              <p className="text-xs text-muted-foreground">{tr('auth.signup.passwordHelp')}</p>
            </div>
            {notice ? <p className="text-xs text-muted-foreground">{notice}</p> : null}
            {error ? <p className="text-xs text-destructive">{error}</p> : null}
            <Button type="submit" disabled={loading}>
              {loading ? tr('account.sendingCode') : tr('account.continue')}
            </Button>
          </form>
        ) : (
          <form onSubmit={confirmChange} className="space-y-4">
            <div className="space-y-2">
              <Label htmlFor="pwCode">{tr('account.confirmationCode')}</Label>
              <Input
                id="pwCode"
                inputMode="numeric"
                autoComplete="one-time-code"
                maxLength={6}
                required
                placeholder="123456"
                value={code}
                onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))}
              />
            </div>
            {notice ? <p className="text-xs text-muted-foreground">{notice}</p> : null}
            {error ? <p className="text-xs text-destructive">{error}</p> : null}
            <div className="flex gap-2">
              <Button type="submit" disabled={loading || code.length < 6}>
                {loading ? tr('account.updating') : tr('account.changePassword')}
              </Button>
              <Button
                type="button"
                variant="outline"
                onClick={() => {
                  setStep('form');
                  setCode('');
                  setError(null);
                  setNotice(null);
                }}
              >
                {tr('account.cancel')}
              </Button>
            </div>
          </form>
        )}
      </CardContent>
    </Card>
  );
}

function Row({ label, value, mono }: { label: string; value: string; mono?: boolean }) {
  return (
    <div className="grid grid-cols-[140px_1fr] gap-4">
      <span className="text-xs text-muted-foreground">{label}</span>
      <span className={mono ? 'font-mono text-xs' : ''}>{value}</span>
    </div>
  );
}

function parseError(err: ApiError): string | null {
  try {
    const parsed = JSON.parse(err.message);
    return parsed?.message ?? null;
  } catch {
    return err.message;
  }
}
