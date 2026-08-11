#!/usr/bin/env node

import { createHash } from 'node:crypto';
import { lstat, readFile, readdir } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const defaultPackageRoot = path.resolve(scriptDir, '..');
const repositoryRoot = path.resolve(defaultPackageRoot, '..', '..');
const packageRoot = resolvePackageRoot(process.argv.slice(2));
const errors = [];
const summary = [];
const sensitiveAssignmentPattern = new RegExp(
  `(?:["'](?:${['api[_-]?key', 'token', 'password', 'secret', 'authorization', 'cookie'].join('|')})["']`
    + '|\\b[A-Z][A-Z0-9_]*(?:API_KEY|TOKEN|PASSWORD|SECRET|AUTHORIZATION|COOKIE)\\b)'
    + '\\s*[:=]\\s*["\\\']?([^\\s"\\\']{12,})',
);

const expectedWorkers = new Map([
  ['delivery-lead', {
    resourceName: 'nubase-delivery-lead',
    skill: 'app-plan',
    identity: 'delivery-lead',
    mcpRoutes: [
      { name: 'nubase-read', serverName: 'mcp-nubase-read' },
      { name: 'project-read', serverName: 'mcp-project-read' },
    ],
  }],
  ['builder-agent', {
    resourceName: 'nubase-builder',
    skill: 'app-build',
    identity: 'builder-agent',
    mcpRoutes: [
      { name: 'nubase-build', serverName: 'mcp-nubase-build' },
      { name: 'project-build', serverName: 'mcp-project-build' },
    ],
  }],
  ['verifier-agent', {
    resourceName: 'nubase-verifier',
    skill: 'release-verify',
    identity: 'verifier-agent',
    mcpRoutes: [
      { name: 'nubase-read', serverName: 'mcp-nubase-read' },
      { name: 'project-read', serverName: 'mcp-project-read' },
    ],
  }],
  ['release-governor', {
    resourceName: 'nubase-release-governor',
    skill: 'release-govern',
    identity: 'release-governor',
    mcpRoutes: [
      { name: 'nubase-release', serverName: 'mcp-nubase-release' },
    ],
  }],
]);

const mcpGatewayHostByRuntime = new Map([
  ['agentteams', 'aigw-local.agentteams.io'],
  ['hiclaw', 'aigw-local.hiclaw.io'],
]);

const requiredIdentitySections = [
  'Name',
  'Role',
  'Capabilities',
  'Inputs',
  'Outputs',
  'Dependencies',
  'Decision Boundary',
  'Trace',
];

const requiredSkillSections = [
  'Purpose',
  'Scenario',
  'Inputs',
  'Outputs',
  'Call Conditions',
  'Dependencies',
  'Procedure',
  'Failure Handling',
  'Safety Constraints',
  'Reuse Boundaries',
  'Agent Relationship',
  'Version',
];

const requiredPlatformStatusTraceFields = ['taskId', 'runId', 'specDigest', 'approvalId'];
const requiredPlatformStatusTraceChecks = requiredPlatformStatusTraceFields.map(
  (field) => `status.${field} === ${field}`,
);
const requiredPlatformProvisioningChecks = [
  'verificationLevel=STATIC_CONTROL_PLANE',
  'state=PROVISIONED',
  'readiness.gateway=true',
  'advertisedEndpoints.gateway',
];
const requiredProjectNameMappingMarkers = [
  'appName',
  'projectRef',
  'goai_',
  'psx_agent_teams_project',
  'goai_psx_agent_teams_project',
  'BLOCKED',
];

await validatePackage();

if (errors.length > 0) {
  console.error(`GOAI contest package validation failed with ${errors.length} error(s):`);
  for (const error of errors) console.error(`- ${error}`);
  process.exitCode = 1;
} else {
  console.log('GOAI contest package validation passed.');
  for (const item of summary) console.log(`- ${item}`);
}

async function validatePackage() {
  await validatePackageTree();
  await validateAgentTeamsPackage();
  await validateIdentitiesAndSkills();
  await validateOperationalRunbooks();
  await validateScenario();
}

async function validateOperationalRunbooks() {
  const projectBootstrapDocuments = [
    [path.join(packageRoot, 'README.md'), 'package README'],
    [path.join(packageRoot, 'agentteams-v1.2.2', 'README.md'), 'AgentTeams README'],
    [path.join(packageRoot, 'compat', 'hiclaw-v1.1.2', 'README.md'), 'HiClaw compatibility runbook'],
    [path.join(packageRoot, 'compat', 'hiclaw-v1.1.2', 'PLATFORM_AUTOMATION_SECURITY.md'), 'Platform automation security runbook'],
    [path.join(packageRoot, 'compat', 'hiclaw-v1.1.2', 'team.yaml'), 'HiClaw compatibility team'],
  ];
  for (const [documentPath, documentLabel] of projectBootstrapDocuments) {
    const documentText = await readRequiredText(documentPath, documentLabel);
    if (!documentText) continue;
    validatePlatformProvisioningChecks(documentText, relative(documentPath));
    validateStaticControlPlaneBoundary(documentText, relative(documentPath));
  }

  for (const documentPath of [
    path.join(packageRoot, 'README.md'),
    path.join(packageRoot, 'agentteams-v1.2.2', 'README.md'),
    path.join(packageRoot, 'compat', 'hiclaw-v1.1.2', 'PLATFORM_AUTOMATION_SECURITY.md'),
    path.join(packageRoot, 'compat', 'hiclaw-v1.1.2', 'team.yaml'),
  ]) {
    const documentText = await readRequiredText(documentPath, 'project name mapping contract');
    if (documentText) validateProjectNameMapping(documentText, relative(documentPath));
  }

  const platformSecurityPath = path.join(
    packageRoot,
    'compat',
    'hiclaw-v1.1.2',
    'PLATFORM_AUTOMATION_SECURITY.md',
  );
  const platformSecurity = await readRequiredText(
    platformSecurityPath,
    'Platform automation security runbook',
  );
  if (platformSecurity && !platformSecurity.includes('--approval-binding "${GOAI_APPROVAL_ID}"')) {
    addError(`${relative(platformSecurityPath)} must bind the automation grant to the reviewed run approval ID`);
  }

  const compatibilityReadmePath = path.join(packageRoot, 'compat', 'hiclaw-v1.1.2', 'README.md');
  const compatibilityReadme = await readRequiredText(
    compatibilityReadmePath,
    'HiClaw compatibility runbook',
  );
  if (!compatibilityReadme) return;

  const requiredCleanupMarkers = [
    'cleanup_higress_refresh()',
    'trap cleanup_higress_refresh EXIT HUP INT TERM',
    'rm -f',
    '/tmp/refresh-higress-mcp-policy.py',
    '/tmp/goai-mcp-tool-policies.json',
    '/tmp/higress-session-cookie-gateway',
  ];
  for (const marker of requiredCleanupMarkers) {
    if (!compatibilityReadme.includes(marker)) {
      addError(`${relative(compatibilityReadmePath)} must install a fail-closed Higress refresh cleanup trap`);
      break;
    }
  }
  summary.push('Operational hygiene: Higress refresh helper, policy, and session cookie are removed by trap');
}

async function validatePackageTree() {
  const files = await walkFiles(packageRoot, 'package root');
  for (const file of files) {
    await scanFileForSensitiveMaterial(file);
  }
  summary.push(`Security boundary: ${files.length} regular files scanned; symlinks rejected`);
}

async function validateAgentTeamsPackage() {
  const runtimeRoot = path.join(packageRoot, 'agentteams-v1.2.2');
  const teamPath = path.join(runtimeRoot, 'team.yaml');
  const teamText = await readRequiredText(teamPath, 'AgentTeams team');
  if (!teamText) return;

  const teamFields = yamlScalarMap(teamText);
  expectEqual(teamFields.get('apiVersion'), 'agentteams.io/v1beta1', 'agentteams-v1.2.2/team.yaml apiVersion');
  expectEqual(teamFields.get('kind'), 'Team', 'agentteams-v1.2.2/team.yaml kind');
  expectEqual(teamFields.get('metadata.name'), 'nubase-agentic-delivery', 'agentteams-v1.2.2/team.yaml metadata.name');

  const members = yamlObjectList(teamText, 'spec.workerMembers');
  if (members.length !== expectedWorkers.size) {
    addError(`agentteams-v1.2.2/team.yaml must declare exactly ${expectedWorkers.size} spec.workerMembers; found ${members.length}`);
  }
  const memberNames = members.map((member) => member.name).filter(Boolean);
  assertUnique(memberNames, 'AgentTeams worker member names');
  const expectedResourceNames = [...expectedWorkers.values()].map((worker) => worker.resourceName).sort();
  if (JSON.stringify([...memberNames].sort()) !== JSON.stringify(expectedResourceNames)) {
    addError('agentteams-v1.2.2/team.yaml workerMembers do not match the four reviewed Worker resources');
  }
  const leaders = members.filter((member) => member.role === 'team_leader');
  if (leaders.length !== 1 || leaders[0]?.name !== 'nubase-delivery-lead') {
    addError('agentteams-v1.2.2/team.yaml must declare nubase-delivery-lead as the only team_leader');
  }

  for (const [directory, contract] of expectedWorkers) {
    const workerPath = path.join(runtimeRoot, 'workers', directory, 'worker.yaml');
    const workerText = await readRequiredText(workerPath, `Worker ${directory}`);
    if (!workerText) continue;
    const fields = yamlScalarMap(workerText);
    const label = relative(workerPath);
    expectEqual(fields.get('apiVersion'), 'agentteams.io/v1beta1', `${label} apiVersion`);
    expectEqual(fields.get('kind'), 'Worker', `${label} kind`);
    expectEqual(fields.get('metadata.name'), contract.resourceName, `${label} metadata.name`);
    expectPresent(fields.get('spec.model'), `${label} spec.model`);
    expectPresent(fields.get('spec.runtime'), `${label} spec.runtime`);
    expectEqual(fields.get('spec.state'), 'Running', `${label} spec.state`);

    const skills = yamlScalarList(workerText, 'spec.skills');
    if (skills.length !== 1 || skills[0] !== contract.skill) {
      addError(`${label} must bind exactly the ${contract.skill} Skill`);
    }
    const identityPath = yamlAnnotation(workerText, 'goai.nubase.io/identity-path');
    const skillPath = yamlAnnotation(workerText, 'goai.nubase.io/skill-path');
    expectEqual(identityPath, `identities/${contract.identity}.md`, `${label} identity annotation`);
    expectEqual(skillPath, `skills/${contract.skill}/SKILL.md`, `${label} Skill annotation`);
    if (!/^\s*mcpServers:\s*$/m.test(workerText)) {
      addError(`${label} must declare an MCP server boundary`);
    }
    const mcpServers = yamlObjectList(workerText, 'spec.mcpServers', label, true);
    if (!deepEqual(mcpServers, expectedMcpServers(contract, 'agentteams'))) {
      addError(`${label} MCP servers must match the reviewed role routes`);
    }
    if (/\bdeploy_app\b/.test(workerText)) {
      addError(`${label} must not claim that remote HTTP MCP exposes deploy_app`);
    }
    if (directory === 'release-governor'
      && (/\bPromote only\b/i.test(workerText) || /\bRole:.*\bpromotion\b/i.test(workerText))) {
      addError(`${label} must not instruct the Release Governor to execute promotion`);
    }
    if (directory === 'delivery-lead' || directory === 'verifier-agent' || directory === 'release-governor') {
      if (!/project-bootstrap-v1/.test(workerText)
        || !/PROVISIONED/.test(workerText)
        || !/BLOCKED/.test(workerText)) {
        addError(`${label} must preserve the PROVISIONED or BLOCKED project-bootstrap-v1 terminal contract`);
      }
    }
    if (directory === 'delivery-lead') {
      validateProjectNameMapping(workerText, label);
    }
    if (directory === 'verifier-agent' || directory === 'release-governor') {
      validatePlatformProvisioningChecks(workerText, label);
      validateStaticControlPlaneBoundary(workerText, label);
    }
  }

  await validateHiclawMcpServers();
  await validateMcpToolPolicies(runtimeRoot);

  summary.push('AgentTeams v1.2.2: 4 Workers, 4 distinct roles, 1 team leader');
}

