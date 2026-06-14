import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdir, mkdtemp, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import type { BridgeConfig } from '../src/config.js';
import { deployApp, runDeployAppCommand } from '../src/deploy-app.js';
import { callTool, TOOLS } from '../src/tools.js';

function config(overrides: Partial<BridgeConfig> = {}): BridgeConfig {
  return {
    nubaseUrl: 'http://localhost:9999',
    projectKey: 'service-role-key',
    allowSqlExecute: true,
    allowDangerousSql: false,
    allowAdminWrite: true,
    ...overrides,
  };
}

test('MCP tool list includes deploy_app', () => {
  const names = new Set(TOOLS.map((tool) => tool.name));
  assert.equal(names.has('deploy_app'), true);
  assert.equal(names.has('deployments_list'), true);
  assert.equal(names.has('deployment_status'), true);
  assert.equal(names.has('deployment_logs'), true);
  assert.equal(names.has('deployment_rollback'), true);
  assert.equal(names.has('projects_list'), true);
  assert.equal(names.has('project_keys_admin'), true);
  assert.equal(names.has('project_provision'), true);
  assert.equal(names.has('project_update'), true);
  assert.equal(names.has('project_select_instructions'), true);
  assert.equal(names.has('storage_list_objects'), true);
  assert.equal(names.has('storage_create_signed_url'), true);
  assert.equal(names.has('storage_create_signed_urls'), true);
  assert.equal(names.has('storage_create_signed_upload_url'), true);
  assert.equal(names.has('auth_get_settings'), true);
  assert.equal(names.has('auth_update_settings'), true);
  assert.equal(names.has('auth_clear_settings'), true);
  assert.equal(names.has('gateway_usage_daily'), true);
  assert.equal(names.has('gateway_usage_by_model'), true);
  assert.equal(names.has('gateway_usage_logs'), true);
  assert.equal(names.has('gateway_pricing'), true);
});

test('deploy_app orchestrates migrations, functions, assets, cron, and memory', async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), 'nubase-deploy-app-'));
  await mkdir(path.join(root, 'dist/css'), { recursive: true });
  await mkdir(path.join(root, 'nubase/functions/api'), { recursive: true });
  await writeFile(path.join(root, 'schema.sql'), 'create table notes(id bigint);\n');
  await writeFile(path.join(root, 'dist/index.html'), '<!doctype html><h1>ok</h1>');
  await writeFile(path.join(root, 'dist/css/app.css'), 'body{}');
  await writeFile(path.join(root, 'nubase/functions/api/index.js'), 'export default { fetch() { return new Response("ok"); } };\n');

  const calls: Array<{ op: string; args?: Record<string, unknown> }> = [];
  const client = fakeClient(calls);
  const result = await deployApp({
    name: 'notes',
    migrations: [{ name: 'schema', file: 'schema.sql' }],
    functions: [{ name: 'api', dir: 'nubase/functions/api', verify: { method: 'POST', body: { ok: true } } }],
    assets: { dir: 'dist', cacheControl: 'public, max-age=3600' },
    cron: [{ name: 'nightly', cronExpression: '0 3 * * *', targetType: 'edge_function', functionSlug: 'api' }],
    rememberDeployment: true,
  }, config(), client, { baseDir: root }) as Record<string, any>;

  assert.equal(result.success, true);
  assert.equal(result.deploymentId, 'dep_1');
  assert.equal(result.publicUrl, 'https://assets.example/index.html');
  assert.deepEqual(calls.filter((call) => !call.op.startsWith('deployment')).map((call) => call.op), [
    'sqlExecute',
    'functionsUpdate',
    'functionsDeploy',
    'functionsInvoke',
    'assetsUpload',
    'assetsUpload',
    'cronCreateJob',
    'memoryWrite',
  ]);
  assert.equal(calls[0]?.op, 'deploymentCreate');
  assert.equal(calls.at(-1)?.op, 'deploymentComplete');
  assert.equal(calls.filter((call) => call.op === 'deploymentRecordStep').length, 7);
  const business = calls.filter((call) => !call.op.startsWith('deployment'));
  assert.match(String(business[0]?.args?.sql), /create table notes/);
  assert.equal(business[3]?.args?.body, '{"ok":true}');
  assert.equal(business[4]?.args?.path, 'css/app.css');
  assert.equal(business[5]?.args?.path, 'index.html');
});

