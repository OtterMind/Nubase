import { createHash } from 'node:crypto';
import { mkdir, readFile, readdir, stat, writeFile } from 'node:fs/promises';
import path from 'node:path';
import type { BridgeConfig } from './config.js';
import { NubaseClient } from './nubase-client.js';

export async function runFunctionsCommand(args: string[], config: BridgeConfig, client = new NubaseClient(config)) {
  const command = args[0];
  if (!command || command === 'help' || command === '--help' || command === '-h') {
    return functionsHelp();
  }
  switch (command) {
    case 'new':
      return functionsNew(args.slice(1));
    case 'list':
      return client.functionsList();
    case 'deploy':
      return functionsDeploy(args.slice(1), client);
    case 'invoke':
      return functionsInvoke(args.slice(1), client);
    case 'delete':
      return functionsDelete(args.slice(1), client);
    case 'logs':
      return functionsLogs(args.slice(1), client);
    case 'secrets':
      return functionsSecrets(args.slice(1), client);
    default:
      throw new Error(`Unsupported functions command: ${command}`);
  }
}

// CLI exit code for a functions command result: refusals and other explicit
// failures (success === false, e.g. PERMISSION_GATE_OFF) exit 1 so scripts and
// agents can detect them; everything else is success.
export function resolveExitCode(result: unknown): number {
  if (result && typeof result === 'object' && (result as Record<string, unknown>).success === false) {
    return 1;
  }
  return 0;
}

export function parseFunctionArgs(args: string[]) {
  const out: Record<string, string | boolean> = {};
  const positional: string[] = [];
  for (let i = 0; i < args.length; i += 1) {
    const arg = args[i];
    if (!arg) continue;
    if (!arg.startsWith('--')) {
      positional.push(arg);
      continue;
    }
    const key = arg.slice(2);
    const next = args[i + 1];
    if (!next || next.startsWith('--')) {
      out[key] = true;
    } else {
      out[key] = next;
      i += 1;
    }
  }
  return { positional, options: out };
}

// Strict integer flag parsing shared by all CLI namespaces. `Number('600s')`
// is NaN, which survives a `typeof x === 'number'` filter and serializes to
// null — the server then silently applies its default. Reject loudly instead.
export function intOption(
  options: Record<string, string | boolean>,
  name: string,
  { min, max }: { min?: number; max?: number } = {}
): number | undefined {
  const value = options[name];
  if (value === undefined) return undefined;
  const parsed = typeof value === 'string' && /^-?\d+$/.test(value.trim()) ? Number(value.trim()) : NaN;
  if (!Number.isSafeInteger(parsed)) throw new Error(`--${name} must be an integer`);
  if (min !== undefined && parsed < min) throw new Error(`--${name} must be an integer >= ${min}`);
  if (max !== undefined && parsed > max) throw new Error(`--${name} must be an integer <= ${max}`);
  return parsed;
}

async function functionsNew(args: string[]) {
  const { positional } = parseFunctionArgs(args);
  const slug = required(positional[0], 'function name');
  const dir = path.join(process.cwd(), 'nubase', 'functions', slug);
  await mkdir(dir, { recursive: true });
  const indexPath = path.join(dir, 'index.js');
  const manifestPath = path.join(dir, 'nubase-function.json');
  await writeFile(indexPath, defaultFunctionSource(), { flag: 'wx' }).catch(async (err: NodeJS.ErrnoException) => {
    if (err.code !== 'EEXIST') throw err;
  });
  await writeFile(manifestPath, JSON.stringify({
    name: slug,
    slug,
    entrypoint: 'index.js',
    verifyJwt: true,
    privileged: false,
  }, null, 2) + '\n', { flag: 'wx' }).catch(async (err: NodeJS.ErrnoException) => {
    if (err.code !== 'EEXIST') throw err;
  });
  return { ok: true, functionDir: dir, files: [indexPath, manifestPath] };
}

