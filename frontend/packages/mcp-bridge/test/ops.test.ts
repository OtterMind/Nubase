import test from 'node:test';
import assert from 'node:assert/strict';
import type { BridgeConfig } from '../src/config.js';
import { NubaseClient } from '../src/nubase-client.js';

interface CapturedRequest {
  url: string;
  method: string;
  headers: Record<string, string>;
  body?: unknown;
}

function nth(calls: CapturedRequest[], i: number): CapturedRequest {
  const call = calls[i];
  assert.ok(call, `expected captured request #${i}`);
  return call;
}

function makeClient(overrides: Partial<BridgeConfig> = {}) {
  const config: BridgeConfig = {
    nubaseUrl: 'http://localhost:9999',
    projectKey: 'service-role-key',
    allowSqlExecute: false,
    allowDangerousSql: false,
    allowAdminWrite: false,
    ...overrides,
  };
  const calls: CapturedRequest[] = [];
  const originalFetch = globalThis.fetch;
  globalThis.fetch = (async (input: Parameters<typeof fetch>[0], init?: RequestInit) => {
    calls.push({
      url: String(input),
      method: init?.method || 'GET',
      headers: Object.fromEntries(new Headers(init?.headers).entries()),
      body: init?.body ? JSON.parse(String(init.body)) : undefined,
    });
    return new Response(JSON.stringify({ ok: true }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    });
  }) as typeof fetch;
  const restore = () => {
    globalThis.fetch = originalFetch;
  };
  return { client: new NubaseClient(config), calls, restore };
}

test('read ops build query strings and never require admin write', async () => {
  const { client, calls, restore } = makeClient();
  try {
    await client.storageListBuckets({ search: 'avatars', limit: 5 });
    await client.authListUsers({ page: 2, perPage: 10, keyword: 'admin' });
    await client.gatewayUsage({ startDate: '2026-06-01', endDate: '2026-06-03' });
  } finally {
    restore();
  }

  assert.equal(nth(calls, 0).url, 'http://localhost:9999/storage/v1/bucket?search=avatars&limit=5');
  assert.equal(nth(calls, 0).method, 'GET');
  assert.equal(nth(calls, 1).url, 'http://localhost:9999/auth/v1/admin/users?page=2&per_page=10&keyword=admin');
  assert.equal(nth(calls, 2).url, 'http://localhost:9999/ai-gateway/admin/v1/usage/overview?start_date=2026-06-01&end_date=2026-06-03');
});

test('write ops are blocked unless admin write is enabled', async () => {
  const { client, calls, restore } = makeClient({ allowAdminWrite: false });
  try {
    const result = (await client.storageCreateBucket({ name: 'uploads' })) as {
      success: boolean;
      code: string;
      remedy: string;
    };
    assert.equal(result.success, false);
    assert.equal(result.code, 'PERMISSION_GATE_OFF');
    assert.match(result.remedy, /NUBASE_ALLOW_ADMIN_WRITE/);
    assert.equal(calls.length, 0, 'blocked write must not hit the network');
  } finally {
    restore();
  }
});

test('write ops call the admin API when enabled', async () => {
  const { client, calls, restore } = makeClient({ allowAdminWrite: true });
  try {
    await client.storageCreateBucket({ name: 'uploads', public: true, fileSizeLimit: 1024 });
    await client.authDeleteUser({ userId: 'u-1', softDelete: true });
    await client.gatewayIssueKey({ name: 'ci' });
  } finally {
    restore();
  }

  assert.equal(nth(calls, 0).method, 'POST');
  assert.equal(nth(calls, 0).url, 'http://localhost:9999/storage/v1/bucket');
  assert.deepEqual(nth(calls, 0).body, { name: 'uploads', public: true, file_size_limit: 1024 });
  assert.equal(nth(calls, 1).method, 'DELETE');
  assert.equal(nth(calls, 1).url, 'http://localhost:9999/auth/v1/admin/users/u-1?should_soft_delete=true');
  assert.equal(nth(calls, 2).url, 'http://localhost:9999/ai-gateway/admin/v1/keys');
  assert.deepEqual(nth(calls, 2).body, { name: 'ci' });
});

