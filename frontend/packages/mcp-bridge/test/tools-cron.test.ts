import test from 'node:test';
import assert from 'node:assert/strict';
import type { BridgeConfig } from '../src/config.js';
import { NubaseClient } from '../src/nubase-client.js';
import { callTool, TOOLS } from '../src/tools.js';

function config(overrides: Partial<BridgeConfig> = {}): BridgeConfig {
  return {
    nubaseUrl: 'http://localhost:9999',
    projectKey: 'service-role-key',
    allowSqlExecute: false,
    allowDangerousSql: false,
    allowAdminWrite: true,
    ...overrides,
  };
}

test('MCP tool list includes cron tools', () => {
  const names = new Set(TOOLS.map((tool) => tool.name));
  for (const name of ['cron_list', 'cron_get', 'cron_create', 'cron_update', 'cron_delete', 'cron_runs']) {
    assert.equal(names.has(name), true, `missing ${name}`);
  }
});

test('cron_create routes the full payload to the client', async () => {
  const calls: Array<Record<string, unknown>> = [];
  const client = {
    cronCreateJob: async (args: Record<string, unknown>) => {
      calls.push(args);
      return { ok: true };
    },
  } as any;
  const args = {
    name: 'nightly',
    cronExpression: '0 3 * * *',
    targetType: 'edge_function',
    functionSlug: 'cleanup',
    timeoutSeconds: 60,
  };
  const result = await callTool('cron_create', args, config(), client);
  assert.deepEqual(result, { ok: true });
  assert.deepEqual(calls[0], args);
});

test('cron_runs targets the job endpoint when a name is given, project-wide otherwise', async () => {
  const calls: string[] = [];
  const client = {
    cronJobRuns: async () => {
      calls.push('jobRuns');
      return [];
    },
    cronRuns: async () => {
      calls.push('runs');
      return [];
    },
  } as any;
  await callTool('cron_runs', { name: 'nightly', limit: 5 }, config(), client);
  await callTool('cron_runs', { limit: 5 }, config(), client);
  assert.deepEqual(calls, ['jobRuns', 'runs']);
});

test('cron_create refuses with PERMISSION_GATE_OFF when admin writes are off', async () => {
  const toolConfig = config({ allowAdminWrite: false });
  const originalFetch = globalThis.fetch;
  let fetched = 0;
  globalThis.fetch = (async () => {
    fetched += 1;
    return new Response('{}', { status: 200 });
  }) as typeof fetch;
  let result: Record<string, any>;
  try {
    result = (await callTool(
      'cron_create',
      { name: 'x', cronExpression: '0 * * * *', targetType: 'db_function', dbFunctionName: 'f' },
      toolConfig,
      new NubaseClient(toolConfig)
    )) as Record<string, any>;
  } finally {
    globalThis.fetch = originalFetch;
  }
  assert.equal(result.success, false);
  assert.equal(result.code, 'PERMISSION_GATE_OFF');
  assert.equal(fetched, 0, 'gated create must not hit the network');
});

test('cron_create posts to the jobs endpoint when admin writes are on', async () => {
  const toolConfig = config({ allowAdminWrite: true });
  const originalFetch = globalThis.fetch;
  const seen: Array<{ url: string; method?: string; body?: unknown }> = [];
  globalThis.fetch = (async (input: Parameters<typeof fetch>[0], init?: RequestInit) => {
    seen.push({ url: String(input), method: init?.method, body: init?.body });
    return new Response(JSON.stringify({ id: 'job_1' }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    });
  }) as typeof fetch;
  try {
    await callTool(
      'cron_create',
      { name: 'nightly', cronExpression: '0 3 * * *', targetType: 'edge_function', functionSlug: 'cleanup' },
      toolConfig,
      new NubaseClient(toolConfig)
    );
  } finally {
    globalThis.fetch = originalFetch;
  }
  assert.equal(seen[0]?.url, 'http://localhost:9999/cron/admin/v1/jobs');
  assert.equal(seen[0]?.method, 'POST');
  const body = JSON.parse(String(seen[0]?.body));
  assert.equal(body.name, 'nightly');
  assert.equal(body.targetType, 'edge_function');
  assert.equal(body.functionSlug, 'cleanup');
});
