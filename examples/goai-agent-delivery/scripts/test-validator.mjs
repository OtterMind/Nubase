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
    expected: /Java Builder must deny executeSql and executeSqlDryRun/,
    mutate: () => mutatePolicy((policy) => {
      const route = policy.routes.find((item) => item.name === 'nubase-build');
      route.javaHttpPolicy.denyTools = route.javaHttpPolicy.denyTools.filter(
        (tool) => tool !== 'executeSql',
      );
      route.javaHttpPolicy.allowTools.push('executeSql');
    }),
  },
  {
    name: 'Java Builder executeSqlDryRun regrant',
    expected: /Java Builder must deny executeSql and executeSqlDryRun/,
    mutate: () => mutatePolicy((policy) => {
      const route = policy.routes.find((item) => item.name === 'nubase-build');
      route.javaHttpPolicy.denyTools = route.javaHttpPolicy.denyTools.filter(
        (tool) => tool !== 'executeSqlDryRun',
      );
      route.javaHttpPolicy.allowTools.push('executeSqlDryRun');
    }),
  },
  {
    name: 'Java read deployment staging escalation',
    expected: /javaHttpPolicy allowTools must match the reviewed Java role partition/,
    mutate: () => mutatePolicy((policy) => {
      const route = policy.routes.find((item) => item.name === 'nubase-read');
      route.javaHttpPolicy.denyTools = route.javaHttpPolicy.denyTools.filter(
        (tool) => tool !== 'deploymentStageAsset',
      );
      route.javaHttpPolicy.allowTools.push('deploymentStageAsset');
    }),
  },
  {
    name: 'platform Builder unclassified provision tool',
    expected: /platformHttpPolicy does not classify tools: platformProjectProvision/,
    mutate: () => mutatePolicy((policy) => {
      const route = policy.routes.find((item) => item.name === 'project-build');
      route.platformHttpPolicy.allowTools = route.platformHttpPolicy.allowTools.filter(
        (tool) => tool !== 'platformProjectProvision',
      );
    }),
  },
  {
    name: 'platform read create escalation',
    expected: /platformHttpPolicy allowTools must match the reviewed platform role partition/,
    mutate: () => mutatePolicy((policy) => {
      const route = policy.routes.find((item) => item.name === 'project-read');
      route.platformHttpPolicy.denyTools = route.platformHttpPolicy.denyTools.filter(
        (tool) => tool !== 'platformProjectCreate',
      );
      route.platformHttpPolicy.allowTools.push('platformProjectCreate');
    }),
  },
  {
    name: 'platform route tenant endpoint crossover',
    expected: /platformHttpPolicy endpoint must be "\/platform\/mcp"/,
    mutate: () => mutatePolicy((policy) => {
      const route = policy.routes.find((item) => item.name === 'project-read');
      route.platformHttpPolicy.endpoint = '/mcp';
    }),
  },
  {
    name: 'Governor platform route grant',
    expected: /MCP servers must match the reviewed role routes/,
    mutate: async () => {
      const workerPath = packagePath('agentteams-v1.2.2', 'workers', 'release-governor', 'worker.yaml');
      const worker = await readFile(workerPath, 'utf8');
      await writeFile(workerPath, worker.replace(
        '  skills:',
        '  mcpServers:\n    - name: project-read\n      url: http://aigw-local.agentteams.io:8080/mcp-servers/mcp-project-read/mcp\n  skills:',
      ));
    },
  },
  {
    name: 'AgentTeams MCP route external URL',
    expected: /builder-agent\/worker\.yaml MCP servers must match the reviewed role routes/,
    mutate: async () => {
      const workerPath = packagePath('agentteams-v1.2.2', 'workers', 'builder-agent', 'worker.yaml');
      const worker = await readFile(workerPath, 'utf8');
      await writeFile(workerPath, worker.replace(
        'http://aigw-local.agentteams.io:8080/mcp-servers/mcp-project-build/mcp',
        'https://external.invalid/mcp',
      ));
    },
  },
  {
    name: 'AgentTeams MCP route transport drift',
    expected: /release-governor\/worker\.yaml MCP servers must match the reviewed role routes/,
    mutate: async () => {
      const workerPath = packagePath('agentteams-v1.2.2', 'workers', 'release-governor', 'worker.yaml');
      const worker = await readFile(workerPath, 'utf8');
      await writeFile(workerPath, worker.replace('      transport: http', '      transport: stdio'));
    },
  },
  {
    name: 'HiClaw MCP route external URL',
    expected: /team\.yaml nubase-builder MCP servers must match the reviewed role routes/,
    mutate: async () => {
      const teamPath = packagePath('compat', 'hiclaw-v1.1.2', 'team.yaml');
      const team = await readFile(teamPath, 'utf8');
      await writeFile(teamPath, team.replace(
        'http://aigw-local.hiclaw.io:8080/mcp-servers/mcp-project-build/mcp',
        'https://external.invalid/mcp',
      ));
    },
  },
  {
    name: 'MCP route duplicate YAML key',
    expected: /builder-agent\/worker\.yaml contains duplicate YAML key transport/,
    mutate: async () => {
      const workerPath = packagePath('agentteams-v1.2.2', 'workers', 'builder-agent', 'worker.yaml');
      const worker = await readFile(workerPath, 'utf8');
      await writeFile(workerPath, worker.replace(
        '      transport: http\n  resources:',
        '      transport: http\n      transport: stdio\n  resources:',
      ));
    },
  },
  {
    name: 'MCP route extra field',
    expected: /builder-agent\/worker\.yaml contains unsupported MCP server YAML structure/,
    mutate: async () => {
      const workerPath = packagePath('agentteams-v1.2.2', 'workers', 'builder-agent', 'worker.yaml');
      const worker = await readFile(workerPath, 'utf8');
      await writeFile(workerPath, worker.replace(
        '      transport: http\n  resources:',
        '      transport: http\n      headers:\n  resources:',
      ));
    },
  },
  {
    name: 'Verifier static provisioning contract removed',
    expected: /must require explicit static control-plane provisioning evidence; missing: verificationLevel=STATIC_CONTROL_PLANE, state=PROVISIONED, readiness\.gateway=true, advertisedEndpoints\.gateway/,
    mutate: async () => {
      const skillPath = packagePath('skills', 'release-verify', 'SKILL.md');
      const skill = await readFile(skillPath, 'utf8');
      await writeFile(skillPath, skill
        .replaceAll('verificationLevel=STATIC_CONTROL_PLANE', 'verification level is present')
        .replaceAll('state=PROVISIONED', 'project state is complete')
        .replaceAll('readiness.gateway=true', 'gateway readiness is true')
        .replaceAll('advertisedEndpoints.gateway', 'gateway endpoint entry'));
    },
  },
  {
    name: 'Verifier static boundary removed',
    expected: /must limit PROVISIONED to static control-plane evidence; missing boundary:/,
    mutate: async () => {
      const skillPath = packagePath('skills', 'release-verify', 'SKILL.md');
      const skill = await readFile(skillPath, 'utf8');
      await writeFile(skillPath, skill
        .replaceAll('Functions/MCP 外部可达', 'service availability')
        .replaceAll('模型 upstream HTTP/计费调用', 'model behavior')
        .replaceAll('应用部署', 'application state')
        .replaceAll('生产可用', 'production state'));
    },
  },
  {
    name: 'Verifier worker static decision contract removed',
    expected: /must require explicit static control-plane provisioning evidence; missing: state=PROVISIONED/,
    mutate: async () => {
      const workerPath = packagePath('agentteams-v1.2.2', 'workers', 'verifier-agent', 'worker.yaml');
      const worker = await readFile(workerPath, 'utf8');
      await writeFile(workerPath, worker.replaceAll('state=PROVISIONED', 'project state is complete'));
    },
  },
  {
    name: 'Verifier status trace exact-match removed',
    expected: /must exact-match all platformProjectStatus trace echoes; missing: status\.approvalId === approvalId/,
    mutate: async () => {
      const skillPath = packagePath('skills', 'release-verify', 'SKILL.md');
      const skill = await readFile(skillPath, 'utf8');
      await writeFile(
        skillPath,
        skill.replaceAll('status.approvalId === approvalId', 'status approval is present'),
      );
    },
  },
  {
    name: 'project name mapping removed',
    expected: /must preserve the deterministic display-name to project-ref mapping; missing: goai_psx_agent_teams_project/,
    mutate: async () => {
      const skillPath = packagePath('skills', 'app-plan', 'SKILL.md');
      const skill = await readFile(skillPath, 'utf8');
      await writeFile(
        skillPath,
        skill.replaceAll('goai_psx_agent_teams_project', 'derived project ref'),
      );
    },
  },
  {
    name: 'platform grant approval binding removed',
    expected: /must bind the automation grant to the reviewed run approval ID/,
    mutate: async () => {
      const runbookPath = packagePath(
        'compat',
        'hiclaw-v1.1.2',
        'PLATFORM_AUTOMATION_SECURITY.md',
      );
      const runbook = await readFile(runbookPath, 'utf8');
      await writeFile(
        runbookPath,
        runbook.replace('--approval-binding "${GOAI_APPROVAL_ID}"', '--approval-binding "approval"'),
      );
    },
  },
  {
    name: 'Higress consumer without runtime prefix',
    expected: /higressConsumers do not match the reviewed Console API consumer identities/,
    mutate: () => mutatePolicy((policy) => {
      const route = policy.routes.find((item) => item.name === 'nubase-build');
      route.higressConsumers = ['nubase-builder'];
    }),
  },
  {
    name: 'Higress refresh cleanup removed',
    expected: /must install a fail-closed Higress refresh cleanup trap/,
    mutate: async () => {
      const readmePath = packagePath('compat', 'hiclaw-v1.1.2', 'README.md');
      const readme = await readFile(readmePath, 'utf8');
      await writeFile(
        readmePath,
        readme.replace('trap cleanup_higress_refresh EXIT HUP INT TERM', 'true'),
      );
    },
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
