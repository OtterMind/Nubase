import { readdir, readFile, stat } from 'node:fs/promises';
import { randomBytes } from 'node:crypto';
import path from 'node:path';
import { required } from './args.js';
import type { BridgeConfig } from './config.js';
import { parseFunctionArgs, runFunctionsCommand } from './functions.js';
import type { NubaseClient } from './nubase-client.js';
import { assertNoSecurityFindings, scanUploadContent } from './security-scan.js';

interface DeployStep {
  step: string;
  name?: string;
  success: boolean;
  result?: unknown;
  error?: string;
}

interface DeployContext {
  config: BridgeConfig;
  client: NubaseClient;
  baseDir: string;
  steps: DeployStep[];
  continueOnError: boolean;
  release?: { id: string; prefix: string };
  deploymentId?: string;
  deploymentRecordError?: string;
}

export async function runDeployAppCommand(args: string[], config: BridgeConfig, client: NubaseClient) {
  const command = args[0];
  if (!command || command === 'help' || command === '--help' || command === '-h') {
    return deployAppHelp();
  }
  if (command !== 'deploy') {
    throw new Error(`Unsupported app command: ${command}`);
  }
  const { positional, options } = parseFunctionArgs(args.slice(1));
  const manifestPath = required(positional[0], 'manifest path');
  const manifest = await loadManifest(manifestPath);
  return deployApp(manifest, config, client, {
    baseDir: path.dirname(path.resolve(manifestPath)),
    continueOnError: options['continue-on-error'] === true || asBoolean(manifest.continueOnError),
    verifyFunctions: options['no-verify-functions'] === true ? false : asBoolean(manifest.verifyFunctions) !== false,
  });
}

