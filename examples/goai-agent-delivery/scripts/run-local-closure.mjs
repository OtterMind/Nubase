#!/usr/bin/env node

import { createHash, randomBytes } from "node:crypto";
import {
  chmodSync,
  existsSync,
  lstatSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  realpathSync,
  renameSync,
  writeFileSync,
} from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const packageRoot = path.resolve(scriptDir, "..");
const evidenceBoundary = path.join(packageRoot, "evidence");
const policyPath = path.join(packageRoot, "agentteams-v1.2.2", "mcp-tool-policies.json");
const validatorPath = path.join(scriptDir, "validate-package.mjs");
const contractsRoot = path.join(packageRoot, "contracts");
const harnessActor = "role-scoped-harness";
const commandTimeoutMs = 30_000;
const reconciliationTimeoutMs = 30_000;
const reconciliationIntervalMs = 500;
const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const sha256Pattern = /^sha256:[a-f0-9]{64}$/;
const etagPattern = /^[^\s\x00-\x1f\x7f]{1,256}$/;
const ownershipVersionIdPattern = /^(?!null$)[^\s\x00-\x1f\x7f]{1,1024}$/i;
const maxMarkerSizeBytes = 64 * 1024;
const explicitDenialPattern = /\b(?:tool not allowed|unknown tool|tool not found)\b/i;

const roles = {
  deliveryLead: {
    agentId: "delivery-lead",
    container: "hiclaw-worker-nubase-delivery-lead",
    route: "nubase-read",
  },
  builder: {
    agentId: "builder-agent",
    container: "hiclaw-worker-nubase-builder",
    route: "nubase-build",
  },
  verifier: {
    agentId: "verifier-agent",
    container: "hiclaw-worker-nubase-verifier",
    route: "nubase-read",
  },
  governor: {
    agentId: "release-governor",
    container: "hiclaw-worker-nubase-release-governor",
    route: "nubase-release",
  },
};

let evidenceRoot;
let finalRunDir;
let runDir;
let runId;
let taskId;
let suffix;
let markerPath;
let startedAt;
let manifestDigest;
let deploymentForCleanup = null;
let rollbackFinished = false;
let stageAttempted = false;
let published = false;
let sequence = 0;
let lastTimestampMs = 0;
const trace = [];
const appName = "goai-bounded-demo";

try {
  const args = parseArgs(process.argv.slice(2));
  if (args.selfTest) {
    runSelfTest();
    process.stdout.write("GOAI local closure self-test passed.\n");
  } else {
    runLocalClosure(args);
  }
} catch (error) {
  const code = error instanceof Error ? error.message : "LOCAL_CLOSURE_FAILED";
  process.stderr.write(`GOAI local closure failed: ${safeCode(code)}\n`);
  process.exitCode = 1;
}

function runLocalClosure(args) {
  assert(args.approveLocalRollback === true, "LOCAL_ROLLBACK_APPROVAL_REQUIRED");

  evidenceRoot = path.resolve(args.evidenceRoot ?? path.join(evidenceBoundary, "local-sandbox"));
  suffix = `${formatTimestamp(new Date())}-${randomBytes(3).toString("hex")}`;
  runId = args.runId ?? `run-local-closure-${suffix}`;
  taskId = args.taskId ?? `task-local-closure-${suffix}`;
  markerPath = `__goai_e2e/${runId}/marker.json`;
  startedAt = new Date().toISOString();

  assertPattern(runId, /^run-[a-z0-9][a-z0-9-]{5,63}$/, "runId");
  assertPattern(taskId, /^task-[a-z0-9][a-z0-9-]{5,63}$/, "taskId");
  runCanonicalValidator();
  prepareEvidenceRoot(evidenceRoot);

  finalRunDir = path.join(evidenceRoot, runId);
  runDir = path.join(evidenceRoot, `.incomplete-${runId}`);
  assert(!existsSync(finalRunDir), "RUN_DIRECTORY_ALREADY_EXISTS");
  assert(!existsSync(runDir), "INCOMPLETE_RUN_DIRECTORY_ALREADY_EXISTS");
  mkdirSync(runDir, { mode: 0o700 });

  try {
    executeClosure();
  } catch (error) {
    const code = error instanceof Error ? error.message : "LOCAL_CLOSURE_FAILED";
    const compensation = compensateAfterFailure();
    const incomplete = published ? "none" : runDir;
    process.stderr.write(
      `GOAI local closure failed: ${safeCode(code)}; compensation=${safeCode(compensation)}; incompleteEvidence=${incomplete}\n`,
    );
    process.exitCode = 1;
  }
}

