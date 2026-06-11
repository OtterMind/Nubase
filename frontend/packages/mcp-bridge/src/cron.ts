import type { BridgeConfig } from './config.js';
import { intOption, parseFunctionArgs } from './functions.js';
import { NubaseClient } from './nubase-client.js';

export async function runCronCommand(args: string[], config: BridgeConfig, client = new NubaseClient(config)) {
  const command = args[0];
  switch (command) {
    case 'list':
      return client.cronListJobs();
    case 'get':
      return cronGet(args.slice(1), client);
    case 'create':
      return cronCreate(args.slice(1), client);
    case 'update':
      return cronUpdate(args.slice(1), client);
    case 'delete':
      return cronDelete(args.slice(1), client);
    case 'runs':
      return cronRuns(args.slice(1), client);
    default:
      // No or unknown subcommand prints usage and exits 0, mirroring functions help.
      return cronHelp();
  }
}

async function cronGet(args: string[], client: NubaseClient) {
  const { positional } = parseFunctionArgs(args);
  const name = required(positional[0], 'job name');
  return client.cronGetJob({ name });
}

async function cronCreate(args: string[], client: NubaseClient) {
  const { positional, options } = parseFunctionArgs(args);
  const name = required(positional[0], 'job name');
  return client.cronCreateJob({
    name,
    cronExpression: required(stringOption(options.cron), '--cron'),
    targetType: required(stringOption(options.target), '--target'),
    ...optionalJobFields(options),
    enabled: options.disabled === true ? false : undefined,
  });
}

async function cronUpdate(args: string[], client: NubaseClient) {
  const { positional, options } = parseFunctionArgs(args);
  const name = required(positional[0], 'job name');
  return client.cronUpdateJob({
    name,
    cronExpression: stringOption(options.cron),
    ...optionalJobFields(options),
    enabled: options.enable === true ? true : options.disable === true || options.disabled === true ? false : undefined,
  });
}

async function cronDelete(args: string[], client: NubaseClient) {
  const { positional } = parseFunctionArgs(args);
  const name = required(positional[0], 'job name');
  return client.cronDeleteJob({ name });
}

async function cronRuns(args: string[], client: NubaseClient) {
  const { positional, options } = parseFunctionArgs(args);
  const limit = intOption(options, 'limit', { min: 1 });
  if (positional[0]) {
    return client.cronJobRuns({ name: positional[0], limit });
  }
  return client.cronRuns({ limit });
}

// Flag-to-payload mapping shared by create and update (name/targetType/enabled
// are handled per-command — targetType is immutable after create).
function optionalJobFields(options: Record<string, string | boolean>) {
  return {
    description: stringOption(options.description),
    functionSlug: stringOption(options.function),
    httpMethod: stringOption(options.method),
    requestPath: stringOption(options.path),
    requestBody: stringOption(options.body),
    dbFunctionName: stringOption(options['db-function']),
    dbFunctionArgs: typeof options.args === 'string' ? parseJsonObjectOption(options.args, 'args') : undefined,
    timeoutSeconds: intOption(options, 'timeout', { min: 1 }),
  };
}

function parseJsonObjectOption(value: string, flag: string): Record<string, unknown> {
  let parsed: unknown;
  try {
    parsed = JSON.parse(value);
  } catch {
    throw new Error(`--${flag} must be valid JSON, got: ${value}`);
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new Error(`--${flag} must be a JSON object, e.g. --${flag} '{"days":7}'`);
  }
  return parsed as Record<string, unknown>;
}

function stringOption(value: string | boolean | undefined) {
  return typeof value === 'string' ? value : undefined;
}

function required(value: string | undefined, name: string) {
  if (!value) throw new Error(`${name} is required`);
  return value;
}

function cronHelp() {
  return {
    usage: [
      'nubase_cli cron list',
      'nubase_cli cron get <name>',
      'NUBASE_ALLOW_ADMIN_WRITE=true nubase_cli cron create <name> --cron "0 3 * * *" --target edge_function --function <slug> [--method POST] [--path /x] [--body \'{"a":1}\'] [--timeout 60] [--description "..."] [--disabled]',
      'NUBASE_ALLOW_ADMIN_WRITE=true nubase_cli cron create <name> --cron "*/5 * * * *" --target db_function --db-function <fn> [--args \'{"days":7}\']',
      'NUBASE_ALLOW_ADMIN_WRITE=true nubase_cli cron update <name> [--cron "..."] [--enable|--disable] [--function <slug>] [--method POST] [--path /x] [--body \'{"a":1}\'] [--db-function <fn>] [--args \'{"days":7}\'] [--timeout 60] [--description "..."]',
      'NUBASE_ALLOW_ADMIN_WRITE=true nubase_cli cron delete <name>',
      'nubase_cli cron runs [name] --limit 50',
    ],
  };
}
