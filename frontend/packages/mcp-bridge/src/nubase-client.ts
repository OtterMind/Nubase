import type { BridgeConfig } from './config.js';
import { classifySql, countStatements } from './sql-risk.js';

export class NubaseClient {
  constructor(private readonly config: BridgeConfig) {}

  capabilities() {
    return this.request('/agent/v1/capabilities');
  }

  // --- One-shot backend snapshot -----------------------------------------
  // Aggregates the read-only state an agent needs to start work into a single
  // round-trip: capabilities, schema, buckets, auth users, and gateway keys.
  // Each section degrades to { error } so an unauthorized or unsupported area
  // never blocks the rest of the snapshot.
  async overview(args: Record<string, unknown> = {}) {
    const schema = typeof args.schema === 'string' && args.schema ? args.schema : 'public';
    const [capabilities, database, storage, auth, aiGateway, projectKeys] = await Promise.all([
      safeSection(() => this.capabilities()),
      safeSection(() => this.dbExportSchema({ schema })),
      safeSection(() => this.storageListBuckets({ limit: 100 })),
      safeSection(() => this.authListUsers({ perPage: 1 })),
      safeSection(() => this.gatewayListKeys()),
      safeSection(() => this.projectKeys()),
    ]);
    return {
      nubaseUrl: this.config.nubaseUrl,
      project: {
        ref: this.config.projectRef,
        keyConfigured: Boolean(this.config.projectKey),
        // The client/anon key for generated frontend apps (call project_keys for the
        // service_role key). Omitted if keys could not be fetched.
        anonKey: (projectKeys && 'anonKey' in projectKeys ? projectKeys.anonKey : undefined) ?? undefined,
        userScoped: Boolean(this.config.userJwt),
        agentId: this.config.agentId,
      },
      permissions: {
        sqlExecute: this.config.allowSqlExecute,
        dangerousSql: this.config.allowDangerousSql,
        adminWrite: this.config.allowAdminWrite,
      },
      capabilities,
      database: { schema, ...database },
      storage,
      auth,
      aiGateway,
      nextSteps: [
        'Call memory_context({ task }) before planning to recall prior decisions.',
        'Inspect database above (or db_export_schema) before any schema change; sql_dry_run before sql_execute.',
        'Use /rest/v1, /auth/v1, /storage/v1 in generated app code; service-role keys stay server-side.',
        'Write durable decisions with memory_write when the task is done.',
      ],
    };
  }

  instructions() {
    return this.request('/agent/v1/instructions');
  }

  memoryContext(args: Record<string, unknown>) {
    return this.request('/mem/v1/search', {
      method: 'POST',
      body: {
        userId: args.userId,
        agentId: args.agentId,
        runId: args.runId,
        query: args.task || args.query,
        topK: args.topK ?? 8,
      },
    });
  }

  memorySearch(args: Record<string, unknown>) {
    return this.request('/mem/v1/search', { method: 'POST', body: args });
  }

  memoryWrite(args: Record<string, unknown>) {
    return this.request('/mem/v1/memories', {
      method: 'POST',
      body: {
        userId: args.userId,
        agentId: args.agentId,
        runId: args.runId,
        infer: args.infer ?? true,
        messages: [{ role: 'user', content: args.content }],
      },
    });
  }

  restSelect(args: Record<string, unknown>) {
    const table = requiredString(args.table, 'table');
    const query = typeof args.query === 'string' && args.query ? args.query : 'select=*';
    return this.request(`/rest/v1/${encodeURIComponent(table)}?${query}`);
  }

  // --- Storage (Supabase-style /storage/v1) -------------------------------

  storageListBuckets(args: Record<string, unknown>) {
    const query = buildQuery({ search: args.search, limit: args.limit, offset: args.offset });
    return this.request(`/storage/v1/bucket${query}`);
  }

  storageCreateBucket(args: Record<string, unknown>) {
    const name = requiredString(args.name, 'name');
    return this.guardedWrite('create bucket', () =>
      this.request('/storage/v1/bucket', {
        method: 'POST',
        body: {
          name,
          public: args.public === true,
          file_size_limit: typeof args.fileSizeLimit === 'number' ? args.fileSizeLimit : undefined,
        },
      })
    );
  }

  storageDeleteBucket(args: Record<string, unknown>) {
    const bucketId = requiredString(args.bucketId, 'bucketId');
    return this.guardedWrite('delete bucket', () =>
      this.request(`/storage/v1/bucket/${encodeURIComponent(bucketId)}`, { method: 'DELETE' })
    );
  }

  // --- Auth admin (Supabase-style /auth/v1/admin) -------------------------

  authListUsers(args: Record<string, unknown>) {
    const query = buildQuery({ page: args.page, per_page: args.perPage, keyword: args.keyword });
    return this.request(`/auth/v1/admin/users${query}`);
  }

