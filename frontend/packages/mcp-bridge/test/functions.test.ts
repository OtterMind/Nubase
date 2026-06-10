import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import type { BridgeConfig } from '../src/config.js';
import { parseFunctionArgs, runFunctionsCommand } from '../src/functions.js';

test('parseFunctionArgs separates positional and options', () => {
  const parsed = parseFunctionArgs(['hello', '--method', 'POST', '--privileged']);
  assert.deepEqual(parsed.positional, ['hello']);
  assert.deepEqual(parsed.options, { method: 'POST', privileged: true });
});

test('functions new scaffolds a function directory', async () => {
  const dir = await mkdtemp(path.join(os.tmpdir(), 'nubase-functions-'));
  const cwd = process.cwd();
  try {
    process.chdir(dir);
    const result = await runFunctionsCommand(['new', 'hello'], config(), fakeClient()) as Record<string, any>;
    assert.equal(result.ok, true);
    const source = await readFile(path.join(dir, 'nubase/functions/hello/index.js'), 'utf8');
    assert.match(source, /Response\.json/);
    const manifest = await readFile(path.join(dir, 'nubase/functions/hello/nubase-function.json'), 'utf8');
    assert.match(manifest, /"verifyJwt": true/);
  } finally {
    process.chdir(cwd);
    await rm(dir, { recursive: true, force: true });
  }
});

test('functions deploy creates and deploys through client', async () => {
  const dir = await mkdtemp(path.join(os.tmpdir(), 'nubase-functions-'));
  const cwd = process.cwd();
  const calls: string[] = [];
  try {
    process.chdir(dir);
    await runFunctionsCommand(['new', 'hello'], config(), fakeClient());
    await runFunctionsCommand(['deploy', 'hello'], config(), fakeClient(calls));
  } finally {
    process.chdir(cwd);
    await rm(dir, { recursive: true, force: true });
  }
  assert.deepEqual(calls, ['create:hello', 'deploy:hello']);
});

test('functions deploy --bundle uploads a single bundled entrypoint', async () => {
  const dir = await mkdtemp(path.join(os.tmpdir(), 'nubase-functions-'));
  const cwd = process.cwd();
  let uploadedBundle = '';
  try {
    process.chdir(dir);
    await runFunctionsCommand(['new', 'hello'], config(), fakeClient());
    await writeFile(
      path.join(dir, 'nubase/functions/hello/helper.js'),
      'export const message = "bundled";\n'
    );
    await writeFile(
      path.join(dir, 'nubase/functions/hello/index.js'),
      'import { message } from "./helper.js"; export default { fetch() { return new Response(message); } };\n'
    );
    await runFunctionsCommand(['deploy', 'hello', '--bundle'], config(), fakeClient([], (bundle) => {
      uploadedBundle = bundle;
    }));
  } finally {
    process.chdir(cwd);
    await rm(dir, { recursive: true, force: true });
  }
  const payload = JSON.parse(Buffer.from(uploadedBundle, 'base64').toString('utf8'));
  assert.equal(payload.files.length, 1);
  assert.equal(payload.files[0].path, 'index.js');
  const source = Buffer.from(payload.files[0].content, 'base64').toString('utf8');
  assert.match(source, /bundled/);
});

test('functions secrets set parses KEY=value assignments', async () => {
  const calls: string[] = [];
  await runFunctionsCommand(['secrets', 'set', 'hello', 'API_KEY=value', 'TOKEN=abc'], config(), fakeClient(calls));
  assert.deepEqual(calls, ['secrets:hello:API_KEY,TOKEN']);
});

function config(): BridgeConfig {
  return {
    nubaseUrl: 'http://localhost:9999',
    projectKey: 'service-role',
    allowSqlExecute: false,
    allowDangerousSql: false,
    allowAdminWrite: true,
  };
}

function fakeClient(calls: string[] = [], onDeploy?: (sourceBundleBase64: string) => void) {
  return {
    functionsList: async () => [],
    functionsCreate: async (args: Record<string, unknown>) => {
      calls.push(`create:${args.slug}`);
      return { ok: true };
    },
    functionsDeploy: async (args: Record<string, unknown>) => {
      calls.push(`deploy:${args.slug}`);
      assert.equal(typeof args.sourceHash, 'string');
      assert.equal(typeof args.sourceBundleBase64, 'string');
      onDeploy?.(String(args.sourceBundleBase64));
      return { ok: true };
    },
    functionsInvoke: async () => ({ ok: true }),
    functionsDelete: async (args: Record<string, unknown>) => {
      calls.push(`delete:${args.slug}`);
      return { ok: true };
    },
    functionsLogs: async () => [],
    functionsListSecrets: async () => [],
    functionsSetSecrets: async (args: Record<string, any>) => {
      calls.push(`secrets:${args.slug}:${Object.keys(args.secrets).sort().join(',')}`);
      return { ok: true };
    },
  } as any;
}
