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

// Capture fetch calls and reply with a canned AssetFileDTO-shaped response.
function stubFetch(seen: Array<{ url: string; method?: string; body?: unknown; contentType?: string }>) {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = (async (input: Parameters<typeof fetch>[0], init?: RequestInit) => {
    const headers = new Headers(init?.headers);
    seen.push({ url: String(input), method: init?.method, body: init?.body, contentType: headers.get('Content-Type') ?? undefined });
    return new Response(JSON.stringify({ path: 'index.html', publicUrl: 'https://app.example/assets/v1/index.html' }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    });
  }) as typeof fetch;
  return () => {
    globalThis.fetch = originalFetch;
  };
}

test('MCP tool list includes asset tools', () => {
  const names = new Set(TOOLS.map((tool) => tool.name));
  for (const name of ['assets_list', 'assets_upload', 'assets_delete', 'assets_update_settings']) {
    assert.equal(names.has(name), true, `missing ${name}`);
  }
});

test('assets_update_settings PATCHes settings when admin writes are on', async () => {
  const toolConfig = config({ allowAdminWrite: true });
  const seen: Array<{ url: string; method?: string; body?: unknown }> = [];
  const originalFetch = globalThis.fetch;
  globalThis.fetch = (async (input: Parameters<typeof fetch>[0], init?: RequestInit) => {
    seen.push({
      url: String(input),
      method: init?.method,
      body: init?.body ? JSON.parse(String(init.body)) : undefined,
    });
    return new Response(JSON.stringify({ spaFallbackPath: 'index.html' }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    });
  }) as typeof fetch;
  try {
    await callTool('assets_update_settings', { spaFallbackPath: 'index.html' }, toolConfig, new NubaseClient(toolConfig));
  } finally {
    globalThis.fetch = originalFetch;
  }
  assert.equal(seen[0]?.method, 'PATCH');
  assert.equal(seen[0]?.url, 'http://localhost:9999/assets/admin/v1/settings');
  assert.deepEqual(seen[0]?.body, { spaFallbackPath: 'index.html' });
});

test('assets_update_settings refuses with PERMISSION_GATE_OFF when admin writes are off', async () => {
  const toolConfig = config({ allowAdminWrite: false });
  const seen: any[] = [];
  const restore = stubFetch(seen);
  let result: Record<string, any>;
  try {
    result = (await callTool('assets_update_settings', { spaFallbackPath: 'index.html' }, toolConfig, new NubaseClient(toolConfig))) as Record<string, any>;
  } finally {
    restore();
  }
  assert.equal(result.success, false);
  assert.equal(result.code, 'PERMISSION_GATE_OFF');
  assert.equal(seen.length, 0);
});

test('assets_upload refuses with PERMISSION_GATE_OFF when admin writes are off', async () => {
  const toolConfig = config({ allowAdminWrite: false });
  const seen: any[] = [];
  const restore = stubFetch(seen);
  let result: Record<string, any>;
  try {
    result = (await callTool('assets_upload', { path: 'index.html', content: '<h1>hi</h1>' }, toolConfig, new NubaseClient(toolConfig))) as Record<string, any>;
  } finally {
    restore();
  }
  assert.equal(result.success, false);
  assert.equal(result.code, 'PERMISSION_GATE_OFF');
  assert.equal(seen.length, 0, 'gated upload must not hit the network');
});

test('assets_upload requires exactly one of content or contentBase64', async () => {
  const toolConfig = config({ allowAdminWrite: true });
  const seen: any[] = [];
  const restore = stubFetch(seen);
  try {
    await assert.rejects(
      callTool('assets_upload', { path: 'index.html' }, toolConfig, new NubaseClient(toolConfig)),
      /exactly one of content/
    );
    await assert.rejects(
      callTool('assets_upload', { path: 'index.html', content: 'a', contentBase64: 'Yg==' }, toolConfig, new NubaseClient(toolConfig)),
      /exactly one of content/
    );
  } finally {
    restore();
  }
  assert.equal(seen.length, 0, 'invalid upload must not hit the network');
});

test('assets_upload rejects malformed contentBase64 instead of uploading corrupt bytes', async () => {
  const toolConfig = config({ allowAdminWrite: true });
  const seen: any[] = [];
  const restore = stubFetch(seen);
  try {
    // A data-URL prefix left on the value is the common agent mistake.
    await assert.rejects(
      callTool('assets_upload', { path: 'logo.png', contentBase64: 'data:image/png;base64,iVBORw0KGgo=' }, toolConfig, new NubaseClient(toolConfig)),
      /not valid base64/
    );
  } finally {
    restore();
  }
  assert.equal(seen.length, 0, 'invalid base64 must not hit the network');
});

test('assets_upload PUTs text content with an inferred content-type and public URL', async () => {
  const toolConfig = config({ allowAdminWrite: true });
  const seen: Array<{ url: string; method?: string; body?: unknown; contentType?: string }> = [];
  const restore = stubFetch(seen);
  let result: Record<string, any>;
  try {
    result = (await callTool('assets_upload', { path: 'css/app.css', content: 'body{}' }, toolConfig, new NubaseClient(toolConfig))) as Record<string, any>;
  } finally {
    restore();
  }
  assert.equal(seen[0]?.method, 'PUT');
  // Slashes in the path survive; only segments are encoded.
  assert.equal(seen[0]?.url, 'http://localhost:9999/assets/admin/v1/files/css/app.css');
  assert.match(String(seen[0]?.contentType), /text\/css/);
  assert.equal(result.publicUrl, 'https://app.example/assets/v1/index.html');
});

test('assets_upload with upsert:false POSTs and encodes path segments', async () => {
  const toolConfig = config({ allowAdminWrite: true });
  const seen: Array<{ url: string; method?: string }> = [];
  const restore = stubFetch(seen);
  try {
    await callTool('assets_upload', { path: '/my dir/a b.html', content: 'x', upsert: false }, toolConfig, new NubaseClient(toolConfig));
  } finally {
    restore();
  }
  assert.equal(seen[0]?.method, 'POST');
  assert.equal(seen[0]?.url, 'http://localhost:9999/assets/admin/v1/files/my%20dir/a%20b.html');
});

test('assets_delete DELETEs the encoded asset path', async () => {
  const toolConfig = config({ allowAdminWrite: true });
  const seen: Array<{ url: string; method?: string }> = [];
  const restore = stubFetch(seen);
  try {
    await callTool('assets_delete', { path: 'css/old.css' }, toolConfig, new NubaseClient(toolConfig));
  } finally {
    restore();
  }
  assert.equal(seen[0]?.method, 'DELETE');
  assert.equal(seen[0]?.url, 'http://localhost:9999/assets/admin/v1/files/css/old.css');
});

test('assets_list GETs the files endpoint with query filters (read-only, ungated)', async () => {
  const toolConfig = config({ allowAdminWrite: false });
  const seen: Array<{ url: string; method?: string }> = [];
  const restore = stubFetch(seen);
  try {
    await callTool('assets_list', { prefix: 'css/', limit: 10 }, toolConfig, new NubaseClient(toolConfig));
  } finally {
    restore();
  }
  assert.match(seen[0]?.url ?? '', /\/assets\/admin\/v1\/files\?/);
  assert.match(seen[0]?.url ?? '', /prefix=css/);
  assert.match(seen[0]?.url ?? '', /limit=10/);
});