export async function deployApp(
  manifest: Record<string, unknown>,
  config: BridgeConfig,
  client: NubaseClient,
  options: { baseDir?: string; continueOnError?: boolean; verifyFunctions?: boolean } = {}
) {
  const ctx: DeployContext = {
    config,
    client,
    baseDir: path.resolve(options.baseDir ?? process.cwd()),
    steps: [],
    continueOnError: options.continueOnError === true,
  };
  const securityScanEnabled = manifest.securityScan !== false;
  const name = stringValue(manifest.name) ?? 'app';
  const summary: Record<string, unknown> = {
    app: name,
    nubaseUrl: config.nubaseUrl,
    projectRef: config.projectRef ?? null,
  };
  const release = resolveRelease(manifest, name);
  ctx.release = release;
  if (release) {
    summary.releaseId = release.id;
    summary.releasePrefix = release.prefix;
  }
  await createDeploymentRecord(ctx, name, manifest);

  try {
    const migrations = arrayValue(manifest.sql ?? manifest.migrations);
    for (const migration of migrations) {
      const item = objectValue(migration, 'migration');
      await recordStep(ctx, 'sql_execute', stringValue(item.name), async () => {
        const sql = await sqlFromManifestItem(item, ctx.baseDir);
        return ctx.client.sqlExecute({ sql });
      });
    }

    const functions = arrayValue(manifest.functions);
    const deployedFunctions: string[] = [];
    for (const fn of functions) {
      const item = objectValue(fn, 'function');
      const slug = requiredStringField(item, 'name', 'function.name');
      await recordStep(ctx, 'functions_deploy', slug, async () => {
        const args = ['deploy', slug];
        const dir = stringValue(item.dir);
        if (dir) args.push('--dir', resolveInputPath(dir, ctx.baseDir));
        if (!securityScanEnabled) args.push('--no-security-scan');
        if (asBoolean(item.bundle) === true) args.push('--bundle');
        if (asBoolean(item.noBundle) === true) args.push('--no-bundle');
        if (asBoolean(item.noVerifyJwt) === true) args.push('--no-verify-jwt');
        return runFunctionsCommand(args, ctx.config, ctx.client);
      });
      deployedFunctions.push(slug);

      const secrets = objectValueOrUndefined(item.secrets);
      if (secrets) {
        await recordStep(ctx, 'functions_secrets_set', slug, () =>
          ctx.client.functionsSetSecrets({ slug, secrets })
        );
      }

      if (options.verifyFunctions !== false && item.verify !== false) {
        const verify = objectValueOrUndefined(item.verify) ?? {};
        await recordStep(ctx, 'functions_invoke', slug, () =>
          ctx.client.functionsInvoke({
            slug,
            method: stringValue(verify.method) ?? 'GET',
            path: stringValue(verify.path),
            body: bodyValue(verify.body),
            contentType: stringValue(verify.contentType),
          })
        );
      }
    }
    summary.functions = deployedFunctions;

    const assetUrls: Record<string, string> = {};
    const assets = objectValueOrUndefined(manifest.assets);
    const uploadedAssets: Array<Record<string, unknown>> = [];
    if (assets) {
      const dir = stringValue(assets.dir);
      if (dir) {
        const prefix = release?.prefix ?? stringValue(assets.prefix) ?? '';
        const cacheControl = stringValue(assets.cacheControl);
        const files = await listFiles(resolveInputPath(dir, ctx.baseDir));
        for (const file of files) {
          const rel = path.relative(resolveInputPath(dir, ctx.baseDir), file).replace(/\\/g, '/');
          const assetPath = joinAssetPath(prefix, rel);
          await uploadAssetFile(ctx, assetPath, file, cacheControl, assetUrls, uploadedAssets, securityScanEnabled);
        }
      }
      for (const asset of arrayValue(assets.files)) {
        const item = objectValue(asset, 'asset');
        const assetPath = joinAssetPath(release?.prefix ?? '', requiredStringField(item, 'path', 'asset.path'));
        const sourceFile = stringValue(item.file);
        const cacheControl = stringValue(item.cacheControl) ?? stringValue(assets.cacheControl);
        await recordStep(ctx, 'assets_upload', assetPath, async () => {
          if (securityScanEnabled) {
            const content = sourceFile
              ? await readFile(resolveInputPath(sourceFile, ctx.baseDir))
              : requiredStringField(item, 'content', 'asset.content');
            await assertNoSecurityFindings(() => scanUploadContent(`asset:${assetPath}`, content));
          }
          const result = sourceFile
            ? await ctx.client.assetsUpload({
                path: assetPath,
                contentBase64: (await readFile(resolveInputPath(sourceFile, ctx.baseDir))).toString('base64'),
                contentType: stringValue(item.contentType),
                cacheControl,
                upsert: item.upsert,
              })
            : await ctx.client.assetsUpload({
                path: assetPath,
                content: requiredStringField(item, 'content', 'asset.content'),
                contentType: stringValue(item.contentType),
                cacheControl,
                upsert: item.upsert,
              });
          captureAssetUrl(assetPath, result, assetUrls);
          uploadedAssets.push(assetManifestEntry(assetPath, result));
          return result;
        });
      }
      if (release) {
        const manifestPath = joinAssetPath(release.prefix, '__nubase_release.json');
        await recordStep(ctx, 'assets_release_manifest', manifestPath, async () => {
          const result = await ctx.client.assetsUpload({
            path: manifestPath,
            content: JSON.stringify({
              app: name,
              releaseId: release.id,
              releasePrefix: release.prefix,
              createdAt: new Date().toISOString(),
              assets: uploadedAssets,
            }, null, 2),
            contentType: 'application/json; charset=utf-8',
            cacheControl: 'no-store',
          });
          captureAssetUrl(manifestPath, result, assetUrls);
          return result;
        });
      }
      const fallbackPath = resolveSpaFallbackPath(assets, release);
      if (fallbackPath) {
        await recordStep(ctx, 'assets_update_settings', fallbackPath, () =>
          ctx.client.assetsUpdateSettings({ spaFallbackPath: fallbackPath })
        );
      }
    }
    summary.assets = assetUrls;
    summary.publicUrl = assetUrls[release ? joinAssetPath(release.prefix, 'index.html') : 'index.html']
      ?? Object.values(assetUrls)[0]
      ?? null;

    const cronJobs = arrayValue(manifest.cron ?? manifest.jobs);
    const deployedJobs: string[] = [];
    for (const job of cronJobs) {
      const item = objectValue(job, 'cron job');
      const jobName = requiredStringField(item, 'name', 'cron.name');
      await recordStep(ctx, 'cron_create', jobName, async () => {
        const payload = { ...item };
        return ctx.client.cronCreateJob(payload);
      });
      deployedJobs.push(jobName);
    }
    summary.cron = deployedJobs;

    if (typeof manifest.memory === 'string' && manifest.memory.trim()) {
      await recordStep(ctx, 'memory_write', undefined, () =>
        ctx.client.memoryWrite({ content: manifest.memory, infer: true })
      );
    } else if (asBoolean(manifest.rememberDeployment) === true) {
      await recordStep(ctx, 'memory_write', undefined, () =>
        ctx.client.memoryWrite({
          content: `Deployed ${name}: ${JSON.stringify(summary)}`,
          infer: true,
        })
      );
    }

    const failed = ctx.steps.filter((step) => !step.success);
    const success = failed.length === 0;
    await completeDeploymentRecord(ctx, success ? 'succeeded' : 'failed', summary.publicUrl as string | null, firstFailure(ctx));
    return {
      success,
      deploymentId: ctx.deploymentId,
      deploymentRecordError: ctx.deploymentRecordError,
      ...summary,
      steps: ctx.steps,
      failedSteps: failed.length,
    };
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err);
    await completeDeploymentRecord(ctx, 'failed', summary.publicUrl as string | null, message);
    throw err;
  }
}