  authCreateUser(args: Record<string, unknown>) {
    const email = requiredString(args.email, 'email');
    return this.guardedWrite('create user', () =>
      this.request('/auth/v1/admin/users', {
        method: 'POST',
        body: {
          email,
          password: typeof args.password === 'string' ? args.password : undefined,
          phone: typeof args.phone === 'string' ? args.phone : undefined,
          role: typeof args.role === 'string' ? args.role : undefined,
        },
      })
    );
  }

  authDeleteUser(args: Record<string, unknown>) {
    const userId = requiredString(args.userId, 'userId');
    const query = buildQuery({ should_soft_delete: args.softDelete === true ? 'true' : undefined });
    return this.guardedWrite('delete user', () =>
      this.request(`/auth/v1/admin/users/${encodeURIComponent(userId)}${query}`, { method: 'DELETE' })
    );
  }

  // --- Database schema introspection --------------------------------------

  dbExportSchema(args: Record<string, unknown>) {
    return this.request('/auth/v1/admin/schema/export-ddl', {
      method: 'POST',
      body: {
        schemaName: typeof args.schema === 'string' && args.schema ? args.schema : 'public',
        tableNames: typeof args.tables === 'string' ? args.tables : undefined,
        includeDropStatements: args.includeDrop === true,
      },
    });
  }

  // --- AI Gateway control plane (/ai-gateway/admin/v1) --------------------

  gatewayListKeys() {
    return this.request('/ai-gateway/admin/v1/keys');
  }

  gatewayIssueKey(args: Record<string, unknown>) {
    return this.guardedWrite('issue gateway key', () =>
      this.request('/ai-gateway/admin/v1/keys', {
        method: 'POST',
        body: {
          name: typeof args.name === 'string' && args.name ? args.name : 'Untitled key',
          description: typeof args.description === 'string' ? args.description : undefined,
          expiresAt: typeof args.expiresAt === 'string' ? args.expiresAt : undefined,
        },
      })
    );
  }

  gatewayRevokeKey(args: Record<string, unknown>) {
    const id = requiredString(args.id, 'id');
    return this.guardedWrite('revoke gateway key', () =>
      this.request(`/ai-gateway/admin/v1/keys/${encodeURIComponent(id)}`, { method: 'DELETE' })
    );
  }

  gatewayUsage(args: Record<string, unknown>) {
    const query = buildQuery({ start_date: args.startDate, end_date: args.endDate });
    return this.request(`/ai-gateway/admin/v1/usage/overview${query}`);
  }

  // --- Edge Functions control plane (/functions/admin/v1) ----------------

  functionsList() {
    return this.request('/functions/admin/v1/functions');
  }

  functionsCreate(args: Record<string, unknown>) {
    const name = requiredString(args.name, 'name');
    return this.guardedWrite('create edge function', () =>
      this.request('/functions/admin/v1/functions', {
        method: 'POST',
        body: {
          name,
          slug: typeof args.slug === 'string' ? args.slug : undefined,
          description: typeof args.description === 'string' ? args.description : undefined,
          verifyJwt: args.verifyJwt,
          enabled: args.enabled,
          privileged: args.privileged,
          entrypoint: typeof args.entrypoint === 'string' ? args.entrypoint : undefined,
        },
      })
    );
  }

  functionsDeploy(args: Record<string, unknown>) {
    const slug = requiredString(args.slug, 'slug');
    const sourceHash = requiredString(args.sourceHash, 'sourceHash');
    return this.guardedWrite('deploy edge function', () =>
      this.request(`/functions/admin/v1/functions/${encodeURIComponent(slug)}/deploy`, {
        method: 'POST',
        body: {
          sourceHash,
          artifactUri: typeof args.artifactUri === 'string' ? args.artifactUri : undefined,
          artifactType: typeof args.artifactType === 'string' ? args.artifactType : 'source_bundle',
          sourceBundleBase64: typeof args.sourceBundleBase64 === 'string' ? args.sourceBundleBase64 : undefined,
        },
      })
    );
  }

  functionsDelete(args: Record<string, unknown>) {
    const slug = requiredString(args.slug, 'slug');
    return this.guardedWrite('delete edge function', () =>
      this.request(`/functions/admin/v1/functions/${encodeURIComponent(slug)}`, { method: 'DELETE' })
    );
  }

  functionsLogs(args: Record<string, unknown>) {
    const query = buildQuery({ function: args.slug, limit: args.limit });
    return this.request(`/functions/admin/v1/invocations${query}`);
  }

  functionsListSecrets(args: Record<string, unknown>) {
    const slug = requiredString(args.slug, 'slug');
    return this.request(`/functions/admin/v1/functions/${encodeURIComponent(slug)}/secrets`);
  }

