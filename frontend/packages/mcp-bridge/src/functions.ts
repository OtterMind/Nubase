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
  const bundle = options.bundle === true ? await bundleEntrypoint(dir) : await bundleDirectory(dir);
  try {
    await client.functionsCreate({
      name: slug,
      slug,
      verifyJwt: options['no-verify-jwt'] === true ? false : undefined,
      privileged: options.privileged === true ? true : undefined,
    });
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err);
    if (!/FUNCTION_EXISTS|409|already exists/i.test(message)) throw err;
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
    limit: typeof options.limit === 'string' ? Number(options.limit) : undefined,
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

async function bundleEntrypoint(dir: string) {
  const entrypoint = await findEntrypoint(dir);
  const esbuild = await loadEsbuild();
  const result = await esbuild.build({
    entryPoints: [entrypoint],
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

async function findEntrypoint(dir: string) {
  for (const file of ['index.ts', 'index.js']) {
    const full = path.join(dir, file);
    if (await fileExists(full)) return full;
  }
  throw new Error(`No edge function entrypoint found in ${dir}; expected index.ts or index.js`);
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
    throw new Error('functions deploy --bundle requires esbuild to be installed in the frontend workspace');
  }
}

async function listFiles(dir: string): Promise<string[]> {
  const items = await readdir(dir);
  const out: string[] = [];
  for (const item of items) {
    const full = path.join(dir, item);
    const info = await stat(full);
    if (info.isDirectory()) out.push(...await listFiles(full));
    else if (info.isFile()) out.push(full);
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
      'NUBASE_ALLOW_ADMIN_WRITE=true nubase_cli functions deploy <name> [--bundle]',
      'NUBASE_ALLOW_ADMIN_WRITE=true nubase_cli functions delete <name>',
      'nubase_cli functions logs [name] --limit 50',
      'nubase_cli functions secrets list <name>',
      'NUBASE_ALLOW_ADMIN_WRITE=true nubase_cli functions secrets set <name> API_KEY=value',
      'nubase_cli functions invoke <name> --method POST --body \'{"ok":true}\'',
    ],
  };
}