async function functionsDeploy(args: string[], client: NubaseClient) {
  const { positional, options } = parseFunctionArgs(args);
  const slug = required(positional[0], 'function name');
  const dir = path.resolve(String(options.dir || path.join('nubase', 'functions', slug)));
  const manifest = await loadFunctionManifest(dir);
  const entrypoint = typeof manifest.entrypoint === 'string' && manifest.entrypoint.trim()
    ? manifest.entrypoint.trim()
    : undefined;
  const bundle = (await shouldBundle(dir, options, entrypoint))
    ? await bundleEntrypoint(dir, entrypoint)
    : await bundleDirectory(dir);
  const metadata = {
    name: typeof manifest.name === 'string' && manifest.name.trim() ? manifest.name.trim() : slug,
    slug,
    verifyJwt: options['no-verify-jwt'] === true ? false : booleanOption(manifest.verifyJwt),
    privileged: options.privileged === true ? true : undefined,
    entrypoint,
  };
  try {
    await client.functionsCreate(metadata);
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err);
    if (!/FUNCTION_EXISTS|409|already exists/i.test(message)) throw err;
    if (typeof client.functionsUpdate === 'function') {
      await client.functionsUpdate(metadata);
    }
  }
  return client.functionsDeploy({
    slug,
    sourceHash: bundle.sourceHash,
    artifactType: 'source_bundle',
    sourceBundleBase64: bundle.sourceBundleBase64,
  });
}

async function functionsInvoke(args: string[], client: NubaseClient) {
  const { positional, options } = parseFunctionArgs(args);
  const slug = required(positional[0], 'function name');
  return client.functionsInvoke({
    slug,
    method: typeof options.method === 'string' ? options.method : 'GET',
    path: typeof options.path === 'string' ? options.path : '',
    body: typeof options.body === 'string' ? options.body : undefined,
    contentType: typeof options['content-type'] === 'string' ? options['content-type'] : 'application/json',
  });
}

async function functionsDelete(args: string[], client: NubaseClient) {
  const { positional } = parseFunctionArgs(args);
  const slug = required(positional[0], 'function name');
  return client.functionsDelete({ slug });
}

async function functionsLogs(args: string[], client: NubaseClient) {
  const { positional, options } = parseFunctionArgs(args);
  return client.functionsLogs({
    slug: positional[0],
    limit: intOption(options, 'limit', { min: 1 }),
  });
}

async function functionsSecrets(args: string[], client: NubaseClient) {
  const action = args[0];
  if (action === 'list') {
    const { positional } = parseFunctionArgs(args.slice(1));
    const slug = required(positional[0], 'function name');
    return client.functionsListSecrets({ slug });
  }
  if (action === 'set') {
    const { positional } = parseFunctionArgs(args.slice(1));
    const slug = required(positional[0], 'function name');
    const pairs = positional.slice(1);
    const secrets: Record<string, string> = {};
    for (const pair of pairs) {
      const eq = pair.indexOf('=');
      if (eq <= 0) throw new Error(`Invalid secret assignment: ${pair}`);
      secrets[pair.slice(0, eq)] = pair.slice(eq + 1);
    }
    return client.functionsSetSecrets({ slug, secrets });
  }
  return {
    usage: [
      'nubase_cli functions secrets list <name>',
      'NUBASE_ALLOW_ADMIN_WRITE=true nubase_cli functions secrets set <name> API_KEY=value',
    ],
  };
}

// TypeScript entrypoints must be compiled before upload — the server deploys raw JS
// and rejects uncompiled .ts (TYPESCRIPT_REQUIRES_BUNDLE) — so deploy auto-bundles
// when the directory has an index.ts without a compiled index.js sibling.
async function shouldBundle(dir: string, options: Record<string, string | boolean>, entrypoint?: string) {
  if (options.bundle === true) return true;
  if (options['no-bundle'] === true) return false;
  const entry = entrypoint || 'index.ts';
  if (!entry.toLowerCase().endsWith('.ts')) return false;
  const compiled = entry.slice(0, -3) + '.js';
  return (await fileExists(path.join(dir, entry))) && !(await fileExists(path.join(dir, compiled)));
}

async function bundleDirectory(dir: string) {
  const files = await listFiles(dir);
  const entries = [];
  for (const file of files) {
    const rel = path.relative(dir, file).replace(/\\/g, '/');
    if (rel.startsWith('node_modules/') || rel.startsWith('.git/')) continue;
    const content = await readFile(file);
    entries.push({ path: rel, content: content.toString('base64') });
  }
  entries.sort((a, b) => a.path.localeCompare(b.path));
  return bundleFiles(entries);
}

