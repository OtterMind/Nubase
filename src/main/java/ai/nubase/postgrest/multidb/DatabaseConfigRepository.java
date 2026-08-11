package ai.nubase.postgrest.multidb;

import ai.nubase.common.enums.DatabaseInitStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Repository for accessing database configurations from metadata database
 * Uses caching to minimize database queries
 */
@Slf4j
@Repository
public class DatabaseConfigRepository {

    private final JdbcTemplate metadataJdbcTemplate;
    private final EncryptionService encryptionService;

    public DatabaseConfigRepository(
            @Qualifier("metadataJdbcTemplate") JdbcTemplate metadataJdbcTemplate,
            EncryptionService encryptionService) {
        this.metadataJdbcTemplate = metadataJdbcTemplate;
        this.encryptionService = encryptionService;
    }

    /**
     * Find database configuration by key
     * Result is cached for 5 minutes
     *
     * @param dbKey the database key
     * @return DatabaseConfig or null if not found
     */
    @Cacheable(value = "databaseConfigs", key = "#dbKey", unless = "#result == null")
    public DatabaseConfig findByDbKey(String dbKey) {
        log.debug("Loading database configuration for: {}", dbKey);

        String sql = """
                SELECT
                    db_key, db_name, description,
                    jdbc_url, db_user, db_password_encrypted,
                    db_schemas, db_anon_role, db_max_rows, db_extra_search_path,
                    jwt_secret_encrypted, jwt_secret_is_base64, jwt_audience, jwt_role_claim_key,
                    pool_size, pool_timeout_ms, pool_max_lifetime_ms,
                    pool_connection_timeout_ms, pool_idle_timeout_ms, pool_minimum_idle,
                    enabled, created_at, updated_at, created_by, updated_by,
                    last_health_check, health_status, health_message,
                    app_code, app_name, schema_name,
                    jwt_secret, service_role_token, oauth_config, auth_config, authenticated_token,
                    init_status, init_message, init_started_at, init_completed_at
                FROM public.database_configs
                WHERE db_key = ?
                """;

        List<DatabaseConfig> results = metadataJdbcTemplate.query(sql, new DatabaseConfigRowMapper(), dbKey);

        if (results.isEmpty()) {
            log.warn("Database configuration not found for key: {}", dbKey);
            return null;
        }

        DatabaseConfig config = results.get(0);

        // Decrypt sensitive data
        try {
            config.setDbPasswordDecrypted(encryptionService.decrypt(config.getDbPasswordEncrypted()));

            if (config.getJwtSecretEncrypted() != null) {
                config.setJwtSecretDecrypted(encryptionService.decrypt(config.getJwtSecretEncrypted()));
            }
        } catch (Exception e) {
            log.error("Failed to decrypt sensitive data for database: {}, errorType={}",
                    dbKey, errorType(e));
            throw new IllegalStateException("Failed to decrypt database configuration");
        }

        log.info("Loaded configuration for database: {}", dbKey);
        return config;
    }

    /**
     * Find all enabled database configurations
     *
     * @return list of all enabled configurations
     */
    public List<DatabaseConfig> findAllEnabled() {
        log.debug("Loading all enabled database configurations");

        String sql = """
                SELECT
                    db_key, db_name, description,
                    jdbc_url, db_user, db_password_encrypted,
                    db_schemas, db_anon_role, db_max_rows, db_extra_search_path,
                    jwt_secret_encrypted, jwt_secret_is_base64, jwt_audience, jwt_role_claim_key,
                    pool_size, pool_timeout_ms, pool_max_lifetime_ms,
                    pool_connection_timeout_ms, pool_idle_timeout_ms, pool_minimum_idle,
                    enabled, created_at, updated_at, created_by, updated_by,
                    last_health_check, health_status, health_message,
                    app_code, app_name, schema_name,
                    jwt_secret, service_role_token, oauth_config, auth_config, authenticated_token,
                    init_status, init_message, init_started_at, init_completed_at
                FROM public.database_configs
                WHERE enabled = true
                ORDER BY db_key
                """;

        List<DatabaseConfig> configs = metadataJdbcTemplate.query(sql, new DatabaseConfigRowMapper());

        // Decrypt sensitive data for all configs
        configs.forEach(config -> {
            try {
                config.setDbPasswordDecrypted(encryptionService.decrypt(config.getDbPasswordEncrypted()));

                if (config.getJwtSecretEncrypted() != null) {
                    config.setJwtSecretDecrypted(encryptionService.decrypt(config.getJwtSecretEncrypted()));
                }
            } catch (Exception e) {
                log.error("Failed to decrypt sensitive data for database: {}, errorType={}",
                        config.getDbKey(), errorType(e));
            }
        });

        log.info("Loaded {} enabled database configurations", configs.size());
        return configs;
    }