function executeClosure() {
  const policy = readJsonFile(policyPath);
  verifyRoleToolInventories(policy);
  record("delivery-lead", "task.received", "succeeded", {
    summary: "The role-scoped harness received a bounded local rollback-drill task.",
  });
  record("delivery-lead", "task.decomposed", "succeeded", {
    summary: "The harness bound plan, build, verification, and governance to separate MCP role identities.",
  });

  assertToolRejected(roles.deliveryLead, "deploymentStageAsset", stageArgs(`sha256:${"0".repeat(64)}`));
  assertToolRejected(roles.verifier, "deploymentStageAsset", stageArgs(`sha256:${"0".repeat(64)}`));
  assertToolRejected(roles.governor, "deploymentStageAsset", stageArgs(`sha256:${"0".repeat(64)}`));
  assertToolRejected(roles.builder, "gatewayListKeys", {});
  assertToolRejected(roles.builder, "executeSql", { sql: "SELECT 1" });
  assertToolRejected(roles.builder, "executeSqlDryRun", { sqlQuery: "SELECT 1" });

  const taskSpec = {
    schemaVersion: "1.0",
    taskId,
    runId,
    profile: "bounded-asset-v1",
    target: "local-sandbox",
    requestedOutcome: "REJECTED_RECOVERED",
    faultInjection: "rollback-drill",
    markerPath,
    executionActor: harnessActor,
  };
  const taskSpecDigest = sha256Json(taskSpec);

  writeText("task-spec.md", taskSpecMarkdown(taskSpec));
  writeText("delivery-plan.md", deliveryPlanMarkdown(taskSpec));
  writeText("acceptance-contract.md", acceptanceContractMarkdown(taskSpec));
  writeJson("execution-attestation.json", {
    schemaVersion: "1.0",
    executionActor: harnessActor,
    transport: "docker-exec-mcporter",
    exercisedRoleIdentities: Object.values(roles).map((role) => role.agentId),
    llmAgentTeamExecutionClaimed: false,
  });
  writeJson("build-manifest.json", {
    schemaVersion: "1.0",
    appName,
    taskId,
    runId,
    profile: "bounded-asset-v1",
    markerPath,
    taskSpecDigest,
    upsert: false,
    executionActor: harnessActor,
  });
  manifestDigest = sha256File("build-manifest.json");

  assert(reconcileBoundedDeployment({ waitForSettlement: false }) == null, "RUN_ID_ALREADY_PRESENT");

  record("builder-agent", "tool.requested", "running", {
    summary: "The role-scoped harness requested a bounded stage through the Builder route.",
    toolName: "deploymentStageAsset",
    target: markerPath,
  });
  stageAttempted = true;
  const stageOutcome = callToolOutcome(roles.builder, "deploymentStageAsset", stageArgs(manifestDigest));
  if (stageOutcome.kind !== "response") {
    recoverUncertainStage();
    fail("STAGE_RESPONSE_UNCERTAIN_RECOVERED");
  }

  const staged = stageOutcome.value;
  if (isUuid(staged?.deploymentId)) deploymentForCleanup = staged.deploymentId;
  if (staged?.success !== true) {
    const reconciled = reconcileBoundedDeployment({ waitForSettlement: true });
    if (reconciled != null) deploymentForCleanup = reconciled.deployment.id;
    fail("STAGE_REJECTED");
  }

  const reconciled = reconcileBoundedDeployment({ waitForSettlement: true });
  assert(reconciled != null, "STAGED_DEPLOYMENT_NOT_RECONCILED");
  deploymentForCleanup = reconciled.deployment.id;
  validateStageResponse(staged, reconciled);
  const deploymentId = staged.deploymentId;

  const assetsBeforeRollback = callTool(roles.verifier, "assetsList", {
    prefix: `__goai_e2e/${runId}/`,
    limit: 10,
  });
  validateMarkerPresent(assetsBeforeRollback, staged);
  validateSucceededDeploymentEvidence(reconciled, staged);

  record("builder-agent", "tool.completed", "succeeded", {
    summary: "The role-scoped harness confirmed one Builder-scoped marker stage with exact ownership evidence.",
    toolName: "deploymentStageAsset",
    result: "passed",
    evidence: ["build-evidence.json"],
  }, { deploymentId });
  writeJson("build-evidence.json", {
    schemaVersion: "1.0",
    taskId,
    runId,
    executionActor: harnessActor,
    exercisedRoleIdentity: "builder-agent",
    deploymentId,
    status: staged.status,
    markerPath,
    artifactDigest: staged.artifactDigest,
    sizeBytes: staged.sizeBytes,
    ownershipEtag: staged.ownershipEtag,
    ownershipVersionId: staged.ownershipVersionId,
    markerPublicUrlAbsent: true,
    manifestDigest,
  });

  const verifiedAt = monotonicTimestamp();
  const verificationReport = {
    schemaVersion: "1.0",
    reportId: `verification-${suffix}`,
    taskId,
    runId,
    verifiedAt,
    verifiedBy: "verifier-agent",
    result: "failed",
    manifest: { path: "build-manifest.json", digest: manifestDigest },
    checks: [
      { name: "bounded-marker-created", status: "passed", evidence: ["build-evidence.json"] },
      { name: "deployment-record-succeeded", status: "passed", evidence: ["build-evidence.json"] },
      { name: "bounded-marker-public-url-absent", status: "passed", evidence: ["build-evidence.json"] },
      { name: "rollback-drill-gate", status: "failed", evidence: ["acceptance-contract.md"] },
    ],
  };
  writeJson("verification-report.json", verificationReport);
  writeText("verification-summary.md", verificationSummaryMarkdown(verificationReport));
  record("verifier-agent", "verification.completed", "failed", {
    summary: "The Verifier-scoped route confirmed integrity before the harness entered the planned recovery branch.",
    result: "failed",
    artifact: "verification-report.json",
  }, { deploymentId });

  const verificationDigest = sha256File("verification-report.json");
  const approvalId = `approval-${suffix}`;
  const approvalRequestedAt = monotonicTimestamp();
  record("release-governor", "approval.requested", "pending", {
    summary: "The role-scoped harness requested only the explicitly authorized local rollback action.",
    decision: "pending",
    artifact: "approval.json",
  }, { deploymentId, approvalId });
  const approvalDecidedAt = monotonicTimestamp();
  const approval = {
    schemaVersion: "1.0",
    approvalId,
    runId,
    requestedAt: approvalRequestedAt,
    requestedBy: "release-governor",
    riskLevel: "low",
    humanApprovalRequired: true,
    decision: "approved",
    requestedAction: {
      type: "rollback",
      target: "sandbox",
      manifest: "build-manifest.json",
      manifestDigest,
      verificationReport: "verification-report.json",
      verificationDigest,
    },
    policyChecks: [
      { name: "bounded-path", status: "passed" },
      { name: "no-overwrite", status: "passed" },
      { name: "planned-rollback-gate", status: "passed" },
    ],
    evidence: ["build-manifest.json", "build-evidence.json", "verification-report.json"],
    decidedBy: "local-operator",
    decidedAt: approvalDecidedAt,
    reason: "The explicit --approve-local-rollback flag authorized only this reversible local sandbox drill.",
  };
  writeJson("approval.json", approval);
  record("release-governor", "approval.decided", "approved", {
    summary: "The harness recorded explicit approval for only the bounded local compensation action.",
    decision: "approved",
    artifact: "approval.json",
  }, { deploymentId, approvalId });

  record("release-governor", "tool.requested", "running", {
    summary: "The role-scoped harness requested compensation through the Release Governor route.",
    toolName: "deploymentRollback",
    target: markerPath,
  }, { deploymentId, approvalId });
  const rollbackVerification = rollbackAndVerify(deploymentId, { allowPartial: false });
  rollbackFinished = true;
  record("release-governor", "rollback.completed", "succeeded", {
    summary: "The Release Governor-scoped route compensated exactly the bounded marker deployment.",
    result: "passed",
    target: markerPath,
  }, { deploymentId, approvalId });
  record("verifier-agent", "verification.completed", "succeeded", {
    summary: "The Verifier-scoped route independently confirmed marker absence and rolled-back status.",
    result: "passed",
    evidence: ["rollback-report.json"],
  }, { deploymentId });

  writeJson("rollback-report.json", {
    schemaVersion: "1.0",
    taskId,
    runId,
    executionActor: harnessActor,
    governorRoleIdentity: "release-governor",
    verifierRoleIdentity: "verifier-agent",
    deploymentId,
    status: rollbackVerification.status,
    markerPath,
    markerAbsent: true,
    ownershipVersionId: rollbackVerification.ownershipVersionId,
    rollbackAction: rollbackVerification.action,
    completedAt: monotonicTimestamp(),
  });
  writeJson("recovery-verification-report.json", {
    schemaVersion: "1.0",
    reportId: `verification-recovery-${suffix}`,
    taskId,
    runId,
    verifiedAt: monotonicTimestamp(),
    verifiedBy: "verifier-agent",
    result: "passed",
    manifest: { path: "build-manifest.json", digest: manifestDigest },
    checks: [
      { name: "bounded-marker-absent", status: "passed", evidence: ["rollback-report.json"] },
      { name: "deployment-rolled-back", status: "passed", evidence: ["rollback-report.json"] },
      { name: "rollback-action-succeeded", status: "passed", evidence: ["rollback-report.json"] },
    ],
  });
  writeJson("release-decision.json", {
    schemaVersion: "1.0",
    taskId,
    runId,
    executionActor: harnessActor,
    exercisedRoleIdentity: "release-governor",
    deploymentId,
    decision: "REJECTED_RECOVERED",
    reason: "The planned rollback drill failed the release gate and bounded compensation completed successfully.",
    productionPromoted: false,
    decidedAt: monotonicTimestamp(),
  });
  writeJson(
    "task-state.json",
    taskState(taskSpec, manifestDigest, verificationDigest, approvalRequestedAt, approvalDecidedAt),
  );
  writeText("retrospective.md", retrospectiveMarkdown(taskSpec, deploymentId));
  record("delivery-lead", "dry_run.completed", "succeeded", {
    summary: "The role-scoped harness completed the bounded local recovery drill without production promotion.",
    result: "passed",
    evidence: ["release-decision.json", "recovery-verification-report.json"],
  }, { deploymentId, approvalId });
  writeTrace();
  writeChecksums();
  validateEvidenceTree();

  renameSync(runDir, finalRunDir);
  published = true;
  runDir = finalRunDir;
  process.stdout.write(`${JSON.stringify({
    success: true,
    taskId,
    runId,
    deploymentId,
    outcome: "REJECTED_RECOVERED",
    markerAbsent: true,
    executionActor: harnessActor,
    evidenceDirectory: finalRunDir,
  }, null, 2)}\n`);
}