test('storage object helpers call list and signed URL endpoints', async () => {
  const { client, calls, restore } = makeClient({ allowAdminWrite: true });
  try {
    await client.storageListObjects({
      bucketId: 'avatars',
      prefix: 'users/',
      limit: 20,
      sortBy: { column: 'name', order: 'asc' },
    });
    await client.storageCreateSignedUrl({ bucketId: 'avatars', path: 'users/a b.png', expiresIn: 60 });
    await client.storageCreateSignedUrls({ bucketId: 'avatars', paths: ['a.png', 'b.png'], expiresIn: 120 });
    await client.storageCreateSignedUploadUrl({ bucketId: 'avatars', path: 'incoming/photo.png', upsert: true });
  } finally {
    restore();
  }

  assert.equal(nth(calls, 0).method, 'POST');
  assert.equal(nth(calls, 0).url, 'http://localhost:9999/storage/v1/object/list/avatars');
  assert.deepEqual(nth(calls, 0).body, {
    prefix: 'users/',
    limit: 20,
    sortBy: { column: 'name', order: 'asc' },
  });
  assert.equal(nth(calls, 1).url, 'http://localhost:9999/storage/v1/object/sign/avatars/users/a%20b.png');
  assert.deepEqual(nth(calls, 1).body, { expiresIn: 60 });
  assert.equal(nth(calls, 2).url, 'http://localhost:9999/storage/v1/object/sign/avatars');
  assert.deepEqual(nth(calls, 2).body, { paths: ['a.png', 'b.png'], expiresIn: 120 });
  assert.equal(nth(calls, 3).url, 'http://localhost:9999/storage/v1/object/upload/sign/avatars/incoming/photo.png');
  assert.equal(nth(calls, 3).headers['x-upsert'], 'true');
});

test('auth settings helpers call tenant settings endpoints', async () => {
  const { client, calls, restore } = makeClient({ allowAdminWrite: true });
  try {
    await client.authGetSettings();
    await client.authUpdateSettings({ settings: { signupEnabled: false, jwtExpirySeconds: 3600 } });
    await client.authClearSettings();
  } finally {
    restore();
  }

  assert.equal(nth(calls, 0).method, 'GET');
  assert.equal(nth(calls, 0).url, 'http://localhost:9999/auth/v1/admin/settings/auth');
  assert.equal(nth(calls, 1).method, 'PUT');
  assert.deepEqual(nth(calls, 1).body, { signupEnabled: false, jwtExpirySeconds: 3600 });
  assert.equal(nth(calls, 2).method, 'DELETE');
});

test('gateway usage detail and pricing helpers call reporting endpoints', async () => {
  const { client, calls, restore } = makeClient();
  try {
    await client.gatewayUsageDaily({ apiKeyId: 'key-1', startDate: '2026-06-01', endDate: '2026-06-02' });
    await client.gatewayUsageByModel({ startDate: '2026-06-01', endDate: '2026-06-02' });
    await client.gatewayUsageLogs({ apiKeyId: 'key-1', page: 1, size: 25 });
    await client.gatewayPricing({});
    await client.gatewayPricing({ all: true });
  } finally {
    restore();
  }

  assert.equal(nth(calls, 0).url, 'http://localhost:9999/ai-gateway/admin/v1/usage/daily?api_key_id=key-1&start_date=2026-06-01&end_date=2026-06-02');
  assert.equal(nth(calls, 1).url, 'http://localhost:9999/ai-gateway/admin/v1/usage/by-model?start_date=2026-06-01&end_date=2026-06-02');
  assert.equal(nth(calls, 2).url, 'http://localhost:9999/ai-gateway/admin/v1/usage/logs?api_key_id=key-1&page=1&size=25');
  assert.equal(nth(calls, 3).url, 'http://localhost:9999/ai-gateway/admin/v1/pricing');
  assert.equal(nth(calls, 4).url, 'http://localhost:9999/ai-gateway/admin/v1/pricing/all');
});

test('db_export_schema defaults to the public schema', async () => {
  const { client, calls, restore } = makeClient();
  try {
    await client.dbExportSchema({});
  } finally {
    restore();
  }
  assert.equal(nth(calls, 0).url, 'http://localhost:9999/auth/v1/admin/schema/export-ddl');
  assert.deepEqual(nth(calls, 0).body, { schemaName: 'public', includeDropStatements: false });
});

