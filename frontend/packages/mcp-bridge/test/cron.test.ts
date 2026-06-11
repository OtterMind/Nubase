import test from 'node:test';
import assert from 'node:assert/strict';
import type { BridgeConfig } from '../src/config.js';
import { runCronCommand } from '../src/cron.js';

test('cron create maps flags onto the create payload', async () => {
  const calls: Array<Record<string, unknown>> = [];
  await runCronCommand(
    [
      'create', 'nightly-cleanup',
      '--cron', '0 3 * * *',
      '--target', 'edge_function',
      '--function', 'cleanup',
      '--method', 'POST',
      '--path', '/run',
      '--body', '{"a":1}',
      '--timeout', '60',
      '--description', 'Nightly cleanup',
      '--disabled',
    ],
    config(),
    fakeClient(calls)
  );
  assert.equal(calls.length, 1);
  assert.deepEqual(calls[0], {
    op: 'create',
    name: 'nightly-cleanup',
    cronExpression: '0 3 * * *',
    targetType: 'edge_function',
    description: 'Nightly cleanup',
    functionSlug: 'cleanup',
    httpMethod: 'POST',
    requestPath: '/run',
    requestBody: '{"a":1}',
    dbFunctionName: undefined,
    dbFunctionArgs: undefined,
    timeoutSeconds: 60,
    enabled: false,
  });
});

test('cron create parses --args as a JSON object for db_function targets', async () => {
  const calls: Array<Record<string, unknown>> = [];
  await runCronCommand(
    ['create', 'purge', '--cron', '*/5 * * * *', '--target', 'db_function', '--db-function', 'purge_old_rows', '--args', '{"days":7}'],
    config(),
    fakeClient(calls)
  );
  assert.equal(calls[0]?.targetType, 'db_function');
  assert.equal(calls[0]?.dbFunctionName, 'purge_old_rows');
  assert.deepEqual(calls[0]?.dbFunctionArgs, { days: 7 });
  assert.equal(calls[0]?.enabled, undefined);
});

test('cron create rejects invalid --args JSON with a clear error', async () => {
  await assert.rejects(
    runCronCommand(
      ['create', 'purge', '--cron', '*/5 * * * *', '--target', 'db_function', '--args', '{days:7}'],
      config(),
      fakeClient()
    ),
    /--args must be valid JSON/
  );
});

test('cron create rejects --args that is not a JSON object', async () => {
  await assert.rejects(
    runCronCommand(
      ['create', 'purge', '--cron', '*/5 * * * *', '--target', 'db_function', '--args', '[1,2]'],
      config(),
      fakeClient()
    ),
    /--args must be a JSON object/
  );
});

test('cron create requires --cron and --target', async () => {
  await assert.rejects(runCronCommand(['create', 'x', '--target', 'db_function'], config(), fakeClient()), /--cron is required/);
  await assert.rejects(runCronCommand(['create', 'x', '--cron', '0 * * * *'], config(), fakeClient()), /--target is required/);
});

test('cron update maps --enable and --disable', async () => {
  const calls: Array<Record<string, unknown>> = [];
  await runCronCommand(['update', 'purge', '--enable'], config(), fakeClient(calls));
  await runCronCommand(['update', 'purge', '--disable', '--cron', '0 4 * * *'], config(), fakeClient(calls));
  assert.equal(calls[0]?.enabled, true);
  assert.equal(calls[0]?.cronExpression, undefined);
  assert.equal(calls[1]?.enabled, false);
  assert.equal(calls[1]?.cronExpression, '0 4 * * *');
});

test('cron update without enable/disable leaves enabled undefined', async () => {
  const calls: Array<Record<string, unknown>> = [];
  await runCronCommand(['update', 'purge', '--description', 'updated'], config(), fakeClient(calls));
  assert.equal(calls[0]?.enabled, undefined);
  assert.equal(calls[0]?.description, 'updated');
});

test('cron runs targets the job endpoint when a name is given', async () => {
  const calls: Array<Record<string, unknown>> = [];
  await runCronCommand(['runs', 'purge', '--limit', '10'], config(), fakeClient(calls));
  assert.deepEqual(calls, [{ op: 'jobRuns', name: 'purge', limit: 10 }]);
});

test('cron runs without a name lists project-wide runs', async () => {
  const calls: Array<Record<string, unknown>> = [];
  await runCronCommand(['runs', '--limit', '25'], config(), fakeClient(calls));
  assert.deepEqual(calls, [{ op: 'runs', limit: 25 }]);
});

test('cron delete calls the client with the job name', async () => {
  const calls: Array<Record<string, unknown>> = [];
  await runCronCommand(['delete', 'purge'], config(), fakeClient(calls));
  assert.deepEqual(calls, [{ op: 'delete', name: 'purge' }]);
});

test('cron get calls the client with the job name', async () => {
  const calls: Array<Record<string, unknown>> = [];
  await runCronCommand(['get', 'purge'], config(), fakeClient(calls));
  assert.deepEqual(calls, [{ op: 'get', name: 'purge' }]);
});

test('cron with no or unknown subcommand returns usage', async () => {
  const empty = await runCronCommand([], config(), fakeClient()) as Record<string, any>;
  assert.ok(Array.isArray(empty.usage));
  assert.match(empty.usage.join('\n'), /cron create/);
  const unknown = await runCronCommand(['bogus'], config(), fakeClient()) as Record<string, any>;
  assert.ok(Array.isArray(unknown.usage));
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
    cronListJobs: async () => {
      calls.push({ op: 'list' });
      return [];
    },
    cronGetJob: async (args: Record<string, unknown>) => {
      calls.push({ op: 'get', ...args });
      return { ok: true };
    },
    cronCreateJob: async (args: Record<string, unknown>) => {
      calls.push({ op: 'create', ...args });
      return { ok: true };
    },
    cronUpdateJob: async (args: Record<string, unknown>) => {
      calls.push({ op: 'update', ...args });
      return { ok: true };
    },
    cronDeleteJob: async (args: Record<string, unknown>) => {
      calls.push({ op: 'delete', ...args });
      return { ok: true };
    },
    cronJobRuns: async (args: Record<string, unknown>) => {
      calls.push({ op: 'jobRuns', ...args });
      return [];
    },
    cronRuns: async (args: Record<string, unknown>) => {
      calls.push({ op: 'runs', ...args });
      return [];
    },
  } as any;
}