function parseArgs(values) {
  const parsed = {};
  for (let index = 0; index < values.length; index += 1) {
    const value = values[index];
    if (value === "--evidence-root") parsed.evidenceRoot = requiredArgument(values[++index]);
    else if (value === "--run-id") parsed.runId = requiredArgument(values[++index]);
    else if (value === "--task-id") parsed.taskId = requiredArgument(values[++index]);
    else if (value === "--approve-local-rollback") parsed.approveLocalRollback = true;
    else if (value === "--self-test") parsed.selfTest = true;
    else fail("UNSUPPORTED_ARGUMENT");
  }
  if (parsed.selfTest && Object.keys(parsed).length !== 1) fail("SELF_TEST_ARGUMENT_CONFLICT");
  return parsed;
}

function requiredArgument(value) {
  if (typeof value !== "string" || value.startsWith("--")) fail("ARGUMENT_VALUE_REQUIRED");
  return value;
}

function runCanonicalValidator() {
  const result = spawnWithTimeout(process.execPath, [validatorPath], packageRoot, 8 * 1024 * 1024);
  if (result.kind !== "completed" || result.status !== 0) fail("CANONICAL_PACKAGE_VALIDATION_FAILED");
}

function prepareEvidenceRoot(root) {
  mkdirSync(evidenceBoundary, { recursive: true, mode: 0o700 });
  const boundary = realpathSync(evidenceBoundary);
  const requested = path.resolve(root);
  const relative = path.relative(boundary, requested);
  if (relative === ".." || relative.startsWith(`..${path.sep}`) || path.isAbsolute(relative)) {
    fail("EVIDENCE_ROOT_OUTSIDE_BOUNDARY");
  }
  assertExistingAncestorsNotSymlinks(boundary, requested);
  mkdirSync(requested, { recursive: true, mode: 0o700 });
  const resolved = realpathSync(root);
  if (resolved !== boundary && !resolved.startsWith(`${boundary}${path.sep}`)) {
    fail("EVIDENCE_ROOT_OUTSIDE_BOUNDARY");
  }
  assertNoSymlinkPath(boundary, resolved);
  chmodSync(resolved, 0o700);
}

function compensateAfterFailure() {
  if (rollbackFinished) return "already-verified";
  if (deploymentForCleanup != null) {
    try {
      const result = rollbackAndVerify(deploymentForCleanup, { allowPartial: true });
      rollbackFinished = true;
      return `verified-${result.status}`;
    } catch {
      return "failed-independent-verification";
    }
  }
  if (stageAttempted) {
    try {
      const reconciled = reconcileBoundedDeployment({ waitForSettlement: true });
      if (reconciled != null) {
        deploymentForCleanup = reconciled.deployment.id;
        const result = rollbackAndVerify(deploymentForCleanup, { allowPartial: true });
        rollbackFinished = true;
        return `verified-${result.status}`;
      }
      verifyMarkerAbsentOnly();
      return "no-deployment-marker-absent";
    } catch {
      return "failed-independent-verification";
    }
  }
  return "not-required";
}

function recoverUncertainStage() {
  const reconciled = reconcileBoundedDeployment({ waitForSettlement: true });
  if (reconciled == null) {
    verifyMarkerAbsentOnly();
    rollbackFinished = true;
    return;
  }
  deploymentForCleanup = reconciled.deployment.id;
  rollbackAndVerify(deploymentForCleanup, { allowPartial: true });
  rollbackFinished = true;
}

function assertNoSymlinkPath(boundary, target) {
  let current = boundary;
  const relative = path.relative(boundary, target);
  for (const segment of relative.split(path.sep).filter(Boolean)) {
    current = path.join(current, segment);
    if (lstatSync(current).isSymbolicLink()) fail("EVIDENCE_PATH_SYMLINK_REJECTED");
  }
}

function assertExistingAncestorsNotSymlinks(boundary, target) {
  let current = boundary;
  const relative = path.relative(boundary, target);
  for (const segment of relative.split(path.sep).filter(Boolean)) {
    current = path.join(current, segment);
    if (!existsSync(current)) return;
    if (lstatSync(current).isSymbolicLink()) fail("EVIDENCE_PATH_SYMLINK_REJECTED");
  }
}

function verifyRoleToolInventories(policy) {
  const routes = new Map(policy.routes.map((route) => [route.name, route]));
  for (const role of Object.values(roles)) {
    const route = routes.get(role.route);
    assert(route, "POLICY_ROUTE_MISSING");
    const inventory = dockerJson(role.container, ["mcporter", "list", "--json"]);
    const server = selectSingleNubaseServer(inventory, role.route);
    assert(server.status === "ok", "MCP_ROUTE_NOT_READY");
    const actual = (server.tools ?? []).map((tool) => tool.name);
    assert(actual.every((name) => typeof name === "string"), "MCP_TOOL_INVENTORY_INVALID");
    assert(new Set(actual).size === actual.length, "MCP_TOOL_INVENTORY_DUPLICATE");
    const expected = [...route.javaHttpPolicy.allowTools];
    assert(JSON.stringify([...actual].sort()) === JSON.stringify(expected.sort()), "MCP_TOOL_INVENTORY_MISMATCH");
  }
}

function selectSingleNubaseServer(inventory, expectedRoute) {
  assert(Array.isArray(inventory?.servers), "MCP_SERVER_INVENTORY_INVALID");
  const nubaseServers = inventory.servers.filter(
    (candidate) => typeof candidate?.name === "string" && candidate.name.startsWith("nubase-"),
  );
  assert(nubaseServers.length === 1, "NUBASE_MCP_SERVER_NOT_SINGLETON");
  assert(nubaseServers[0].name === expectedRoute, "NUBASE_MCP_ROUTE_MISMATCH");
  return nubaseServers[0];
}

function stageArgs(digest) {
  return { appName, taskId, runId, manifestDigest: digest };
}

function callTool(role, toolName, toolArgs) {
  const outcome = callToolOutcome(role, toolName, toolArgs);
  if (outcome.kind !== "response") fail("RUNTIME_COMMAND_UNCERTAIN");
  return outcome.value;
}

function callToolOutcome(role, toolName, toolArgs) {
  const result = spawnWithTimeout("docker", [
    "exec",
    role.container,
    "mcporter",
    "call",
    `${role.route}.${toolName}`,
    "--args",
    JSON.stringify(toolArgs),
    "--output",
    "json",
  ], packageRoot, 4 * 1024 * 1024);
  if (result.kind !== "completed" || result.status !== 0) return { kind: "uncertain" };
  try {
    return { kind: "response", value: JSON.parse(result.stdout) };
  } catch {
    return { kind: "uncertain" };
  }
}

function assertToolRejected(role, toolName, toolArgs) {
  const result = spawnWithTimeout("docker", [
    "exec",
    role.container,
    "mcporter",
    "call",
    `${role.route}.${toolName}`,
    "--args",
    JSON.stringify(toolArgs),
    "--output",
    "json",
  ], packageRoot, 1024 * 1024);
  assert(result.kind === "completed", "FORBIDDEN_TOOL_REJECTION_UNVERIFIED");
  assert(isExplicitToolRejection(result), "FORBIDDEN_TOOL_REJECTION_UNVERIFIED");
}