test('sql_execute records a schema change to the nubase.migrations audit trail', async () => {
  const { client, calls, restore } = makeClient({ allowSqlExecute: true, agentId: 'codex', runId: 'run-7' });
  let result: Record<string, any>;
  try {
    result = (await client.sqlExecute({ sql: "create table todos(id bigint); -- it's fine" })) as Record<string, any>;
  } finally {
    restore();
  }

  assert.equal(calls.length, 2, 'one call for the DDL, one for the audit insert');
  assert.equal(nth(calls, 0).url, 'http://localhost:9999/auth/v1/admin/sql/execute');
  const audit = String((nth(calls, 1).body as { query: string }).query);
  assert.match(audit, /create table if not exists nubase\.migrations/);
  assert.match(audit, /insert into nubase\.migrations/);
  assert.match(audit, /'SCHEMA_WRITE'/);
  assert.match(audit, /'codex'/);
  assert.match(audit, /'run-7'/);
  // single quotes in the recorded SQL must be doubled, not break the literal
  assert.match(audit, /it''s fine/);
  assert.equal(result.migrationRecorded, true);
});

test('sql_execute does not record pure data writes', async () => {
  const { client, calls, restore } = makeClient({ allowSqlExecute: true });
  try {
    await client.sqlExecute({ sql: "insert into todos(title) values ('ship')" });
  } finally {
    restore();
  }
  assert.equal(calls.length, 1, 'DATA_WRITE is not a migration; no audit insert');
});

test('recordMigrations=false disables the audit trail', async () => {
  const { client, calls, restore } = makeClient({ allowSqlExecute: true, recordMigrations: false });
  try {
    await client.sqlExecute({ sql: 'create table todos(id bigint)' });
  } finally {
    restore();
  }
  assert.equal(calls.length, 1, 'recording disabled: only the DDL call');
});

test('a failing audit insert does not fail the execute', async () => {
  const config: BridgeConfig = {
    nubaseUrl: 'http://localhost:9999',
    projectKey: 'service-role-key',
    allowSqlExecute: true,
    allowDangerousSql: false,
    allowAdminWrite: false,
  };
  const originalFetch = globalThis.fetch;
  let call = 0;
  globalThis.fetch = (async () => {
    call += 1;
    if (call === 2) return new Response('audit boom', { status: 500 });
    return new Response(JSON.stringify({ ok: true }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    });
  }) as typeof fetch;

  let result: Record<string, any>;
  try {
    result = (await new NubaseClient(config).sqlExecute({ sql: 'create table todos(id bigint)' })) as Record<string, any>;
  } finally {
    globalThis.fetch = originalFetch;
  }
  assert.equal(result.migrationRecorded, false);
  assert.match(result.migrationError, /audit boom/);
  assert.equal(result.ok, true, 'the DDL itself still succeeded');
});

test('list_migrations returns an empty list when the table is absent', async () => {
  const config: BridgeConfig = {
    nubaseUrl: 'http://localhost:9999',
    projectKey: 'service-role-key',
    allowSqlExecute: false,
    allowDangerousSql: false,
    allowAdminWrite: false,
  };
  const originalFetch = globalThis.fetch;
  globalThis.fetch = (async () =>
    new Response('relation "nubase.migrations" does not exist', { status: 400 })) as typeof fetch;

  let result: Record<string, any>;
  try {
    result = (await new NubaseClient(config).listMigrations({})) as Record<string, any>;
  } finally {
    globalThis.fetch = originalFetch;
  }
  assert.deepEqual(result.migrations, []);
  assert.match(result.note, /No migrations recorded/);
});

test('functions_invoke returns the envelope for function 4xx/5xx instead of throwing', async () => {
  const config: BridgeConfig = {
    nubaseUrl: 'http://localhost:9999',
    projectKey: 'service-role-key',
    allowSqlExecute: false,
    allowDangerousSql: false,
    allowAdminWrite: false,
  };
  const originalFetch = globalThis.fetch;
  globalThis.fetch = (async () =>
    new Response(JSON.stringify({ error: 'invalid payload' }), {
      status: 422,
      headers: { 'Content-Type': 'application/json' },
    })) as typeof fetch;

  let result: Record<string, any>;
  try {
    result = (await new NubaseClient(config).functionsInvoke({
      slug: 'hello',
      method: 'POST',
      body: '{}',
    })) as Record<string, any>;
  } finally {
    globalThis.fetch = originalFetch;
  }
  assert.equal(result.status, 422);
  assert.deepEqual(result.data, { error: 'invalid payload' });
  assert.equal(result.headers['content-type'], 'application/json');
});

