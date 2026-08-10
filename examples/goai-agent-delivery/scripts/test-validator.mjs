#!/usr/bin/env node

import assert from 'node:assert/strict';
import { cp, mkdtemp, readFile, rm, symlink, unlink, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const sourcePackage = path.resolve(scriptDirectory, '..');
const validator = path.join(scriptDirectory, 'validate-package.mjs');
const temporaryRoot = await mkdtemp(path.join(tmpdir(), 'goai-validator-test-'));
const testPackage = path.join(temporaryRoot, 'goai-agent-delivery');

const cases = [
  {
    name: 'unknown manifest field',
    expected: /unknown property unexpectedField/,
    mutate: () => mutateManifest((manifest) => { manifest.unexpectedField = true; }),
  },
  {
    name: 'path traversal',
    expected: /path escapes its allowed root/,
    mutate: () => mutateManifest((manifest) => { manifest.migrations[0].file = '../outside.sql'; }),
  },
  {
    name: 'symlink',
    expected: /must not be a symlink/,
    mutate: async () => {
      const target = packagePath('scenario', 'schema.sql');
      await unlink(target);
      await symlink(path.join(sourcePackage, 'scenario', 'schema.sql'), target);
    },
  },
  {
    name: 'inline secret',
    expected: /inline sensitive value detected/,
    mutate: async () => {
      const assignmentName = ['NUBASE', 'API', 'KEY'].join('_');
      const fakeValue = ['contest', 'fixture', 'not', 'a', 'credential'].join('-');
      await writeFile(packagePath('scenario', 'inline-secret.fixture'), `${assignmentName}=${fakeValue}\n`);
    },
  },
  {
    name: 'disabled security scan',
    expected: /securityScan must be true/,
    mutate: () => mutateManifest((manifest) => { manifest.securityScan = false; }),
  },
  {
    name: 'continue on error',
    expected: /continueOnError must be false/,
    mutate: () => mutateManifest((manifest) => { manifest.continueOnError = true; }),
  },
  {
    name: 'dangerous SQL',
    expected: /contains a dangerous SQL statement/,
    mutate: async () => {
      const schemaPath = packagePath('scenario', 'schema.sql');
      const schema = await readFile(schemaPath, 'utf8');
      await writeFile(schemaPath, `${schema}\ndrop table delivery_tasks;\n`);
    },
  },
  {
    name: 'unauthorized task-state transition',
    expected: /phase approved cannot be written by builder-agent/,
    mutate: async () => {
      const statePath = packagePath('scenario', 'task-state.example.json');
      const state = JSON.parse(await readFile(statePath, 'utf8'));
      state.history.at(-1).updatedBy = 'builder-agent';
      await writeFile(statePath, `${JSON.stringify(state, null, 2)}\n`);
    },
  },
  {
    name: 'pending approval with decision fields',
    expected: /expected exactly one oneOf branch to match/,
    mutate: async () => {
      const approvalPath = packagePath('scenario', 'approval.example.json');
      const approval = JSON.parse(await readFile(approvalPath, 'utf8'));
      approval.decision = 'pending';
      await writeFile(approvalPath, `${JSON.stringify(approval, null, 2)}\n`);
    },
  },
  {
    name: 'release governor promotion instruction',
    expected: /must not instruct the Release Governor to execute promotion/,
    mutate: async () => {
      const workerPath = packagePath('agentteams-v1.2.2', 'workers', 'release-governor', 'worker.yaml');
      const worker = await readFile(workerPath, 'utf8');
      await writeFile(workerPath, worker.replace(
        'Never execute or simulate promotion;',
        'Promote only after approval;',
      ));
    },
  },
  {
    name: 'verification digest mismatch',
    expected: /approval verificationDigest/,
    mutate: async () => {
      const approvalPath = packagePath('scenario', 'approval.example.json');
      const approval = JSON.parse(await readFile(approvalPath, 'utf8'));
      approval.requestedAction.verificationDigest = `sha256:${'0'.repeat(64)}`;
      await writeFile(approvalPath, `${JSON.stringify(approval, null, 2)}\n`);
    },
  },
  {
    name: 'approval requested before verification',
    expected: /approval requestedAt must not precede independent verification/,
    mutate: async () => {
      const approvalPath = packagePath('scenario', 'approval.example.json');
      const approval = JSON.parse(await readFile(approvalPath, 'utf8'));
      approval.requestedAt = '2026-08-10T04:02:00.000Z';
      await writeFile(approvalPath, `${JSON.stringify(approval, null, 2)}\n`);
    },
  },
  {
    name: 'unclassified Java HTTP tool',
    expected: /javaHttpPolicy does not classify tools: memorySearch/,
    mutate: () => mutatePolicy((policy) => {
      const route = policy.routes.find((item) => item.name === 'nubase-read');
      route.javaHttpPolicy.allowTools = route.javaHttpPolicy.allowTools.filter(
        (tool) => tool !== 'memorySearch',
      );
    }),
  },
  {
    name: 'transport tool naming mix',
    expected: /javaHttpPolicy uses invalid exact camelCase Java tool names: memory_search/,
    mutate: () => mutatePolicy((policy) => {
      const route = policy.routes.find((item) => item.name === 'nubase-read');
      route.javaHttpPolicy.allowTools.push('memory_search');
    }),
  },
  {
    name: 'stdio Builder destructive tool grant',
    expected: /stdioBridgePolicy allowTools must match the reviewed stdio role partition/,
    mutate: () => mutatePolicy((policy) => {
      const route = policy.routes.find((item) => item.name === 'nubase-build');
      route.stdioBridgePolicy.denyTools = route.stdioBridgePolicy.denyTools.filter(
        (tool) => tool !== 'assets_delete',
      );
      route.stdioBridgePolicy.allowTools.push('assets_delete');
    }),
  },
  {
    name: 'Java Builder executeSql grant',
    expected: /Java Builder must deny executeSql/,
    mutate: () => mutatePolicy((policy) => {
      const route = policy.routes.find((item) => item.name === 'nubase-build');
      route.javaHttpPolicy.denyTools = route.javaHttpPolicy.denyTools.filter(
        (tool) => tool !== 'executeSql',
      );
      route.javaHttpPolicy.allowTools.push('executeSql');
    }),
  },
  {
    name: 'Higress consumer without runtime prefix',
    expected: /higressConsumers do not match the reviewed Console API consumer identities/,
    mutate: () => mutatePolicy((policy) => {
      const route = policy.routes.find((item) => item.name === 'nubase-build');
      route.higressConsumers = ['nubase-builder'];
    }),
  },
];

try {
  for (const testCase of cases) {
    await resetPackage();
    await testCase.mutate();
    const result = runValidator();
    assert.notEqual(result.status, 0, `${testCase.name}: validator unexpectedly passed`);
    assert.match(
      `${result.stdout}\n${result.stderr}`,
      testCase.expected,
      `${testCase.name}: validator failed for an unexpected reason`,
    );
  }
  console.log(`Validator negative cases passed (${cases.length}).`);
} finally {
  await rm(temporaryRoot, { recursive: true, force: true });
}

async function resetPackage() {
  await rm(testPackage, { recursive: true, force: true });
  await cp(sourcePackage, testPackage, { recursive: true });
}

async function mutateManifest(mutate) {
  const manifestPath = packagePath('scenario', 'nubase.deploy.json');
  const manifest = JSON.parse(await readFile(manifestPath, 'utf8'));
  mutate(manifest);
  await writeFile(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`);
}

async function mutatePolicy(mutate) {
  const policyPath = packagePath('agentteams-v1.2.2', 'mcp-tool-policies.json');
  const policy = JSON.parse(await readFile(policyPath, 'utf8'));
  mutate(policy);
  await writeFile(policyPath, `${JSON.stringify(policy, null, 2)}\n`);
}

function runValidator() {
  return spawnSync(process.execPath, [validator, '--package-root', testPackage], {
    cwd: temporaryRoot,
    encoding: 'utf8',
    timeout: 10000,
    maxBuffer: 1024 * 1024,
  });
}

function packagePath(...segments) {
  return path.join(testPackage, ...segments);
}
