import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { AuthSubNav } from './sub-nav';

// Stub next/link to a plain anchor for jsdom.
vi.mock('next/link', () => ({
  default: ({ href, children, ...props }: React.AnchorHTMLAttributes<HTMLAnchorElement> & { href: string }) => (
    <a href={href} {...props}>{children}</a>
  ),
}));

describe('AuthSubNav', () => {
  it('renders all five tabs with the correct hrefs for the project', () => {
    render(<AuthSubNav projectRef="demo" active="settings" />);

    const expected: Record<string, string> = {
      Users: '/project/demo/auth',
      Providers: '/project/demo/auth/providers',
      SSO: '/project/demo/auth/sso',
      'Email Templates': '/project/demo/auth/templates',
      Settings: '/project/demo/auth/settings',
    };
    for (const [label, href] of Object.entries(expected)) {
      const link = screen.getByRole('link', { name: new RegExp(label) });
      expect(link).toHaveAttribute('href', href);
    }
  });

  it('marks the active tab distinctly from the others', () => {
    render(<AuthSubNav projectRef="demo" active="settings" />);
    const active = screen.getByRole('link', { name: /Settings/ });
    const inactive = screen.getByRole('link', { name: /Users/ });
    expect(active.className).toContain('text-foreground');
    expect(active.className).not.toContain('text-muted-foreground');
    expect(inactive.className).toContain('text-muted-foreground');
  });
});
