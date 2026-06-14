import { requiredObject, requiredString } from './args.js';
import type { BridgeConfig } from './config.js';
import { withScope } from './context.js';
import { deployApp } from './deploy-app.js';
import { fetchDocs } from './docs.js';
import { runFunctionsCommand } from './functions.js';
import type { NubaseClient } from './nubase-client.js';

export interface ToolDefinition {
  name: string;
  description: string;
  inputSchema: Record<string, unknown>;
}

interface ToolEntry {
  description: string;
  inputSchema: Record<string, unknown>;
  handler: (args: Record<string, unknown>, config: BridgeConfig, client: NubaseClient) => unknown;
}

// Single source of truth per tool: schema and handler live side by side so a
// schema-advertised argument can never be silently dropped by a forgotten
// dispatch case. TOOLS and callTool are both derived from this table.
const TOOL_TABLE: Record<string, ToolEntry> = {
  fetch_docs: {
    description: 'Fetch bundled Nubase agent docs. Topics: overview, quickstart, setup, memory, database, auth, storage, ai_gateway, security, or all.',
    inputSchema: objectSchema({
      topic: { type: 'string' },
    }),
    handler: (args) => fetchDocs(typeof args.topic === 'string' ? args.topic : undefined),
  },
  nubase_capabilities: {
    description: 'Discover Nubase backend capabilities and stable API paths.',
    inputSchema: objectSchema({}),
    handler: (_args, _config, client) => client.capabilities(),
  },
  nubase_instructions: {
    description: 'Return agent instructions for using Nubase safely.',
    inputSchema: objectSchema({}),
    handler: (_args, _config, client) => client.instructions(),
  },
  nubase_overview: {
    description: 'One-shot snapshot of the whole backend in a single call: capabilities, database schema, storage buckets, auth users, AI Gateway keys, current permissions, and suggested next steps. Call this first when starting a Nubase task. Read-only; each section degrades gracefully if unauthorized.',
    inputSchema: objectSchema({
      schema: { type: 'string' },
    }),
    handler: (args, _config, client) => client.overview(args),
  },
  project_keys: {
    description: "Return this project's API keys for building apps: the anon/authenticated key (safe to embed in browser/client code, subject to RLS + user JWTs) and the service_role key (server-side/trusted tooling only — never ship to a browser). Read-only.",
    inputSchema: objectSchema({}),
    handler: (_args, _config, client) => client.projectKeys(),
  },
  projects_list: {
    description: 'List platform projects visible to the platform admin token. Requires NUBASE_PLATFORM_JWT or NUBASE_PLATFORM_KEY/NUBASE_METADATA_SERVICE_ROLE_KEY. Read-only.',
    inputSchema: objectSchema({}),
    handler: (_args, _config, client) => client.projectsList(),
  },
  project_keys_admin: {
    description: 'Fetch service_role and authenticated tokens for a platform project ref. Requires platform admin auth. Read-only but returns secrets; never put service_role tokens in frontend code.',
    inputSchema: objectSchema({ ref: { type: 'string' } }, ['ref']),
    handler: (args, _config, client) => client.projectKeysAdmin(args),
  },
  project_provision: {
    description: 'Provision a PENDING_INIT or INIT_FAILED project by ref. Requires platform admin auth and NUBASE_ALLOW_ADMIN_WRITE=true.',
    inputSchema: objectSchema({ ref: { type: 'string' } }, ['ref']),
    handler: (args, _config, client) => client.projectProvision(args),
  },
  project_update: {
    description: 'Rename, describe, pause, or resume a project. Requires platform admin auth and NUBASE_ALLOW_ADMIN_WRITE=true. Soft delete is intentionally not exposed.',
    inputSchema: objectSchema({
      ref: { type: 'string' },
      appName: { type: 'string' },
      description: { type: 'string' },
      enabled: { type: 'boolean' },
    }, ['ref']),
    handler: (args, _config, client) => client.projectUpdate(args),
  },
  project_select_instructions: {
    description: 'Return environment/config values needed to point the bridge at a project. Does not write files or secrets.',
    inputSchema: objectSchema({
      ref: { type: 'string' },
      serviceRoleKey: { type: 'string' },
      anonKey: { type: 'string' },
    }, ['ref']),
    handler: (args, _config, client) => client.projectSelectInstructions(args),
  },
  deploy_app: {
    description: 'Deploy a generated app from a manifest object: SQL migrations, Edge Functions, static Assets, cron jobs, and optional deployment Memory. This is the one-call deploy path for Codex/Claude Code. SQL execution needs NUBASE_ALLOW_SQL_EXECUTE=true; deploy writes need NUBASE_ALLOW_ADMIN_WRITE=true.',
    inputSchema: objectSchema({
      manifest: {
        type: 'object',
        description: 'Deployment manifest. Supports name, migrations/sql, functions, assets, cron/jobs, memory, rememberDeployment, continueOnError, verifyFunctions.',
      },
      baseDir: { type: 'string' },
      continueOnError: { type: 'boolean' },
      verifyFunctions: { type: 'boolean' },
    }, ['manifest']),
    handler: (args, config, client) => deployApp(
      requiredObject(args.manifest, 'manifest'),
      config,
      client,
      {
        baseDir: typeof args.baseDir === 'string' ? args.baseDir : undefined,
        continueOnError: args.continueOnError === true,
        verifyFunctions: typeof args.verifyFunctions === 'boolean' ? args.verifyFunctions : undefined,
      }
    ),
  },
  deployments_list: {
    description: 'List recent app deployments for this project. Read-only.',
    inputSchema: objectSchema({
      limit: { type: 'number' },
    }),
    handler: (args, _config, client) => client.deploymentsList(args),
  },
  deployment_status: {
    description: 'Get one app deployment with its recorded steps. Read-only.',
    inputSchema: objectSchema({
      id: { type: 'string' },
    }, ['id']),
    handler: (args, _config, client) => client.deploymentStatus(args),
  },
  deployment_logs: {
    description: 'List recorded deployment steps/logs for one app deployment. Read-only.',
    inputSchema: objectSchema({
      id: { type: 'string' },
    }, ['id']),
    handler: (args, _config, client) => client.deploymentLogs(args),
  },
  deployment_rollback: {
    description: 'Rollback supported resources from one app deployment. Write op; disabled unless NUBASE_ALLOW_ADMIN_WRITE=true. Currently deletes recorded Assets and cron jobs, and records skipped actions for non-reversible steps.',
    inputSchema: objectSchema({
      id: { type: 'string' },
    }, ['id']),
    handler: (args, _config, client) => client.deploymentRollback(args),
  },
  memory_context: {
    description: 'Return compact relevant memory context for a task. Scope defaults can come from NUBASE_USER_ID, NUBASE_AGENT_ID, and NUBASE_RUN_ID.',
    inputSchema: objectSchema({
      task: { type: 'string' },
      topK: { type: 'number' },
      userId: { type: 'string' },
      agentId: { type: 'string' },
      runId: { type: 'string' },
    }, ['task']),
    handler: (args, config, client) => client.memoryContext(withScope(config, args)),
  },
  memory_search: {
    description: 'Search Nubase long-term memory.',
    inputSchema: objectSchema({
      query: { type: 'string' },
      topK: { type: 'number' },
      userId: { type: 'string' },
      agentId: { type: 'string' },
      runId: { type: 'string' },
    }, ['query']),
    handler: (args, config, client) => client.memorySearch(withScope(config, args)),
  },
  memory_write: {
    description: 'Write durable Nubase memory.',
    inputSchema: objectSchema({
      content: { type: 'string' },
      infer: { type: 'boolean' },
      userId: { type: 'string' },
      agentId: { type: 'string' },
      runId: { type: 'string' },
    }, ['content']),
    handler: (args, config, client) => client.memoryWrite(withScope(config, args)),
  },
  rest_select: {
    description: 'Call Nubase /rest/v1 for a table using a PostgREST query string, for example select=*&limit=10.',
    inputSchema: objectSchema({
      table: { type: 'string' },
      query: { type: 'string' },
    }, ['table']),
    handler: (args, _config, client) => client.restSelect(args),
  },
  sql_dry_run: {
    description: 'Classify SQL risk and statement count without executing it.',
    inputSchema: objectSchema({ sql: { type: 'string' } }, ['sql']),
    handler: (args, _config, client) => client.sqlDryRun(args),
  },
  sql_execute: {
    description: 'Execute SQL through Nubase admin API. Disabled unless NUBASE_ALLOW_SQL_EXECUTE=true.',
    inputSchema: objectSchema({ sql: { type: 'string' } }, ['sql']),
    handler: (args, _config, client) => client.sqlExecute(args),
  },
  db_export_schema: {
    description: 'Export table DDL for a Postgres schema (default public) to inspect the database structure. Read-only.',
    inputSchema: objectSchema({
      schema: { type: 'string' },
      tables: { type: 'string' },
      includeDrop: { type: 'boolean' },
    }),
    handler: (args, _config, client) => client.dbExportSchema(args),
  },
  db_list_migrations: {
    description: 'List the audit trail of schema-changing SQL applied through sql_execute (most recent first), with timestamp, risk, and the SQL text. Read-only; returns an empty list if nothing has been recorded yet.',
    inputSchema: objectSchema({
      limit: { type: 'number' },
    }),
    handler: (args, _config, client) => client.listMigrations(args),
  },
  storage_list_buckets: {
    description: 'List Nubase storage buckets. Read-only.',
    inputSchema: objectSchema({
      search: { type: 'string' },
      limit: { type: 'number' },
      offset: { type: 'number' },
    }),
    handler: (args, _config, client) => client.storageListBuckets(args),
  },
  storage_create_bucket: {
    description: 'Create a storage bucket. Write op; disabled unless NUBASE_ALLOW_ADMIN_WRITE=true.',
    inputSchema: objectSchema({
      name: { type: 'string' },
      public: { type: 'boolean' },
      fileSizeLimit: { type: 'number' },
    }, ['name']),
    handler: (args, _config, client) => client.storageCreateBucket(args),
  },
  storage_delete_bucket: {
    description: 'Delete a storage bucket. Write op; disabled unless NUBASE_ALLOW_ADMIN_WRITE=true.',
    inputSchema: objectSchema({ bucketId: { type: 'string' } }, ['bucketId']),
    handler: (args, _config, client) => client.storageDeleteBucket(args),
  },
  storage_list_objects: {
    description: 'List objects in a storage bucket with optional prefix/search/sort. Read-only.',
    inputSchema: objectSchema({
      bucketId: { type: 'string' },
      prefix: { type: 'string' },
      search: { type: 'string' },
      limit: { type: 'number' },
      offset: { type: 'number' },
      sortBy: { type: 'object' },
    }, ['bucketId']),
    handler: (args, _config, client) => client.storageListObjects(args),
  },
  storage_create_signed_url: {
    description: 'Create a temporary signed download URL for one storage object. Read-only control operation.',
    inputSchema: objectSchema({
      bucketId: { type: 'string' },
      path: { type: 'string' },
      expiresIn: { type: 'number' },
    }, ['bucketId', 'path']),
    handler: (args, _config, client) => client.storageCreateSignedUrl(args),
  },
  storage_create_signed_urls: {
    description: 'Create temporary signed download URLs for multiple storage object paths. Read-only control operation.',
    inputSchema: objectSchema({
      bucketId: { type: 'string' },
      paths: { type: 'array', items: { type: 'string' } },
      expiresIn: { type: 'number' },
    }, ['bucketId', 'paths']),
    handler: (args, _config, client) => client.storageCreateSignedUrls(args),
  },
  storage_create_signed_upload_url: {
    description: 'Create a temporary signed upload URL for a storage object path. Write-capable; disabled unless NUBASE_ALLOW_ADMIN_WRITE=true.',
    inputSchema: objectSchema({
      bucketId: { type: 'string' },
      path: { type: 'string' },
      upsert: { type: 'boolean' },
    }, ['bucketId', 'path']),
    handler: (args, _config, client) => client.storageCreateSignedUploadUrl(args),
  },
  auth_list_users: {
    description: 'List auth users with optional keyword search. Read-only.',
    inputSchema: objectSchema({
      page: { type: 'number' },
      perPage: { type: 'number' },
      keyword: { type: 'string' },
    }),
    handler: (args, _config, client) => client.authListUsers(args),
  },
  auth_create_user: {
    description: 'Create an auth user. Write op; disabled unless NUBASE_ALLOW_ADMIN_WRITE=true.',
    inputSchema: objectSchema({
      email: { type: 'string' },
      password: { type: 'string' },
      phone: { type: 'string' },
      role: { type: 'string' },
    }, ['email']),
    handler: (args, _config, client) => client.authCreateUser(args),
  },
  auth_delete_user: {
    description: 'Delete an auth user by id. Write op; disabled unless NUBASE_ALLOW_ADMIN_WRITE=true.',
    inputSchema: objectSchema({
      userId: { type: 'string' },
      softDelete: { type: 'boolean' },
    }, ['userId']),
    handler: (args, _config, client) => client.authDeleteUser(args),
  },
  auth_get_settings: {
    description: 'Read tenant auth settings such as signup/login policy. Read-only.',
    inputSchema: objectSchema({}),
    handler: (_args, _config, client) => client.authGetSettings(),
  },
  auth_update_settings: {
    description: 'Replace tenant auth settings. Pass a settings object matching the backend TenantAuthConfig. Write op; disabled unless NUBASE_ALLOW_ADMIN_WRITE=true.',
    inputSchema: objectSchema({
      settings: { type: 'object' },
    }, ['settings']),
    handler: (args, _config, client) => client.authUpdateSettings(args),
  },
  auth_clear_settings: {
    description: 'Clear tenant auth settings and fall back to defaults. Write op; disabled unless NUBASE_ALLOW_ADMIN_WRITE=true.',
    inputSchema: objectSchema({}),
    handler: (_args, _config, client) => client.authClearSettings(),
  },
  gateway_list_keys: {
    description: 'List AI Gateway self-routing keys (nbk_) for this project. Read-only.',
    inputSchema: objectSchema({}),
    handler: (_args, _config, client) => client.gatewayListKeys(),
  },
  gateway_issue_key: {
    description: 'Issue a new AI Gateway key (full key returned once). Write op; disabled unless NUBASE_ALLOW_ADMIN_WRITE=true.',
    inputSchema: objectSchema({
      name: { type: 'string' },
      description: { type: 'string' },
      expiresAt: { type: 'string' },
    }),
    handler: (args, _config, client) => client.gatewayIssueKey(args),
  },
  gateway_revoke_key: {
    description: 'Revoke an AI Gateway key by id. Write op; disabled unless NUBASE_ALLOW_ADMIN_WRITE=true.',
    inputSchema: objectSchema({ id: { type: 'string' } }, ['id']),
    handler: (args, _config, client) => client.gatewayRevokeKey(args),
  },
  gateway_usage: {
    description: 'AI Gateway usage overview (tokens, requests, cost) for a date range. Read-only.',
    inputSchema: objectSchema({
      startDate: { type: 'string' },
      endDate: { type: 'string' },
    }),
    handler: (args, _config, client) => client.gatewayUsage(args),
  },
  gateway_usage_daily: {
    description: 'AI Gateway daily usage for a date range, optionally filtered by API key id. Read-only.',
    inputSchema: objectSchema({
      apiKeyId: { type: 'string' },
      startDate: { type: 'string' },
      endDate: { type: 'string' },
    }),
    handler: (args, _config, client) => client.gatewayUsageDaily(args),
  },
  gateway_usage_by_model: {
    description: 'AI Gateway usage grouped by model for a date range. Read-only.',
    inputSchema: objectSchema({
      startDate: { type: 'string' },
      endDate: { type: 'string' },
    }),
    handler: (args, _config, client) => client.gatewayUsageByModel(args),
  },
  gateway_usage_logs: {
    description: 'AI Gateway request usage logs with pagination and optional date/key filters. Read-only.',
    inputSchema: objectSchema({
      apiKeyId: { type: 'string' },
      startDate: { type: 'string' },
      endDate: { type: 'string' },
      page: { type: 'number' },
      size: { type: 'number' },
    }),
    handler: (args, _config, client) => client.gatewayUsageLogs(args),
  },
  gateway_pricing: {
    description: 'List AI Gateway model pricing. Pass all=true to include all configured pricing rows. Read-only.',
    inputSchema: objectSchema({
      all: { type: 'boolean' },
    }),
    handler: (args, _config, client) => client.gatewayPricing(args),
  },
  functions_list: {
    description: 'List Edge Functions for this project. Read-only.',
    inputSchema: objectSchema({}),
    handler: (_args, _config, client) => client.functionsList(),
  },
  functions_new: {
    description: 'Scaffold a local Edge Function under nubase/functions/<name>. Writes local files only.',
    inputSchema: objectSchema({
      name: { type: 'string' },
    }, ['name']),
    handler: (args, config, client) => runFunctionsCommand(['new', requiredString(args.name, 'name')], config, client),
  },
  functions_deploy: {
    description: 'Bundle and deploy a local Edge Function using the same manifest, esbuild, sourceHash, and sourceBundleBase64 flow as nubase_cli functions deploy. Write op; disabled unless NUBASE_ALLOW_ADMIN_WRITE=true.',
    inputSchema: objectSchema({
      name: { type: 'string' },
      dir: { type: 'string' },
      bundle: { type: 'boolean' },
      noBundle: { type: 'boolean' },
      noVerifyJwt: { type: 'boolean' },
    }, ['name']),
    handler: (args, config, client) => runFunctionsCommand(functionsDeployArgs(args), config, client),
  },
  functions_invoke: {
    description: 'Invoke a deployed Edge Function over /functions/v1 and return the HTTP status, headers, and body envelope. Function-level 4xx/5xx responses are returned, not thrown. Write op; disabled unless NUBASE_ALLOW_ADMIN_WRITE=true.',
    inputSchema: objectSchema({
      name: { type: 'string' },
      method: { type: 'string' },
      path: { type: 'string' },
      body: { type: 'string' },
      contentType: { type: 'string' },
    }, ['name']),
    // Gated: invoking a function executes arbitrary code with the service_role
    // key. The CLI invoke path stays ungated by design.
    handler: (args, _config, client) => client.functionsInvokeGuarded({
      slug: requiredString(args.name, 'name'),
      method: typeof args.method === 'string' ? args.method : undefined,
      path: typeof args.path === 'string' ? args.path : undefined,
      body: typeof args.body === 'string' ? args.body : undefined,
      contentType: typeof args.contentType === 'string' ? args.contentType : undefined,
    }),
  },
  functions_logs: {
    description: 'List Edge Function invocation logs, optionally filtered by function name. Read-only.',
    inputSchema: objectSchema({
      name: { type: 'string' },
      limit: { type: 'number' },
    }),
    handler: (args, _config, client) => client.functionsLogs({
      slug: typeof args.name === 'string' ? args.name : undefined,
      limit: typeof args.limit === 'number' ? args.limit : undefined,
    }),
  },
  functions_delete: {
    description: 'Delete an Edge Function. Write op; disabled unless NUBASE_ALLOW_ADMIN_WRITE=true.',
    inputSchema: objectSchema({
      name: { type: 'string' },
    }, ['name']),
    handler: (args, _config, client) => client.functionsDelete({ slug: requiredString(args.name, 'name') }),
  },
  functions_secrets_list: {
    description: 'List secret names for an Edge Function. Read-only; secret values are never returned.',
    inputSchema: objectSchema({
      name: { type: 'string' },
    }, ['name']),
    handler: (args, _config, client) => client.functionsListSecrets({ slug: requiredString(args.name, 'name') }),
  },
  functions_secrets_set: {
    description: 'Set Edge Function secrets from an object of KEY/value pairs. Write op; disabled unless NUBASE_ALLOW_ADMIN_WRITE=true.',
    inputSchema: objectSchema({
      name: { type: 'string' },
      secrets: {
        type: 'object',
        additionalProperties: { type: 'string' },
      },
    }, ['name', 'secrets']),
    handler: (args, _config, client) => client.functionsSetSecrets({
      slug: requiredString(args.name, 'name'),
      secrets: requiredObject(args.secrets, 'secrets'),
    }),
  },
  cron_list: {
    description: 'List scheduled (cron) jobs for this project. Read-only.',
    inputSchema: objectSchema({}),
    handler: (_args, _config, client) => client.cronListJobs(),
  },
  cron_get: {
    description: 'Get one scheduled job by name. Read-only.',
    inputSchema: objectSchema({ name: { type: 'string' } }, ['name']),
    handler: (args, _config, client) => client.cronGetJob(args),
  },
  cron_create: {
    description:
      'Create a scheduled (cron) job. targetType is edge_function (set functionSlug; optional httpMethod/requestPath/requestBody) or db_function (set dbFunctionName; optional dbFunctionArgs). Write op; disabled unless NUBASE_ALLOW_ADMIN_WRITE=true.',
    inputSchema: objectSchema({
      name: { type: 'string' },
      cronExpression: { type: 'string' },
      targetType: { type: 'string', enum: ['edge_function', 'db_function'] },
      description: { type: 'string' },
      functionSlug: { type: 'string' },
      httpMethod: { type: 'string' },
      requestPath: { type: 'string' },
      requestBody: { type: 'string' },
      dbFunctionName: { type: 'string' },
      dbFunctionArgs: { type: 'object' },
      timeoutSeconds: { type: 'number' },
      enabled: { type: 'boolean' },
    }, ['name', 'cronExpression', 'targetType']),
    handler: (args, _config, client) => client.cronCreateJob(args),
  },
  cron_update: {
    description:
      'Update a scheduled job by name (cronExpression, enabled, target fields). targetType is immutable. Write op; disabled unless NUBASE_ALLOW_ADMIN_WRITE=true.',
    inputSchema: objectSchema({
      name: { type: 'string' },
      cronExpression: { type: 'string' },
      description: { type: 'string' },
      functionSlug: { type: 'string' },
      httpMethod: { type: 'string' },
      requestPath: { type: 'string' },
      requestBody: { type: 'string' },
      dbFunctionName: { type: 'string' },
      dbFunctionArgs: { type: 'object' },
      timeoutSeconds: { type: 'number' },
      enabled: { type: 'boolean' },
    }, ['name']),
    handler: (args, _config, client) => client.cronUpdateJob(args),
  },
  cron_delete: {
    description: 'Delete a scheduled job by name. Write op; disabled unless NUBASE_ALLOW_ADMIN_WRITE=true.',
    inputSchema: objectSchema({ name: { type: 'string' } }, ['name']),
    handler: (args, _config, client) => client.cronDeleteJob(args),
  },
  cron_runs: {
    description: 'List scheduled-job run history. Pass name for one job, omit it for the whole project. Read-only.',
    inputSchema: objectSchema({
      name: { type: 'string' },
      limit: { type: 'number' },
    }),
    handler: (args, _config, client) =>
      typeof args.name === 'string' && args.name ? client.cronJobRuns(args) : client.cronRuns(args),
  },
  assets_list: {
    description: 'List published static assets (with their public URLs) for this project. Read-only.',
    inputSchema: objectSchema({
      prefix: { type: 'string' },
      search: { type: 'string' },
      limit: { type: 'number' },
      offset: { type: 'number' },
    }),
    handler: (args, _config, client) => client.assetsList(args),
  },
  assets_upload: {
    description:
      "Publish a static asset to the project's public CDN (/assets/v1/<path>) — this is where a generated frontend goes. Pass content for UTF-8 text (html/css/js/svg/json) or contentBase64 for binaries (images/fonts). Content-Type is inferred from the path when omitted. upsert defaults to true. Returns the public URL. Write op; disabled unless NUBASE_ALLOW_ADMIN_WRITE=true.",
    inputSchema: objectSchema({
      path: { type: 'string' },
      content: { type: 'string' },
      contentBase64: { type: 'string' },
      contentType: { type: 'string' },
      cacheControl: { type: 'string' },
      upsert: { type: 'boolean' },
    }, ['path']),
    handler: (args, _config, client) => client.assetsUpload(args),
  },
  assets_delete: {
    description: 'Delete a published static asset by path. Write op; disabled unless NUBASE_ALLOW_ADMIN_WRITE=true.',
    inputSchema: objectSchema({ path: { type: 'string' } }, ['path']),
    handler: (args, _config, client) => client.assetsDelete(args),
  },
  assets_update_settings: {
    description: 'Update static asset delivery settings, including spaFallbackPath for generated SPA routes. Write op; disabled unless NUBASE_ALLOW_ADMIN_WRITE=true.',
    inputSchema: objectSchema({
      defaultCacheControl: { type: 'string' },
      customBaseUrl: { type: 'string' },
      spaFallbackPath: { type: 'string' },
      maxFileSizeBytes: { type: 'number' },
    }),
    handler: (args, _config, client) => client.assetsUpdateSettings(args),
  },
};

export const TOOLS: ToolDefinition[] = Object.entries(TOOL_TABLE).map(([name, entry]) => ({
  name,
  description: entry.description,
  inputSchema: entry.inputSchema,
}));

export async function callTool(
  name: string,
  args: Record<string, unknown>,
  config: BridgeConfig,
  client: NubaseClient
) {
  const entry = TOOL_TABLE[name];
  if (!entry) throw new Error(`Unknown tool: ${name}`);
  return entry.handler(args, config, client);
}

function functionsDeployArgs(args: Record<string, unknown>) {
  const cliArgs = ['deploy', requiredString(args.name, 'name')];
  if (typeof args.dir === 'string' && args.dir) cliArgs.push('--dir', args.dir);
  if (args.bundle === true && args.noBundle === true) {
    throw new Error('functions_deploy cannot set both bundle and noBundle');
  }
  if (args.bundle === true) cliArgs.push('--bundle');
  if (args.noBundle === true) cliArgs.push('--no-bundle');
  if (args.noVerifyJwt === true) cliArgs.push('--no-verify-jwt');
  return cliArgs;
}

function objectSchema(properties: Record<string, unknown>, required: string[] = []) {
  return {
    type: 'object',
    properties,
    required,
    additionalProperties: false,
  };
}