    /**
     * Save or update database configuration
     * Evicts cache after save
     *
     * @param config the database configuration to save
     */
    @CacheEvict(value = "databaseConfigs", key = "#config.dbKey")
    public void save(DatabaseConfig config) {
        log.info("Saving database configuration for: {}", config.getDbKey());
        prepareForPersistence(config);

        // Check if exists
        Integer count = metadataJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM public.database_configs WHERE db_key = ?",
                Integer.class,
                config.getDbKey()
        );

        if (count != null && count > 0) {
            // Update existing
            updateConfig(config);
        } else {
            // Insert new
            insertConfig(config, false);
        }

        log.info("Successfully saved database configuration for: {}", config.getDbKey());
    }

    /**
     * Atomically insert a new database configuration without updating an existing project.
     *
     * <p>This is the create-only persistence boundary used by platform automation. The existing
     * {@link #save(DatabaseConfig)} method intentionally retains its upsert behavior for Studio and
     * legacy callers. PostgreSQL resolves concurrent creates for the same {@code db_key}; the loser
     * receives {@code false} and never reaches an update path.</p>
     */
    @CacheEvict(value = "databaseConfigs", key = "#config.dbKey")
    public boolean insertIfAbsent(DatabaseConfig config) {
        log.info("Creating database configuration if absent for: {}", config.getDbKey());
        prepareForPersistence(config);
        boolean inserted = insertConfig(config, true) == 1;
        if (inserted) {
            log.info("Created database configuration for: {}", config.getDbKey());
        } else {
            log.info("Database configuration already exists for: {}", config.getDbKey());
        }
        return inserted;
    }

    private void prepareForPersistence(DatabaseConfig config) {
        config.validate();

        try {
            if (!encryptionService.isEncrypted(config.getDbPasswordEncrypted())) {
                config.setDbPasswordEncrypted(encryptionService.encrypt(config.getDbPasswordEncrypted()));
            }

            if (config.getJwtSecretEncrypted() != null
                    && !encryptionService.isEncrypted(config.getJwtSecretEncrypted())) {
                config.setJwtSecretEncrypted(encryptionService.encrypt(config.getJwtSecretEncrypted()));
            }
        } catch (Exception e) {
            log.error("Failed to encrypt sensitive data for database: {}, errorType={}",
                    config.getDbKey(), errorType(e));
            throw new IllegalStateException("Failed to encrypt database configuration");
        }
    }

    /**
     * Delete database configuration
     * Evicts cache after deletion
     *
     * @param dbKey the database key to delete
     */
    @CacheEvict(value = "databaseConfigs", key = "#dbKey")
    public void delete(String dbKey) {
        log.info("Deleting database configuration for: {}", dbKey);

        String sql = "DELETE FROM public.database_configs WHERE db_key = ?";
        int deleted = metadataJdbcTemplate.update(sql, dbKey);

        if (deleted > 0) {
            log.info("Deleted database configuration for: {}", dbKey);
        } else {
            log.warn("No database configuration found to delete for: {}", dbKey);
        }
    }

    /**
     * Update health status for a database
     *
     * @param dbKey   database key
     * @param status  health status ('healthy', 'degraded', 'unhealthy')
     * @param message health message (optional)
     */
    public void updateHealthStatus(String dbKey, String status, String message) {
        log.debug("Updating health status for {}: {}", dbKey, status);

        String sql = """
                UPDATE public.database_configs
                SET health_status = ?,
                    health_message = ?,
                    last_health_check = NOW()
                WHERE db_key = ?
                """;

        metadataJdbcTemplate.update(sql, status, message, dbKey);

        // Don't evict cache on health check updates to avoid performance impact
    }

    /**
     * Enable or disable a database
     *
     * @param dbKey   database key
     * @param enabled true to enable, false to disable
     */
    @CacheEvict(value = "databaseConfigs", key = "#dbKey")
    public void setEnabled(String dbKey, boolean enabled) {
        log.info("Setting enabled={} for database: {}", enabled, dbKey);

        String sql = """
                UPDATE public.database_configs
                SET enabled = ?,
                    updated_at = NOW()
                WHERE db_key = ?
                """;

        metadataJdbcTemplate.update(sql, enabled, dbKey);
    }


    /**
     * Patch the user-visible fields of a project: display name and description.
     * Either argument can be null to leave that field unchanged.
     */
    @CacheEvict(value = "databaseConfigs", key = "#dbKey")
    public void updateProjectMetadata(String dbKey, String appName, String description) {
        if (appName == null && description == null) return;
        StringBuilder sql = new StringBuilder("UPDATE public.database_configs SET updated_at = NOW()");
        java.util.List<Object> params = new java.util.ArrayList<>();
        if (appName != null) {
            sql.append(", app_name = ?");
            params.add(appName);
        }
        if (description != null) {
            sql.append(", description = ?");
            params.add(description);
        }
        sql.append(" WHERE db_key = ?");
        params.add(dbKey);
        metadataJdbcTemplate.update(sql.toString(), params.toArray());
    }

    @CacheEvict(value = "databaseConfigs", key = "#dbKey")
    public void updateOauth(String dbKey, String oauthConfig) {
        String sql = """
                UPDATE public.database_configs
                SET oauth_config = ?,
                    updated_at = NOW()
                WHERE db_key = ?
                """;
        metadataJdbcTemplate.update(sql, oauthConfig, dbKey);
    }

    /** Persist the per-tenant auth settings JSON (TenantAuthConfig); null clears the override. */
    @CacheEvict(value = "databaseConfigs", key = "#dbKey")
    public void updateAuthConfig(String dbKey, String authConfigJson) {
        String sql = """
                UPDATE public.database_configs
                SET auth_config = ?,
                    updated_at = NOW()
                WHERE db_key = ?
                """;
        metadataJdbcTemplate.update(sql, authConfigJson, dbKey);
    }

    /**
     * Atomically claim physical database initialization for one worker.
     *
     * <p>The PostgreSQL clock is the sole time source. PostgreSQL also generates the opaque lease
     * token returned to the caller, so a worker cannot finalize a lease it did not acquire. A
     * legacy {@code INITIALIZING} row without the V14 lease columns is reclaimable only after its
     * {@code init_started_at} exceeds the configured timeout.
     */
    @CacheEvict(value = "databaseConfigs", key = "#dbKey")
    public Optional<InitializationLease> tryStartInitialization(
            String dbKey, Duration leaseDuration) {
        long leaseMillis = requirePositiveLeaseMillis(leaseDuration);
        String sql = """
                WITH candidate AS (
                    SELECT db_key, init_status
                    FROM public.database_configs
                    WHERE db_key = ?
                      AND (
                        init_status IN (?, ?)
                        OR (
                          init_status = ?
                          AND (
                            init_lease_expires_at < NOW()
                            OR (
                              init_lease_expires_at IS NULL
                              AND (
                                init_started_at IS NULL
                                OR init_started_at < NOW()
                                    - (CAST(? AS BIGINT) * INTERVAL '1 millisecond')
                              )
                            )
                          )
                        )
                      )
                    FOR UPDATE
                )
                UPDATE public.database_configs AS config
                SET init_status = ?,
                    init_message = ?,
                    init_started_at = NOW(),
                    init_completed_at = NULL,
                    init_lease_token = gen_random_uuid(),
                    init_lease_expires_at = NOW()
                        + (CAST(? AS BIGINT) * INTERVAL '1 millisecond'),
                    init_fence_version = init_fence_version + 1,
                    enabled = FALSE,
                    updated_at = NOW()
                FROM candidate
                WHERE config.db_key = candidate.db_key
                RETURNING config.init_lease_token,
                          candidate.init_status AS previous_init_status,
                          config.init_started_at,
                          config.init_lease_expires_at
                """;
        List<InitializationLease> claimed = metadataJdbcTemplate.query(
                sql,
                (rs, rowNum) -> new InitializationLease(
                        rs.getObject("init_lease_token", UUID.class),
                        rs.getString("previous_init_status"),
                        timestampToInstant(rs.getTimestamp("init_started_at")),
                        timestampToInstant(rs.getTimestamp("init_lease_expires_at"))),
                dbKey,
                DatabaseInitStatus.PENDING_INIT.name(),
                DatabaseInitStatus.INIT_FAILED.name(),
                DatabaseInitStatus.INITIALIZING.name(),
                leaseMillis,
                DatabaseInitStatus.INITIALIZING.name(),
                "Physical database initialization started",
                leaseMillis);
        return claimed.stream().findFirst();
    }

    /**
     * Extend an active lease. An expired or superseded holder cannot revive itself.
     *
     * <p>{@code init_started_at} is refreshed for rolling compatibility with older nodes whose
     * stale-lease check only understands that timestamp.
     */
    @CacheEvict(value = "databaseConfigs", key = "#dbKey")
    public boolean renewInitializationLease(
            String dbKey, UUID leaseToken, Duration leaseDuration) {
        long leaseMillis = requirePositiveLeaseMillis(leaseDuration);
        String sql = """
                UPDATE public.database_configs
                SET init_lease_expires_at = NOW()
                        + (CAST(? AS BIGINT) * INTERVAL '1 millisecond'),
                    init_started_at = NOW(),
                    updated_at = NOW()
                WHERE db_key = ?
                  AND init_status = ?
                  AND init_lease_token = ?
                  AND init_lease_expires_at > NOW()
                """;
        return metadataJdbcTemplate.update(
                sql,
                leaseMillis,
                dbKey,
                DatabaseInitStatus.INITIALIZING.name(),
                leaseToken) == 1;
    }

    /** Complete initialization only when the caller still owns an unexpired lease. */
    @CacheEvict(value = "databaseConfigs", key = "#dbKey")
    public boolean completeInitialization(String dbKey, UUID leaseToken, String initMessage) {
        String sql = """
                UPDATE public.database_configs
                SET init_status = ?,
                    init_message = ?,
                    init_completed_at = NOW(),
                    init_lease_token = NULL,
                    init_lease_expires_at = NULL,
                    init_fence_version = init_fence_version + 1,
                    enabled = TRUE,
                    updated_at = NOW()
                WHERE db_key = ?
                  AND init_status = ?
                  AND init_lease_token = ?
                  AND init_lease_expires_at > NOW()
                """;
        return metadataJdbcTemplate.update(
                sql,
                DatabaseInitStatus.INITIALIZED.name(),
                initMessage,
                dbKey,
                DatabaseInitStatus.INITIALIZING.name(),
                leaseToken) == 1;
    }

    /** Record failure only when the caller still owns an unexpired lease. */
    @CacheEvict(value = "databaseConfigs", key = "#dbKey")
    public boolean failInitialization(String dbKey, UUID leaseToken, String initMessage) {
        String sql = """
                UPDATE public.database_configs
                SET init_status = ?,
                    init_message = ?,
                    init_completed_at = NOW(),
                    init_lease_token = NULL,
                    init_lease_expires_at = NULL,
                    init_fence_version = init_fence_version + 1,
                    enabled = FALSE,
                    updated_at = NOW()
                WHERE db_key = ?
                  AND init_status = ?
                  AND init_lease_token = ?
                  AND init_lease_expires_at > NOW()
                """;
        return metadataJdbcTemplate.update(
                sql,
                DatabaseInitStatus.INIT_FAILED.name(),
                initMessage,
                dbKey,
                DatabaseInitStatus.INITIALIZING.name(),
                leaseToken) == 1;
    }

    public record InitializationLease(
            UUID token,
            String previousStatus,
            Instant startedAt,
            Instant expiresAt) {
    }

    private static long requirePositiveLeaseMillis(Duration leaseDuration) {
        if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        long leaseMillis = leaseDuration.toMillis();
        if (leaseMillis == 0L) {
            throw new IllegalArgumentException("leaseDuration must be at least one millisecond");
        }
        return leaseMillis;
    }

    private static Instant timestampToInstant(Timestamp timestamp) {
        return timestamp != null ? timestamp.toInstant() : null;
    }

    /**
     * Update the generated tenant JDBC URL after the configured PostgreSQL endpoint changes.
     */
    @CacheEvict(value = "databaseConfigs", key = "#dbKey")
    public void updateJdbcUrl(String dbKey, String jdbcUrl) {
        log.info("Updating JDBC URL for database configuration: {}", dbKey);

        String sql = """
                UPDATE public.database_configs
                SET jdbc_url = ?,
                    updated_at = NOW()
                WHERE db_key = ?
                """;

        metadataJdbcTemplate.update(sql, jdbcUrl, dbKey);
    }

    /**
     * Replace the exposed schemas list and extra search path for a tenant.
     *
     * <p>Both fields are stored as PostgreSQL {@code TEXT[]}. {@code null} values are left
     * untouched on the row — pass an updated list for the field you want to set.
     */
    @CacheEvict(value = "databaseConfigs", key = "#dbKey")
    public void updateDbSchemas(String dbKey,
                                java.util.List<String> dbSchemas,
                                java.util.List<String> dbExtraSearchPath) {
        log.info("Updating dbSchemas for {}: dbSchemas={}, extraSearchPath={}",
                dbKey, dbSchemas, dbExtraSearchPath);

        StringBuilder sql = new StringBuilder("UPDATE public.database_configs SET updated_at = NOW()");
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (dbSchemas != null) {
            sql.append(", db_schemas = ?");
            args.add(dbSchemas.toArray(new String[0]));
        }
        if (dbExtraSearchPath != null) {
            sql.append(", db_extra_search_path = ?");
            args.add(dbExtraSearchPath.toArray(new String[0]));
        }
        sql.append(" WHERE db_key = ?");
        args.add(dbKey);

        metadataJdbcTemplate.update(con -> {
            var ps = con.prepareStatement(sql.toString());
            int paramIdx = 1;
            if (dbSchemas != null) {
                ps.setArray(paramIdx++, con.createArrayOf("text", dbSchemas.toArray(new String[0])));
            }
            if (dbExtraSearchPath != null) {
                ps.setArray(paramIdx++, con.createArrayOf("text", dbExtraSearchPath.toArray(new String[0])));
            }
            ps.setString(paramIdx, dbKey);
            return ps;
        });
    }

    /**
     * Update enabled status
     *
     * @param dbKey   database key
     * @param enabled true to enable, false to disable
     */
    @CacheEvict(value = "databaseConfigs", key = "#dbKey")
    public void updateEnabled(String dbKey, boolean enabled) {
        log.info("Updating enabled status for {}: enabled={}", dbKey, enabled);

        String sql = """
                UPDATE public.database_configs
                SET enabled = ?,
                    updated_at = NOW()
                WHERE db_key = ?
                """;

        metadataJdbcTemplate.update(sql, enabled, dbKey);
    }

    private int insertConfig(DatabaseConfig config, boolean ignoreDbKeyConflict) {
        String sql = """
                INSERT INTO public.database_configs (
                    db_key, db_name, description,
                    jdbc_url, db_user, db_password_encrypted,
                    db_schemas, db_anon_role, db_max_rows, db_extra_search_path,
                    jwt_secret_encrypted, jwt_secret_is_base64, jwt_audience, jwt_role_claim_key,
                    pool_size, pool_timeout_ms, pool_max_lifetime_ms,
                    pool_connection_timeout_ms, pool_idle_timeout_ms, pool_minimum_idle,
                    enabled, created_by,app_code, app_name, schema_name, jwt_secret, service_role_token, oauth_config,authenticated_token,
                    init_status, init_message
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,?,?,?)
                """ + (ignoreDbKeyConflict ? " ON CONFLICT (db_key) DO NOTHING" : "");


        return metadataJdbcTemplate.update(sql,
                config.getDbKey(),
                config.getDbName(),
                config.getDescription(),
                config.getJdbcUrl(),
                config.getDbUser(),
                config.getDbPasswordEncrypted(),
                config.getDbSchemas() != null ? config.getDbSchemas().toArray(new String[0]) : null,
                config.getDbAnonRole(),
                config.getDbMaxRows(),
                config.getDbExtraSearchPath() != null ? config.getDbExtraSearchPath().toArray(new String[0]) : null,
                config.getJwtSecretEncrypted(),
                config.getJwtSecretIsBase64(),
                config.getJwtAudience(),
                config.getJwtRoleClaimKey(),
                config.getPoolSize(),
                config.getPoolTimeoutMs(),
                config.getPoolMaxLifetimeMs(),
                config.getPoolConnectionTimeoutMs(),
                config.getPoolIdleTimeoutMs(),
                config.getPoolMinimumIdle(),
                config.getEnabled(),
                config.getCreatedBy(),
                config.getAppCode(),
                config.getAppName(),
                config.getSchemaName(),
                config.getJwtSecret(),
                config.getServiceRoleToken(),
                config.getOauthConfig(),
                config.getAuthenticatedToken(),
                config.getInitStatus(),
                config.getInitMessage()
        );
    }

    private void updateConfig(DatabaseConfig config) {
        String sql = """
                UPDATE public.database_configs
                SET db_name = ?,
                    description = ?,
                    jdbc_url = ?,
                    db_user = ?,
                    db_password_encrypted = ?,
                    db_schemas = ?,
                    db_anon_role = ?,
                    db_max_rows = ?,
                    db_extra_search_path = ?,
                    jwt_secret_encrypted = ?,
                    jwt_secret_is_base64 = ?,
                    jwt_audience = ?,
                    jwt_role_claim_key = ?,
                    pool_size = ?,
                    pool_timeout_ms = ?,
                    pool_max_lifetime_ms = ?,
                    pool_connection_timeout_ms = ?,
                    pool_idle_timeout_ms = ?,
                    pool_minimum_idle = ?,
                    enabled = ?,
                    updated_by = ?,
                    app_code = ?,
                    app_name = ?,
                    schema_name = ?,
                    jwt_secret = ?,
                    service_role_token = ?,
                    authenticated_token = ?,
                    oauth_config = ?,
                    init_status = ?,
                    init_message = ?,
                    updated_at = NOW()
                WHERE db_key = ?
                """;

        metadataJdbcTemplate.update(sql,
                config.getDbName(),
                config.getDescription(),
                config.getJdbcUrl(),
                config.getDbUser(),
                config.getDbPasswordEncrypted(),
                config.getDbSchemas() != null ? config.getDbSchemas().toArray(new String[0]) : null,
                config.getDbAnonRole(),
                config.getDbMaxRows(),
                config.getDbExtraSearchPath() != null ? config.getDbExtraSearchPath().toArray(new String[0]) : null,
                config.getJwtSecretEncrypted(),
                config.getJwtSecretIsBase64(),
                config.getJwtAudience(),
                config.getJwtRoleClaimKey(),
                config.getPoolSize(),
                config.getPoolTimeoutMs(),
                config.getPoolMaxLifetimeMs(),
                config.getPoolConnectionTimeoutMs(),
                config.getPoolIdleTimeoutMs(),
                config.getPoolMinimumIdle(),
                config.getEnabled(),
                config.getUpdatedBy(),
                config.getAppCode(),
                config.getAppName(),
                config.getSchemaName(),
                config.getJwtSecret(),
                config.getServiceRoleToken(),
                config.getAuthenticatedToken(),
                config.getOauthConfig(),
                config.getInitStatus(),
                config.getInitMessage(),
                config.getDbKey()
        );
    }

    /**
     * One paginated query backing {@code GET /auth/v1/admin/projects}.
     * <p>
     * Visibility is folded into a single predicate: a super-admin (or the metadata
     * service-role key) sees every active or provisioning project; a regular user sees only projects
     * they are a member of via {@code platform_user_projects} (the {@code EXISTS} check, which
     * also keeps each project to a single row regardless of how many members it has).
     *
     * @param isSuperAdmin true to bypass the per-user membership filter
     * @param userId       the caller's platform user id
     * @param limit        page size
     * @param offset       rows to skip
     */
    public List<ProjectListItem> findVisibleProjects(boolean isSuperAdmin, java.util.UUID userId,
                                                     int limit, int offset) {
        String sql = """
                SELECT
                    c.db_key, c.app_code, c.app_name, c.db_name, c.description,
                    c.schema_name, c.init_status, c.health_status, c.enabled,
                    c.created_at, c.updated_at, c.service_role_token
                FROM public.database_configs c
                WHERE (c.enabled = true
                       OR c.init_status IN ('PENDING_INIT', 'INITIALIZING', 'INIT_FAILED'))
                  AND ( CAST(? AS boolean) = true
                        OR EXISTS (SELECT 1 FROM public.platform_user_projects pup
                                   WHERE pup.db_key = c.db_key AND pup.user_id = ?) )
                ORDER BY c.created_at DESC, c.db_key
                LIMIT ? OFFSET ?
                """;
        return metadataJdbcTemplate.query(sql, new ProjectListItemRowMapper(),
                isSuperAdmin, userId, limit, offset);
    }

    /**
     * Total count matching {@link #findVisibleProjects} (without paging), for the page total.
     */
    public long countVisibleProjects(boolean isSuperAdmin, java.util.UUID userId) {
        String sql = """
                SELECT COUNT(*)
                FROM public.database_configs c
                WHERE (c.enabled = true
                       OR c.init_status IN ('PENDING_INIT', 'INITIALIZING', 'INIT_FAILED'))
                  AND ( CAST(? AS boolean) = true
                        OR EXISTS (SELECT 1 FROM public.platform_user_projects pup
                                   WHERE pup.db_key = c.db_key AND pup.user_id = ?) )
                """;
        Long count = metadataJdbcTemplate.queryForObject(sql, Long.class, isSuperAdmin, userId);
        return count != null ? count : 0L;
    }

    public DatabaseConfig findByAppCode(String appCode) {
        log.debug("Loading appCode configuration for: {}", appCode);

        String sql = """
                SELECT
                    db_key, db_name, description,
                    jdbc_url, db_user, db_password_encrypted,
                    db_schemas, db_anon_role, db_max_rows, db_extra_search_path,
                    jwt_secret_encrypted, jwt_secret_is_base64, jwt_audience, jwt_role_claim_key,
                    pool_size, pool_timeout_ms, pool_max_lifetime_ms,
                    pool_connection_timeout_ms, pool_idle_timeout_ms, pool_minimum_idle,
                    enabled, created_at, updated_at, created_by, updated_by,
                    last_health_check, health_status, health_message,
                    app_code, app_name, schema_name,
                    jwt_secret, service_role_token, oauth_config, auth_config, authenticated_token,
                    init_status, init_message, init_started_at, init_completed_at
                FROM public.database_configs
                WHERE app_code = ?
                """;
        // Note: no `enabled = true` filter here. Admin endpoints (PATCH/DELETE on
        // /admin/projects/{ref}) need to find paused projects to resume them. Callers that
        // only care about active tenants (UnifiedMultiTenancyFilter, normal traffic) still
        // reject disabled rows via DatabaseConfig#isAvailable().

        List<DatabaseConfig> results = metadataJdbcTemplate.query(sql, new DatabaseConfigRowMapper(), appCode);

        if (results.isEmpty()) {
            log.warn("Database configuration not found for appCode: {}", appCode);
            return null;
        }

        DatabaseConfig config = results.get(0);

        // Decrypt sensitive data
        try {
            config.setDbPasswordDecrypted(encryptionService.decrypt(config.getDbPasswordEncrypted()));

            if (config.getJwtSecretEncrypted() != null) {
                config.setJwtSecretDecrypted(encryptionService.decrypt(config.getJwtSecretEncrypted()));
            }
        } catch (Exception e) {
            log.error("Failed to decrypt sensitive data for appCode: {}, errorType={}",
                    appCode, errorType(e));
            throw new IllegalStateException("Failed to decrypt database configuration");
        }

        log.info("Loaded configuration for appCode: {}", appCode);
        return config;
    }

    private static String errorType(Throwable error) {
        String simpleName = error.getClass().getSimpleName();
        return simpleName.isBlank() ? "Exception" : simpleName;
    }

    /**
     * RowMapper for DatabaseConfig
     */
    private static class DatabaseConfigRowMapper implements RowMapper<DatabaseConfig> {
        @Override
        public DatabaseConfig mapRow(ResultSet rs, int rowNum) throws SQLException {
            return DatabaseConfig.builder()
                    .dbKey(rs.getString("db_key"))
                    .dbName(rs.getString("db_name"))
                    .description(rs.getString("description"))
                    .jdbcUrl(rs.getString("jdbc_url"))
                    .dbUser(rs.getString("db_user"))
                    .dbPasswordEncrypted(rs.getString("db_password_encrypted"))
                    .dbSchemas(arrayToList(rs.getArray("db_schemas")))
                    .dbAnonRole(rs.getString("db_anon_role"))
                    .dbMaxRows((Integer) rs.getObject("db_max_rows"))
                    .dbExtraSearchPath(arrayToList(rs.getArray("db_extra_search_path")))
                    .jwtSecretEncrypted(rs.getString("jwt_secret_encrypted"))
                    .jwtSecretIsBase64(rs.getBoolean("jwt_secret_is_base64"))
                    .jwtAudience(rs.getString("jwt_audience"))
                    .jwtRoleClaimKey(rs.getString("jwt_role_claim_key"))
                    .poolSize((Integer) rs.getObject("pool_size"))
                    .poolTimeoutMs((Integer) rs.getObject("pool_timeout_ms"))
                    .poolMaxLifetimeMs((Integer) rs.getObject("pool_max_lifetime_ms"))
                    .poolConnectionTimeoutMs((Integer) rs.getObject("pool_connection_timeout_ms"))
                    .poolIdleTimeoutMs((Integer) rs.getObject("pool_idle_timeout_ms"))
                    .poolMinimumIdle((Integer) rs.getObject("pool_minimum_idle"))
                    .enabled(rs.getBoolean("enabled"))
                    .createdAt(toInstant(rs.getTimestamp("created_at")))
                    .updatedAt(toInstant(rs.getTimestamp("updated_at")))
                    .createdBy(rs.getString("created_by"))
                    .updatedBy(rs.getString("updated_by"))
                    .lastHealthCheck(toInstant(rs.getTimestamp("last_health_check")))
                    .healthStatus(rs.getString("health_status"))
                    .healthMessage(rs.getString("health_message"))
                    .appCode(rs.getString("app_code"))
                    .appName(rs.getString("app_name"))
                    .schemaName(rs.getString("schema_name"))
                    .jwtSecret(rs.getString("jwt_secret"))
                    .serviceRoleToken(rs.getString("service_role_token"))
                    .authenticatedToken(rs.getString("authenticated_token"))
                    .oauthConfig(rs.getString("oauth_config"))
                    .authConfigJson(rs.getString("auth_config"))
                    .initStatus(rs.getString("init_status"))
                    .initMessage(rs.getString("init_message"))
                    .initStartedAt(toInstant(rs.getTimestamp("init_started_at")))
                    .initCompletedAt(toInstant(rs.getTimestamp("init_completed_at")))
                    .build();
        }

        private List<String> arrayToList(Array array) throws SQLException {
            if (array == null) {
                return null;
            }
            Object[] objects = (Object[]) array.getArray();
            return Arrays.stream(objects)
                    .map(Object::toString)
                    .collect(Collectors.toList());
        }

        private java.time.Instant toInstant(Timestamp timestamp) {
            return timestamp != null ? timestamp.toInstant() : null;
        }
    }

    private static class ProjectListItemRowMapper implements RowMapper<ProjectListItem> {
        @Override
        public ProjectListItem mapRow(ResultSet rs, int rowNum) throws SQLException {
            return ProjectListItem.builder()
                    .dbKey(rs.getString("db_key"))
                    .appCode(rs.getString("app_code"))
                    .appName(rs.getString("app_name"))
                    .dbName(rs.getString("db_name"))
                    .description(rs.getString("description"))
                    .schemaName(rs.getString("schema_name"))
                    .initStatus(rs.getString("init_status"))
                    .healthStatus(rs.getString("health_status"))
                    .enabled(rs.getBoolean("enabled"))
                    .createdAt(toInstant(rs.getTimestamp("created_at")))
                    .updatedAt(toInstant(rs.getTimestamp("updated_at")))
                    .serviceRoleToken(rs.getString("service_role_token"))
                    .build();
        }

        private java.time.Instant toInstant(Timestamp timestamp) {
            return timestamp != null ? timestamp.toInstant() : null;
        }
    }
}