function isExplicitToolRejection(result) {
  let body = null;
  try {
    body = JSON.parse(result.stdout || result.stderr);
  } catch {
    body = null;
  }
  if (body?.success === true && body?.isError !== true) return false;
  const boundedText = `${result.stdout ?? ""}\n${result.stderr ?? ""}`.slice(0, 64 * 1024);
  return explicitDenialPattern.test(boundedText);
}

function dockerJson(container, command) {
  const result = spawnWithTimeout("docker", ["exec", container, ...command], packageRoot, 4 * 1024 * 1024);
  if (result.kind !== "completed" || result.status !== 0) fail("RUNTIME_COMMAND_FAILED");
  try {
    return JSON.parse(result.stdout);
  } catch {
    fail("RUNTIME_RESPONSE_NOT_JSON");
  }
}

function spawnWithTimeout(command, commandArgs, cwd, maxBuffer) {
  const result = spawnSync(command, commandArgs, {
    cwd,
    encoding: "utf8",
    maxBuffer,
    timeout: commandTimeoutMs,
    killSignal: "SIGKILL",
  });
  if (result.error != null || result.signal != null || result.status == null) {
    return { kind: "uncertain", stdout: result.stdout ?? "", stderr: result.stderr ?? "" };
  }
  return {
    kind: "completed",
    status: result.status,
    stdout: result.stdout ?? "",
    stderr: result.stderr ?? "",
  };
}

function reconcileBoundedDeployment({ waitForSettlement }) {
  const deadline = Date.now() + (waitForSettlement ? reconciliationTimeoutMs : 0);
  let lastEvidence = null;
  do {
    const deployments = callTool(roles.verifier, "deploymentsList", { limit: 200 });
    assert(Array.isArray(deployments), "DEPLOYMENT_LIST_INVALID");
    const boundedSameRun = deployments.filter((deployment) => (
      deployment?.runId === runId && deployment?.manifestSummary?.profile === "bounded-asset-v1"
    ));
    assert(boundedSameRun.length <= 1, "BOUNDED_DEPLOYMENT_NOT_UNIQUE");
    if (boundedSameRun.length === 1) {
      const candidate = boundedSameRun[0];
      assert(candidate.manifestSummary?.manifestDigest === manifestDigest, "RUN_ID_MANIFEST_CONFLICT");
      lastEvidence = readDeploymentEvidence(candidate.id);
      assert(sameDeploymentIdentity(candidate, lastEvidence.deployment), "DEPLOYMENT_LIST_DETAIL_MISMATCH");
      if (!waitForSettlement || isSettledDeployment(lastEvidence)) return lastEvidence;
    }
    if (!waitForSettlement || Date.now() >= deadline) break;
    sleepSync(reconciliationIntervalMs);
  } while (Date.now() <= deadline);
  return lastEvidence;
}

function readDeploymentEvidence(deploymentId) {
  assertPattern(deploymentId, uuidPattern, "deploymentId");
  const detail = callTool(roles.verifier, "deploymentStatus", { id: deploymentId });
  const logs = callTool(roles.verifier, "deploymentLogs", { id: deploymentId });
  assert(detail?.deployment != null && Array.isArray(detail.steps), "DEPLOYMENT_STATUS_INVALID");
  assert(Array.isArray(logs), "DEPLOYMENT_LOGS_INVALID");
  validateDeploymentIdentity(detail.deployment);
  assert(sameStepSequence(detail.steps, logs), "DEPLOYMENT_STATUS_LOGS_MISMATCH");
  return { deployment: detail.deployment, steps: logs };
}

function validateDeploymentIdentity(deployment) {
  assertPattern(deployment?.id, uuidPattern, "deploymentId");
  assert(deployment.appName === appName, "DEPLOYMENT_APP_NAME_MISMATCH");
  assert(deployment.runId === runId, "DEPLOYMENT_RUN_ID_MISMATCH");
  assert(deployment.agentId === "builder-agent", "DEPLOYMENT_AGENT_ID_MISMATCH");
  assert(deployment.publicUrl == null, "DEPLOYMENT_PUBLIC_URL_PRESENT");
  const summary = deployment.manifestSummary;
  assertExactKeys(summary, [
    "artifactDigest",
    "manifestDigest",
    "markerPath",
    "profile",
    "taskId",
    "transport",
  ], "DEPLOYMENT_MANIFEST_FIELDS_MISMATCH");
  assert(summary.manifestDigest === manifestDigest, "DEPLOYMENT_MANIFEST_DIGEST_MISMATCH");
  assert(summary.markerPath === markerPath, "DEPLOYMENT_MARKER_PATH_MISMATCH");
  assert(summary.profile === "bounded-asset-v1", "DEPLOYMENT_PROFILE_MISMATCH");
  assert(summary.taskId === taskId, "DEPLOYMENT_TASK_ID_MISMATCH");
  assert(summary.transport === "java-http-mcp", "DEPLOYMENT_TRANSPORT_MISMATCH");
  assertPattern(summary.artifactDigest, sha256Pattern, "artifactDigest");
}

function sameDeploymentIdentity(left, right) {
  return left?.id === right?.id
    && left?.runId === right?.runId
    && left?.appName === right?.appName
    && JSON.stringify(left?.manifestSummary) === JSON.stringify(right?.manifestSummary);
}

function sameStepSequence(left, right) {
  if (left.length !== right.length) return false;
  return left.every((step, index) => {
    const other = right[index];
    return step?.id === other?.id
      && step?.stepOrder === other?.stepOrder
      && step?.stepName === other?.stepName
      && step?.targetName === other?.targetName
      && step?.status === other?.status
      && JSON.stringify(step?.result) === JSON.stringify(other?.result)
      && step?.errorMessage === other?.errorMessage;
  });
}

function isSettledDeployment(evidence) {
  return evidence.deployment.status !== "running";
}

function validateStageResponse(staged, evidence) {
  assert(staged.success === true, "STAGE_DID_NOT_SUCCEED");
  assert(staged.status === "succeeded", "STAGE_STATUS_MISMATCH");
  assert(staged.path === markerPath, "STAGE_PATH_MISMATCH");
  assertPattern(staged.deploymentId, uuidPattern, "deploymentId");
  assert(staged.deploymentId === evidence.deployment.id, "STAGE_DEPLOYMENT_ID_MISMATCH");
  assertPattern(staged.artifactDigest, sha256Pattern, "artifactDigest");
  assert(
    staged.artifactDigest === evidence.deployment.manifestSummary.artifactDigest,
    "STAGE_DEPLOYMENT_ARTIFACT_DIGEST_MISMATCH",
  );
  validateBoundedMarkerSize(staged.sizeBytes, "STAGE_SIZE_INVALID");
  assertPattern(staged.ownershipEtag, etagPattern, "ownershipEtag");
  assertPattern(staged.ownershipVersionId, ownershipVersionIdPattern, "ownershipVersionId");
  assert(staged.errorCode == null, "STAGE_ERROR_CODE_PRESENT");
}