async function uploadAssetFile(
  ctx: DeployContext,
  assetPath: string,
  file: string,
  cacheControl: string | undefined,
  assetUrls: Record<string, string>,
  uploadedAssets?: Array<Record<string, unknown>>,
  securityScanEnabled = true
) {
  await recordStep(ctx, 'assets_upload', assetPath, async () => {
    const content = await readFile(file);
    if (securityScanEnabled) {
      await assertNoSecurityFindings(() => scanUploadContent(`asset:${assetPath}`, content));
    }
    const result = await ctx.client.assetsUpload({
      path: assetPath,
      contentBase64: content.toString('base64'),
      cacheControl,
    });
    captureAssetUrl(assetPath, result, assetUrls);
    uploadedAssets?.push(assetManifestEntry(assetPath, result));
    return result;
  });
}

async function recordStep(ctx: DeployContext, step: string, name: string | undefined, run: () => Promise<unknown>) {
  let result: unknown;
  try {
    result = await run();
  } catch (err) {
    const error = err instanceof Error ? err.message : String(err);
    const recorded = { step, name, success: false, error };
    ctx.steps.push(recorded);
    await recordDeploymentStep(ctx, recorded);
    if (!ctx.continueOnError) throw err;
    return { success: false, error };
  }
  const success = !isExplicitFailure(result);
  const recorded = { step, name, success, result };
  ctx.steps.push(recorded);
  await recordDeploymentStep(ctx, recorded);
  if (!success && !ctx.continueOnError) {
    throw new Error(`Step ${step}${name ? ` (${name})` : ''} failed: ${JSON.stringify(result)}`);
  }
  return result;
}

async function createDeploymentRecord(ctx: DeployContext, name: string, manifest: Record<string, unknown>) {
  try {
    const result = await ctx.client.deploymentCreate({
      appName: name,
      manifestSummary: manifestSummary(manifest, ctx.release),
      agentId: ctx.config.agentId,
      runId: ctx.config.runId,
    });
    if (result && typeof result === 'object' && typeof (result as Record<string, unknown>).id === 'string') {
      ctx.deploymentId = (result as Record<string, string>).id;
    }
  } catch (err) {
    ctx.deploymentRecordError = err instanceof Error ? err.message : String(err);
  }
}

