import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, readFile, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import type { BridgeConfig } from '../src/config.js';
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

test('MCP tool list includes Edge Function tools', () => {
  const names = new Set(TOOLS.map((tool) => tool.name));
  assert.equal(names.has('functions_list'), true);
  assert.equal(names.has('functions_deploy'), true);
  assert.equal(names.has('functions_invoke'), true);
  assert.equal(names.has('functions_secrets_set'), true);
});

test('functions_deploy MCP tool reuses CLI bundling and deploy flow', async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), 'nubase-mcp-functions-'));
  const cwd = process.cwd();
  try {
    process.chdir(root);
    const fnDir = path.join(root, 'nubase/functions/hello');
    await mkdir(fnDir, { recursive: true });
    await writeFile(path.join(fnDir, 'helper.js'), 'export const message = "hi";\n');
    await writeFile(path.join(fnDir, 'index.js'), 'import { message } from "./helper.js";\nexport default { fetch: () => new Response(message) };\n');

    const calls: Array<{ op: string; args: Record<string, unknown> }> = [];
    const client = {
      functionsCreate: async (args: Record<string, unknown>) => {
        calls.push({ op: 'create', args });
        return { ok: true };
      },
      functionsDeploy: async (args: Record<string, unknown>) => {
        calls.push({ op: 'deploy', args });
        return { ok: true };
      },
    } as any;

    const result = await callTool('functions_deploy', { name: 'hello' }, config(), client);

    assert.deepEqual(result, { ok: true });
    assert.equal(calls[0]?.op, 'create');
    assert.equal(calls[1]?.op, 'deploy');
    const bundleJson = Buffer.from(String(calls[1]?.args.sourceBundleBase64), 'base64').toString('utf8');
    const bundle = JSON.parse(bundleJson);
    assert.deepEqual(bundle.files.map((file: { path: string }) => file.path).sort(), ['helper.js', 'index.js']);
    assert.equal(typeof calls[1]?.args.sourceHash, 'string');
  } finally {
    process.chdir(cwd);
  }
});

test('functions_invoke MCP tool calls the functions HTTP client', async () => {
  const calls: Record<string, unknown>[] = [];
  const client = {
    functionsInvoke: async (args: Record<string, unknown>) => {
      calls.push(args);
      return { status: 201, data: { ok: true } };
    },
  } as any;

  const result = await callTool('functions_invoke', {
    name: 'hello',
    method: 'POST',
    path: '/work',
    body: '{"ok":true}',
    contentType: 'application/json',
  }, config(), client);

  assert.deepEqual(result, { status: 201, data: { ok: true } });
  assert.deepEqual(calls[0], {
    slug: 'hello',
    method: 'POST',
    path: '/work',
    body: '{"ok":true}',
    contentType: 'application/json',
  });
});

test('functions_delete MCP tool keeps admin-write gate in the client', async () => {
  const client = {
    functionsDelete: async () => ({ success: false, code: 'PERMISSION_GATE_OFF' }),
  } as any;

  const result = await callTool('functions_delete', { name: 'hello' }, config({ allowAdminWrite: false }), client);

  assert.deepEqual(result, { success: false, code: 'PERMISSION_GATE_OFF' });
});

