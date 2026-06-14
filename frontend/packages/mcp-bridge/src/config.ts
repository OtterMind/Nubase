import { defaultConfigPath, legacyConfigPath, loadStoredAuthConfig } from './auth-config.js';

export const DEFAULT_NUBASE_URL = 'https://nubase.ai';

export interface BridgeConfig {
  nubaseUrl: string;
  projectKey: string;
  projectRef?: string;
  // The anon/authenticated key for client apps. Captured at authorize time or via
  // NUBASE_ANON_KEY; projectKey itself is the service_role key.
  anonKey?: string;
  platformKey?: string;
  platformJwt?: string;
  userJwt?: string;
  agentId?: string;
  userId?: string;
  runId?: string;
  allowSqlExecute: boolean;
  allowDangerousSql: boolean;
  allowAdminWrite: boolean;
  recordMigrations?: boolean;
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): BridgeConfig {
  const nubaseUrl = stripTrailingSlash(env.NUBASE_URL || DEFAULT_NUBASE_URL);
  const projectKey = env.NUBASE_PROJECT_KEY || env.NUBASE_API_KEY || '';
  return {
    nubaseUrl,
    projectKey,
    projectRef: blankToUndefined(env.NUBASE_PROJECT_REF),
    anonKey: blankToUndefined(env.NUBASE_ANON_KEY),
    platformKey: blankToUndefined(env.NUBASE_PLATFORM_KEY || env.NUBASE_METADATA_SERVICE_ROLE_KEY),
    platformJwt: blankToUndefined(env.NUBASE_PLATFORM_JWT),
    userJwt: blankToUndefined(env.NUBASE_USER_JWT),
    agentId: blankToUndefined(env.NUBASE_AGENT_ID),
    userId: blankToUndefined(env.NUBASE_USER_ID),
    runId: blankToUndefined(env.NUBASE_RUN_ID),
    allowSqlExecute: truthy(env.NUBASE_ALLOW_SQL_EXECUTE),
    allowDangerousSql: truthy(env.NUBASE_ALLOW_DANGEROUS_SQL),
    allowAdminWrite: truthy(env.NUBASE_ALLOW_ADMIN_WRITE),
    // On by default; set NUBASE_RECORD_MIGRATIONS=false to disable the audit trail.
    recordMigrations: !explicitlyFalse(env.NUBASE_RECORD_MIGRATIONS),
  };
}

export async function loadConfigAsync(env: NodeJS.ProcessEnv = process.env): Promise<BridgeConfig> {
  const config = loadConfig(env);
  // The saved config also carries projectRef and the anon key, which project_keys
  // needs — so read it whenever any of key/ref/anonKey is still missing from env.
  if (config.projectKey && config.projectRef && config.anonKey) return config;

  const stored = await loadStoredAuthConfig(defaultConfigPath(env)) ?? (
    env.NUBASE_CONFIG ? null : await loadStoredAuthConfig(legacyConfigPath())
  );
  if (!stored) return config;

  return {
    ...config,
    nubaseUrl: env.NUBASE_URL ? config.nubaseUrl : stored.nubaseUrl,
    projectKey: config.projectKey || stored.projectKey,
    projectRef: config.projectRef || stored.projectRef,
    anonKey: config.anonKey || stored.anonKey,
  };
}

function stripTrailingSlash(value: string) {
  return value.replace(/\/+$/, '');
}

function blankToUndefined(value: string | undefined) {
  return value && value.trim() ? value.trim() : undefined;
}

function truthy(value: string | undefined) {
  return ['1', 'true', 'yes', 'on'].includes((value || '').toLowerCase());
}

function explicitlyFalse(value: string | undefined) {
  return ['0', 'false', 'no', 'off'].includes((value || '').toLowerCase());
}