test('deploy_app can publish assets as a release with manifest and SPA fallback', async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), 'nubase-deploy-release-'));
  await mkdir(path.join(root, 'dist'), { recursive: true });
  await writeFile(path.join(root, 'dist/index.html'), '<!doctype html><div id="root"></div>');
  await writeFile(path.join(root, 'dist/app.js'), 'console.log("ok");');

  const calls: Array<{ op: string; args?: Record<string, unknown> }> = [];
  const result = await deployApp({
    name: 'spa',
    assets: {
      dir: 'dist',
      release: true,
      releaseId: 'v1',
      spaFallback: true,
    },
  }, config(), fakeClient(calls), { baseDir: root }) as Record<string, any>;

  assert.equal(result.releaseId, 'v1');
  assert.equal(result.releasePrefix, '__releases/spa/v1');
  assert.equal(result.publicUrl, 'https://assets.example/__releases/spa/v1/index.html');
  const uploads = calls.filter((call) => call.op === 'assetsUpload');
  assert.deepEqual(uploads.map((call) => call.args?.path).sort(), [
    '__releases/spa/v1/__nubase_release.json',
    '__releases/spa/v1/app.js',
    '__releases/spa/v1/index.html',
  ]);
  const releaseManifest = uploads.find((call) => call.args?.path === '__releases/spa/v1/__nubase_release.json');
  assert.match(String(releaseManifest?.args?.content), /"releaseId": "v1"/);
  const settings = calls.find((call) => call.op === 'assetsUpdateSettings');
  assert.equal(settings?.args?.spaFallbackPath, '__releases/spa/v1/index.html');
});

test('deploy_app blocks asset secrets before upload', async () => {
  const calls: Array<{ op: string; args?: Record<string, unknown> }> = [];
  await assert.rejects(
    () => deployApp({
      assets: {
        files: [{
          path: 'index.html',
          content: '<script>const key = "sk-123456789012345678901234567890";</script>',
        }],
      },
    }, config(), fakeClient(calls)),
    /Security scan blocked upload/
  );
  assert.deepEqual(calls.filter((call) => call.op === 'assetsUpload'), []);
});

test('deploy_app can explicitly disable security scan', async () => {
  const calls: Array<{ op: string; args?: Record<string, unknown> }> = [];
  const result = await deployApp({
    securityScan: false,
    assets: {
      files: [{
        path: 'index.html',
        content: '<script>const key = "sk-123456789012345678901234567890";</script>',
      }],
    },
  }, config(), fakeClient(calls)) as Record<string, any>;

  assert.equal(result.success, true);
  assert.equal(calls.some((call) => call.op === 'assetsUpload'), true);
});

test('deploy_app can continue after an explicit permission-gate failure', async () => {
  const calls: Array<{ op: string; args?: Record<string, unknown> }> = [];
  const client = {
    ...fakeClient(calls),
    sqlExecute: async (args: Record<string, unknown>) => {
      calls.push({ op: 'sqlExecute', args });
      return { success: false, code: 'PERMISSION_GATE_OFF' };
    },
  } as any;

  const result = await deployApp({
    migrations: [{ name: 'schema', sql: 'create table notes(id bigint)' }],
    cron: [{ name: 'nightly', cronExpression: '0 3 * * *', targetType: 'db_function', dbFunctionName: 'tick' }],
  }, config(), client, { continueOnError: true, verifyFunctions: false }) as Record<string, any>;

  assert.equal(result.success, false);
  assert.equal(result.failedSteps, 1);
  assert.deepEqual(calls.filter((call) => !call.op.startsWith('deployment')).map((call) => call.op), ['sqlExecute', 'cronCreateJob']);
  assert.equal(calls.at(-1)?.op, 'deploymentComplete');
  assert.equal(calls.at(-1)?.args?.status, 'failed');
});

