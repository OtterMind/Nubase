import { describe, it, expect } from 'vitest';
import { existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import { projectNav } from './workspace-shell';

// The App Router project routes live under src/app/project/[ref]/<segment>/page.tsx.
// Every sidebar nav item must point at one of those pages — a nav item without a
// matching page renders a dead link that 404s (regression: the "Logs" item linked
// to /project/[ref]/logs which was never implemented).
const componentsDir = dirname(fileURLToPath(import.meta.url));
const projectRouteDir = resolve(componentsDir, '../app/project/[ref]');

function pageExists(dir: string): boolean {
  return existsSync(resolve(dir, 'page.tsx')) || existsSync(resolve(dir, 'page.ts'));
}

describe('project sidebar navigation', () => {
  it('every project nav item links to an implemented App Router page (no dead links / 404s)', () => {
    const ref = 'demo';

    const deadLinks = projectNav(ref)
      .filter((item) => {
        // Strip the /project/{ref} prefix → route segment ('' for the project home).
        const segment = item.href.replace(`/project/${ref}`, '').replace(/^\//, '');
        const dir = segment ? resolve(projectRouteDir, segment) : projectRouteDir;
        return !pageExists(dir);
      })
      .map((item) => item.href);

    expect(deadLinks).toEqual([]);
  });
});
