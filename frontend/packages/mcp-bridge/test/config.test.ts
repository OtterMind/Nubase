import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { loadConfig, loadConfigAsync } from '../src/config.js';
import { resolveScope } from '../src/context.js';

test('loadConfig reads explicit user and agent context', () => {
  const config = loadConfig({
    NUBASE_URL: 'http://localhost:9999/',
    NUBASE_PROJECT_KEY: 'project-key',
    NUBASE_USER_JWT: 'jwt',
    NUBASE_AGENT_ID: 'codex',
    NUBASE_USER_ID: 'user-1',
    NUBASE_RUN_ID: 'run-1',
    NUBASE_PLATFORM_KEY: 'platform-key',
    NUBASE_PLATFORM_JWT: 'platform-jwt',
    NUBASE_ALLOW_SQL_EXECUTE: 'true',
    NUBASE_ALLOW_ADMIN_WRITE: 'true',
  });

  assert.equal(config.nubaseUrl, 'http://localhost:9999');
  assert.equal(config.projectKey, 'project-key');
  assert.equal(config.userJwt, 'jwt');
  assert.equal(config.agentId, 'codex');
  assert.equal(config.userId, 'user-1');
  assert.equal(config.runId, 'run-1');
  assert.equal(config.platformKey, 'platform-key');
  assert.equal(config.platformJwt, 'platform-jwt');
  assert.equal(config.allowSqlExecute, true);
  assert.equal(config.allowAdminWrite, true);
});

test('loadConfig defaults admin write to false', () => {
  const config = loadConfig({ NUBASE_PROJECT_KEY: 'project-key' });
  assert.equal(config.nubaseUrl, 'https://nubase.ai');
  assert.equal(config.allowAdminWrite, false);
});

test('loadConfig reads project ref and anon key from env', () => {
  const config = loadConfig({
    NUBASE_PROJECT_KEY: 'service-role-key',
    NUBASE_PROJECT_REF: 'proj_123',
    NUBASE_ANON_KEY: 'anon-key',
  });
  assert.equal(config.projectRef, 'proj_123');
  assert.equal(config.anonKey, 'anon-key');
});

test('loadConfigAsync fills project ref and anon key from saved config', async () => {
  const dir = await mkdtemp(path.join(os.tmpdir(), 'nubase-cli-config-'));
  const configPath = path.join(dir, 'config.json');
  await writeFile(configPath, JSON.stringify({
    nubaseUrl: 'http://localhost:9999/',
    projectKey: 'stored-service-role',
    projectRef: 'proj_stored',
    anonKey: 'stored-anon-key',
    savedAt: '2026-05-31T00:00:00.000Z',
  }));

  try {
    const config = await loadConfigAsync({ NUBASE_CONFIG: configPath });
    assert.equal(config.projectKey, 'stored-service-role');
    assert.equal(config.projectRef, 'proj_stored');
    assert.equal(config.anonKey, 'stored-anon-key');
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});

test('resolveScope lets tool args override env defaults', () => {
  const config = loadConfig({
    NUBASE_PROJECT_KEY: 'project-key',
    NUBASE_AGENT_ID: 'codex',
    NUBASE_USER_ID: 'user-1',
  });

  assert.deepEqual(resolveScope(config, { agentId: 'cursor' }), {
    userId: 'user-1',
    agentId: 'cursor',
    runId: undefined,
  });
});

test('loadConfigAsync falls back to saved authorization config when env key is absent', async () => {
  const dir = await mkdtemp(path.join(os.tmpdir(), 'nubase-cli-config-'));
  const configPath = path.join(dir, 'config.json');
  await writeFile(configPath, JSON.stringify({
    nubaseUrl: 'http://localhost:9999/',
    projectKey: 'stored-project-key',
    savedAt: '2026-05-31T00:00:00.000Z',
  }));

  try {
    const config = await loadConfigAsync({ NUBASE_CONFIG: configPath });
    assert.equal(config.nubaseUrl, 'http://localhost:9999');
    assert.equal(config.projectKey, 'stored-project-key');
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});

test('loadConfigAsync reads the default project authorization config', async () => {
  const dir = await mkdtemp(path.join(os.tmpdir(), 'nubase-cli-config-'));
  const originalCwd = process.cwd();
  await mkdir(path.join(dir, '.nubase'), { recursive: true });
  await writeFile(path.join(dir, '.nubase', 'config.json'), JSON.stringify({
    nubaseUrl: 'https://project.example/',
    projectKey: 'project-local-key',
    savedAt: '2026-05-31T00:00:00.000Z',
  }));

  try {
    process.chdir(dir);
    const config = await loadConfigAsync({});
    assert.equal(config.nubaseUrl, 'https://project.example');
    assert.equal(config.projectKey, 'project-local-key');
  } finally {
    process.chdir(originalCwd);
    await rm(dir, { recursive: true, force: true });
  }
});

test('loadConfigAsync keeps explicit environment key over saved authorization config', async () => {
  const dir = await mkdtemp(path.join(os.tmpdir(), 'nubase-cli-config-'));
  const configPath = path.join(dir, 'config.json');
  await writeFile(configPath, JSON.stringify({
    nubaseUrl: 'http://stored.example',
    projectKey: 'stored-project-key',
    savedAt: '2026-05-31T00:00:00.000Z',
  }));

  try {
    const config = await loadConfigAsync({
      NUBASE_CONFIG: configPath,
      NUBASE_URL: 'http://env.example/',
      NUBASE_PROJECT_KEY: 'env-project-key',
    });
    assert.equal(config.nubaseUrl, 'http://env.example');
    assert.equal(config.projectKey, 'env-project-key');
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});