async function validateHiclawMcpServers() {
  const teamPath = path.join(packageRoot, 'compat', 'hiclaw-v1.1.2', 'team.yaml');
  const teamText = await readRequiredText(teamPath, 'HiClaw compatibility team');
  if (!teamText) return;
  const label = relative(teamPath);
  const actualByResourceName = hiclawMcpServersByResource(teamText, label);
  const expectedResourceNames = new Set(
    [...expectedWorkers.values()].map((worker) => worker.resourceName),
  );
  for (const resourceName of actualByResourceName.keys()) {
    if (!expectedResourceNames.has(resourceName)) {
      addError(`${label} contains unexpected HiClaw role ${resourceName}`);
    }
  }
  for (const contract of expectedWorkers.values()) {
    const actual = actualByResourceName.get(contract.resourceName);
    if (!deepEqual(actual, expectedMcpServers(contract, 'hiclaw'))) {
      addError(`${label} ${contract.resourceName} MCP servers must match the reviewed role routes`);
    }
  }
}

async function validateMcpToolPolicies(runtimeRoot) {
  const policyPath = path.join(runtimeRoot, 'mcp-tool-policies.json');
  const policy = await readJson(policyPath, 'MCP tool policies');
  if (!policy) return;
  const policyLabel = relative(policyPath);
  assertExactKeys(
    policy,
    [
      'schemaVersion',
      'agentTeamsVersion',
      'enforcement',
      'optionalDefenseInDepth',
      'secretInjection',
      'deniedSensitiveToolsByTransport',
      'routes',
      'requiredToolsNotYetExposed',
    ],
    policyLabel,
  );
  expectEqual(policy.schemaVersion, '2.0.0', policyLabel + ' schemaVersion');
  expectEqual(policy.agentTeamsVersion, 'v1.2.2', policyLabel + ' agentTeamsVersion');
  expectEqual(policy.secretInjection, 'runtime-only', policyLabel + ' secretInjection');
  if (!deepEqual(policy.enforcement, [
    'Higress MCP server allowTools',
    'Higress consumer authorization',
  ])) {
    addError(policyLabel + ' must declare the two authoritative Higress enforcement boundaries');
  }

  const defense = policy.optionalDefenseInDepth ?? {};
  assertExactKeys(
    defense,
    ['component', 'controls', 'scope', 'notCovered'],
    policyLabel + ' optionalDefenseInDepth',
  );
  expectEqual(
    defense.component,
    'Nubase TypeScript stdio MCP bridge',
    policyLabel + ' optionalDefenseInDepth component',
  );
  if (!deepEqual(defense.controls, ['NUBASE_ALLOWED_TOOLS', 'NUBASE_DENIED_TOOLS'])) {
    addError(policyLabel + ' optional stdio controls must use the reviewed allow/deny variables');
  }
  expectEqual(
    defense.scope,
    'tools/list and call dispatch',
    policyLabel + ' optionalDefenseInDepth scope',
  );
  if (!deepEqual(defense.notCovered, ['direct Nubase CLI commands', 'remote Java /mcp'])) {
    addError(policyLabel + ' must explicitly exclude direct CLI and remote Java /mcp from stdio defense-in-depth');
  }

  const stdioTools = await extractStdioMcpTools();
  const javaTools = await extractJavaMcpTools();
  const platformTools = await extractPlatformMcpTools();
  await validatePlatformStatusSourceContract();
  if (stdioTools.length === 0 || javaTools.length === 0 || platformTools.length === 0) return;

  const sensitiveByTransport = policy.deniedSensitiveToolsByTransport ?? {};
  assertExactKeys(
    sensitiveByTransport,
    ['stdioBridgePolicy', 'javaHttpPolicy', 'platformHttpPolicy'],
    policyLabel + ' deniedSensitiveToolsByTransport',
  );
  const transportInventories = new Map([
    ['stdioBridgePolicy', stdioTools],
    ['javaHttpPolicy', javaTools],
    ['platformHttpPolicy', platformTools],
  ]);
  for (const [transport, inventory] of transportInventories) {
    const sensitive = Array.isArray(sensitiveByTransport[transport])
      ? sensitiveByTransport[transport]
      : [];
    if (!Array.isArray(sensitiveByTransport[transport])) {
      addError(policyLabel + ' deniedSensitiveToolsByTransport.' + transport + ' must be an array');
    }
    assertUnique(sensitive, policyLabel + ' deniedSensitiveToolsByTransport.' + transport);
    const inventorySet = new Set(inventory);
    for (const tool of sensitive) {
      if (!inventorySet.has(tool)) {
        addError(policyLabel + ' references unknown ' + transport + ' sensitive tool ' + tool);
      }
    }
  }

  const expectedConsumerByWorker = new Map([
    ['nubase-delivery-lead', 'worker-nubase-delivery-lead'],
    ['nubase-builder', 'worker-nubase-builder'],
    ['nubase-verifier', 'worker-nubase-verifier'],
    ['nubase-release-governor', 'worker-nubase-release-governor'],
  ]);
  const expectedRoutes = new Map([
    ['nubase-read', {
      server: 'mcp-nubase-read',
      workers: ['nubase-delivery-lead', 'nubase-verifier'],
      consumers: ['worker-nubase-delivery-lead', 'worker-nubase-verifier'],
      guards: {
        NUBASE_ALLOW_SQL_EXECUTE: false,
        NUBASE_ALLOW_DANGEROUS_SQL: false,
        NUBASE_ALLOW_ADMIN_WRITE: false,
      },
      stdioAllow: [
        'fetch_docs',
        'nubase_capabilities',
        'nubase_instructions',
        'memory_context',
        'memory_search',
        'rest_select',
        'sql_dry_run',
        'db_export_schema',
        'db_list_migrations',
        'deployments_list',
        'deployment_status',
        'deployment_logs',
        'app_workers_list',
        'app_worker_status',
        'storage_list_buckets',
        'storage_list_objects',
        'auth_get_settings',
        'gateway_usage',
        'gateway_usage_daily',
        'gateway_usage_by_model',
        'gateway_usage_logs',
        'gateway_pricing',
        'assets_list',
        'functions_list',
        'functions_logs',
        'cron_list',
        'cron_get',
        'cron_runs',
      ],
      javaReadiness: 'READY_AFTER_ROUTE_AND_AUTH_VERIFICATION',
      javaAllow: [
        'memorySearch',
        'memoryContext',
        'listTables',
        'getTableStructure',
        'exportRlsPolicies',
        'deploymentsList',
        'deploymentStatus',
        'deploymentLogs',
        'appWorkersList',
        'appWorkerStatus',
        'storageListBuckets',
        'assetsList',
        'functionsList',
        'functionsLogs',
        'cronList',
        'cronGet',
        'cronRuns',
      ],
    }],
    ['nubase-build', {
      server: 'mcp-nubase-build',
      workers: ['nubase-builder'],
      consumers: ['worker-nubase-builder'],
      guards: {
        NUBASE_ALLOW_SQL_EXECUTE: true,
        NUBASE_ALLOW_DANGEROUS_SQL: false,
        NUBASE_ALLOW_ADMIN_WRITE: true,
      },
      stdioAllow: [
        'fetch_docs',
        'nubase_capabilities',
        'nubase_instructions',
        'memory_context',
        'memory_search',
        'memory_write',
        'rest_select',
        'sql_dry_run',
        'sql_execute',
        'db_export_schema',
        'db_list_migrations',
        'deploy_app',
        'deployments_list',
        'deployment_status',
        'deployment_logs',
        'storage_list_buckets',
        'storage_list_objects',
        'auth_get_settings',
        'gateway_usage',
        'gateway_usage_by_model',
        'gateway_pricing',
        'assets_list',
        'assets_upload',
        'functions_list',
        'functions_deploy',
        'functions_invoke',
        'functions_logs',
        'cron_list',
        'cron_get',
        'cron_create',
        'cron_update',
        'cron_runs',
      ],
      javaReadiness: 'PARTIAL',
      javaAllow: [
        'memorySearch',
        'memoryContext',
        'memoryWrite',
        'listTables',
        'getTableStructure',
        'exportRlsPolicies',
        'deploymentsList',
        'deploymentStatus',
        'deploymentLogs',
        'deploymentStageAsset',
        'storageListBuckets',
        'assetsList',
        'assetsUpload',
        'functionsList',
        'functionsCreate',
        'functionsUpdate',
        'functionsDeployBundle',
        'functionsLogs',
        'cronList',
        'cronGet',
        'cronCreate',
        'cronUpdate',
        'cronRuns',
      ],
    }],
    ['nubase-release', {
      server: 'mcp-nubase-release',
      workers: ['nubase-release-governor'],
      consumers: ['worker-nubase-release-governor'],
      guards: {
        NUBASE_ALLOW_SQL_EXECUTE: false,
        NUBASE_ALLOW_DANGEROUS_SQL: false,
        NUBASE_ALLOW_ADMIN_WRITE: true,
      },
      stdioAllow: [
        'fetch_docs',
        'nubase_capabilities',
        'nubase_instructions',
        'memory_context',
        'memory_search',
        'memory_write',
        'sql_dry_run',
        'db_export_schema',
        'db_list_migrations',
        'deployments_list',
        'deployment_status',
        'deployment_logs',
        'deployment_rollback',
        'app_workers_list',
        'app_worker_status',
        'gateway_usage',
        'gateway_usage_daily',
        'gateway_usage_by_model',
        'gateway_usage_logs',
        'gateway_pricing',
        'assets_list',
        'functions_list',
        'functions_logs',
        'cron_list',
        'cron_get',
        'cron_runs',
      ],
      javaReadiness: 'READY_AFTER_ROUTE_AND_AUTH_VERIFICATION',
      javaAllow: [
        'memorySearch',
        'memoryContext',
        'memoryWrite',
        'listTables',
        'getTableStructure',
        'exportRlsPolicies',
        'deploymentsList',
        'deploymentStatus',
        'deploymentLogs',
        'deploymentRollback',
        'appWorkersList',
        'appWorkerStatus',
        'assetsList',
        'functionsList',
        'functionsLogs',
        'cronList',
        'cronGet',
        'cronRuns',
      ],
    }],
    ['project-build', {
      server: 'mcp-project-build',
      workers: ['nubase-builder'],
      consumers: ['worker-nubase-builder'],
      transport: 'platform',
      platformReadiness: 'READY_AFTER_ROUTE_AND_AUTH_VERIFICATION',
      platformAllow: [
        'platformProjectCreate',
        'platformProjectProvision',
        'platformProjectStatus',
      ],
    }],
    ['project-read', {
      server: 'mcp-project-read',
      workers: ['nubase-delivery-lead', 'nubase-verifier'],
      consumers: ['worker-nubase-delivery-lead', 'worker-nubase-verifier'],
      transport: 'platform',
      platformReadiness: 'READY_AFTER_ROUTE_AND_AUTH_VERIFICATION',
      platformAllow: ['platformProjectStatus'],
    }],
  ]);

  if (!Array.isArray(policy.routes) || policy.routes.length !== expectedRoutes.size) {
    addError(policyLabel + ' must declare exactly ' + expectedRoutes.size + ' role routes');
    return;
  }
  assertUnique(policy.routes.map((route) => route.name), 'MCP policy route names');
  assertUnique(policy.routes.map((route) => route.mcpServerName), 'MCP policy server names');
  const workerRouteBindings = [];
  const consumerRouteBindings = [];

  for (const route of policy.routes) {
    const label = policyLabel + ' route ' + (route.name ?? '<unnamed>');
    const expected = expectedRoutes.get(route.name);
    if (!expected) {
      addError(label + ' is not an expected route');
      continue;
    }
    const isPlatformRoute = expected.transport === 'platform';
    assertExactKeys(
      route,
      isPlatformRoute
        ? ['name', 'mcpServerName', 'agentTeamsWorkers', 'higressConsumers', 'platformHttpPolicy']
        : [
          'name',
          'mcpServerName',
          'agentTeamsWorkers',
          'higressConsumers',
          'stdioBridgePolicy',
          'javaHttpPolicy',
        ],
      label,
    );
    expectEqual(route.mcpServerName, expected.server, label + ' mcpServerName');

    const workers = Array.isArray(route.agentTeamsWorkers) ? route.agentTeamsWorkers : [];
    const consumers = Array.isArray(route.higressConsumers) ? route.higressConsumers : [];
    if (!Array.isArray(route.agentTeamsWorkers)) addError(label + ' agentTeamsWorkers must be an array');
    if (!Array.isArray(route.higressConsumers)) addError(label + ' higressConsumers must be an array');
    assertUnique(workers, label + ' agentTeamsWorkers');
    assertUnique(consumers, label + ' higressConsumers');
    if (!deepEqual(workers, expected.workers)) {
      addError(label + ' agentTeamsWorkers do not match the reviewed Worker resources');
    }
    if (!deepEqual(consumers, expected.consumers)) {
      addError(label + ' higressConsumers do not match the reviewed Console API consumer identities');
    }
    if (workers.length !== consumers.length) {
      addError(label + ' must declare one Higress consumer for each AgentTeams Worker');
    }
    for (let index = 0; index < workers.length; index += 1) {
      const expectedConsumer = expectedConsumerByWorker.get(workers[index]);
      if (!expectedConsumer || consumers[index] !== expectedConsumer) {
        addError(
          label + ' consumer mapping must explicitly bind ' + (workers[index] ?? '<missing>')
          + ' to ' + (expectedConsumer ?? '<unknown>')
          + '; found ' + (consumers[index] ?? '<missing>'),
        );
      }
    }
    workerRouteBindings.push(...workers.map((worker) => `${worker}:${route.name}`));
    consumerRouteBindings.push(...consumers.map((consumer) => `${consumer}:${route.name}`));

    if (isPlatformRoute) {
      const platformPolicy = route.platformHttpPolicy ?? {};
      assertExactKeys(
        platformPolicy,
        ['endpoint', 'readiness', 'readinessReason', 'allowTools', 'denyTools'],
        label + ' platformHttpPolicy',
      );
      expectEqual(platformPolicy.endpoint, '/platform/mcp', label + ' platformHttpPolicy endpoint');
      expectEqual(
        platformPolicy.readiness,
        expected.platformReadiness,
        label + ' platformHttpPolicy readiness',
      );
      expectPresent(platformPolicy.readinessReason, label + ' platformHttpPolicy readinessReason');
      const platformPartition = validateToolPartition(
        platformPolicy,
        platformTools,
        sensitiveByTransport.platformHttpPolicy ?? [],
        label + ' platformHttpPolicy',
        /^[a-z][A-Za-z0-9]*$/,
        'exact camelCase platform',
      );
      if (!sameStringSet(platformPartition.allow, expected.platformAllow)) {
        addError(label + ' platformHttpPolicy allowTools must match the reviewed platform role partition');
      }
      if (!/independent|separate|isolated/i.test(platformPolicy.readinessReason ?? '')
        || !/SQL/i.test(platformPolicy.readinessReason ?? '')
        || !/key|secret|upstream token/i.test(platformPolicy.readinessReason ?? '')) {
        addError(label + ' platformHttpPolicy readinessReason must disclose isolation and forbidden sensitive inputs');
      }
      continue;
    }

    const stdioPolicy = route.stdioBridgePolicy ?? {};
    assertExactKeys(
      stdioPolicy,
      ['bridgeGuards', 'allowTools', 'denyTools'],
      label + ' stdioBridgePolicy',
    );
    assertExactKeys(
      stdioPolicy.bridgeGuards ?? {},
      ['NUBASE_ALLOW_SQL_EXECUTE', 'NUBASE_ALLOW_DANGEROUS_SQL', 'NUBASE_ALLOW_ADMIN_WRITE'],
      label + ' stdioBridgePolicy bridgeGuards',
    );
    if (!deepEqual(stdioPolicy.bridgeGuards, expected.guards)) {
      addError(label + ' stdioBridgePolicy bridgeGuards do not match the least-privilege role');
    }
    const stdioPartition = validateToolPartition(
      stdioPolicy,
      stdioTools,
      sensitiveByTransport.stdioBridgePolicy ?? [],
      label + ' stdioBridgePolicy',
      /^[a-z][a-z0-9_]*$/,
      'snake_case stdio',
    );
    if (!sameStringSet(stdioPartition.allow, expected.stdioAllow)) {
      addError(label + ' stdioBridgePolicy allowTools must match the reviewed stdio role partition');
    }

    const javaPolicy = route.javaHttpPolicy ?? {};
    assertExactKeys(
      javaPolicy,
      ['readiness', 'readinessReason', 'allowTools', 'denyTools'],
      label + ' javaHttpPolicy',
    );
    expectEqual(javaPolicy.readiness, expected.javaReadiness, label + ' javaHttpPolicy readiness');
    expectPresent(javaPolicy.readinessReason, label + ' javaHttpPolicy readinessReason');
    const javaPartition = validateToolPartition(
      javaPolicy,
      javaTools,
      sensitiveByTransport.javaHttpPolicy ?? [],
      label + ' javaHttpPolicy',
      /^[a-z][A-Za-z0-9]*$/,
      'exact camelCase Java',
    );
    if (!sameStringSet(javaPartition.allow, expected.javaAllow)) {
      addError(label + ' javaHttpPolicy allowTools must match the reviewed Java role partition');
    }
    if (route.name === 'nubase-build') {
      if (javaPolicy.readiness !== 'PARTIAL') {
        addError(label + ' Java Builder readiness must remain PARTIAL');
      }
      const javaSqlTools = ['executeSql', 'executeSqlDryRun'];
      if (javaSqlTools.some((tool) => javaPartition.allow.includes(tool)
        || !javaPartition.deny.includes(tool))) {
        addError(label + ' Java Builder must deny executeSql and executeSqlDryRun');
      }
      if (!/no SQL execution or dry-run capability/i.test(javaPolicy.readinessReason ?? '')
        || !/schema apply is unavailable/i.test(javaPolicy.readinessReason ?? '')) {
        addError(label + ' Java Builder readinessReason must disclose that SQL execution, dry-run, and schema apply are unavailable');
      }
    }
  }

  assertUnique(workerRouteBindings, 'MCP policy Worker/route bindings');
  assertUnique(consumerRouteBindings, 'MCP policy consumer/route bindings');

  if (!Array.isArray(policy.requiredToolsNotYetExposed)
    || !policy.requiredToolsNotYetExposed.includes('deployment_promote')) {
    addError(policyLabel + ' must disclose that deployment_promote is not yet exposed');
  }
  if (stdioTools.includes('deployment_promote') || javaTools.includes('deploymentPromote')) {
    addError(policyLabel + ' marks deployment promotion missing even though a source inventory contains it');
  }
  summary.push(
    'MCP policy: ' + policy.routes.length + ' role routes classify all ' + stdioTools.length
    + ' stdio tools, ' + javaTools.length + ' Java tenant HTTP tools, and '
    + platformTools.length + ' platform HTTP tools',
  );
}