function validateSucceededDeploymentEvidence(evidence, staged) {
  assert(evidence.deployment.status === "succeeded", "DEPLOYMENT_NOT_SUCCEEDED");
  const originalSteps = evidence.steps.filter((step) => !String(step.stepName).startsWith("rollback:"));
  assert(originalSteps.length === 1, "DEPLOYMENT_ORIGINAL_STEP_COUNT_MISMATCH");
  const succeededAssets = originalSteps.filter(
    (step) => step.stepName === "assets_upload" && step.status === "succeeded",
  );
  assert(succeededAssets.length === 1, "SUCCEEDED_ASSET_STEP_NOT_UNIQUE");
  const step = succeededAssets[0];
  assert(step.stepOrder === 1, "ASSET_STEP_ORDER_MISMATCH");
  assert(step.targetName === markerPath, "ASSET_STEP_TARGET_MISMATCH");
  assertExactKeys(
    step.result,
    ["artifactDigest", "sizeBytes", "etag", "ownershipVersionId"],
    "ASSET_STEP_RESULT_FIELDS_MISMATCH",
  );
  validateArtifactDigestChain(
    staged.artifactDigest,
    evidence.deployment.manifestSummary.artifactDigest,
    step.result.artifactDigest,
  );
  validateBoundedMarkerSize(step.result.sizeBytes, "ASSET_STEP_SIZE_INVALID");
  assert(step.result.sizeBytes === staged.sizeBytes, "ASSET_STEP_SIZE_MISMATCH");
  assert(step.result.etag === staged.ownershipEtag, "ASSET_STEP_ETAG_MISMATCH");
  assertPattern(step.result.ownershipVersionId, ownershipVersionIdPattern, "ownershipVersionId");
  assert(
    step.result.ownershipVersionId === staged.ownershipVersionId,
    "ASSET_STEP_VERSION_ID_MISMATCH",
  );
  assert(step.errorMessage == null, "ASSET_STEP_ERROR_PRESENT");
}

function validateMarkerPresent(response, staged) {
  const files = extractFiles(response);
  assert(files.length === 1, "BOUNDED_MARKER_FILE_COUNT_MISMATCH");
  const marker = files[0];
  assert(marker.path === markerPath, "MARKER_PATH_MISMATCH");
  validateBoundedMarkerSize(marker.sizeBytes, "MARKER_SIZE_INVALID");
  assert(marker.sizeBytes === staged.sizeBytes, "MARKER_SIZE_MISMATCH");
  assert(marker.etag === staged.ownershipEtag, "MARKER_ETAG_MISMATCH");
  assert(marker.contentType === "application/json", "MARKER_CONTENT_TYPE_MISMATCH");
  assert(marker.cacheControl === "no-store", "MARKER_CACHE_CONTROL_MISMATCH");
  assert(marker.publicUrl == null, "MARKER_PUBLIC_URL_PRESENT");
}

function rollbackAndVerify(deploymentId, { allowPartial }) {
  const rollbackOutcome = callToolOutcome(roles.governor, "deploymentRollback", { id: deploymentId });
  const post = waitForPostRollbackEvidence(deploymentId);
  const originalSucceededAssets = post.steps.filter(
    (step) => step.stepName === "assets_upload" && step.status === "succeeded" && step.targetName === markerPath,
  );
  assert(originalSucceededAssets.length <= 1, "SUCCEEDED_ASSET_STEP_NOT_UNIQUE");

  if (originalSucceededAssets.length === 1) {
    assert(post.deployment.status === "rolled_back", "POST_ROLLBACK_STATUS_MISMATCH");
    const ownershipVersionId = originalSucceededAssets[0].result?.ownershipVersionId;
    assertPattern(ownershipVersionId, ownershipVersionIdPattern, "ownershipVersionId");
    const actions = post.steps.filter(
      (step) => step.stepName === "rollback:assets_upload" && step.targetName === markerPath,
    );
    assert(actions.length === 1, "ROLLBACK_LOG_ACTION_NOT_UNIQUE");
    validateRollbackLogAction(actions[0], ownershipVersionId);
    if (rollbackOutcome.kind === "response") {
      const idempotent = rollbackOutcome.value?.success === true
        && rollbackOutcome.value?.deploymentId === deploymentId
        && rollbackOutcome.value?.status === "rolled_back"
        && Array.isArray(rollbackOutcome.value?.actions)
        && rollbackOutcome.value.actions.length === 0;
      if (!idempotent || !allowPartial) {
        validateRollbackResponse(rollbackOutcome.value, deploymentId, ownershipVersionId);
      }
    }
    return {
      status: post.deployment.status,
      ownershipVersionId,
      action: normalizedRollbackAction(actions[0]),
      responseReconciled: rollbackOutcome.kind !== "response",
    };
  }

  assert(allowPartial, "ROLLBACK_ORIGINAL_ASSET_STEP_MISSING");
  assert(
    ["partially_rolled_back", "rolled_back"].includes(post.deployment.status),
    "POST_COMPENSATION_STATUS_MISMATCH",
  );
  if (rollbackOutcome.kind === "response") {
    assert(rollbackOutcome.value?.deploymentId === deploymentId, "ROLLBACK_DEPLOYMENT_ID_MISMATCH");
    assert(rollbackOutcome.value?.status === post.deployment.status, "ROLLBACK_RESPONSE_STATUS_MISMATCH");
  }
  return { status: post.deployment.status, action: null, responseReconciled: rollbackOutcome.kind !== "response" };
}

function waitForPostRollbackEvidence(deploymentId) {
  const deadline = Date.now() + reconciliationTimeoutMs;
  let lastStatus = null;
  do {
    const assets = callTool(roles.verifier, "assetsList", {
      prefix: `__goai_e2e/${runId}/`,
      limit: 10,
    });
    const post = readDeploymentEvidence(deploymentId);
    lastStatus = post.deployment.status;
    if (extractFiles(assets).length === 0
      && ["rolled_back", "partially_rolled_back", "rollback_failed"].includes(lastStatus)) {
      return post;
    }
    if (Date.now() >= deadline) break;
    sleepSync(reconciliationIntervalMs);
  } while (Date.now() <= deadline);
  fail(lastStatus === "rollback_failed" ? "ROLLBACK_FAILED" : "POST_ROLLBACK_NOT_SETTLED");
}

function validateRollbackResponse(rollback, deploymentId, ownershipVersionId) {
  assert(rollback?.success === true, "ROLLBACK_DID_NOT_SUCCEED");
  assert(rollback.deploymentId === deploymentId, "ROLLBACK_DEPLOYMENT_ID_MISMATCH");
  assert(rollback.status === "rolled_back", "ROLLBACK_STATUS_MISMATCH");
  assert(Array.isArray(rollback.actions) && rollback.actions.length === 1, "ROLLBACK_ACTION_COUNT_MISMATCH");
  const action = rollback.actions[0];
  assert(action.stepName === "rollback:assets_upload", "ROLLBACK_ACTION_STEP_MISMATCH");
  assert(action.targetName === markerPath, "ROLLBACK_ACTION_TARGET_MISMATCH");
  assert(action.status === "succeeded", "ROLLBACK_ACTION_STATUS_MISMATCH");
  assertExactKeys(
    action.result,
    ["operation", "ownershipVersionId", "path"],
    "ROLLBACK_ACTION_RESULT_FIELDS_MISMATCH",
  );
  assert(action.result.operation === "asset_version_deleted", "ROLLBACK_ACTION_OPERATION_MISMATCH");
  assert(
    action.result.ownershipVersionId === ownershipVersionId,
    "ROLLBACK_ACTION_VERSION_ID_MISMATCH",
  );
  assert(action.result.path === markerPath, "ROLLBACK_ACTION_RESULT_PATH_MISMATCH");
  assert(action.errorMessage == null, "ROLLBACK_ACTION_ERROR_PRESENT");
}