async function bundleEntrypoint(dir: string, configuredEntrypoint?: string) {
  const entrypoint = await findEntrypoint(dir, configuredEntrypoint);
  const esbuild = await loadEsbuild();
  const result = await esbuild.build({
    entryPoints: [entrypoint],
    // esbuild's service process keeps the cwd it was spawned with; resolve
    // relative to the function directory instead of whatever that was.
    absWorkingDir: dir,
    bundle: true,
    write: false,
    format: 'esm',
    platform: 'browser',
    target: 'es2022',
    legalComments: 'none',
    logLevel: 'silent',
  });
  const output = result.outputFiles?.[0]?.text;
  if (!output) throw new Error('esbuild did not produce a bundled edge function');
  return bundleFiles([{ path: 'index.js', content: Buffer.from(output).toString('base64') }]);
}

function bundleFiles(entries: Array<{ path: string; content: string }>) {
  const payload = JSON.stringify({ files: entries });
  return {
    sourceHash: createHash('sha256').update(payload).digest('hex'),
    sourceBundleBase64: Buffer.from(payload).toString('base64'),
  };
}

async function findEntrypoint(dir: string, configuredEntrypoint?: string) {
  const candidates = configuredEntrypoint ? [configuredEntrypoint] : ['index.ts', 'index.js'];
  for (const file of candidates) {
    const full = path.join(dir, file);
    if (await fileExists(full)) return full;
  }
  throw new Error(`No edge function entrypoint found in ${dir}; expected ${candidates.join(' or ')}`);
}

async function loadFunctionManifest(dir: string): Promise<Record<string, unknown>> {
  const manifestPath = path.join(dir, 'nubase-function.json');
  try {
    return JSON.parse(await readFile(manifestPath, 'utf8'));
  } catch (err) {
    const code = (err as NodeJS.ErrnoException).code;
    if (code === 'ENOENT') return {};
    throw new Error(`Invalid nubase-function.json: ${err instanceof Error ? err.message : String(err)}`);
  }
}

function booleanOption(value: unknown) {
  return typeof value === 'boolean' ? value : undefined;
}

async function fileExists(file: string) {
  try {
    const info = await stat(file);
    return info.isFile();
  } catch {
    return false;
  }
}

async function loadEsbuild(): Promise<{
  build: (options: Record<string, unknown>) => Promise<{ outputFiles?: Array<{ text: string }> }>;
}> {
  try {
    const importPackage = new Function('specifier', 'return import(specifier)') as (specifier: string) => Promise<any>;
    return await importPackage('esbuild');
  } catch {
    throw new Error('functions deploy needs esbuild to compile this function (TypeScript entrypoint or --bundle); install esbuild or deploy precompiled JS');
  }
}

async function listFiles(dir: string): Promise<string[]> {
  const items = await readdir(dir, { withFileTypes: true });
  const out: string[] = [];
  for (const item of items) {
    if (item.isDirectory()) {
      // Prune dependency/VCS trees before recursing — never walk into them.
      if (item.name === 'node_modules' || item.name === '.git') continue;
      out.push(...await listFiles(path.join(dir, item.name)));
    } else if (item.isFile()) {
      out.push(path.join(dir, item.name));
    }
  }
  return out;
}

function required(value: string | undefined, name: string) {
  if (!value) throw new Error(`${name} is required`);
  return value;
}

function defaultFunctionSource() {
  return `export default {
  async fetch(req, env) {
    return Response.json({
      ok: true,
      method: req.method,
      url: req.url,
      projectRef: env.NUBASE_PROJECT_REF,
    });
  },
};
`;
}

function functionsHelp() {
  return {
    usage: [
      'nubase_cli functions new <name>',
      'nubase_cli functions list',
      'NUBASE_ALLOW_ADMIN_WRITE=true nubase_cli functions deploy <name> [--bundle|--no-bundle]',
      'NUBASE_ALLOW_ADMIN_WRITE=true nubase_cli functions delete <name>',
      'nubase_cli functions logs [name] --limit 50',
      'nubase_cli functions secrets list <name>',
      'NUBASE_ALLOW_ADMIN_WRITE=true nubase_cli functions secrets set <name> API_KEY=value',
      'nubase_cli functions invoke <name> --method POST --body \'{"ok":true}\'',
    ],
  };
}
