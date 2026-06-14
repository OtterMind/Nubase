import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import type { BridgeConfig } from '../src/config.js';
import { runAssetsCommand } from '../src/assets.js';

test('assets upload --content maps inline text onto the upload payload', async () => {
  const calls: Array<Record<string, unknown>> = [];
  await runAssetsCommand(
    ['upload', 'robots.txt', '--content', 'User-agent: *', '--cache-control', 'public, max-age=60'],
    config(),
    fakeClient(calls)
  );
  assert.equal(calls.length, 1);
  assert.deepEqual(calls[0], {
    op: 'upload',
    path: 'robots.txt',
    content: 'User-agent: *',
    contentType: undefined,
    cacheControl: 'public, max-age=60',
    upsert: undefined,
  });
});

test('assets upload --file reads bytes and base64-encodes them', async () => {
  const dir = await mkdtemp(path.join(os.tmpdir(), 'nubase-assets-'));
  const file = path.join(dir, 'index.html');
  await writeFile(file, '<h1>hi</h1>');
  const calls: Array<Record<string, unknown>> = [];
  await runAssetsCommand(
    ['upload', 'index.html', '--file', file, '--content-type', 'text/html', '--create'],
    config(),
    fakeClient(calls)
  );
  assert.equal(calls[0]?.path, 'index.html');
  assert.equal(calls[0]?.content, undefined);
  assert.equal(Buffer.from(String(calls[0]?.contentBase64), 'base64').toString('utf8'), '<h1>hi</h1>');
  assert.equal(calls[0]?.contentType, 'text/html');
  assert.equal(calls[0]?.upsert, false);
});

test('assets upload rejects when neither --file nor --content is given', async () => {
  await assert.rejects(
    runAssetsCommand(['upload', 'index.html'], config(), fakeClient()),
    /exactly one of --file/
  );
});

test('assets upload rejects when both --file and --content are given', async () => {
  await assert.rejects(
    runAssetsCommand(['upload', 'index.html', '--file', '/tmp/x', '--content', 'y'], config(), fakeClient()),
    /exactly one of --file/
  );
});

test('assets upload requires the asset path', async () => {
  await assert.rejects(
    runAssetsCommand(['upload', '--content', 'x'], config(), fakeClient()),
    /asset path is required/
  );
});

test('assets delete calls the client with the path', async () => {
  const calls: Array<Record<string, unknown>> = [];
  await runAssetsCommand(['delete', 'css/old.css'], config(), fakeClient(calls));
  assert.deepEqual(calls, [{ op: 'delete', path: 'css/old.css' }]);
});

test('assets list maps filter flags', async () => {
  const calls: Array<Record<string, unknown>> = [];
  await runAssetsCommand(['list', '--prefix', 'css/', '--limit', '10'], config(), fakeClient(calls));
  assert.equal(calls[0]?.op, 'list');
  assert.equal(calls[0]?.prefix, 'css/');
  assert.equal(calls[0]?.limit, 10);
});

test('assets list rejects a non-integer --limit before calling the client', async () => {
  const calls: Array<Record<string, unknown>> = [];
  await assert.rejects(
    runAssetsCommand(['list', '--limit', 'abc'], config(), fakeClient(calls)),
    /--limit must be an integer/
  );
  assert.equal(calls.length, 0);
});

test('assets with no or unknown subcommand returns usage', async () => {
  const empty = (await runAssetsCommand([], config(), fakeClient())) as Record<string, any>;
  assert.ok(Array.isArray(empty.usage));
  assert.match(empty.usage.join('\n'), /assets upload/);
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

function fakeClient(calls: Array<Record<string, unknown>> = []) {
  return {
    assetsList: async (args: Record<string, unknown>) => {
      calls.push({ op: 'list', ...args });
      return [];
    },
    assetsUpload: async (args: Record<string, unknown>) => {
      calls.push({ op: 'upload', ...args });
      return { ok: true };
    },
    assetsDelete: async (args: Record<string, unknown>) => {
      calls.push({ op: 'delete', ...args });
      return { ok: true };
    },
  } as any;
}
