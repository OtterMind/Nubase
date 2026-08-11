package ai.nubase.platform.mcp;

import ai.nubase.ai.gateway.util.GatewayKeyUtil;
import ai.nubase.postgrest.multidb.DatabaseConfig;
import ai.nubase.postgrest.multidb.DatabaseConfigRepository;
import ai.nubase.postgrest.multidb.RoutingDataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.Set;

/** Performs fail-closed, read-only checks inside a provisioned tenant database. */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "nubase.platform-mcp", name = "enabled", havingValue = "true")
public class PlatformMcpTenantReadinessProbe {

    private static final int QUERY_TIMEOUT_SECONDS = 5;

    static final Set<String> AUTH_TABLES = Set.of(
            "auth.users",
            "auth.sessions",
            "auth.refresh_tokens",
            "auth.identities",
            "auth.mfa_factors",
            "auth.mfa_challenges",
            "auth.mfa_amr_claims",
            "auth.one_time_tokens",
            "auth.audit_log_entries",
            "auth.flow_state",
            "auth.sso_providers",
            "auth.sso_domains",
            "auth.saml_providers",
            "auth.saml_relay_states");
    static final Set<String> STORAGE_TABLES = Set.of(
            "storage.buckets",
            "storage.objects",
            "storage.buckets_vectors",
            "storage.vector_indexes");
    static final Set<String> MEMORY_TABLES = Set.of(
            "mem.memories",
            "mem.memory_history",
            "mem.entities",
            "mem.session_messages",
            "mem.config");
    static final Set<String> ASSETS_TABLES = Set.of(
            "assets.files",
            "assets.settings");
    static final Set<String> GATEWAY_TABLES = Set.of(
            "ai_gateway.upstream_configs",
            "ai_gateway.api_keys",
            "ai_gateway.api_usage_logs",
            "ai_gateway.daily_token_usage",
            "ai_gateway.model_pricing");

    static final Set<String> AUTH_POLICIES = Set.of(
            policy("auth.users", "Users can view own user data"),
            policy("auth.users", "Users can update own user data"),
            policy("auth.sessions", "Users can view own sessions"),
            policy("auth.refresh_tokens", "Users can view own refresh tokens"),
            policy("auth.identities", "Users can view own identities"));
    static final Set<String> STORAGE_POLICIES = Set.of(
            policy("storage.buckets", "Authenticated users can view buckets"),
            policy("storage.buckets", "Bucket owners can manage buckets"),
            policy("storage.objects", "Public bucket objects are viewable"),
            policy("storage.objects", "Users can manage own objects"));
    static final Set<String> MEMORY_POLICIES = Set.of(
            policy("mem.memories", "Users can manage own memories"),
            policy("mem.memory_history", "Users can view own memory history"),
            policy("mem.entities", "Users can manage own entities"),
            policy("mem.session_messages", "Users can manage own session messages"),
            policy("mem.config", "Authenticated can read mem config"));
    static final Set<String> ASSETS_POLICIES = Set.of(
            policy("assets.files", "Asset files are readable"),
            policy("assets.settings", "Asset settings are readable"));

    private static final String TABLES_SQL = """
            SELECT table_schema, table_name
            FROM information_schema.tables
            WHERE table_type = 'BASE TABLE'
              AND table_schema IN ('auth', 'storage', 'mem', 'assets', 'ai_gateway')
            """;
    private static final String RLS_SQL = """
            SELECT namespace.nspname, relation.relname
            FROM pg_class relation
            JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
            WHERE relation.relkind IN ('r', 'p')
              AND relation.relrowsecurity = TRUE
              AND namespace.nspname IN ('auth', 'storage', 'mem', 'assets')
            """;
    private static final String POLICIES_SQL = """
            SELECT schemaname, tablename, policyname
            FROM pg_policies
            WHERE schemaname IN ('auth', 'storage', 'mem', 'assets')
            """;
    private static final String DEFAULT_GATEWAY_KEY_SQL = """
            SELECT EXISTS (
                SELECT 1
                FROM ai_gateway.api_keys
                WHERE key_hash = ?
                  AND scope = 'all'
                  AND is_active = TRUE
                  AND revoked_at IS NULL
                  AND (expires_at IS NULL OR expires_at > NOW())
            )
            """;