async function recordDeploymentStep(ctx: DeployContext, step: DeployStep) {
  if (!ctx.deploymentId) return;
  try {
    await ctx.client.deploymentRecordStep({
      deploymentId: ctx.deploymentId,
      stepOrder: ctx.steps.length,
      stepName: step.step,
      targetName: step.name,
      status: step.success ? 'succeeded' : 'failed',
      result: step.result && typeof step.result === 'object' ? step.result : undefined,
      errorMessage: step.error,
    });
  } catch (err) {
    ctx.deploymentRecordError = err instanceof Error ? err.message : String(err);
  }
}

async function completeDeploymentRecord(ctx: DeployContext, status: string, publicUrl: string | null, errorMessage: string | undefined) {
  if (!ctx.deploymentId) return;
  try {
    await ctx.client.deploymentComplete({
      deploymentId: ctx.deploymentId,
      status,
      publicUrl: publicUrl ?? undefined,
      errorMessage,
    });
  } catch (err) {
    ctx.deploymentRecordError = err instanceof Error ? err.message : String(err);
  }
}

function firstFailure(ctx: DeployContext) {
  const failed = ctx.steps.find((step) => !step.success);
  return failed?.error;
}

function manifestSummary(manifest: Record<string, unknown>, release?: { id: string; prefix: string }) {
  return {
    hasMigrations: arrayValue(manifest.sql ?? manifest.migrations).length > 0,
    functions: arrayValue(manifest.functions).map((item) =>
      item && typeof item === 'object' ? stringValue((item as Record<string, unknown>).name) : undefined
    ).filter(Boolean),
    hasAssets: Boolean(objectValueOrUndefined(manifest.assets)),
    releaseId: release?.id,
    releasePrefix: release?.prefix,
    cron: arrayValue(manifest.cron ?? manifest.jobs).map((item) =>
      item && typeof item === 'object' ? stringValue((item as Record<string, unknown>).name) : undefined
    ).filter(Boolean),
  };
}

function resolveRelease(manifest: Record<string, unknown>, appName: string) {
  const assets = objectValueOrUndefined(manifest.assets);
  if (!assets) return undefined;
  const releaseEnabled = assets.release === true || typeof assets.releaseId === 'string' || typeof assets.releasePrefix === 'string';
  if (!releaseEnabled) return undefined;
  const releaseId = sanitizeReleaseSegment(stringValue(assets.releaseId) ?? defaultReleaseId());
  const explicitPrefix = stringValue(assets.releasePrefix);
  const prefix = explicitPrefix
    ? normalizeReleasePrefix(explicitPrefix)
    : joinAssetPath(joinAssetPath('__releases', sanitizeReleaseSegment(appName)), releaseId);
  return { id: releaseId, prefix };
}

function resolveSpaFallbackPath(assets: Record<string, unknown>, release: { prefix: string } | undefined) {
  const spaFallback = assets.spaFallback ?? assets.spaFallbackPath;
  if (spaFallback === false) return undefined;
  if (typeof spaFallback === 'string') {
    return joinAssetPath(release?.prefix ?? '', spaFallback);
  }
  if (spaFallback === true) {
    return joinAssetPath(release?.prefix ?? '', 'index.html');
  }
  return undefined;
}

function defaultReleaseId() {
  return `${new Date().toISOString().replace(/[-:.TZ]/g, '').slice(0, 14)}-${randomBytes(3).toString('hex')}`;
}

function sanitizeReleaseSegment(value: string) {
  return value.trim().toLowerCase().replace(/[^a-z0-9._-]+/g, '-').replace(/^-+|-+$/g, '') || 'release';
}

function normalizeReleasePrefix(value: string) {
  return value.replace(/^\/+|\/+$/g, '').split('/').map(sanitizeReleaseSegment).filter(Boolean).join('/');
}

