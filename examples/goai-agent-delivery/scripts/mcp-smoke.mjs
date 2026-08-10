#!/usr/bin/env node

import assert from 'node:assert/strict';
import { mkdtemp, rm, stat } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const packageRoot = path.resolve(scriptDirectory, '..');
const repositoryRoot = path.resolve(packageRoot, '..', '..');
const bridgeEntry = path.join(
  repositoryRoot,
  'frontend',
  'packages',
  'mcp-bridge',
  'dist',
  'src',
  'index.js',
);

const allowedTools = ['fetch_docs', 'sql_dry_run'];
const deniedTools = [
  'deploy_app',
  'deployment_rollback',
  'functions_secrets_set',
  'project_keys',
  'project_keys_admin',
  'sql_execute',
];

await assertBridgeBuilt();
const temporaryDirectory = await mkdtemp(path.join(tmpdir(), 'nubase-mcp-smoke-'));
let child;

try {
  const isolatedEnvironment = {
    PATH: process.env.PATH ?? '',
    NUBASE_CONFIG: path.join(temporaryDirectory, 'missing-config.json'),
    NUBASE_URL: 'http://127.0.0.1:9',
    NUBASE_ALLOWED_TOOLS: allowedTools.join(','),
    NUBASE_DENIED_TOOLS: deniedTools.join(','),
    NUBASE_ALLOW_SQL_EXECUTE: 'false',
    NUBASE_ALLOW_DANGEROUS_SQL: 'false',
    NUBASE_ALLOW_ADMIN_WRITE: 'false',
    NUBASE_RECORD_MIGRATIONS: 'false',
  };
  assertCredentialFree(isolatedEnvironment);

  child = spawn(process.execPath, [bridgeEntry], {
    cwd: temporaryDirectory,
    env: isolatedEnvironment,
    stdio: ['pipe', 'pipe', 'pipe'],
  });
  const client = createJsonRpcClient(child);

  const initialize = await client.request('initialize', {
    protocolVersion: '2024-11-05',
    capabilities: {},
    clientInfo: { name: 'goai-contest-smoke', version: '1.0.0' },
  });
  assert.equal(initialize.protocolVersion, '2024-11-05');
  assert.equal(initialize.serverInfo?.name, 'nubase_cli');
  client.notify('notifications/initialized');

  const listed = await client.request('tools/list');
  const listedNames = listed.tools.map((tool) => tool.name).sort();
  assert.deepEqual(listedNames, [...allowedTools].sort());
  assert.equal(listedNames.some((name) => deniedTools.includes(name)), false);

  const safeDryRun = await client.callTool('sql_dry_run', {
    sql: 'create table contest_smoke (id bigint primary key)',
  });
  assert.deepEqual(
    pick(safeDryRun, ['success', 'risk', 'statementCount', 'executable']),
    { success: true, risk: 'SCHEMA_WRITE', statementCount: 1, executable: true },
  );

  const dangerousDryRun = await client.callTool('sql_dry_run', {
    sql: 'drop table contest_smoke',
  });
  assert.deepEqual(
    pick(dangerousDryRun, ['success', 'risk', 'statementCount', 'executable']),
    { success: true, risk: 'DANGEROUS', statementCount: 1, executable: false },
  );

  await assert.rejects(
    () => client.request('tools/call', { name: 'project_keys', arguments: {} }),
    /Tool is not allowed by the active Nubase tool policy: project_keys/,
  );

  await client.close();
  console.log('Credential-free stdio MCP smoke passed.');
  console.log(`- Tool discovery is limited to: ${allowedTools.join(', ')}`);
  console.log('- Dispatch rejects a denied sensitive tool.');
  console.log('- SQL dry-run classifies schema and dangerous statements without execution.');
} finally {
  if (child && child.exitCode === null && child.signalCode === null) child.kill('SIGTERM');
  await rm(temporaryDirectory, { recursive: true, force: true });
}