function validateRollbackLogAction(action, ownershipVersionId) {
  assert(action.status === "succeeded", "ROLLBACK_LOG_ACTION_STATUS_MISMATCH");
  assertExactKeys(
    action.result,
    ["operation", "ownershipVersionId", "path"],
    "ROLLBACK_LOG_RESULT_FIELDS_MISMATCH",
  );
  assert(action.result.operation === "asset_version_deleted", "ROLLBACK_LOG_OPERATION_MISMATCH");
  assert(action.result.ownershipVersionId === ownershipVersionId, "ROLLBACK_LOG_VERSION_ID_MISMATCH");
  assert(action.result.path === markerPath, "ROLLBACK_LOG_PATH_MISMATCH");
  assert(action.errorMessage == null, "ROLLBACK_LOG_ERROR_PRESENT");
}

function normalizedRollbackAction(action) {
  return {
    stepName: action.stepName,
    targetName: action.targetName,
    status: action.status,
    result: action.result,
    errorMessage: action.errorMessage,
  };
}

function verifyMarkerAbsentOnly() {
  const assets = callTool(roles.verifier, "assetsList", {
    prefix: `__goai_e2e/${runId}/`,
    limit: 10,
  });
  assert(extractFiles(assets).length === 0, "MARKER_STILL_PRESENT");
}

function extractFiles(response) {
  assert(response?.success === true && Array.isArray(response.files), "ASSET_LIST_INVALID");
  return response.files;
}

function validateArtifactDigestChain(responseDigest, summaryDigest, stepDigest) {
  assertPattern(responseDigest, sha256Pattern, "artifactDigest");
  assertPattern(summaryDigest, sha256Pattern, "artifactDigest");
  assertPattern(stepDigest, sha256Pattern, "artifactDigest");
  assert(responseDigest === summaryDigest, "STAGE_DEPLOYMENT_ARTIFACT_DIGEST_MISMATCH");
  assert(responseDigest === stepDigest, "ASSET_STEP_DIGEST_MISMATCH");
}

function validateBoundedMarkerSize(value, code) {
  assert(Number.isSafeInteger(value) && value > 0 && value <= maxMarkerSizeBytes, code);
}

function taskState(spec, digest, verificationDigest, approvalRequestedAt, approvalDecidedAt) {
  const times = Array.from({ length: 5 }, () => monotonicTimestamp());
  return {
    schemaVersion: "1.0",
    taskId: spec.taskId,
    runId: spec.runId,
    sequence: 5,
    version: 5,
    phase: "rolled_back",
    history: [
      state(1, "planned", "delivery-lead", times[0]),
      state(2, "building", "builder-agent", times[1]),
      state(3, "verifying", "verifier-agent", times[2]),
      state(4, "rollback_required", "release-governor", times[3]),
      state(5, "rolled_back", "release-governor", times[4]),
    ],
    assignments: Object.values(roles).map((role, index) => ({
      agentId: role.agentId,
      role: ["plan", "build", "verify", "govern"][index],
      status: "completed",
      inputVersion: index + 1,
    })),
    artifacts: [
      artifact("build-manifest", "build-manifest.json", digest, "builder-agent", 2),
      artifact("verification-report", "verification-report.json", verificationDigest, "verifier-agent", 3),
      artifact("rollback-report", "rollback-report.json", sha256File("rollback-report.json"), "release-governor", 5),
      artifact(
        "recovery-verification-report",
        "recovery-verification-report.json",
        sha256File("recovery-verification-report.json"),
        "verifier-agent",
        5,
      ),
    ],
    approval: {
      required: true,
      status: "approved",
      approvalId: `approval-${suffix}`,
      requestedAt: approvalRequestedAt,
      decidedAt: approvalDecidedAt,
      decidedBy: "local-operator",
      version: 5,
    },
  };
}

function state(number, phase, updatedBy, updatedAt) {
  return { sequence: number, version: number, phase, updatedAt, updatedBy };
}

function artifact(name, artifactPath, digest, producedBy, version) {
  return { name, path: artifactPath, digest, producedBy, version };
}

function record(agentId, eventType, status, payload, correlation = {}) {
  sequence += 1;
  trace.push({
    schemaVersion: "1.0",
    eventId: `event-${suffix}-${String(sequence).padStart(2, "0")}`,
    runId,
    timestamp: monotonicTimestamp(),
    sequence,
    agentId,
    eventType,
    status,
    correlation: { taskId, ...correlation },
    payload,
  });
}

function writeTrace() {
  writeFileSync(path.join(runDir, "trace.jsonl"), `${trace.map((event) => JSON.stringify(event)).join("\n")}\n`, {
    mode: 0o600,
  });
}

function writeChecksums() {
  const files = expectedEvidenceFiles().filter((file) => file !== "checksums.sha256");
  const lines = files.map((file) => `${sha256File(file).slice("sha256:".length)}  ${file}`);
  writeFileSync(path.join(runDir, "checksums.sha256"), `${lines.join("\n")}\n`, { mode: 0o600 });
}