  functionsSetSecrets(args: Record<string, unknown>) {
    const slug = requiredString(args.slug, 'slug');
    const secrets = args.secrets;
    if (!secrets || typeof secrets !== 'object' || Array.isArray(secrets)) {
      throw new Error('secrets object is required');
    }
    return this.guardedWrite('set edge function secrets', () =>
      this.request(`/functions/admin/v1/functions/${encodeURIComponent(slug)}/secrets`, {
        method: 'POST',
        body: { secrets },
      })
    );
  }

  functionsInvoke(args: Record<string, unknown>) {
    const slug = requiredString(args.slug, 'slug');
    const method = typeof args.method === 'string' ? args.method.toUpperCase() : 'GET';
    const path = typeof args.path === 'string' && args.path ? `/${args.path.replace(/^\/+/, '')}` : '';
    return this.rawRequest(`/functions/v1/${encodeURIComponent(slug)}${path}`, {
      method,
      body: typeof args.body === 'string' ? args.body : undefined,
      contentType: typeof args.contentType === 'string' ? args.contentType : 'application/json',
    });
  }

  // --- Project API keys ---------------------------------------------------
  // The two project keys an app needs: the anon/authenticated key for browser
  // and client code, and the service_role key for trusted server-side code.
  // The anon key is captured at authorize time (or via NUBASE_ANON_KEY) — the
  // tenant service_role key cannot read it from the platform keys endpoint.
  async projectKeys() {
    const serviceRoleKey = this.config.projectKey || null;
    const anonKey = this.config.anonKey ?? null;
    if (!anonKey) {
      return {
        success: false,
        code: 'ANON_KEY_UNAVAILABLE',
        error: 'The anon/authenticated key is not available to the bridge. It is captured when you authorize the CLI, or can be provided directly.',
        remedy: 'Re-run nubase_cli authorize (with a Studio that returns the anon key) so it is saved to the Nubase config, or copy the authenticated key from the Studio project Settings page and set NUBASE_ANON_KEY in the MCP bridge env.',
        userAction: 'Ask the user to re-authorize or set NUBASE_ANON_KEY so the anon key is available.',
        projectRef: this.config.projectRef ?? null,
        nubaseUrl: this.config.nubaseUrl,
        serviceRoleKey,
      };
    }
    return {
      projectRef: this.config.projectRef ?? null,
      nubaseUrl: this.config.nubaseUrl,
      // Safe to embed in browser/client app code (subject to RLS + user JWTs).
      anonKey,
      // Server-side / trusted tooling only — never ship to a browser.
      serviceRoleKey,
      usage: {
        anonKey: 'Use as the apikey header in generated frontend/client apps, together with user JWTs and RLS.',
        serviceRoleKey: 'Use only in trusted server-side code or local agent tooling; bypasses RLS.',
      },
    };
  }

  private async guardedWrite<T>(action: string, run: () => Promise<T>) {
    if (!this.config.allowAdminWrite) {
      return {
        success: false,
        code: 'PERMISSION_GATE_OFF',
        error: `Cannot ${action}: this is supported but admin writes are gated off by default. This is a permission switch, not a missing feature — it will work once enabled.`,
        remedy: 'Set NUBASE_ALLOW_ADMIN_WRITE=true in the MCP bridge env, then retry.',
        userAction: `Ask the user to enable admin writes (NUBASE_ALLOW_ADMIN_WRITE=true) so you can ${action}.`,
      };
    }
    return run();
  }

  sqlDryRun(args: Record<string, unknown>) {
    const sql = requiredString(args.sql, 'sql');
    const risk = classifySql(sql);
    return {
      success: true,
      risk,
      statementCount: countStatements(sql),
      executable: risk !== 'DANGEROUS',
    };
  }

  async sqlExecute(args: Record<string, unknown>) {
    const sql = requiredString(args.sql, 'sql');
    const dryRun = this.sqlDryRun({ sql });
    if (!this.config.allowSqlExecute) {
      return {
        success: false,
        code: 'PERMISSION_GATE_OFF',
        error: 'SQL execution is supported but gated off by default. This is a permission switch, not a missing feature.',
        remedy: 'Set NUBASE_ALLOW_SQL_EXECUTE=true in the MCP bridge env, then retry.',
        userAction: 'Ask the user to enable SQL execution (NUBASE_ALLOW_SQL_EXECUTE=true) before retrying.',
        dryRun,
      };
    }
    if (dryRun.risk === 'DANGEROUS' && !this.config.allowDangerousSql) {
      return {
        success: false,
        code: 'DANGEROUS_SQL_BLOCKED',
        error: 'This statement is classified DANGEROUS (e.g. drop/truncate/bulk delete) and is blocked by default.',
        remedy: 'Set NUBASE_ALLOW_DANGEROUS_SQL=true to allow it, and confirm the operation with the user first.',
        userAction: 'Confirm the destructive intent with the user, then ask them to set NUBASE_ALLOW_DANGEROUS_SQL=true.',
        dryRun,
      };
    }
    const result = await this.request('/auth/v1/admin/sql/execute', {
      method: 'POST',
      body: { query: sql },
    });
    const out: Record<string, unknown> = { risk: dryRun.risk, ...result };

    // Audit trail: record schema-changing executions so they can be reviewed
    // and replayed later. Best-effort — a failure here never fails the execute.
    const isSchemaChange = dryRun.risk === 'SCHEMA_WRITE' || dryRun.risk === 'DANGEROUS';
    const succeeded = !result || (result as Record<string, unknown>).success !== false;
    if (this.config.recordMigrations !== false && isSchemaChange && succeeded) {
      try {
        await this.recordMigration({ sql, risk: dryRun.risk, statementCount: dryRun.statementCount });
        out.migrationRecorded = true;
      } catch (err) {
        out.migrationRecorded = false;
        out.migrationError = err instanceof Error ? err.message : String(err);
      }
    }
    return out;
  }

