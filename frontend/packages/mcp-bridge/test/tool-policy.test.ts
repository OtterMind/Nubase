import test from 'node:test';
import assert from 'node:assert/strict';
import type { BridgeConfig } from '../src/config.js';
import { callTool, isToolAllowed, toolsForConfig } from '../src/tools.js';

function config(overrides: Partial<BridgeConfig> = {}): BridgeConfig {
  return {
    nubaseUrl: 'http://localhost:9999',
    projectKey: '',
    allowSqlExecute: false,
    allowDangerousSql: false,
    allowAdminWrite: false,
    ...overrides,
  };
}

test('tool policy defaults to the complete tool catalog', () => {
  const toolConfig = config();
  assert.equal(isToolAllowed('nubase_overview', toolConfig), true);
  assert.equal(toolsForConfig(toolConfig).length > 10, true);
});

test('allowlist limits both discovery and dispatch', async () => {
  const toolConfig = config({ allowedTools: ['fetch_docs', 'sql_dry_run'] });
  const names = toolsForConfig(toolConfig).map((tool) => tool.name);
  assert.deepEqual(names, ['fetch_docs', 'sql_dry_run']);

  await assert.rejects(
    () => callTool('project_keys', {}, toolConfig, {} as never),
    /Tool is not allowed by the active Nubase tool policy: project_keys/
  );
});

test('denylist takes precedence over an allowlist', async () => {
  const toolConfig = config({
    allowedTools: ['fetch_docs', 'project_keys'],
    deniedTools: ['project_keys'],
  });
  assert.equal(isToolAllowed('fetch_docs', toolConfig), true);
  assert.equal(isToolAllowed('project_keys', toolConfig), false);
  assert.deepEqual(toolsForConfig(toolConfig).map((tool) => tool.name), ['fetch_docs']);

  await assert.rejects(
    () => callTool('project_keys', {}, toolConfig, {} as never),
    /Tool is not allowed by the active Nubase tool policy/
  );
});