async function extractStdioMcpTools() {
  const toolSourcePath = path.join(
    repositoryRoot,
    'frontend',
    'packages',
    'mcp-bridge',
    'src',
    'tools.ts',
  );
  let toolSource;
  try {
    toolSource = await readFile(toolSourcePath, 'utf8');
  } catch {
    addError('cannot read frontend/packages/mcp-bridge/src/tools.ts to validate MCP policy completeness');
    return [];
  }
  const tableBody = toolSource.split('const TOOL_TABLE', 2)[1]?.split('export const TOOLS', 1)[0] ?? '';
  const tools = [...tableBody.matchAll(/^  ([a-z][a-z0-9_]+): \{/gm)].map((match) => match[1]);
  if (tools.length === 0) {
    addError('could not extract the Nubase stdio MCP tool inventory');
    return [];
  }
  assertUnique(tools, 'Nubase stdio MCP source tool names');
  return tools;
}

async function extractJavaMcpTools() {
  const toolsRoot = path.join(repositoryRoot, 'src', 'main', 'java', 'ai', 'nubase', 'mcp', 'tools');
  let entries;
  try {
    entries = await readdir(toolsRoot, { withFileTypes: true });
  } catch {
    addError('cannot read src/main/java/ai/nubase/mcp/tools to validate Java MCP policy completeness');
    return [];
  }
  const sourceFiles = entries
    .filter((entry) => entry.isFile() && /McpTools\.java$/.test(entry.name))
    .map((entry) => path.join(toolsRoot, entry.name))
    .sort();
  if (sourceFiles.length === 0) {
    addError('could not find Java *McpTools.java sources');
    return [];
  }

  const tools = [];
  for (const sourcePath of sourceFiles) {
    const source = await readFile(sourcePath, 'utf8');
    let awaitingMethod = false;
    let annotationLine = 0;
    const lines = source.split(/\r?\n/);
    for (let index = 0; index < lines.length; index += 1) {
      const line = lines[index];
      if (/^\s*@Tool\b/.test(line)) {
        if (awaitingMethod) {
          addError(path.relative(repositoryRoot, sourcePath) + ':' + annotationLine + ' @Tool has no public method');
        }
        awaitingMethod = true;
        annotationLine = index + 1;
      }
      if (!awaitingMethod) continue;
      const method = line.match(
        /^\s*public\s+(?!class\b|interface\b|enum\b|record\b).*\b([A-Za-z_$][A-Za-z0-9_$]*)\s*\(/,
      );
      if (method) {
        tools.push(method[1]);
        awaitingMethod = false;
      }
    }
    if (awaitingMethod) {
      addError(path.relative(repositoryRoot, sourcePath) + ':' + annotationLine + ' @Tool has no public method');
    }
  }
  if (tools.length === 0) {
    addError('could not extract the Nubase Java HTTP MCP tool inventory');
    return [];
  }
  assertUnique(tools, 'Nubase Java HTTP MCP source tool names');
  return tools;
}

async function extractPlatformMcpTools() {
  const platformRoot = path.join(
    repositoryRoot,
    'src',
    'main',
    'java',
    'ai',
    'nubase',
    'platform',
    'mcp',
  );
  const sourceNames = ['PlatformMcpController.java', 'PlatformProjectAutomationFacade.java'];
  const tools = [];
  for (const sourceName of sourceNames) {
    const sourcePath = path.join(platformRoot, sourceName);
    let source;
    try {
      source = await readFile(sourcePath, 'utf8');
    } catch {
      addError('cannot read ' + path.relative(repositoryRoot, sourcePath)
        + ' to validate the independent Platform MCP inventory');
      return [];
    }
    for (const match of source.matchAll(/"(platformProject[A-Z][A-Za-z0-9]*)"/g)) {
      if (!tools.includes(match[1])) tools.push(match[1]);
    }
  }
  if (tools.length === 0) {
    addError('could not extract the explicit Platform MCP tool registry');
    return [];
  }
  assertUnique(tools, 'Platform MCP canonical source tool names');
  return tools;
}

async function validatePlatformStatusSourceContract() {
  const sourcePath = path.join(
    repositoryRoot,
    'src',
    'main',
    'java',
    'ai',
    'nubase',
    'platform',
    'mcp',
    'PlatformProjectDtos.java',
  );
  let source;
  try {
    source = await readFile(sourcePath, 'utf8');
  } catch {
    addError('cannot read ' + path.relative(repositoryRoot, sourcePath)
      + ' to validate the Platform MCP status trace contract');
    return;
  }
  const statusBody = source.match(/public record StatusResult\(([\s\S]*?)\)\s*\{/u)?.[1] ?? '';
  const requiredResponseFields = [
    ...requiredPlatformStatusTraceFields,
    'state',
    'verificationLevel',
    'readiness',
    'advertisedEndpoints',
  ];
  const missing = requiredResponseFields.filter(
    (field) => !new RegExp(`\\b${field}\\b`).test(statusBody),
  );
  if (missing.length) {
    addError('PlatformProjectDtos.StatusResult must expose static provisioning fields: ' + missing.join(', '));
  }

  const facadePath = path.join(path.dirname(sourcePath), 'PlatformProjectAutomationFacade.java');
  let facadeSource;
  try {
    facadeSource = await readFile(facadePath, 'utf8');
  } catch {
    addError('cannot read ' + path.relative(repositoryRoot, facadePath)
      + ' to validate the Platform MCP static provisioning states');
    return;
  }
  for (const marker of ['STATIC_CONTROL_PLANE', 'PROVISIONED']) {
    if (!facadeSource.includes(`"${marker}"`)) {
      addError(`PlatformProjectAutomationFacade must emit ${marker}`);
    }
  }
}

function validateToolPartition(policy, inventory, sensitiveTools, label, namePattern, convention) {
  const allow = Array.isArray(policy.allowTools) ? policy.allowTools : [];
  const deny = Array.isArray(policy.denyTools) ? policy.denyTools : [];
  if (!Array.isArray(policy.allowTools)) addError(label + ' allowTools must be an array');
  if (!Array.isArray(policy.denyTools)) addError(label + ' denyTools must be an array');
  assertUnique(allow, label + ' allowTools');
  assertUnique(deny, label + ' denyTools');

  const overlap = allow.filter((tool) => deny.includes(tool));
  if (overlap.length) addError(label + ' allowTools and denyTools overlap: ' + overlap.join(', '));
  const classified = [...allow, ...deny];
  const invalidNames = classified.filter((tool) => typeof tool !== 'string' || !namePattern.test(tool));
  if (invalidNames.length) {
    addError(label + ' uses invalid ' + convention + ' tool names: ' + invalidNames.join(', '));
  }

  const inventorySet = new Set(inventory);
  const union = new Set(classified);
  const missing = inventory.filter((tool) => !union.has(tool));
  const unknown = [...union].filter((tool) => !inventorySet.has(tool));
  if (missing.length) addError(label + ' does not classify tools: ' + missing.join(', '));
  if (unknown.length) addError(label + ' classifies unknown tools: ' + unknown.join(', '));
  for (const tool of sensitiveTools) {
    if (!deny.includes(tool)) addError(label + ' must deny sensitive tool ' + tool);
  }
  return { allow, deny };
}

function sameStringSet(actual, expected) {
  return actual.length === expected.length
    && deepEqual([...actual].sort(), [...expected].sort());
}

async function validateIdentitiesAndSkills() {
  for (const [directory, contract] of expectedWorkers) {
    const identityPath = path.join(packageRoot, 'identities', `${contract.identity}.md`);
    const identityText = await readRequiredText(identityPath, `Identity ${directory}`);
    if (identityText) {
      const sections = markdownSections(identityText);
      assertExactSectionSet(sections, requiredIdentitySections, relative(identityPath));
      for (const section of requiredIdentitySections) {
        if (!sections.get(section)?.trim()) addError(`${relative(identityPath)} section ${section} must not be empty`);
      }
      validatePlatformStatusTraceChecks(identityText, relative(identityPath));
      validatePlatformProvisioningChecks(identityText, relative(identityPath));
      validateStaticControlPlaneBoundary(identityText, relative(identityPath));
      if (directory === 'delivery-lead') {
        validateProjectNameMapping(identityText, relative(identityPath));
      }
    }

    const skillPath = path.join(packageRoot, 'skills', contract.skill, 'SKILL.md');
    const skillText = await readRequiredText(skillPath, `Skill ${contract.skill}`);
    if (!skillText) continue;
    const frontmatter = parseFrontmatter(skillText, relative(skillPath));
    assertExactKeys(frontmatter, ['name', 'description', 'assign_when', 'version'], `${relative(skillPath)} frontmatter`);
    expectEqual(frontmatter.name, contract.skill, `${relative(skillPath)} frontmatter name`);
    expectPresent(frontmatter.description, `${relative(skillPath)} frontmatter description`);
    expectPresent(frontmatter.assign_when, `${relative(skillPath)} frontmatter assign_when`);
    if (!/^\d+\.\d+\.\d+$/.test(frontmatter.version ?? '')) {
      addError(`${relative(skillPath)} frontmatter version must be semantic x.y.z`);
    }
    const sections = markdownSections(skillText);
    assertExactSectionSet(sections, requiredSkillSections, relative(skillPath));
    for (const section of requiredSkillSections) {
      if (!sections.get(section)?.trim()) addError(`${relative(skillPath)} section ${section} must not be empty`);
    }
    if (/\bdeploy_app\b/.test(skillText) && /remote|HTTP MCP/i.test(skillText)) {
      addError(`${relative(skillPath)} must not claim that remote HTTP MCP exposes deploy_app`);
    }
    if (!/project-bootstrap-v1/.test(skillText)
      || !/BLOCKED/.test(skillText)
      || !/PROVISIONED/.test(skillText)) {
      addError(`${relative(skillPath)} must preserve the reviewed project-bootstrap-v1 terminal contract`);
    }
    validatePlatformProvisioningChecks(skillText, relative(skillPath));
    validateStaticControlPlaneBoundary(skillText, relative(skillPath));
    if (!/approvalId/.test(skillText)) {
      addError(`${relative(skillPath)} must preserve the project-bootstrap approval binding`);
    }
    validatePlatformStatusTraceChecks(skillText, relative(skillPath));
    if (contract.skill === 'app-plan') {
      validateProjectNameMapping(skillText, relative(skillPath));
    }
  }

  summary.push('Identity and Skill contracts: 4 identities and 4 versioned Skills');
}

function validatePlatformStatusTraceChecks(text, label) {
  const missing = requiredPlatformStatusTraceChecks.filter((check) => !text.includes(check));
  if (missing.length) {
    addError(`${label} must exact-match all platformProjectStatus trace echoes; missing: ${missing.join(', ')}`);
  }
}

function validateProjectNameMapping(text, label) {
  const missing = requiredProjectNameMappingMarkers.filter((marker) => !text.includes(marker));
  if (missing.length) {
    addError(`${label} must preserve the deterministic display-name to project-ref mapping; missing: ${missing.join(', ')}`);
  }
}

function validatePlatformProvisioningChecks(text, label) {
  const missing = requiredPlatformProvisioningChecks.filter((check) => !text.includes(check));
  if (missing.length) {
    addError(`${label} must require explicit static control-plane provisioning evidence; missing: ${missing.join(', ')}`);
  }
}

function validateStaticControlPlaneBoundary(text, label) {
  const requiredBoundaryGroups = [
    ['Functions/MCP external reachability', [/Functions/i, /MCP/i, /external[\s\S]{0,80}reachability|外部可达/i]],
    ['upstream HTTP or billing call', [/upstream/i, /HTTP/i, /billable|billing|计费/i]],
    ['application deployment', [/application deployment|应用部署|应用已部署/i]],
    ['production readiness', [/production readiness|生产可用/i]],
  ];
  const missing = requiredBoundaryGroups
    .filter(([, patterns]) => !patterns.every((pattern) => pattern.test(text)))
    .map(([boundary]) => boundary);
  if (missing.length) {
    addError(`${label} must limit PROVISIONED to static control-plane evidence; missing boundary: ${missing.join(', ')}`);
  }
}

async function validateScenario() {
  const scenarioRoot = path.join(packageRoot, 'scenario');
  const contractsRoot = path.join(packageRoot, 'contracts');

  const manifestSchema = await readJson(path.join(contractsRoot, 'deployment-manifest.schema.json'), 'deployment manifest schema');
  const functionSchema = await readJson(path.join(contractsRoot, 'function-manifest.schema.json'), 'Function manifest schema');
  const verificationSchema = await readJson(path.join(contractsRoot, 'verification-report.schema.json'), 'verification report schema');
  const approvalSchema = await readJson(path.join(contractsRoot, 'approval.schema.json'), 'approval schema');
  const traceSchema = await readJson(path.join(contractsRoot, 'trace-event.schema.json'), 'trace schema');
  const taskStateSchema = await readJson(path.join(contractsRoot, 'task-state.schema.json'), 'shared task state schema');
  if (!manifestSchema || !functionSchema || !verificationSchema || !approvalSchema || !traceSchema || !taskStateSchema) return;

  const manifestPath = path.join(scenarioRoot, 'nubase.deploy.json');
  const manifest = await readJson(manifestPath, 'deployment manifest');
  if (!manifest) return;
  validateBySchema(manifest, manifestSchema, 'scenario/nubase.deploy.json');
  validateForbiddenManifestFields(manifest);

  const migrationNames = [];
  for (const migration of manifest.migrations ?? []) {
    migrationNames.push(migration.name);
    const sqlPath = await resolveRegularPath(scenarioRoot, migration.file, `migration ${migration.name}`);
    if (!sqlPath) continue;
    const sql = await readFile(sqlPath, 'utf8');
    validateSql(sql, relative(sqlPath));
  }
  assertUnique(migrationNames, 'migration names');

  const functionNames = [];
  for (const fn of manifest.functions ?? []) {
    functionNames.push(fn.name);
    const functionDir = await resolveDirectory(scenarioRoot, fn.dir, `Function ${fn.name}`);
    if (!functionDir) continue;
    const functionManifestPath = path.join(functionDir, 'nubase-function.json');
    const functionManifest = await readJson(functionManifestPath, `Function manifest ${fn.name}`);
    if (functionManifest) {
      validateBySchema(functionManifest, functionSchema, relative(functionManifestPath));
      expectEqual(functionManifest.name, fn.name, `${relative(functionManifestPath)} name`);
      expectEqual(functionManifest.slug, fn.name, `${relative(functionManifestPath)} slug`);
      await resolveRegularPath(functionDir, functionManifest.entrypoint, `Function ${fn.name} entrypoint`);
    }
  }
  assertUnique(functionNames, 'Function names');

  for (const job of manifest.cron ?? []) {
    if (job.targetType === 'edge_function' && !functionNames.includes(job.functionSlug)) {
      addError(`cron job ${job.name} references unknown Function ${job.functionSlug}`);
    }
    if (job.enabled !== false) {
      addError(`contest dry-run cron job ${job.name} must be disabled`);
    }
  }

  const assetsDir = await resolveDirectory(scenarioRoot, manifest.assets?.dir, 'Assets directory');
  if (assetsDir) await resolveRegularPath(assetsDir, 'index.html', 'Assets entrypoint');

  const verificationPath = path.join(scenarioRoot, 'verification-report.example.json');
  const verification = await readJson(verificationPath, 'verification report example');
  if (verification) {
    validateBySchema(verification, verificationSchema, 'scenario/verification-report.example.json');
    await validateVerificationSemantics(verification, manifestPath, scenarioRoot);
  }

  const approvalPath = path.join(scenarioRoot, 'approval.example.json');
  const approval = await readJson(approvalPath, 'approval example');
  if (approval) {
    validateBySchema(approval, approvalSchema, 'scenario/approval.example.json');
    validateApprovalSemantics(approval, verification);
    const approvedManifestPath = await resolveRegularPath(scenarioRoot, approval.requestedAction?.manifest, 'approved manifest');
    if (approvedManifestPath) {
      const digest = `sha256:${createHash('sha256').update(await readFile(approvedManifestPath)).digest('hex')}`;
      expectEqual(approval.requestedAction.manifestDigest, digest, 'approval manifestDigest');
    }
    const approvedVerificationPath = await resolveRegularPath(
      scenarioRoot,
      approval.requestedAction?.verificationReport,
      'approved verification report',
    );
    if (approvedVerificationPath) {
      const digest = `sha256:${createHash('sha256').update(await readFile(approvedVerificationPath)).digest('hex')}`;
      expectEqual(approval.requestedAction.verificationDigest, digest, 'approval verificationDigest');
    }
    for (const evidence of approval.evidence ?? []) {
      await resolveRegularPath(scenarioRoot, evidence, `approval evidence ${evidence}`);
    }
  }

  const tracePath = path.join(scenarioRoot, 'trace.example.jsonl');
  const trace = await readJsonLines(tracePath, 'trace example');
  for (let index = 0; index < trace.length; index += 1) {
    validateBySchema(trace[index], traceSchema, `scenario/trace.example.jsonl:${index + 1}`);
  }
  validateTraceSemantics(trace, approval, verification);

  const taskStatePath = path.join(scenarioRoot, 'task-state.example.json');
  const taskState = await readJson(taskStatePath, 'shared task state example');
  if (taskState) {
    validateBySchema(taskState, taskStateSchema, 'scenario/task-state.example.json');
    await validateTaskStateSemantics(taskState, approval, verification, trace, scenarioRoot);
  }

  summary.push(`Scenario: ${manifest.migrations.length} migration, ${manifest.functions.length} Function, ${manifest.cron.length} disabled cron job`);
  summary.push(`Evidence contracts: 1 immutable verification report, 1 human approval fixture, and ${trace.length} correlated trace events`);
  summary.push(`Shared state: ${taskState?.history?.length ?? 0} monotonic versions with artifact digests`);
}

async function validateTaskStateSemantics(state, approval, verification, trace, scenarioRoot) {
  const phaseOwners = new Map([
    ['intake', ['delivery-lead', 'system']],
    ['planned', ['delivery-lead']],
    ['building', ['builder-agent']],
    ['verifying', ['verifier-agent']],
    ['awaiting_approval', ['release-governor']],
    ['approved', ['human-reviewer']],
    ['rejected', ['human-reviewer']],
    ['rollback_required', ['release-governor', 'system']],
    ['rolled_back', ['release-governor']],
    ['completed', ['delivery-lead', 'system']],
    ['blocked', ['delivery-lead', 'builder-agent', 'verifier-agent', 'release-governor', 'system']],
  ]);
  const allowedTransitions = new Map([
    ['intake', ['planned', 'blocked']],
    ['planned', ['building', 'blocked']],
    ['building', ['verifying', 'rollback_required', 'blocked']],
    ['verifying', ['awaiting_approval', 'rollback_required', 'blocked']],
    ['awaiting_approval', ['approved', 'rejected', 'blocked']],
    ['approved', ['completed', 'rollback_required']],
    ['rejected', ['completed']],
    ['rollback_required', ['rolled_back', 'blocked']],
    ['rolled_back', ['completed']],
    ['blocked', ['planned', 'rollback_required']],
  ]);
  let previousSequence = 0;
  let previousVersion = 0;
  let previousTimestamp = 0;
  let previousPhase;
  for (const item of state.history ?? []) {
    if (item.sequence <= previousSequence) addError('task state history sequence must be strictly increasing');
    if (item.version <= previousVersion) addError('task state history version must be strictly increasing');
    const timestamp = Date.parse(item.updatedAt);
    if (Number.isFinite(timestamp) && timestamp <= previousTimestamp) addError('task state history timestamps must be strictly increasing');
    const owners = phaseOwners.get(item.phase) ?? [];
    if (!owners.includes(item.updatedBy)) {
      addError(`task state phase ${item.phase} cannot be written by ${item.updatedBy}`);
    }
    if (previousPhase && !(allowedTransitions.get(previousPhase) ?? []).includes(item.phase)) {
      addError(`illegal task state transition: ${previousPhase} -> ${item.phase}`);
    }
    previousSequence = item.sequence;
    previousVersion = item.version;
    if (Number.isFinite(timestamp)) previousTimestamp = timestamp;
    previousPhase = item.phase;
  }
  const current = state.history?.at(-1);
  if (!current
    || current.sequence !== state.sequence
    || current.version !== state.version
    || current.phase !== state.phase) {
    addError('task state current sequence, version, and phase must match the final history item');
  }
  const assignmentAgents = (state.assignments ?? []).map((assignment) => assignment.agentId);
  const expectedAgents = [...expectedWorkers.keys()].sort();
  if (JSON.stringify([...assignmentAgents].sort()) !== JSON.stringify(expectedAgents)) {
    addError('task state assignments must contain each reviewed Agent exactly once');
  }
  assertUnique(assignmentAgents, 'task state assignment agent IDs');
  for (const assignment of state.assignments ?? []) {
    if (assignment.inputVersion > state.version) addError(`task state assignment ${assignment.agentId} references a future inputVersion`);
  }
  const artifactNames = [];
  const artifactPaths = [];
  for (const artifact of state.artifacts ?? []) {
    artifactNames.push(artifact.name);
    artifactPaths.push(artifact.path);
    if (artifact.version > state.version) addError(`task state artifact ${artifact.name} references a future version`);
    const file = await resolveRegularPath(scenarioRoot, artifact.path, `task state artifact ${artifact.name}`);
    if (!file) continue;
    const digest = `sha256:${createHash('sha256').update(await readFile(file)).digest('hex')}`;
    expectEqual(artifact.digest, digest, `task state artifact ${artifact.name} digest`);
  }
  assertUnique(artifactNames, 'task state artifact names');
  assertUnique(artifactPaths, 'task state artifact paths');
  if (verification) {
    const verificationArtifact = (state.artifacts ?? []).find(
      (artifact) => artifact.path === approval?.requestedAction?.verificationReport,
    );
    if (!verificationArtifact) {
      addError('task state must contain the approval-bound verification report artifact');
    } else {
      expectEqual(verificationArtifact.name, 'verification-report', 'task state verification artifact name');
      expectEqual(verificationArtifact.producedBy, 'verifier-agent', 'task state verification artifact producer');
      expectEqual(verificationArtifact.digest, approval?.requestedAction?.verificationDigest, 'task state verification artifact digest');
    }
  }
  if (approval) {
    expectEqual(state.runId, approval.runId, 'task state runId');
    expectEqual(state.approval?.approvalId, approval.approvalId, 'task state approvalId');
    expectEqual(state.approval?.status, approval.decision, 'task state approval status');
    expectEqual(state.approval?.requestedAt, approval.requestedAt, 'task state approval requestedAt');
    expectEqual(state.approval?.decidedAt, approval.decidedAt, 'task state approval decidedAt');
    expectEqual(state.approval?.version, state.version, 'task state approval version');
  }
  const firstTrace = trace[0];
  if (firstTrace) {
    expectEqual(state.runId, firstTrace.runId, 'task state and trace runId');
    expectEqual(state.taskId, firstTrace.correlation?.taskId, 'task state and trace taskId');
  }
}

function validateForbiddenManifestFields(manifest) {
  const serialized = JSON.stringify(manifest);
  const forbiddenNames = ['secrets', 'contentBase64', 'noVerifyJwt', 'no-security-scan'];
  for (const name of forbiddenNames) {
    if (Object.hasOwn(manifest, name) || serialized.includes(`"${name}"`)) {
      addError(`scenario/nubase.deploy.json must not contain ${name}`);
    }
  }
  if (manifest.securityScan !== true) addError('scenario/nubase.deploy.json securityScan must be true');
  if (manifest.continueOnError !== false) addError('scenario/nubase.deploy.json continueOnError must be false');
  if (manifest.verifyFunctions !== true) addError('scenario/nubase.deploy.json verifyFunctions must be true');
}

async function validateVerificationSemantics(verification, manifestPath, scenarioRoot) {
  const manifestDigest = `sha256:${createHash('sha256').update(await readFile(manifestPath)).digest('hex')}`;
  expectEqual(verification.manifest?.path, path.basename(manifestPath), 'verification report manifest path');
  expectEqual(verification.manifest?.digest, manifestDigest, 'verification report manifest digest');
  const checkNames = (verification.checks ?? []).map((check) => check.name);
  assertUnique(checkNames, 'verification report check names');
  if (verification.result === 'passed' && (verification.checks ?? []).some((check) => check.status !== 'passed')) {
    addError('passed verification report cannot contain a failed or blocked check');
  }
  for (const check of verification.checks ?? []) {
    for (const evidence of check.evidence ?? []) {
      await resolveRegularPath(scenarioRoot, evidence, `verification evidence ${check.name}/${evidence}`);
    }
  }
}

function validateApprovalSemantics(approval, verification) {
  const hasDecisionFields = Boolean(approval.decidedBy || approval.decidedAt || approval.reason);
  if (approval.decision === 'pending' && hasDecisionFields) {
    addError('pending approval must not contain decidedBy, decidedAt, or reason');
  }
  if (approval.decision !== 'pending' && (!approval.decidedBy || !approval.decidedAt || !approval.reason)) {
    addError('approved or rejected approval must contain decidedBy, decidedAt, and reason');
  }
  if ((approval.policyChecks ?? []).some((check) => check.status !== 'passed') && approval.decision === 'approved') {
    addError('approval cannot be approved when a policy check failed');
  }
  if (approval.decidedAt && Date.parse(approval.decidedAt) < Date.parse(approval.requestedAt)) {
    addError('approval decidedAt must not precede requestedAt');
  }
  if (verification) {
    expectEqual(approval.runId, verification.runId, 'approval and verification runId');
    expectEqual(
      approval.requestedAction?.verificationReport,
      'verification-report.example.json',
      'approval verification report path',
    );
    expectEqual(approval.requestedAction?.manifest, verification.manifest?.path, 'approval and verification manifest path');
    expectEqual(approval.requestedAction?.manifestDigest, verification.manifest?.digest, 'approval and verification manifest digest');
    if (Date.parse(approval.requestedAt) < Date.parse(verification.verifiedAt)) {
      addError('approval requestedAt must not precede independent verification');
    }
    if (approval.decision === 'approved' && verification.result !== 'passed') {
      addError('approval cannot be approved without a passed independent verification report');
    }
    if (!(approval.evidence ?? []).includes(approval.requestedAction?.manifest)) {
      addError('approval evidence must include the bound manifest');
    }
    if (!(approval.evidence ?? []).includes(approval.requestedAction?.verificationReport)) {
      addError('approval evidence must include the bound verification report');
    }
  }
}

function validateTraceSemantics(trace, approval, verification) {
  if (trace.length === 0) {
    addError('scenario/trace.example.jsonl must contain trace events');
    return;
  }
  const eventIds = new Set();
  const runIds = new Set();
  const taskIds = new Set();
  let previousTimestamp = 0;
  for (let index = 0; index < trace.length; index += 1) {
    const event = trace[index];
    if (event.sequence !== index + 1) addError(`trace sequence must be contiguous from 1; found ${event.sequence} at line ${index + 1}`);
    if (eventIds.has(event.eventId)) addError(`duplicate trace eventId ${event.eventId}`);
    if (event.correlation?.parentEventId && !eventIds.has(event.correlation.parentEventId)) {
      addError(`trace event ${event.eventId} references a parent that has not occurred`);
    }
    eventIds.add(event.eventId);
    runIds.add(event.runId);
    taskIds.add(event.correlation?.taskId);
    const timestamp = Date.parse(event.timestamp);
    if (Number.isFinite(timestamp) && timestamp < previousTimestamp) addError(`trace timestamps are not monotonic at ${event.eventId}`);
    if (Number.isFinite(timestamp)) previousTimestamp = timestamp;
    if (event.eventType === 'deployment.completed') {
      addError('synthetic dry-run trace must not claim deployment.completed');
    }
    if (event.payload?.toolName === 'deploy_app') {
      addError('synthetic dry-run trace must not claim a deploy_app call');
    }
  }
  if (runIds.size !== 1) addError('trace events must share exactly one runId');
  if (taskIds.size !== 1) addError('trace events must share exactly one taskId');
  const requiredTypes = [
    'task.received',
    'task.decomposed',
    'tool.requested',
    'tool.completed',
    'verification.completed',
    'approval.requested',
    'approval.decided',
    'dry_run.completed',
  ];
  const types = new Set(trace.map((event) => event.eventType));
  for (const type of requiredTypes) {
    if (!types.has(type)) addError(`trace example is missing ${type}`);
  }
  if (approval) {
    if (![...runIds].includes(approval.runId)) addError('approval runId must match trace runId');
    const approvalEvents = trace.filter((event) => event.correlation?.approvalId === approval.approvalId);
    if (approvalEvents.length < 2) addError('trace must correlate approval.requested and approval.decided to the approval fixture');
    const decision = approvalEvents.find((event) => event.eventType === 'approval.decided');
    if (decision?.payload?.decision !== approval.decision) addError('trace approval decision must match approval fixture');
    const request = approvalEvents.find((event) => event.eventType === 'approval.requested');
    expectEqual(request?.timestamp, approval.requestedAt, 'trace approval request timestamp');
    expectEqual(decision?.timestamp, approval.decidedAt, 'trace approval decision timestamp');
  }
  if (verification) {
    const verificationEvent = trace.find((event) => event.eventType === 'verification.completed');
    expectEqual(verificationEvent?.timestamp, verification.verifiedAt, 'trace verification timestamp');
    expectEqual(verificationEvent?.payload?.artifact, 'verification-report.example.json', 'trace verification artifact');
    expectEqual(verificationEvent?.payload?.result, verification.result, 'trace verification result');
  }
}

function validateSql(sql, label) {
  const normalized = stripSqlComments(sql).trim();
  const statements = normalized.split(';').map((statement) => statement.trim()).filter(Boolean);
  if (statements.length === 0) addError(`${label} must contain at least one SQL statement`);
  for (const statement of statements) {
    const compact = statement.replace(/\s+/g, ' ').toLowerCase();
    if (/^(drop|truncate|reindex|cluster)\b/.test(compact)
      || /^vacuum\s+full\b/.test(compact)
      || /^delete\s+from\b/.test(compact)
      || /^alter\b.*\bdrop\b/.test(compact)
      || /\bcopy\b.*\bprogram\b/.test(compact)
      || /\bsecurity\s+definer\b/.test(compact)
      || /\bset\s+role\b/.test(compact)) {
      addError(`${label} contains a dangerous SQL statement`);
    }
  }

  const tables = [...normalized.matchAll(/\bcreate\s+table\s+(?:if\s+not\s+exists\s+)?([A-Za-z0-9_."]+)/gi)]
    .map((match) => match[1].replaceAll('"', ''));
  for (const table of tables) {
    const escaped = escapeRegExp(table).replace('\\.', '\\s*\\.\\s*');
    if (!new RegExp(`\\balter\\s+table\\s+${escaped}\\s+enable\\s+row\\s+level\\s+security\\b`, 'i').test(normalized)) {
      addError(`${label} creates ${table} without enabling row level security`);
    }
    if (!new RegExp(`\\bcreate\\s+policy[\\s\\S]+?\\bon\\s+${escaped}\\b`, 'i').test(normalized)) {
      addError(`${label} creates ${table} without an RLS policy`);
    }
  }
}

function stripSqlComments(sql) {
  return sql.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/--.*$/gm, ' ');
}

function validateBySchema(value, schema, location) {
  const schemaErrors = [];
  validateSchemaNode(value, schema, location, schemaErrors);
  for (const error of schemaErrors) addError(error);
}

function validateSchemaNode(value, schema, location, out) {
  if (!schema || typeof schema !== 'object' || Array.isArray(schema)) {
    out.push(`${location}: invalid validator schema node`);
    return;
  }
  if (Object.hasOwn(schema, 'const') && !deepEqual(value, schema.const)) {
    out.push(`${location}: value must equal ${JSON.stringify(schema.const)}`);
  }
  if (Array.isArray(schema.enum) && !schema.enum.some((candidate) => deepEqual(value, candidate))) {
    out.push(`${location}: value is not in the allowed enum`);
  }
  if (schema.type !== undefined && !matchesType(value, schema.type)) {
    out.push(`${location}: expected type ${JSON.stringify(schema.type)}`);
    return;
  }

  if (Array.isArray(schema.oneOf)) {
    const matches = schema.oneOf.filter((branch) => {
      const branchErrors = [];
      validateSchemaNode(value, branch, location, branchErrors);
      return branchErrors.length === 0;
    }).length;
    if (matches !== 1) out.push(`${location}: expected exactly one oneOf branch to match; matched ${matches}`);
  }
  if (Array.isArray(schema.allOf)) {
    for (const branch of schema.allOf) validateSchemaNode(value, branch, location, out);
  }
  if (schema.not) {
    const branchErrors = [];
    validateSchemaNode(value, schema.not, location, branchErrors);
    if (branchErrors.length === 0) out.push(`${location}: value matches a forbidden schema`);
  }

  if (typeof value === 'string') {
    if (schema.minLength !== undefined && value.length < schema.minLength) out.push(`${location}: string is shorter than ${schema.minLength}`);
    if (schema.maxLength !== undefined && value.length > schema.maxLength) out.push(`${location}: string is longer than ${schema.maxLength}`);
    if (schema.pattern !== undefined && !new RegExp(schema.pattern).test(value)) out.push(`${location}: string does not match required pattern`);
    if (schema.format === 'date-time' && (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z$/.test(value) || !Number.isFinite(Date.parse(value)))) {
      out.push(`${location}: expected an RFC 3339 UTC date-time`);
    }
  }
  if (typeof value === 'number') {
    if (schema.minimum !== undefined && value < schema.minimum) out.push(`${location}: number is below minimum ${schema.minimum}`);
    if (schema.maximum !== undefined && value > schema.maximum) out.push(`${location}: number is above maximum ${schema.maximum}`);
  }
  if (Array.isArray(value)) {
    if (schema.minItems !== undefined && value.length < schema.minItems) out.push(`${location}: array has fewer than ${schema.minItems} items`);
    if (schema.maxItems !== undefined && value.length > schema.maxItems) out.push(`${location}: array has more than ${schema.maxItems} items`);
    if (schema.uniqueItems === true) {
      const serialized = value.map((item) => JSON.stringify(item));
      if (new Set(serialized).size !== serialized.length) out.push(`${location}: array items must be unique`);
    }
    if (schema.items) value.forEach((item, index) => validateSchemaNode(item, schema.items, `${location}[${index}]`, out));
  }
  if (value && typeof value === 'object' && !Array.isArray(value)) {
    const properties = schema.properties ?? {};
    for (const key of schema.required ?? []) {
      if (!Object.hasOwn(value, key)) out.push(`${location}: missing required property ${key}`);
    }
    for (const [key, child] of Object.entries(value)) {
      if (Object.hasOwn(properties, key)) {
        validateSchemaNode(child, properties[key], `${location}.${key}`, out);
      } else if (schema.additionalProperties === false) {
        out.push(`${location}: unknown property ${key}`);
      } else if (schema.additionalProperties && typeof schema.additionalProperties === 'object') {
        validateSchemaNode(child, schema.additionalProperties, `${location}.${key}`, out);
      }
    }
  }
}

function matchesType(value, expected) {
  const types = Array.isArray(expected) ? expected : [expected];
  return types.some((type) => {
    if (type === 'null') return value === null;
    if (type === 'array') return Array.isArray(value);
    if (type === 'object') return Boolean(value) && typeof value === 'object' && !Array.isArray(value);
    if (type === 'integer') return Number.isInteger(value);
    if (type === 'number') return typeof value === 'number' && Number.isFinite(value);
    return typeof value === type;
  });
}

async function walkFiles(root, label) {
  let info;
  try {
    info = await lstat(root);
  } catch (error) {
    addError(`${label} is missing: ${relative(root)}`);
    return [];
  }
  if (info.isSymbolicLink()) {
    addError(`${label} must not be a symlink: ${relative(root)}`);
    return [];
  }
  if (info.isFile()) return [root];
  if (!info.isDirectory()) {
    addError(`${label} must be a regular file or directory: ${relative(root)}`);
    return [];
  }
  const files = [];
  for (const entry of await readdir(root, { withFileTypes: true })) {
    if (entry.name === '.DS_Store') {
      addError(`unexpected OS metadata file: ${relative(path.join(root, entry.name))}`);
      continue;
    }
    files.push(...await walkFiles(path.join(root, entry.name), label));
  }
  return files;
}

async function scanFileForSensitiveMaterial(file) {
  const rel = relative(file);
  const base = path.basename(file).toLowerCase();
  const parts = rel.split('/').map((part) => part.toLowerCase());
  if (base === '.env' || base.startsWith('.env.') || base === '.npmrc' || base === '.pypirc'
    || base === 'id_rsa' || base.endsWith('.pem') || base.endsWith('.key') || parts.includes('.nubase')) {
    addError(`sensitive filename is not allowed in contest package: ${rel}`);
  }
  const data = await readFile(file);
  if (data.length > 2 * 1024 * 1024) addError(`contest package file exceeds 2 MiB review limit: ${rel}`);
  if (data.includes(0)) {
    addError(`binary file is not allowed in the reviewable contest package: ${rel}`);
    return;
  }
  const text = data.toString('utf8');
  const patterns = [
    /-----BEGIN (?:RSA |EC |OPENSSH |DSA |)?PRIVATE KEY-----/,
    /\beyJ[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}\b/,
    /\bsk-(?:ant-)?[A-Za-z0-9_-]{20,}\b/,
    /\bAKIA[0-9A-Z]{16}\b/,
    /\bgh[pousr]_[A-Za-z0-9]{24,}\b/,
    /\bxox[baprs]-[A-Za-z0-9-]{20,}\b/,
    /https?:\/\/[^\s/@:]+:[^\s/@]+@/,
  ];
  if (patterns.some((pattern) => pattern.test(text))) addError(`potential credential material detected in ${rel}`);
  for (const line of text.split(/\r?\n/)) {
    const match = line.match(sensitiveAssignmentPattern);
    if (!match) continue;
    const value = match[1];
    if (!/^\$\{[A-Z0-9_]+\}$/.test(value)
      && !/^\{\{[A-Za-z0-9_.-]+\}\}$/.test(value)
      && !/^<[^>]+>$/.test(value)
      && !/^YOUR_[A-Z0-9_]+$/.test(value)
      && !/^(?:REDACTED|PLACEHOLDER|null|false|true)$/i.test(value)) {
      addError(`inline sensitive value detected in ${rel}`);
      break;
    }
  }
}

async function resolveRegularPath(base, input, label) {
  const target = await resolveSafePath(base, input, label);
  if (!target) return null;
  const info = await safeLstat(target, label);
  if (!info) return null;
  if (info.isSymbolicLink()) {
    addError(`${label} must not be a symlink: ${relative(target)}`);
    return null;
  }
  if (!info.isFile()) {
    addError(`${label} must be a regular file: ${relative(target)}`);
    return null;
  }
  return target;
}

async function resolveDirectory(base, input, label) {
  const target = await resolveSafePath(base, input, label);
  if (!target) return null;
  const info = await safeLstat(target, label);
  if (!info) return null;
  if (info.isSymbolicLink()) {
    addError(`${label} must not be a symlink: ${relative(target)}`);
    return null;
  }
  if (!info.isDirectory()) {
    addError(`${label} must be a directory: ${relative(target)}`);
    return null;
  }
  await walkFiles(target, label);
  return target;
}

async function resolveSafePath(base, input, label) {
  if (typeof input !== 'string' || input.trim() === '') {
    addError(`${label} path must be a non-empty string`);
    return null;
  }
  if (path.isAbsolute(input) || /^[A-Za-z]:[\\/]/.test(input) || input.includes('\\')) {
    addError(`${label} path must be a portable relative path`);
    return null;
  }
  const target = path.resolve(base, input);
  const rel = path.relative(base, target);
  if (rel === '..' || rel.startsWith(`..${path.sep}`) || path.isAbsolute(rel)) {
    addError(`${label} path escapes its allowed root`);
    return null;
  }
  const segments = rel.split(path.sep).filter(Boolean);
  let current = base;
  for (const segment of segments.slice(0, -1)) {
    current = path.join(current, segment);
    const info = await safeLstat(current, label);
    if (!info) return null;
    if (info.isSymbolicLink()) {
      addError(`${label} traverses a symlink: ${relative(current)}`);
      return null;
    }
  }
  return target;
}

async function safeLstat(file, label) {
  try {
    return await lstat(file);
  } catch {
    addError(`${label} does not exist: ${relative(file)}`);
    return null;
  }
}

async function readRequiredText(file, label) {
  const resolved = await resolveRegularPath(packageRoot, path.relative(packageRoot, file), label);
  if (!resolved) return null;
  return readFile(resolved, 'utf8');
}

async function readJson(file, label) {
  const text = await readRequiredText(file, label);
  if (text === null) return null;
  try {
    assertNoDuplicateJsonKeys(text, relative(file));
    return JSON.parse(text);
  } catch (error) {
    addError(`${relative(file)} is not strict JSON: ${safeError(error)}`);
    return null;
  }
}

async function readJsonLines(file, label) {
  const text = await readRequiredText(file, label);
  if (text === null) return [];
  const out = [];
  const lines = text.split(/\r?\n/);
  for (let index = 0; index < lines.length; index += 1) {
    if (!lines[index].trim()) continue;
    try {
      assertNoDuplicateJsonKeys(lines[index], `${relative(file)}:${index + 1}`);
      out.push(JSON.parse(lines[index]));
    } catch (error) {
      addError(`${relative(file)}:${index + 1} is not strict JSON: ${safeError(error)}`);
    }
  }
  return out;
}

function assertNoDuplicateJsonKeys(text, label) {
  let index = 0;
  parseValue('$');
  skipWhitespace();
  if (index !== text.length) throw new Error(`${label}: unexpected trailing content`);

  function parseValue(location) {
    skipWhitespace();
    const char = text[index];
    if (char === '{') return parseObject(location);
    if (char === '[') return parseArray(location);
    if (char === '"') return parseString();
    const token = text.slice(index).match(/^(?:true|false|null|-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?)/)?.[0];
    if (!token) throw new Error(`${label}: invalid JSON token at offset ${index}`);
    index += token.length;
  }

  function parseObject(location) {
    index += 1;
    skipWhitespace();
    const keys = new Set();
    if (text[index] === '}') {
      index += 1;
      return;
    }
    while (index < text.length) {
      skipWhitespace();
      if (text[index] !== '"') throw new Error(`${label}: expected object key at offset ${index}`);
      const key = parseString();
      if (keys.has(key)) throw new Error(`${label}: duplicate key ${location}.${key}`);
      keys.add(key);
      skipWhitespace();
      if (text[index] !== ':') throw new Error(`${label}: expected colon after ${location}.${key}`);
      index += 1;
      parseValue(`${location}.${key}`);
      skipWhitespace();
      if (text[index] === '}') {
        index += 1;
        return;
      }
      if (text[index] !== ',') throw new Error(`${label}: expected comma at offset ${index}`);
      index += 1;
    }
    throw new Error(`${label}: unterminated object`);
  }

  function parseArray(location) {
    index += 1;
    skipWhitespace();
    if (text[index] === ']') {
      index += 1;
      return;
    }
    let item = 0;
    while (index < text.length) {
      parseValue(`${location}[${item}]`);
      item += 1;
      skipWhitespace();
      if (text[index] === ']') {
        index += 1;
        return;
      }
      if (text[index] !== ',') throw new Error(`${label}: expected comma at offset ${index}`);
      index += 1;
    }
    throw new Error(`${label}: unterminated array`);
  }

  function parseString() {
    const start = index;
    index += 1;
    while (index < text.length) {
      if (text[index] === '\\') {
        index += 2;
        continue;
      }
      if (text[index] === '"') {
        index += 1;
        return JSON.parse(text.slice(start, index));
      }
      index += 1;
    }
    throw new Error(`${label}: unterminated string`);
  }

  function skipWhitespace() {
    while (/\s/.test(text[index] ?? '')) index += 1;
  }
}

function expectedMcpServers(contract, runtime) {
  const host = mcpGatewayHostByRuntime.get(runtime);
  if (!host) throw new Error(`unsupported MCP runtime ${runtime}`);
  return contract.mcpRoutes.map((route) => ({
    name: route.name,
    url: `http://${host}:8080/mcp-servers/${route.serverName}/mcp`,
    transport: 'http',
  }));
}

function hiclawMcpServersByResource(text, label) {
  const lines = text.split(/\r?\n/);
  const leaderIndexes = exactYamlKeyIndexes(lines, 'leader', 2);
  const workerListIndexes = exactYamlKeyIndexes(lines, 'workers', 2);
  if (leaderIndexes.length !== 1) {
    addError(`${label} must declare exactly one spec.leader mapping`);
  }
  if (workerListIndexes.length !== 1) {
    addError(`${label} must declare exactly one spec.workers list`);
  }
  if (leaderIndexes.length !== 1 || workerListIndexes.length !== 1) return new Map();

  const result = new Map();
  const leaderStart = leaderIndexes[0];
  const workersStart = workerListIndexes[0];
  const leaderName = directYamlScalar(lines, leaderStart + 1, workersStart, 4, 'name');
  if (!leaderName) {
    addError(`${label} HiClaw leader name is required`);
  } else {
    result.set(
      leaderName,
      yamlObjectListInRange(
        lines,
        leaderStart + 1,
        workersStart,
        4,
        `${label} ${leaderName}`,
      ),
    );
  }

  const workerStarts = [];
  for (let index = workersStart + 1; index < lines.length; index += 1) {
    const match = lines[index].match(/^ {4}- name:\s*(.+?)\s*$/);
    if (match) workerStarts.push({ index, name: unquoteYaml(match[1]) });
  }
  for (let index = 0; index < workerStarts.length; index += 1) {
    const worker = workerStarts[index];
    const end = workerStarts[index + 1]?.index ?? lines.length;
    if (result.has(worker.name)) {
      addError(`${label} contains duplicate HiClaw role ${worker.name}`);
      continue;
    }
    result.set(
      worker.name,
      yamlObjectListInRange(
        lines,
        worker.index + 1,
        end,
        6,
        `${label} ${worker.name}`,
      ),
    );
  }
  return result;
}

function yamlObjectListInRange(lines, start, end, fieldIndent, label) {
  const keyIndexes = [];
  const keyPrefix = ' '.repeat(fieldIndent);
  for (let index = start; index < end; index += 1) {
    if (lines[index] === `${keyPrefix}mcpServers:`) keyIndexes.push(index);
  }
  if (keyIndexes.length === 0) return [];
  if (keyIndexes.length > 1) {
    addError(`${label} contains duplicate YAML key mcpServers`);
  }

  const items = [];
  let current = null;
  const listStart = keyIndexes[0];
  for (let index = listStart + 1; index < end; index += 1) {
    const line = lines[index];
    if (!line.trim() || line.trimStart().startsWith('#')) continue;
    const indent = line.match(/^\s*/)[0].length;
    if (indent <= fieldIndent) break;
    const first = line.match(
      new RegExp(`^ {${fieldIndent + 2}}- ([A-Za-z0-9_.-]+):\\s*(.+?)\\s*$`),
    );
    if (first) {
      current = {};
      setYamlObjectProperty(current, first[1], unquoteYaml(first[2]), label);
      items.push(current);
      continue;
    }
    const property = line.match(
      new RegExp(`^ {${fieldIndent + 4}}([A-Za-z0-9_.-]+):\\s*(.+?)\\s*$`),
    );
    if (current && property) {
      setYamlObjectProperty(current, property[1], unquoteYaml(property[2]), label);
      continue;
    }
    addError(`${label} contains unsupported MCP server YAML structure`);
  }
  return items;
}

function exactYamlKeyIndexes(lines, key, indent) {
  const expected = `${' '.repeat(indent)}${key}:`;
  const indexes = [];
  for (let index = 0; index < lines.length; index += 1) {
    if (lines[index] === expected) indexes.push(index);
  }
  return indexes;
}

function directYamlScalar(lines, start, end, indent, key) {
  const pattern = new RegExp(`^ {${indent}}${escapeRegExp(key)}:\\s*(.+?)\\s*$`);
  const values = [];
  for (let index = start; index < end; index += 1) {
    const match = lines[index].match(pattern);
    if (match) values.push(unquoteYaml(match[1]));
  }
  return values.length === 1 ? values[0] : undefined;
}

function setYamlObjectProperty(target, key, value, label) {
  if (Object.hasOwn(target, key)) {
    addError(`${label} contains duplicate YAML key ${key}`);
    return;
  }
  target[key] = value;
}

function yamlScalarMap(text) {
  const fields = new Map();
  const stack = [];
  for (const rawLine of text.split(/\r?\n/)) {
    if (!rawLine.trim() || rawLine.trimStart().startsWith('#') || rawLine.trimStart().startsWith('- ')) continue;
    const match = rawLine.match(/^(\s*)([A-Za-z0-9_.\/-]+):(?:\s*(.*))?$/);
    if (!match) continue;
    const indent = match[1].length;
    const key = match[2];
    const rawValue = match[3]?.trim() ?? '';
    while (stack.length && stack.at(-1).indent >= indent) stack.pop();
    const fieldPath = [...stack.map((entry) => entry.key), key].join('.');
    if (rawValue && rawValue !== '|' && rawValue !== '>') fields.set(fieldPath, unquoteYaml(rawValue));
    if (!rawValue) stack.push({ indent, key });
  }
  return fields;
}

function yamlObjectList(text, fieldPath, label = fieldPath, strict = false) {
  const lines = text.split(/\r?\n/);
  const key = fieldPath.split('.').at(-1);
  const keyPattern = new RegExp(`^\\s*${escapeRegExp(key)}:\\s*$`);
  const starts = lines
    .map((line, index) => (keyPattern.test(line) ? index : -1))
    .filter((index) => index >= 0);
  const start = starts[0] ?? -1;
  if (start < 0) return [];
  const baseIndent = lines[start].match(/^\s*/)[0].length;
  const duplicateAtSameIndent = starts.some(
    (index) => index !== start && lines[index].match(/^\s*/)[0].length === baseIndent,
  );
  if (duplicateAtSameIndent) addError(`${label} contains duplicate YAML key ${key}`);
  const items = [];
  let current = null;
  for (let index = start + 1; index < lines.length; index += 1) {
    const line = lines[index];
    if (!line.trim() || line.trimStart().startsWith('#')) continue;
    const indent = line.match(/^\s*/)[0].length;
    if (indent <= baseIndent) break;
    const first = line.match(/^\s*-\s+([A-Za-z0-9_.-]+):\s*(.+?)\s*$/);
    if (first) {
      current = {};
      setYamlObjectProperty(current, first[1], unquoteYaml(first[2]), label);
      items.push(current);
      continue;
    }
    const property = line.match(/^\s+([A-Za-z0-9_.-]+):\s*(.+?)\s*$/);
    if (current && property) {
      setYamlObjectProperty(current, property[1], unquoteYaml(property[2]), label);
      continue;
    }
    if (strict) addError(`${label} contains unsupported MCP server YAML structure`);
  }
  return items;
}

function yamlScalarList(text, fieldPath) {
  const items = yamlObjectList(text, fieldPath);
  if (items.length > 0) return items.map((item) => Object.values(item)[0]);
  const lines = text.split(/\r?\n/);
  const key = fieldPath.split('.').at(-1);
  const start = lines.findIndex((line) => new RegExp(`^\\s*${escapeRegExp(key)}:\\s*$`).test(line));
  if (start < 0) return [];
  const baseIndent = lines[start].match(/^\s*/)[0].length;
  const values = [];
  for (let index = start + 1; index < lines.length; index += 1) {
    const line = lines[index];
    if (!line.trim()) continue;
    const indent = line.match(/^\s*/)[0].length;
    if (indent <= baseIndent) break;
    const match = line.match(/^\s*-\s+(.+?)\s*$/);
    if (match) values.push(unquoteYaml(match[1]));
  }
  return values;
}

function yamlAnnotation(text, key) {
  const match = text.match(new RegExp(`^\\s*${escapeRegExp(key)}:\\s*(.+?)\\s*$`, 'm'));
  return match ? unquoteYaml(match[1]) : undefined;
}

function unquoteYaml(value) {
  const trimmed = value.trim();
  if ((trimmed.startsWith('"') && trimmed.endsWith('"')) || (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
    return trimmed.slice(1, -1);
  }
  return trimmed;
}

function parseFrontmatter(text, label) {
  const match = text.match(/^---\r?\n([\s\S]*?)\r?\n---(?:\r?\n|$)/);
  if (!match) {
    addError(`${label} is missing YAML frontmatter`);
    return {};
  }
  const out = {};
  for (const line of match[1].split(/\r?\n/)) {
    if (!line.trim()) continue;
    const field = line.match(/^([A-Za-z0-9_-]+):\s*(.+?)\s*$/);
    if (!field) {
      addError(`${label} contains unsupported frontmatter syntax`);
      continue;
    }
    if (Object.hasOwn(out, field[1])) addError(`${label} contains duplicate frontmatter key ${field[1]}`);
    out[field[1]] = unquoteYaml(field[2]);
  }
  return out;
}

function markdownSections(text) {
  const sections = new Map();
  const matches = [...text.matchAll(/^## ([^\r\n]+)\r?$/gm)];
  for (let index = 0; index < matches.length; index += 1) {
    const name = matches[index][1].trim();
    const start = matches[index].index + matches[index][0].length;
    const end = matches[index + 1]?.index ?? text.length;
    if (sections.has(name)) addError(`duplicate Markdown section ${name}`);
    sections.set(name, text.slice(start, end).trim());
  }
  return sections;
}

function assertExactSectionSet(sections, expected, label) {
  const actualNames = [...sections.keys()];
  const missing = expected.filter((name) => !sections.has(name));
  const unknown = actualNames.filter((name) => !expected.includes(name));
  if (missing.length) addError(`${label} is missing sections: ${missing.join(', ')}`);
  if (unknown.length) addError(`${label} contains unknown sections: ${unknown.join(', ')}`);
}

function assertExactKeys(value, expected, label) {
  const actual = Object.keys(value);
  const missing = expected.filter((key) => !actual.includes(key));
  const unknown = actual.filter((key) => !expected.includes(key));
  if (missing.length) addError(`${label} is missing fields: ${missing.join(', ')}`);
  if (unknown.length) addError(`${label} contains unknown fields: ${unknown.join(', ')}`);
}

function assertUnique(values, label) {
  if (new Set(values).size !== values.length) addError(`${label} must be unique`);
}

function expectEqual(actual, expected, label) {
  if (actual !== expected) addError(`${label} must be ${JSON.stringify(expected)}; found ${JSON.stringify(actual)}`);
}

function expectPresent(value, label) {
  if (typeof value !== 'string' || !value.trim()) addError(`${label} is required`);
}

function addError(message) {
  errors.push(message);
}

function deepEqual(left, right) {
  return JSON.stringify(left) === JSON.stringify(right);
}

function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function relative(file) {
  const value = path.relative(packageRoot, file).split(path.sep).join('/');
  return value || '.';
}

function safeError(error) {
  return error instanceof Error ? error.message.replace(/[\r\n]+/g, ' ') : String(error);
}

function resolvePackageRoot(args) {
  if (args.length === 0) return defaultPackageRoot;
  if (args.length === 2 && args[0] === '--package-root') return path.resolve(args[1]);
  console.error('Usage: node validate-package.mjs [--package-root <path>]');
  process.exit(2);
}