function assetManifestEntry(path: string, result: unknown) {
  const out: Record<string, unknown> = { path };
  if (result && typeof result === 'object') {
    const obj = result as Record<string, unknown>;
    if (typeof obj.publicUrl === 'string') out.publicUrl = obj.publicUrl;
    if (typeof obj.etag === 'string') out.etag = obj.etag;
    if (typeof obj.sizeBytes === 'number') out.sizeBytes = obj.sizeBytes;
    if (typeof obj.contentType === 'string') out.contentType = obj.contentType;
  }
  return out;
}

function isExplicitFailure(result: unknown) {
  return Boolean(result && typeof result === 'object' && (result as Record<string, unknown>).success === false);
}

async function loadManifest(manifestPath: string) {
  try {
    return JSON.parse(await readFile(path.resolve(manifestPath), 'utf8')) as Record<string, unknown>;
  } catch (err) {
    throw new Error(`Invalid deploy manifest: ${err instanceof Error ? err.message : String(err)}`);
  }
}

async function sqlFromManifestItem(item: Record<string, unknown>, baseDir: string) {
  const sql = stringValue(item.sql);
  const file = stringValue(item.file);
  if ((sql === undefined) === (file === undefined)) {
    throw new Error('Each migration must provide exactly one of sql or file');
  }
  return sql ?? await readFile(resolveInputPath(file!, baseDir), 'utf8');
}

async function listFiles(root: string): Promise<string[]> {
  const info = await stat(root);
  if (info.isFile()) return [root];
  const entries = await readdir(root, { withFileTypes: true });
  const out: string[] = [];
  for (const entry of entries) {
    if (entry.name === '.DS_Store') continue;
    const full = path.join(root, entry.name);
    if (entry.isDirectory()) out.push(...await listFiles(full));
    if (entry.isFile()) out.push(full);
  }
  return out.sort((a, b) => a.localeCompare(b));
}

function captureAssetUrl(assetPath: string, result: unknown, assetUrls: Record<string, string>) {
  if (!result || typeof result !== 'object') return;
  const record = result as Record<string, unknown>;
  const publicUrl = stringValue(record.publicUrl) ?? stringValue(record.url);
  if (publicUrl) assetUrls[assetPath] = publicUrl;
}

function bodyValue(value: unknown) {
  if (typeof value === 'string') return value;
  if (value === undefined || value === null) return undefined;
  return JSON.stringify(value);
}

function objectValue(value: unknown, name: string) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error(`${name} must be an object`);
  }
  return value as Record<string, unknown>;
}

function objectValueOrUndefined(value: unknown) {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : undefined;
}

function arrayValue(value: unknown) {
  if (value === undefined || value === null) return [];
  if (!Array.isArray(value)) throw new Error('Expected an array in deploy manifest');
  return value;
}

function stringValue(value: unknown) {
  return typeof value === 'string' && value.trim() ? value : undefined;
}

function requiredStringField(record: Record<string, unknown>, key: string, label: string) {
  const value = stringValue(record[key]);
  if (!value) throw new Error(`${label} is required`);
  return value;
}

function asBoolean(value: unknown) {
  return typeof value === 'boolean' ? value : undefined;
}

function resolveInputPath(input: string, baseDir: string) {
  return path.isAbsolute(input) ? input : path.resolve(baseDir, input);
}

function joinAssetPath(prefix: string, rel: string) {
  return [prefix.replace(/^\/+|\/+$/g, ''), rel.replace(/^\/+/g, '')].filter(Boolean).join('/');
}

function deployAppHelp() {
  return {
    usage: [
      'nubase_cli app deploy nubase.deploy.json',
      'nubase_cli app deploy nubase.deploy.json --continue-on-error',
      'nubase_cli app deploy nubase.deploy.json --no-verify-functions',
    ],
    manifest: {
      name: 'notes',
      migrations: [{ name: 'schema', file: 'schema.sql' }],
      functions: [{ name: 'api', dir: 'nubase/functions/api', verify: { method: 'GET' } }],
      assets: { dir: 'dist', cacheControl: 'public, max-age=31536000' },
      cron: [{ name: 'nightly', cronExpression: '0 3 * * *', targetType: 'edge_function', functionSlug: 'api' }],
    },
  };
}