async function assertBridgeBuilt() {
  try {
    const metadata = await stat(bridgeEntry);
    assert.equal(metadata.isFile(), true);
  } catch {
    throw new Error(
      'The stdio MCP bridge is not built. Run pnpm --filter nubase_cli build from frontend first.',
    );
  }
}

function assertCredentialFree(environment) {
  const credentialNames = [
    'NUBASE_ANON_KEY',
    'NUBASE_API_KEY',
    'NUBASE_METADATA_SERVICE_ROLE_KEY',
    'NUBASE_PLATFORM_JWT',
    'NUBASE_PLATFORM_KEY',
    'NUBASE_PROJECT_KEY',
    'NUBASE_USER_JWT',
  ];
  for (const name of credentialNames) assert.equal(Object.hasOwn(environment, name), false);
}

function createJsonRpcClient(processHandle) {
  let nextId = 1;
  let stdoutBuffer = '';
  let stderrBuffer = '';
  let closed = false;
  const pending = new Map();

  processHandle.stdout.setEncoding('utf8');
  processHandle.stdout.on('data', (chunk) => {
    stdoutBuffer += chunk;
    while (stdoutBuffer.includes('\n')) {
      const newline = stdoutBuffer.indexOf('\n');
      const line = stdoutBuffer.slice(0, newline).trim();
      stdoutBuffer = stdoutBuffer.slice(newline + 1);
      if (line) acceptResponse(line);
    }
  });
  processHandle.stderr.setEncoding('utf8');
  processHandle.stderr.on('data', (chunk) => {
    stderrBuffer = `${stderrBuffer}${chunk}`.slice(-8192);
  });
  processHandle.once('error', (error) => rejectAll(error));
  processHandle.once('exit', (code, signal) => {
    closed = true;
    if (pending.size > 0) {
      rejectAll(new Error(`MCP bridge exited before responding (code=${code}, signal=${signal})`));
    }
  });

  function acceptResponse(line) {
    let response;
    try {
      response = JSON.parse(line);
    } catch {
      rejectAll(new Error('MCP bridge wrote non-JSON data to stdout'));
      return;
    }
    const deferred = pending.get(response.id);
    if (!deferred) return;
    pending.delete(response.id);
    clearTimeout(deferred.timeout);
    if (response.error) {
      deferred.reject(new Error(response.error.message ?? 'Unknown JSON-RPC error'));
    } else {
      deferred.resolve(response.result);
    }
  }

  function rejectAll(error) {
    for (const deferred of pending.values()) {
      clearTimeout(deferred.timeout);
      deferred.reject(error);
    }
    pending.clear();
  }

  function send(message) {
    if (closed || !processHandle.stdin.writable) {
      throw new Error(`MCP bridge is not writable${stderrBuffer ? '; inspect its stderr locally' : ''}`);
    }
    processHandle.stdin.write(`${JSON.stringify(message)}\n`);
  }

  async function request(method, params) {
    const id = nextId++;
    const result = new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {
        pending.delete(id);
        reject(new Error(`Timed out waiting for MCP response to ${method}`));
      }, 5000);
      pending.set(id, { resolve, reject, timeout });
    });
    send({ jsonrpc: '2.0', id, method, ...(params === undefined ? {} : { params }) });
    return result;
  }

  return {
    request,
    notify(method, params) {
      send({ jsonrpc: '2.0', method, ...(params === undefined ? {} : { params }) });
    },
    async callTool(name, args) {
      const result = await request('tools/call', { name, arguments: args });
      assert.equal(result.content?.length, 1);
      assert.equal(result.content[0]?.type, 'text');
      return JSON.parse(result.content[0].text);
    },
    async close() {
      if (closed) return;
      processHandle.stdin.end();
      await new Promise((resolve, reject) => {
        const timeout = setTimeout(() => {
          processHandle.kill('SIGTERM');
          reject(new Error('MCP bridge did not exit after stdin closed'));
        }, 2000);
        processHandle.once('exit', () => {
          clearTimeout(timeout);
          resolve();
        });
      });
    },
  };
}

function pick(value, keys) {
  return Object.fromEntries(keys.map((key) => [key, value[key]]));
}