test('functions_invoke still throws on network-level failures', async () => {
  const config: BridgeConfig = {
    nubaseUrl: 'http://localhost:9999',
    projectKey: 'service-role-key',
    allowSqlExecute: false,
    allowDangerousSql: false,
    allowAdminWrite: false,
  };
  const originalFetch = globalThis.fetch;
  globalThis.fetch = (async () => {
    throw new TypeError('fetch failed');
  }) as typeof fetch;
  try {
    await assert.rejects(
      () => new NubaseClient(config).functionsInvoke({ slug: 'hello' }),
      /fetch failed/
    );
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test('other client calls still throw on non-2xx responses', async () => {
  const config: BridgeConfig = {
    nubaseUrl: 'http://localhost:9999',
    projectKey: 'service-role-key',
    allowSqlExecute: false,
    allowDangerousSql: false,
    allowAdminWrite: false,
  };
  const originalFetch = globalThis.fetch;
  globalThis.fetch = (async () => new Response('forbidden', { status: 403 })) as typeof fetch;
  try {
    await assert.rejects(() => new NubaseClient(config).functionsList(), /forbidden/);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test('overview aggregates every read section in one call and echoes permissions', async () => {
  const { client, calls, restore } = makeClient({ allowSqlExecute: true, userJwt: 'jwt', agentId: 'codex' });
  let result: Record<string, any>;
  try {
    result = (await client.overview({})) as Record<string, any>;
  } finally {
    restore();
  }

  const urls = calls.map((c) => c.url);
  assert.ok(urls.includes('http://localhost:9999/agent/v1/capabilities'));
  assert.ok(urls.includes('http://localhost:9999/auth/v1/admin/schema/export-ddl'));
  assert.ok(urls.includes('http://localhost:9999/storage/v1/bucket?limit=100'));
  assert.ok(urls.includes('http://localhost:9999/auth/v1/admin/users?per_page=1'));
  assert.ok(urls.includes('http://localhost:9999/ai-gateway/admin/v1/keys'));

  assert.equal(result.database.schema, 'public');
  assert.equal(result.permissions.sqlExecute, true);
  assert.equal(result.project.userScoped, true);
  assert.equal(result.project.agentId, 'codex');
  assert.ok(Array.isArray(result.nextSteps) && result.nextSteps.length > 0);
});

test('overview degrades a failing section to { error } without dropping the rest', async () => {
  const config: BridgeConfig = {
    nubaseUrl: 'http://localhost:9999',
    projectKey: 'service-role-key',
    allowSqlExecute: false,
    allowDangerousSql: false,
    allowAdminWrite: false,
  };
  const originalFetch = globalThis.fetch;
  globalThis.fetch = (async (input: Parameters<typeof fetch>[0]) => {
    if (String(input).includes('/storage/v1/bucket')) {
      return new Response('forbidden', { status: 403 });
    }
    return new Response(JSON.stringify({ ok: true }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    });
  }) as typeof fetch;

  let result: Record<string, any>;
  try {
    result = (await new NubaseClient(config).overview({})) as Record<string, any>;
  } finally {
    globalThis.fetch = originalFetch;
  }

  assert.ok('error' in result.storage, 'failing storage section should carry an error');
  assert.deepEqual(result.capabilities, { ok: true }, 'other sections still resolve');
});

test('deployment client methods call the deployment control plane', async () => {
  const { client, calls, restore } = makeClient({ allowAdminWrite: true });
  try {
    await client.deploymentCreate({ appName: 'notes' });
    await client.deploymentRecordStep({
      deploymentId: 'dep-1',
      stepOrder: 1,
      stepName: 'assets_upload',
      status: 'succeeded',
    });
    await client.deploymentComplete({ deploymentId: 'dep-1', status: 'succeeded', publicUrl: 'https://app.example' });
    await client.deploymentsList({ limit: 10 });
    await client.deploymentStatus({ id: 'dep-1' });
    await client.deploymentLogs({ id: 'dep-1' });
    await client.deploymentRollback({ id: 'dep-1' });
  } finally {
    restore();
  }

  assert.equal(nth(calls, 0).url, 'http://localhost:9999/deployments/admin/v1/deployments');
  assert.equal(nth(calls, 0).method, 'POST');
  assert.deepEqual(nth(calls, 0).body, { appName: 'notes' });
  assert.equal(nth(calls, 1).url, 'http://localhost:9999/deployments/admin/v1/deployments/dep-1/steps');
  assert.equal(nth(calls, 2).url, 'http://localhost:9999/deployments/admin/v1/deployments/dep-1/complete');
  assert.equal(nth(calls, 3).url, 'http://localhost:9999/deployments/admin/v1/deployments?limit=10');
  assert.equal(nth(calls, 4).url, 'http://localhost:9999/deployments/admin/v1/deployments/dep-1');
  assert.equal(nth(calls, 5).url, 'http://localhost:9999/deployments/admin/v1/deployments/dep-1/logs');
  assert.equal(nth(calls, 6).method, 'POST');
  assert.equal(nth(calls, 6).url, 'http://localhost:9999/deployments/admin/v1/deployments/dep-1/rollback');
});

test('deployment rollback is blocked unless admin write is enabled', async () => {
  const { client, calls, restore } = makeClient({ allowAdminWrite: false });
  try {
    const result = (await client.deploymentRollback({ id: 'dep-1' })) as {
      success: boolean;
      code: string;
    };
    assert.equal(result.success, false);
    assert.equal(result.code, 'PERMISSION_GATE_OFF');
    assert.equal(calls.length, 0);
  } finally {
    restore();
  }
});

test('project lifecycle tools require platform auth', async () => {
  const { client, calls, restore } = makeClient();
  try {
    const result = (await client.projectsList()) as Record<string, any>;
    assert.equal(result.success, false);
    assert.equal(result.code, 'PLATFORM_AUTH_REQUIRED');
    assert.equal(calls.length, 0);
  } finally {
    restore();
  }
});

test('project lifecycle tools use platform key or jwt auth', async () => {
  const config: BridgeConfig = {
    nubaseUrl: 'http://localhost:9999',
    projectKey: 'service-role-key',
    platformKey: 'metadata-key',
    allowSqlExecute: false,
    allowDangerousSql: false,
    allowAdminWrite: true,
  };
  const seen: Array<{ url: string; method: string; headers: Record<string, string>; body?: unknown }> = [];
  const originalFetch = globalThis.fetch;
  globalThis.fetch = (async (input: Parameters<typeof fetch>[0], init?: RequestInit) => {
    seen.push({
      url: String(input),
      method: init?.method || 'GET',
      headers: Object.fromEntries(new Headers(init?.headers).entries()),
      body: init?.body ? JSON.parse(String(init.body)) : undefined,
    });
    return new Response(JSON.stringify({ ok: true }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    });
  }) as typeof fetch;
  try {
    const client = new NubaseClient(config);
    await client.projectsList();
    await client.projectKeysAdmin({ ref: 'proj_1' });
    await client.projectProvision({ ref: 'proj_1' });
    await client.projectUpdate({ ref: 'proj_1', appName: 'Notes', enabled: true });
  } finally {
    globalThis.fetch = originalFetch;
  }

  assert.equal(seen[0]?.url, 'http://localhost:9999/auth/v1/admin/projects');
  assert.equal(seen[0]?.headers.apikey, 'metadata-key');
  assert.equal(seen[1]?.url, 'http://localhost:9999/auth/v1/admin/projects/proj_1/keys');
  assert.equal(seen[2]?.method, 'POST');
  assert.equal(seen[2]?.url, 'http://localhost:9999/auth/v1/admin/projects/proj_1/provision');
  assert.equal(seen[3]?.method, 'PATCH');
  assert.deepEqual(seen[3]?.body, { appName: 'Notes', enabled: true });
});

test('project lifecycle write tools are gated by admin write', async () => {
  const { client, calls, restore } = makeClient({ platformKey: 'metadata-key', allowAdminWrite: false });
  try {
    const result = (await client.projectProvision({ ref: 'proj_1' })) as Record<string, any>;
    assert.equal(result.success, false);
    assert.equal(result.code, 'PERMISSION_GATE_OFF');
    assert.equal(calls.length, 0);
  } finally {
    restore();
  }
});
