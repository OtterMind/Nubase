'use client';

import { useEffect, useState } from 'react';
import { Moon, Sun } from 'lucide-react';
import { useI18n } from '@/lib/i18n';

const STORAGE_KEY = 'nubase.theme';

type Theme = 'light' | 'dark';

function applyTheme(t: Theme) {
  const root = document.documentElement;
  if (t === 'dark') root.classList.add('dark');
  else root.classList.remove('dark');
}

export function ThemeToggle({ className }: { className?: string }) {
  const { tr } = useI18n();
  const [theme, setTheme] = useState<Theme>('light');

  useEffect(() => {
    const stored = (typeof window !== 'undefined' ? window.localStorage.getItem(STORAGE_KEY) : null) as Theme | null;
    const initial: Theme = stored === 'light' || stored === 'dark' ? stored : 'light';
    setTheme(initial);
    applyTheme(initial);
  }, []);

  function toggle() {
    const next: Theme = theme === 'dark' ? 'light' : 'dark';
    setTheme(next);
    applyTheme(next);
    try {
      window.localStorage.setItem(STORAGE_KEY, next);
    } catch {
      // ignore
    }
  }

  const label = theme === 'dark' ? tr('theme.light') : tr('theme.dark');

  return (
    <button
      onClick={toggle}
      className={'rounded-md border border-transparent p-1.5 text-muted-foreground transition-colors hover:border-border hover:bg-accent hover:text-foreground ' + (className ?? '')}
      aria-label={label}
      title={label}
    >
      {theme === 'dark' ? <Sun className="h-4 w-4" /> : <Moon className="h-4 w-4" />}
    </button>
  );
}
