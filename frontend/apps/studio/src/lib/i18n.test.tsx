import { render, screen, fireEvent } from '@testing-library/react';
import { describe, expect, it, beforeEach, vi } from 'vitest';
import { I18nProvider, LanguageToggle, t, useI18n } from './i18n';

function Probe() {
  const { locale, setLocale, tr } = useI18n();
  return (
    <div>
      <p>{locale}</p>
      <p>{tr('auth.login.title')}</p>
      <button onClick={() => setLocale('zh-CN')}>set zh</button>
    </div>
  );
}

describe('studio i18n', () => {
  beforeEach(() => {
    const store = new Map<string, string>();
    vi.stubGlobal('localStorage', {
      clear: () => store.clear(),
      getItem: (key: string) => store.get(key) ?? null,
      setItem: (key: string, value: string) => store.set(key, value),
      removeItem: (key: string) => store.delete(key),
    });
    window.localStorage.clear();
    document.documentElement.lang = '';
  });

  it('translates dictionary keys with interpolation', () => {
    expect(t('en', 'shell.projectWarning', { status: 'pending' })).toContain('Project is pending');
    expect(t('zh-CN', 'shell.projectWarning', { status: 'pending' })).toContain('项目状态为 pending');
  });

  it('defaults to English and persists language changes', () => {
    render(
      <I18nProvider>
        <Probe />
      </I18nProvider>,
    );

    expect(screen.getByText('en')).toBeInTheDocument();
    expect(screen.getByText('Sign in to Studio')).toBeInTheDocument();

    fireEvent.click(screen.getByText('set zh'));

    expect(screen.getByText('zh-CN')).toBeInTheDocument();
    expect(screen.getByText('登录 Studio')).toBeInTheDocument();
    expect(window.localStorage.getItem('nubase.studio.locale')).toBe('zh-CN');
    expect(document.documentElement.lang).toBe('zh-CN');
  });

  it('renders a language toggle that switches between English and Chinese', () => {
    render(
      <I18nProvider>
        <LanguageToggle />
        <Probe />
      </I18nProvider>,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Switch language to Chinese' }));

    expect(screen.getByText('zh-CN')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '切换语言为英文' })).toBeInTheDocument();
  });
});