    private final DatabaseConfigRepository databaseConfigRepository;
    private final RoutingDataSource routingDataSource;

    public TenantReadiness check(
            String dbKey,
            boolean memoryEnabled,
            boolean assetsEnabled,
            boolean gatewayEnabled) {
        try {
            DatabaseConfig config = databaseConfigRepository.findByDbKey(dbKey);
            if (config == null) {
                return TenantReadiness.unavailable();
            }
            try (Connection connection = routingDataSource
                    .getOrCreateDataSource(dbKey, config)
                    .getConnection()) {
                connection.setReadOnly(true);
                return inspect(connection, config, memoryEnabled, assetsEnabled, gatewayEnabled);
            }
        } catch (Exception error) {
            log.warn(
                    "Platform tenant readiness probe failed: dbKey={}, probeCode={}, errorType={}",
                    dbKey,
                    "TENANT_READINESS_UNAVAILABLE",
                    error.getClass().getSimpleName());
            return TenantReadiness.unavailable();
        }
    }

    TenantReadiness inspect(
            Connection connection,
            DatabaseConfig config,
            boolean memoryEnabled,
            boolean assetsEnabled,
            boolean gatewayEnabled) throws SQLException {
        if (!connection.isValid(QUERY_TIMEOUT_SECONDS)) {
            return TenantReadiness.unavailable();
        }

        Set<String> tables = readQualifiedNames(connection, TABLES_SQL);
        Set<String> rlsTables = readQualifiedNames(connection, RLS_SQL);
        Set<String> policies = readPolicies(connection);

        boolean auth = complete(tables, AUTH_TABLES)
                && complete(rlsTables, AUTH_TABLES)
                && complete(policies, AUTH_POLICIES);
        boolean storage = complete(tables, STORAGE_TABLES)
                && complete(rlsTables, STORAGE_TABLES)
                && complete(policies, STORAGE_POLICIES);
        boolean memory = memoryEnabled
                && complete(tables, MEMORY_TABLES)
                && complete(rlsTables, MEMORY_TABLES)
                && complete(policies, MEMORY_POLICIES);
        boolean assets = assetsEnabled
                && complete(tables, ASSETS_TABLES)
                && complete(rlsTables, ASSETS_TABLES)
                && complete(policies, ASSETS_POLICIES);
        boolean gateway = gatewayEnabled
                && complete(tables, GATEWAY_TABLES)
                && defaultGatewayKeyReady(connection, config.getServiceRoleToken());
        return new TenantReadiness(true, auth, storage, memory, assets, gateway);
    }

    private Set<String> readQualifiedNames(Connection connection, String sql) throws SQLException {
        Set<String> names = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    names.add(rows.getString(1) + "." + rows.getString(2));
                }
            }
        }
        return names;
    }

    private Set<String> readPolicies(Connection connection) throws SQLException {
        Set<String> policies = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(POLICIES_SQL)) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    policies.add(policy(
                            rows.getString(1) + "." + rows.getString(2),
                            rows.getString(3)));
                }
            }
        }
        return policies;
    }

    private boolean defaultGatewayKeyReady(Connection connection, String serviceRoleToken)
            throws SQLException {
        if (serviceRoleToken == null || serviceRoleToken.isBlank()) {
            return false;
        }
        String keyHash = GatewayKeyUtil.sha256Hex(serviceRoleToken.trim());
        try (PreparedStatement statement = connection.prepareStatement(DEFAULT_GATEWAY_KEY_SQL)) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            statement.setString(1, keyHash);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && rows.getBoolean(1);
            }
        }
    }

    private static boolean complete(Set<String> actual, Set<String> required) {
        return actual.containsAll(required);
    }

    private static String policy(String table, String name) {
        return table + "|" + name;
    }

    public record TenantReadiness(
            boolean database,
            boolean auth,
            boolean storage,
            boolean memory,
            boolean assets,
            boolean gateway) {

        public static TenantReadiness unavailable() {
            return new TenantReadiness(false, false, false, false, false, false);
        }
    }
}