function validateEvidenceTree() {
  const actualFiles = readdirSync(runDir).sort();
  assert(JSON.stringify(actualFiles) === JSON.stringify(expectedEvidenceFiles()), "EVIDENCE_FILE_SET_MISMATCH");
  const forbidden = [
    /-----BEGIN [A-Z ]*PRIVATE KEY-----/,
    /authorization\s*[:=]\s*(?:bearer|basic)\s+/i,
    /\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\b/,
    /\b(?:api[_-]?key|token|password|secret|cookie)\s*[:=]\s*["']?[A-Za-z0-9+/_=-]{12,}/i,
  ];
  for (const file of actualFiles) {
    const filePath = path.join(runDir, file);
    const stat = lstatSync(filePath);
    assert(stat.isFile() && !stat.isSymbolicLink(), "EVIDENCE_NON_REGULAR_FILE");
    assert((stat.mode & 0o077) === 0, "EVIDENCE_FILE_MODE_TOO_BROAD");
    const content = readFileSync(filePath, "utf8");
    assert(!forbidden.some((pattern) => pattern.test(content)), "EVIDENCE_SENSITIVE_PATTERN");
  }
  validateChecksums();
  validateGeneratedSchemas();
  const traceEvents = readFileSync(path.join(runDir, "trace.jsonl"), "utf8")
    .trim()
    .split("\n")
    .map((line) => JSON.parse(line));
  assert(traceEvents.length === trace.length, "TRACE_EVENT_COUNT_MISMATCH");
  assert(traceEvents.every((event) => event.runId === runId), "TRACE_RUN_ID_MISMATCH");
  assert(traceEvents.every((event, index) => event.sequence === index + 1), "TRACE_SEQUENCE_MISMATCH");
  assert(readJson("execution-attestation.json").executionActor === harnessActor, "EXECUTION_ACTOR_MISMATCH");
  validateVersionedEvidence();
}

function validateVersionedEvidence() {
  const build = readJson("build-evidence.json");
  const rollback = readJson("rollback-report.json");
  assertPattern(build.ownershipVersionId, ownershipVersionIdPattern, "ownershipVersionId");
  assert(build.markerPublicUrlAbsent === true, "EVIDENCE_MARKER_PUBLIC_URL_NOT_PROVEN_ABSENT");
  assert(rollback.ownershipVersionId === build.ownershipVersionId, "EVIDENCE_VERSION_ID_MISMATCH");
  assert(
    rollback.rollbackAction?.result?.ownershipVersionId === build.ownershipVersionId,
    "EVIDENCE_ROLLBACK_VERSION_ID_MISMATCH",
  );
  assert(
    rollback.rollbackAction?.result?.operation === "asset_version_deleted",
    "EVIDENCE_ROLLBACK_OPERATION_MISMATCH",
  );
}

function expectedEvidenceFiles() {
  return [
    "acceptance-contract.md",
    "approval.json",
    "build-evidence.json",
    "build-manifest.json",
    "checksums.sha256",
    "delivery-plan.md",
    "execution-attestation.json",
    "recovery-verification-report.json",
    "release-decision.json",
    "retrospective.md",
    "rollback-report.json",
    "task-spec.md",
    "task-state.json",
    "trace.jsonl",
    "verification-report.json",
    "verification-summary.md",
  ].sort();
}

function validateGeneratedSchemas() {
  const approvalSchema = readJsonFile(path.join(contractsRoot, "approval.schema.json"));
  const verificationSchema = readJsonFile(path.join(contractsRoot, "verification-report.schema.json"));
  const taskStateSchema = readJsonFile(path.join(contractsRoot, "task-state.schema.json"));
  const traceSchema = readJsonFile(path.join(contractsRoot, "trace-event.schema.json"));
  validateBySchema(readJson("approval.json"), approvalSchema, "approval.json");
  validateBySchema(readJson("verification-report.json"), verificationSchema, "verification-report.json");
  validateBySchema(
    readJson("recovery-verification-report.json"),
    verificationSchema,
    "recovery-verification-report.json",
  );
  validateBySchema(readJson("task-state.json"), taskStateSchema, "task-state.json");
  const events = readFileSync(path.join(runDir, "trace.jsonl"), "utf8").trim().split("\n");
  for (let index = 0; index < events.length; index += 1) {
    validateBySchema(JSON.parse(events[index]), traceSchema, `trace.jsonl:${index + 1}`);
  }
}

function validateBySchema(value, schema, location) {
  const errors = [];
  validateSchemaNode(value, schema, location, errors);
  if (errors.length > 0) fail(`JSON_SCHEMA_VALIDATION_FAILED_${safeCode(location)}`);
}

function validateSchemaNode(value, schema, location, out) {
  if (!schema || typeof schema !== "object" || Array.isArray(schema)) {
    out.push(`${location}: invalid schema`);
    return;
  }
  if (Object.hasOwn(schema, "const") && !deepEqual(value, schema.const)) out.push(`${location}: const`);
  if (Array.isArray(schema.enum) && !schema.enum.some((candidate) => deepEqual(value, candidate))) {
    out.push(`${location}: enum`);
  }
  if (schema.type !== undefined && !matchesType(value, schema.type)) {
    out.push(`${location}: type`);
    return;
  }
  if (Array.isArray(schema.oneOf)) {
    const matches = schema.oneOf.filter((branch) => {
      const branchErrors = [];
      validateSchemaNode(value, branch, location, branchErrors);
      return branchErrors.length === 0;
    }).length;
    if (matches !== 1) out.push(`${location}: oneOf`);
  }
  for (const branch of schema.allOf ?? []) validateSchemaNode(value, branch, location, out);
  if (schema.not) {
    const branchErrors = [];
    validateSchemaNode(value, schema.not, location, branchErrors);
    if (branchErrors.length === 0) out.push(`${location}: not`);
  }
  if (typeof value === "string") {
    if (schema.minLength !== undefined && value.length < schema.minLength) out.push(`${location}: minLength`);
    if (schema.maxLength !== undefined && value.length > schema.maxLength) out.push(`${location}: maxLength`);
    if (schema.pattern !== undefined && !new RegExp(schema.pattern).test(value)) out.push(`${location}: pattern`);
    if (schema.format === "date-time"
      && (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z$/.test(value)
        || !Number.isFinite(Date.parse(value)))) {
      out.push(`${location}: date-time`);
    }
  }
  if (typeof value === "number") {
    if (schema.minimum !== undefined && value < schema.minimum) out.push(`${location}: minimum`);
    if (schema.maximum !== undefined && value > schema.maximum) out.push(`${location}: maximum`);
  }
  if (Array.isArray(value)) {
    if (schema.minItems !== undefined && value.length < schema.minItems) out.push(`${location}: minItems`);
    if (schema.maxItems !== undefined && value.length > schema.maxItems) out.push(`${location}: maxItems`);
    if (schema.uniqueItems === true && new Set(value.map((item) => JSON.stringify(item))).size !== value.length) {
      out.push(`${location}: uniqueItems`);
    }
    if (schema.items) value.forEach((item, index) => validateSchemaNode(item, schema.items, `${location}[${index}]`, out));
  }
  if (value && typeof value === "object" && !Array.isArray(value)) {
    const properties = schema.properties ?? {};
    for (const key of schema.required ?? []) {
      if (!Object.hasOwn(value, key)) out.push(`${location}: required ${key}`);
    }
    for (const [key, child] of Object.entries(value)) {
      if (Object.hasOwn(properties, key)) {
        validateSchemaNode(child, properties[key], `${location}.${key}`, out);
      } else if (schema.additionalProperties === false) {
        out.push(`${location}: additional ${key}`);
      } else if (schema.additionalProperties && typeof schema.additionalProperties === "object") {
        validateSchemaNode(child, schema.additionalProperties, `${location}.${key}`, out);
      }
    }
  }
}

function matchesType(value, expected) {
  const types = Array.isArray(expected) ? expected : [expected];
  return types.some((type) => {
    if (type === "null") return value === null;
    if (type === "array") return Array.isArray(value);
    if (type === "object") return Boolean(value) && typeof value === "object" && !Array.isArray(value);
    if (type === "integer") return Number.isInteger(value);
    if (type === "number") return typeof value === "number" && Number.isFinite(value);
    return typeof value === type;
  });
}

function validateChecksums() {
  const lines = readFileSync(path.join(runDir, "checksums.sha256"), "utf8").trim().split("\n");
  const expected = expectedEvidenceFiles().filter((file) => file !== "checksums.sha256");
  assert(lines.length === expected.length, "CHECKSUM_FILE_COUNT_MISMATCH");
  for (let index = 0; index < lines.length; index += 1) {
    const match = lines[index].match(/^([a-f0-9]{64})  ([A-Za-z0-9._/-]+)$/);
    assert(match != null, "CHECKSUM_LINE_INVALID");
    assert(match[2] === expected[index], "CHECKSUM_FILE_ORDER_MISMATCH");
    assert(sha256File(match[2]) === `sha256:${match[1]}`, "CHECKSUM_MISMATCH");
  }
}

function writeJson(name, value) {
  writeFileSync(path.join(runDir, name), `${JSON.stringify(value, null, 2)}\n`, { mode: 0o600 });
}

function writeText(name, value) {
  writeFileSync(path.join(runDir, name), value.endsWith("\n") ? value : `${value}\n`, { mode: 0o600 });
}

function readJson(name) {
  return readJsonFile(path.join(runDir, name));
}

function readJsonFile(name) {
  return JSON.parse(readFileSync(name, "utf8"));
}

function sha256File(name) {
  return sha256Bytes(readFileSync(path.join(runDir, name)));
}

function sha256Bytes(value) {
  return `sha256:${createHash("sha256").update(value).digest("hex")}`;
}

function sha256Json(value) {
  return sha256Bytes(JSON.stringify(value));
}

function taskSpecMarkdown(spec) {
  return `# Local Closure Task\n\n- Task: \`${spec.taskId}\`\n- Run: \`${spec.runId}\`\n- Target: \`${spec.target}\`\n- Profile: \`${spec.profile}\`\n- Execution actor: \`${harnessActor}\`\n- Planned outcome: \`${spec.requestedOutcome}\`\n- Production promotion: disabled\n- LLM AgentTeams execution claim: none\n`;
}

function deliveryPlanMarkdown(spec) {
  return `# Delivery Plan\n\nThe \`${harnessActor}\` exercises four isolated MCP role identities; it does not claim an LLM-orchestrated AgentTeams run.\n\n1. Use the Delivery Lead-scoped identity to fix the bounded contract for \`${spec.runId}\` and require an explicitly enabled private, versioned backend bucket with every public Assets origin disabled.\n2. Use the Builder-scoped identity to stage one server-generated marker with overwrite disabled and no public URL.\n3. Use the service-role Verifier-scoped identity to check the otherwise hidden marker and deployment record independently.\n4. Use the Release Governor-scoped identity to reject promotion and compensate the bounded deployment.\n5. Use the Verifier-scoped identity to confirm the marker is absent and the deployment is rolled back.\n`;
}

function acceptanceContractMarkdown(spec) {
  return `# Acceptance Contract\n\n- Execution actor must be \`${harnessActor}\`; no LLM AgentTeams execution is claimed.\n- Only \`${spec.markerPath}\` may be created.\n- \`NUBASE_ASSETS_BOUNDED_PRIVATE_STORAGE_ENABLED\` must be explicitly \`true\`; \`NUBASE_ASSETS_BUCKET\`, \`NUBASE_ASSETS_PUBLIC_BASE_URL\`, and \`R2_PUBLIC_URL\` must remain empty.\n- The marker must be server generated and uploaded with \`upsert=false\` into a dedicated private bucket whose versioning was enabled before the run.\n- A successful stage must return and record one non-secret \`ownershipVersionId\` and no public URL.\n- Anonymous/authenticated metadata access and public GET, HEAD, and SPA fallback must hide the reserved marker; only the service-role Verifier may inspect sanitized metadata through \`assetsList\`.\n- Sensitive and SQL execution tools must remain unavailable through explicit transport rejection.\n- The planned rollback gate must produce a no-go decision.\n- Completion requires marker absence, deployment status \`rolled_back\`, and one \`asset_version_deleted\` action for that exact ownership version.\n- No production promotion is permitted.\n`;
}

function verificationSummaryMarkdown(report) {
  return `# Verification Summary\n\nThe \`${harnessActor}\` used the Verifier-scoped route to confirm the bounded marker and deployment record, then failed the planned rollback-drill gate. Result: \`${report.result}\`. This is role-isolation evidence, not an LLM AgentTeams execution claim.\n`;
}

function retrospectiveMarkdown(spec, deploymentId) {
  return `# Retrospective\n\nThe \`${harnessActor}\` exercised four scoped MCP identities for \`${spec.runId}\`. Deployment \`${deploymentId}\` was compensated, the private marker was never assigned a public URL, the marker was removed, and no production promotion was attempted. This artifact does not claim that four LLM Workers performed the run.\n`;
}

function monotonicTimestamp() {
  const now = Math.max(Date.now(), lastTimestampMs + 1);
  lastTimestampMs = now;
  return new Date(now).toISOString();
}

function sleepSync(milliseconds) {
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, milliseconds);
}

