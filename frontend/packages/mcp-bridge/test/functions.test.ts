import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdir, mkdtemp, readFile, rm, symlink, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import type { BridgeConfig } from '../src/config.js';
import { parseFunctionArgs, resolveExitCode, runFunctionsCommand } from '../src/functions.js';

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

test('functions deploy auto-bundles a TypeScript entrypoint', async () => {
  const dir = await mkdtemp(path.join(os.tmpdir(), 'nubase-functions-'));
  const cwd = process.cwd();
  let uploadedBundle = '';
  try {
    process.chdir(dir);
    const fnDir = path.join(dir, 'nubase/functions/hello');
    await mkdir(fnDir, { recursive: true });
    await writeFile(
      path.join(fnDir, 'index.ts'),
      'interface Env { NUBASE_PROJECT_REF: string }\n'
        + 'export default { async fetch(req: Request, env: Env): Promise<Response> { return new Response(env.NUBASE_PROJECT_REF); } };\n'
    );
    await runFunctionsCommand(['deploy', 'hello'], config(), fakeClient([], (bundle) => {
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
  assert.doesNotMatch(source, /interface Env|: Promise<Response>/);
  assert.match(source, /NUBASE_PROJECT_REF/);
});

test('functions deploy reads manifest entrypoint and updates existing function metadata', async () => {
  const dir = await mkdtemp(path.join(os.tmpdir(), 'nubase-functions-'));
  const cwd = process.cwd();
  const calls: string[] = [];
  let uploadedBundle = '';
  try {
    process.chdir(dir);
    const fnDir = path.join(dir, 'nubase/functions/hello');
    await mkdir(fnDir, { recursive: true });
    await writeFile(path.join(fnDir, 'main.ts'), 'export default { fetch() { return new Response("ok"); } };\n');
    await writeFile(path.join(fnDir, 'nubase-function.json'), JSON.stringify({
      name: 'Hello Fn',
      entrypoint: 'main.ts',
      verifyJwt: false,
      privileged: true,
    }));
    await runFunctionsCommand(['deploy', 'hello'], config(), fakeClient(calls, (bundle) => {
      uploadedBundle = bundle;
    }, true));
  } finally {
    process.chdir(cwd);
    await rm(dir, { recursive: true, force: true });
  }
  assert.deepEqual(calls, ['create:hello', 'update:hello:main.ts:false:undefined', 'deploy:hello']);
  const payload = JSON.parse(Buffer.from(uploadedBundle, 'base64').toString('utf8'));
  assert.equal(payload.files[0].path, 'index.js');
});

test('functions deploy --no-bundle uploads the raw TypeScript directory', async () => {
  const dir = await mkdtemp(path.join(os.tmpdir(), 'nubase-functions-'));
  const cwd = process.cwd();
  let uploadedBundle = '';
  try {
    process.chdir(dir);
    const fnDir = path.join(dir, 'nubase/functions/hello');
    await mkdir(fnDir, { recursive: true });
    await writeFile(path.join(fnDir, 'index.ts'), 'export default { fetch() { return new Response("ok"); } };\n');
    await runFunctionsCommand(['deploy', 'hello', '--no-bundle'], config(), fakeClient([], (bundle) => {
      uploadedBundle = bundle;
    }));
  } finally {
    process.chdir(cwd);
    await rm(dir, { recursive: true, force: true });
  }
  const payload = JSON.parse(Buffer.from(uploadedBundle, 'base64').toString('utf8'));
  assert.deepEqual(payload.files.map((f: { path: string }) => f.path), ['index.ts']);
});

test('functions deploy prunes node_modules and .git before walking', async () => {
  const dir = await mkdtemp(path.join(os.tmpdir(), 'nubase-functions-'));
  const cwd = process.cwd();
  let uploadedBundle = '';
  try {
    process.chdir(dir);
    await runFunctionsCommand(['new', 'hello'], config(), fakeClient());
    const fnDir = path.join(dir, 'nubase/functions/hello');
    await mkdir(path.join(fnDir, 'node_modules/some-dep'), { recursive: true });
    await writeFile(path.join(fnDir, 'node_modules/some-dep/index.js'), 'module.exports = 1;\n');
    // A broken symlink inside node_modules: a walk that descends and stats
    // entries would throw ENOENT, so a passing deploy proves we pruned early.
    await symlink(path.join(fnDir, 'node_modules/missing-target'), path.join(fnDir, 'node_modules/broken'));
    await mkdir(path.join(fnDir, '.git'), { recursive: true });
    await writeFile(path.join(fnDir, '.git/HEAD'), 'ref: refs/heads/main\n');
    await writeFile(path.join(fnDir, 'helper.js'), 'export const ok = true;\n');
    await runFunctionsCommand(['deploy', 'hello'], config(), fakeClient([], (bundle) => {
      uploadedBundle = bundle;
    }));
  } finally {
    process.chdir(cwd);
    await rm(dir, { recursive: true, force: true });
  }
  const payload = JSON.parse(Buffer.from(uploadedBundle, 'base64').toString('utf8'));
  const paths = payload.files.map((file: { path: string }) => file.path);
  assert.deepEqual(paths.sort(), ['helper.js', 'index.js', 'nubase-function.json']);
});

test('resolveExitCode maps refusals to 1 and everything else to 0', () => {
  assert.equal(resolveExitCode({ success: false, code: 'PERMISSION_GATE_OFF' }), 1);
  assert.equal(resolveExitCode({ ok: true }), 0);
  assert.equal(resolveExitCode({ success: true }), 0);
  assert.equal(resolveExitCode([]), 0);
  assert.equal(resolveExitCode(null), 0);
  assert.equal(resolveExitCode(undefined), 0);
  assert.equal(resolveExitCode({ usage: ['nubase_cli functions list'] }), 0);
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

function fakeClient(calls: string[] = [], onDeploy?: (sourceBundleBase64: string) => void, createAlreadyExists = false) {
  return {
    functionsList: async () => [],
    functionsCreate: async (args: Record<string, unknown>) => {
      calls.push(`create:${args.slug}`);
      if (createAlreadyExists) throw new Error('FUNCTION_EXISTS');
      return { ok: true };
    },
    functionsUpdate: async (args: Record<string, unknown>) => {
      calls.push(`update:${args.slug}:${args.entrypoint}:${args.verifyJwt}:${args.privileged}`);
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
