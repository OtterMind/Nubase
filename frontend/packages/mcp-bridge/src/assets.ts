import { readFile } from 'node:fs/promises';
import { intOption, required } from './args.js';
import type { BridgeConfig } from './config.js';
import { parseFunctionArgs } from './functions.js';
import { NubaseClient } from './nubase-client.js';

export async function runAssetsCommand(args: string[], config: BridgeConfig, client = new NubaseClient(config)) {
  const command = args[0];
  switch (command) {
    case 'list':
      return assetsList(args.slice(1), client);
    case 'upload':
      return assetsUpload(args.slice(1), client);
    case 'delete':
      return assetsDelete(args.slice(1), client);
    default:
      // No or unknown subcommand prints usage and exits 0, mirroring functions/cron help.
      return assetsHelp();
  }
}

async function assetsList(args: string[], client: NubaseClient) {
  const { options } = parseFunctionArgs(args);
  return client.assetsList({
    prefix: stringOption(options.prefix),
    search: stringOption(options.search),
    limit: intOption(options, 'limit', { min: 1 }),
    offset: intOption(options, 'offset', { min: 0 }),
  });
}

// Upload reads bytes from a local --file (any type, sent as base64) or inline
// --content text. The positional arg is the public asset path (e.g. index.html
// or css/app.css).
async function assetsUpload(args: string[], client: NubaseClient) {
  const { positional, options } = parseFunctionArgs(args);
  const path = required(positional[0], 'asset path');
  const file = stringOption(options.file);
  const content = stringOption(options.content);
  if ((file === undefined) === (content === undefined)) {
    throw new Error('Provide exactly one of --file <localPath> or --content <text>');
  }
  const source =
    file !== undefined ? { contentBase64: (await readFile(file)).toString('base64') } : { content };
  return client.assetsUpload({
    path,
    ...source,
    contentType: stringOption(options['content-type']),
    cacheControl: stringOption(options['cache-control']),
    // Default upsert; --create forces create-only (409s when the path exists).
    upsert: options.create === true ? false : undefined,
  });
}

async function assetsDelete(args: string[], client: NubaseClient) {
  const { positional } = parseFunctionArgs(args);
  const path = required(positional[0], 'asset path');
  return client.assetsDelete({ path });
}

function stringOption(value: string | boolean | undefined) {
  return typeof value === 'string' ? value : undefined;
}

function assetsHelp() {
  return {
    usage: [
      'nubase_cli assets list [--prefix css/] [--search app] [--limit 100] [--offset 0]',
      "NUBASE_ALLOW_ADMIN_WRITE=true nubase_cli assets upload index.html --file ./dist/index.html [--content-type 'text/html'] [--cache-control 'public, max-age=3600'] [--create]",
      'NUBASE_ALLOW_ADMIN_WRITE=true nubase_cli assets upload robots.txt --content "User-agent: *"',
      'NUBASE_ALLOW_ADMIN_WRITE=true nubase_cli assets delete css/old.css',
    ],
  };
}