function formatTimestamp(value) {
  return value.toISOString().replace(/[-:]/g, "").replace(/\.\d{3}Z$/, "z").toLowerCase();
}

function safeCode(value) {
  return String(value).replace(/[^A-Z0-9_-]/gi, "_").slice(0, 80) || "LOCAL_CLOSURE_FAILED";
}

function assertExactKeys(value, expected, code) {
  assert(value != null && typeof value === "object" && !Array.isArray(value), code);
  const actual = Object.keys(value).sort();
  assert(JSON.stringify(actual) === JSON.stringify([...expected].sort()), code);
}

function isUuid(value) {
  return typeof value === "string" && uuidPattern.test(value);
}

function assertPattern(value, pattern, name) {
  if (typeof value !== "string" || !pattern.test(value)) fail(`${name.toUpperCase()}_INVALID`);
}

function deepEqual(left, right) {
  return JSON.stringify(left) === JSON.stringify(right);
}

function assert(condition, code) {
  if (!condition) fail(code);
}

function fail(code) {
  throw new Error(code);
}

function runSelfTest() {
  const unrelated = { name: "metrics", status: "ok", tools: [] };
  const target = { name: "nubase-read", status: "ok", tools: [{ name: "projectsGet" }] };
  assert(selectSingleNubaseServer({ servers: [unrelated, target] }, "nubase-read") === target, "SELF_TEST_ROUTE");
  let duplicateRejected = false;
  try {
    selectSingleNubaseServer({ servers: [target, { ...target }] }, "nubase-read");
  } catch {
    duplicateRejected = true;
  }
  assert(duplicateRejected, "SELF_TEST_ROUTE_DUPLICATE");
  assert(
    isExplicitToolRejection({ stdout: "", stderr: "Error: tool not allowed", status: 1 }),
    "SELF_TEST_EXPLICIT_DENIAL",
  );
  assert(
    !isExplicitToolRejection({ stdout: JSON.stringify({ success: false, error: "business failure" }), stderr: "", status: 0 }),
    "SELF_TEST_BUSINESS_DENIAL",
  );
  const schema = {
    type: "object",
    additionalProperties: false,
    required: ["value"],
    properties: { value: { const: "ok" } },
  };
  validateBySchema({ value: "ok" }, schema, "self-test");
  const firstTimestamp = monotonicTimestamp();
  const secondTimestamp = monotonicTimestamp();
  assert(Date.parse(secondTimestamp) > Date.parse(firstTimestamp), "SELF_TEST_TIMESTAMP_ORDER");
  assert(commandTimeoutMs > 0 && reconciliationTimeoutMs > 0, "SELF_TEST_TIMEOUTS");
  assert(parseArgs(["--approve-local-rollback"]).approveLocalRollback === true, "SELF_TEST_APPROVAL_FLAG");
  assertPattern("version-self-test", ownershipVersionIdPattern, "ownershipVersionId");
  let missingVersionRejected = false;
  try {
    assertPattern(undefined, ownershipVersionIdPattern, "ownershipVersionId");
  } catch {
    missingVersionRejected = true;
  }
  assert(missingVersionRejected, "SELF_TEST_MISSING_VERSION_ID");
  let nullVersionRejected = false;
  try {
    assertPattern("null", ownershipVersionIdPattern, "ownershipVersionId");
  } catch {
    nullVersionRejected = true;
  }
  assert(nullVersionRejected, "SELF_TEST_NULL_VERSION_ID");
  const digestA = sha256Bytes("artifact-a");
  const digestB = sha256Bytes("artifact-b");
  validateArtifactDigestChain(digestA, digestA, digestA);
  const digestMismatchCases = [
    [digestB, digestA, digestA],
    [digestA, digestB, digestA],
    [digestA, digestA, digestB],
  ];
  for (const mismatch of digestMismatchCases) {
    let mismatchRejected = false;
    try {
      validateArtifactDigestChain(...mismatch);
    } catch {
      mismatchRejected = true;
    }
    assert(mismatchRejected, "SELF_TEST_ARTIFACT_DIGEST_MISMATCH");
  }
  validateBoundedMarkerSize(256, "SELF_TEST_MARKER_SIZE");
  const rollbackAction = {
    status: "succeeded",
    result: {
      operation: "asset_version_deleted",
      ownershipVersionId: "version-self-test",
      path: markerPath,
    },
    errorMessage: null,
  };
  validateRollbackLogAction(rollbackAction, "version-self-test");
  let mismatchedVersionRejected = false;
  try {
    validateRollbackLogAction(rollbackAction, "version-other");
  } catch {
    mismatchedVersionRejected = true;
  }
  assert(mismatchedVersionRejected, "SELF_TEST_MISMATCHED_VERSION_ID");
}