test('deploy_app CLI loads a manifest relative to its file', async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), 'nubase-deploy-app-'));
  await writeFile(path.join(root, 'schema.sql'), 'create table cli_notes(id bigint);\n');
  const manifestPath = path.join(root, 'nubase.deploy.json');
  await writeFile(manifestPath, JSON.stringify({
    migrations: [{ name: 'schema', file: 'schema.sql' }],
  }));
  const calls: Array<{ op: string; args?: Record<string, unknown> }> = [];

  const result = await runDeployAppCommand(['deploy', manifestPath], config(), fakeClient(calls)) as Record<string, unknown>;

  assert.equal(result.success, true);
  assert.match(String(calls.find((call) => call.op === 'sqlExecute')?.args?.sql), /cli_notes/);
});

test('deploy_app MCP tool dispatches to the orchestrator', async () => {
  const calls: Array<{ op: string; args?: Record<string, unknown> }> = [];
  const result = await callTool(
    'deploy_app',
    { manifest: { migrations: [{ name: 'schema', sql: 'create table mcp_notes(id bigint)' }] } },
    config(),
    fakeClient(calls)
  ) as Record<string, unknown>;

  assert.equal(result.success, true);
  assert.deepEqual(calls.filter((call) => !call.op.startsWith('deployment')).map((call) => call.op), ['sqlExecute']);
});

function fakeClient(calls: Array<{ op: string; args?: Record<string, unknown> }>) {
  return {
    deploymentCreate: async (args: Record<string, unknown>) => {
      calls.push({ op: 'deploymentCreate', args });
      return { id: 'dep_1' };
    },
    deploymentRecordStep: async (args: Record<string, unknown>) => {
      calls.push({ op: 'deploymentRecordStep', args });
      return { ok: true };
    },
    deploymentComplete: async (args: Record<string, unknown>) => {
      calls.push({ op: 'deploymentComplete', args });
      return { ok: true };
    },
    sqlExecute: async (args: Record<string, unknown>) => {
      calls.push({ op: 'sqlExecute', args });
      return { success: true };
    },
    functionsUpdate: async (args: Record<string, unknown>) => {
      calls.push({ op: 'functionsUpdate', args });
      return { ok: true };
    },
    functionsCreate: async (args: Record<string, unknown>) => {
      calls.push({ op: 'functionsCreate', args });
      return { ok: true };
    },
    functionsDeploy: async (args: Record<string, unknown>) => {
      calls.push({ op: 'functionsDeploy', args });
      return { ok: true };
    },
    functionsInvoke: async (args: Record<string, unknown>) => {
      calls.push({ op: 'functionsInvoke', args });
      return { status: 200, data: { ok: true } };
    },
    functionsSetSecrets: async (args: Record<string, unknown>) => {
      calls.push({ op: 'functionsSetSecrets', args });
      return { ok: true };
    },
    assetsUpload: async (args: Record<string, unknown>) => {
      calls.push({ op: 'assetsUpload', args });
      return { path: args.path, publicUrl: `https://assets.example/${args.path}` };
    },
    assetsUpdateSettings: async (args: Record<string, unknown>) => {
      calls.push({ op: 'assetsUpdateSettings', args });
      return { ok: true };
    },
    cronCreateJob: async (args: Record<string, unknown>) => {
      calls.push({ op: 'cronCreateJob', args });
      return { ok: true };
    },
    memoryWrite: async (args: Record<string, unknown>) => {
      calls.push({ op: 'memoryWrite', args });
      return { ok: true };
    },
  } as any;
}