  // Append-only audit table in a dedicated `nubase` schema (kept out of public
  // so it does not clutter the user's app schema). Ensured idempotently.
  private async recordMigration(entry: { sql: string; risk: string; statementCount: number }) {
    const query = `create schema if not exists nubase;
create table if not exists nubase.migrations (
  id bigint generated always as identity primary key,
  applied_at timestamptz not null default now(),
  risk text not null,
  statement_count integer not null default 1,
  sql text not null,
  agent_id text,
  run_id text,
  user_id text
);
insert into nubase.migrations (risk, statement_count, sql, agent_id, run_id, user_id)
values (${sqlLiteral(entry.risk)}, ${entry.statementCount}, ${sqlLiteral(entry.sql)}, ${sqlLiteralOrNull(this.config.agentId)}, ${sqlLiteralOrNull(this.config.runId)}, ${sqlLiteralOrNull(this.config.userId)});`;
    return this.request('/auth/v1/admin/sql/execute', { method: 'POST', body: { query } });
  }

  // Read the audit trail. Hardcoded SELECT against our own table, so it does not
  // require NUBASE_ALLOW_SQL_EXECUTE (reading the log is always safe).
  async listMigrations(args: Record<string, unknown> = {}) {
    const limit = typeof args.limit === 'number' && args.limit > 0 ? Math.min(Math.floor(args.limit), 200) : 50;
    try {
      return await this.request('/auth/v1/admin/sql/execute', {
        method: 'POST',
        body: {
          query: `select id, applied_at, risk, statement_count, sql, agent_id, run_id, user_id from nubase.migrations order by applied_at desc limit ${limit};`,
        },
      });
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      if (/does not exist|nubase\.migrations/i.test(message)) {
        return { migrations: [], note: 'No migrations recorded yet (nubase.migrations not present).' };
      }
      throw err;
    }
  }

  private async request(path: string, options: { method?: string; body?: unknown } = {}) {
    const { data } = await this.rawRequest(path, {
      method: options.method,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      contentType: 'application/json',
    });
    return data;
  }

  private async rawRequest(path: string, options: { method?: string; body?: string; contentType?: string } = {}) {
    if (!this.config.projectKey) {
      throw new Error('Missing NUBASE_PROJECT_KEY or NUBASE_API_KEY.');
    }
    const headers: Record<string, string> = {
      apikey: this.config.projectKey,
    };
    if (options.contentType) headers['Content-Type'] = options.contentType;
    if (this.config.userJwt) {
      headers.Authorization = `Bearer ${this.config.userJwt}`;
    }
    const response = await fetch(`${this.config.nubaseUrl}${path}`, {
      method: options.method || 'GET',
      headers,
      body: options.body,
    });
    const text = await response.text();
    const data = parseResponse(text);
    if (!response.ok) {
      throw new Error(typeof data === 'string' ? data : JSON.stringify(data));
    }
    return { status: response.status, headers: Object.fromEntries(response.headers.entries()), data };
  }
}

async function safeSection<T>(run: () => Promise<T>): Promise<T | { error: string }> {
  try {
    return await run();
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }
}

function parseResponse(text: string) {
  if (!text) return null;
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}

function buildQuery(params: Record<string, unknown>) {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') continue;
    search.set(key, String(value));
  }
  const query = search.toString();
  return query ? `?${query}` : '';
}

function requiredString(value: unknown, name: string) {
  if (typeof value !== 'string' || !value.trim()) {
    throw new Error(`${name} is required`);
  }
  return value;
}

// Postgres string literal with single quotes doubled — safe for arbitrary text.
function sqlLiteral(value: string) {
  return `'${value.replace(/'/g, "''")}'`;
}

function sqlLiteralOrNull(value: string | undefined) {
  return value === undefined || value === null ? 'NULL' : sqlLiteral(String(value));
}
