package ai.nubase.auth.service;

import ai.nubase.auth.dto.request.admin.InitDatabaseRequest;
import ai.nubase.auth.dto.response.admin.InitDatabaseResponse;
import ai.nubase.common.enums.DatabaseInitStatus;
import ai.nubase.common.enums.Role;
import ai.nubase.common.util.SqlSafe;
import ai.nubase.postgrest.multidb.DatabaseConfig;
import ai.nubase.postgrest.multidb.DatabaseConfigRepository;
import ai.nubase.postgrest.multidb.EncryptionService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Database initialization service - full database-level multi-tenant isolation.
 * <p>
 * Execution flow:
 * 1. Phase one: create database configuration (status: pending_init)
 * - Generate JWT secret and API keys
 * - Save the configuration to the database_configs table
 * 2. Phase two: initialize the physical database (status: initializing -> initialized)
 * - Create a new PostgreSQL database
 * - Create a dedicated database user (with a random password)
 * - Initialize the Supabase schema structures
 * - Update the configuration status to initialized
 * <p>
 * <strong>Security warning:</strong>
 * - This service creates new physical databases and grants full privileges
 * - Accessible only via service_role authentication
 * - All operations are logged for auditing
 *
 * @author nubase
 * @since 2025-01-03
 */
@Service
@Slf4j
public class DatabaseInitService {

    private final DatabaseConfigRepository databaseConfigRepository;
    private final EncryptionService encryptionService;

    private final JdbcTemplate metadataJdbcTemplate;

    public DatabaseInitService(
            DatabaseConfigRepository databaseConfigRepository,
            EncryptionService encryptionService,
            @Qualifier("metadataJdbcTemplate") JdbcTemplate metadataJdbcTemplate) {
        this.databaseConfigRepository = databaseConfigRepository;
        this.encryptionService = encryptionService;
        this.metadataJdbcTemplate = metadataJdbcTemplate;
    }

    // PostgreSQL server configuration (injected from configuration files)
    @Value("${pgrst.multidb.postgres.host}")
    private String postgresHost;

    @Value("${pgrst.multidb.postgres.port}")
    private Integer postgresPort;

    @Value("${spring.datasource.metadata.username}")
    private String metadataUsername;

    @Value("${spring.datasource.metadata.password}")
    private String metadataPassword;

    /**
     * Whether the mem schema (Batch A/B/this PR) should be created on new tenants. When
     * {@code false}, {@code initializeSupabaseSchemas} skips the mem DDL entirely and the
     * pgvector extension is not required. Mirrors {@code nubase.mem.enabled}.
     */
    @Value("${nubase.mem.enabled:true}")
    private boolean memEnabled;

    /**
     * Whether the per-project {@code ai_gateway} schema should be created on new tenants.
     * When {@code false}, {@code initializeSupabaseSchemas} skips the gateway DDL and the
     * AI_GATEWAY grant blocks in init_roles.sql are stripped. Mirrors {@code nubase.ai-gateway.enabled}.
     */
    @Value("${nubase.ai-gateway.enabled:true}")
    private boolean aiGatewayEnabled;

    /**
     * Whether the per-project {@code assets} schema (static asset CDN) should be created on
     * new tenants. When {@code false}, the assets DDL is skipped and the ASSETS grant blocks
     * in init_roles.sql are stripped. Mirrors {@code nubase.assets.enabled}.
     */
    @Value("${nubase.assets.enabled:true}")
    private boolean assetsEnabled;

    /**
     * PostgreSQL text-search config name baked into the {@code mem.memories} GIN index at
     * init time. Defaults to {@code simple} when not set. Whitelist-validated below.
     */
    @Value("${nubase.mem.search.fts-config:simple}")
    private String memFtsConfig;

    /**
     * Embedding dimension baked into {@code vector(N)} at init time. Must match the
     * provider configured under {@code nubase.mem.embedding.dimensions}, or every insert
     * will fail at runtime.
     */
    @Value("${nubase.mem.embedding.dimensions:1536}")
    private int memEmbeddingDimensions;

    /**
     * Same whitelist as {@code MemoryRepository.ALLOWED_FTS_CONFIGS} — kept in sync so the
     * index (built here) and the query (built there) agree.
     */
    private static final java.util.Set<String> ALLOWED_FTS_CONFIGS = java.util.Set.of(
            "simple", "english", "spanish", "french", "german", "italian",
            "portuguese", "russian", "dutch", "norwegian", "swedish", "danish",
            "finnish", "hungarian", "turkish", "chinese", "zhparser", "jieba"
    );

    /**
     * Bounded sanity range for {@code nubase.mem.embedding.dimensions}. The hard upper bound
     * matches pgvector's compiled-in {@code VECTOR_MAX_DIM = 16000}. The lower bound just
     * catches obvious typos (1 / 0).
     */
    private static final int MIN_EMBEDDING_DIMENSIONS = 64;
    private static final int MAX_EMBEDDING_DIMENSIONS = 16000;

    // SQL file paths
    private static final String INIT_AUTH_SCHEMA_SQL = "db/supabase/init_auth_schema.sql";
    private static final String INIT_STORAGE_SCHEMA_SQL = "db/supabase/init_storage_schema.sql";
    private static final String INIT_MEM_SCHEMA_SQL = "db/supabase/init_mem_schema.sql";
    private static final String INIT_AI_GATEWAY_SCHEMA_SQL = "db/supabase/init_ai_gateway_schema.sql";
    private static final String INIT_ASSETS_SCHEMA_SQL = "db/supabase/init_assets_schema.sql";
    private static final String INIT_ROLES_SQL = "db/supabase/init_roles.sql";

    /**
     * Phase one: create the database configuration (does not initialize the physical database).
     * Status: pending_init
     * <p>
     * 1. Generate JWT secret and API keys
     * 2. Save the configuration to the database_configs table (status: pending_init)
     * 3. Return the configuration information
     */
    public InitDatabaseResponse createDatabaseConfig(InitDatabaseRequest request) {
        long startTime = System.currentTimeMillis();
        List<String> executedSteps = new ArrayList<>();

        try {
            // Set defaults
            String dbKey = request.getDbKey();
            String dbName = request.getDbName();
            String appCode = request.getAppCode();
            String appName = request.getAppName() != null ? request.getAppName() : appCode;
            String serviceRole = request.getServiceRole() != null ? request.getServiceRole() : Role.SERVICE_ROLE.getValue();
            String authenticatedRole = request.getAuthenticatedRole() != null ? request.getAuthenticatedRole() : Role.AUTHENTICATED.getValue();
            String anonRole = request.getAnonRole() != null ? request.getAnonRole() : Role.ANON.getValue();

            log.info("Creating database configuration: dbKey={}, dbName={}, appCode={}", dbKey, dbName, appCode);

            // Reject a duplicate reference instead of silently returning the existing project — the
            // caller (Studio "New project") surfaces this so the user can pick a different reference.
            DatabaseConfig config = databaseConfigRepository.findByDbKey(dbKey);
            if (config != null) {
                return InitDatabaseResponse.error(
                        "A project with reference '" + dbKey + "' already exists. Choose a different reference.",
                        "duplicate_reference",
                        System.currentTimeMillis() - startTime
                );
            }

            // Generate the database user and password (saved but not yet created)
            String dbUser = dbName + "_user";
            String dbPassword = generateRandomPassword();
            String jdbcUrl = String.format("jdbc:postgresql://%s:%d/%s", postgresHost, postgresPort, dbName);
            executedSteps.add("Generated database user credentials");

            // Generate JWT secret and API keys
            String jwtSecret = generateJwtSecret();
            SecretKey jwtSecretKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));

            String serviceRoleToken = generateApiKey(jwtSecretKey, serviceRole, appCode);
            String authenticatedToken = generateApiKey(jwtSecretKey, authenticatedRole, appCode);
            executedSteps.add("Generated JWT secret and API keys");

            // Build the configuration object (status: pending_init)
            DatabaseConfig databaseConfig = buildDatabaseConfig(
                    dbKey, dbName, appCode, appName, request.getDescription(),
                    jdbcUrl, dbUser, dbPassword,
                    jwtSecret, serviceRoleToken, authenticatedToken,
                    serviceRole, authenticatedRole, anonRole,
                    request.getPoolSize(), request.getExternalUserId()
            );

            // Set the initialization status
            databaseConfig.setInitStatus(DatabaseInitStatus.PENDING_INIT.name());
            databaseConfig.setInitMessage("Configuration created, waiting for physical database initialization");
            databaseConfig.setEnabled(true); // Disabled prior to initialization

            // Save the configuration to database_configs
            databaseConfigRepository.save(databaseConfig);
            executedSteps.add("Saved configuration to database_configs (status: pending_init)");

            long executionTime = System.currentTimeMillis() - startTime;
            log.info("Database configuration created successfully in {}ms for dbKey={}", executionTime, dbKey);

            return InitDatabaseResponse.success(
                    jwtSecret,
                    serviceRoleToken,
                    authenticatedToken,
                    databaseConfig.getInitStatus(),
                    executedSteps,
                    executionTime
            );

        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Failed to create database configuration: {}", request.getAppCode(), e);

            return InitDatabaseResponse.error(
                    "Database configuration creation failed: " + e.getMessage(),
                    getDetailedErrorMessage(e, executedSteps),
                    executionTime
            );
        }
    }

    /**
     * Phase two: initialize the physical database.
     * Status: initializing -> initialized / init_failed
     * <p>
     * 1. Update status to initializing
     * 2. Create the physical database and user
     * 3. Initialize the Supabase schemas
     * 4. Update status to initialized
     */
    public InitDatabaseResponse initializePhysicalDatabase(String dbKey) {
        long startTime = System.currentTimeMillis();
        List<String> executedSteps = new ArrayList<>();
        HikariDataSource newDatabaseDataSource = null;
        HikariDataSource newDatabaseSuperDataSource = null;

        try {
            // 1. Fetch the configuration
            DatabaseConfig databaseConfig = databaseConfigRepository.findByDbKey(dbKey);
            if (databaseConfig == null) {
                throw new IllegalArgumentException("Database configuration not found for dbKey: " + dbKey);
            }

            // Check the status
            if (DatabaseInitStatus.INITIALIZED.name().equals(databaseConfig.getInitStatus())) {
                log.info("Database already initialized for dbKey={}, skipping initialization", dbKey);
                return InitDatabaseResponse.success(
                        databaseConfig.getJwtSecret(), // JWT secret already exists
                        databaseConfig.getServiceRoleToken(), // Service role token already exists
                        databaseConfig.getAuthenticatedToken(), // Authenticated token already exists
                        databaseConfig.getInitStatus(),
                        executedSteps,
                        System.currentTimeMillis() - startTime
                );
            }

            log.info("Starting physical database initialization for dbKey={}", dbKey);

            // 2. Update status to initializing
            databaseConfigRepository.updateInitStatus(
                    dbKey,
                    DatabaseInitStatus.INITIALIZING.name(),
                    "Physical database initialization started",
                    Instant.now(),
                    null
            );
            executedSteps.add("Updated status to initializing");

            // Decrypt the password
            String dbPassword = encryptionService.decrypt(databaseConfig.getDbPasswordEncrypted());
            String dbName = databaseConfig.getDbName();
            String dbUser = databaseConfig.getDbUser();

            // Extract role information (from JWT tokens, or use defaults)
            String serviceRole = Role.SERVICE_ROLE.getValue();
            String authenticatedRole = Role.AUTHENTICATED.getValue();
            String anonRole = Role.ANON.getValue();

            // 3. Create the database and user
            createDatabaseAndUser(dbName, dbUser, dbPassword, executedSteps);

            // 4. Connect to the new database and initialize it
            String jdbcUrl = databaseConfig.getJdbcUrl();
            newDatabaseSuperDataSource = createDataSource(jdbcUrl, metadataUsername, metadataPassword);
            initSuperExtensions(newDatabaseSuperDataSource);

            // Configure permissions on the public schema
            configurePublicSchema(newDatabaseSuperDataSource, dbUser);
            executedSteps.add("Configured public schema ownership");

            newDatabaseDataSource = createDataSource(jdbcUrl, dbUser, dbPassword);
            initializeSupabaseSchemas(newDatabaseDataSource, serviceRole, authenticatedRole, anonRole, dbUser);

            // Check and create any missing roles
            checkAndCreateMissingRoles(newDatabaseSuperDataSource, serviceRole, authenticatedRole, anonRole, executedSteps);

            initializeSupabaseRoles(newDatabaseSuperDataSource, serviceRole, authenticatedRole, anonRole, dbUser);

            // 5. Update status to initialized
            databaseConfigRepository.updateInitStatus(
                    dbKey,
                    DatabaseInitStatus.INITIALIZED.name(),
                    "Physical database initialized successfully",
                    null,
                    Instant.now()
            );
            databaseConfigRepository.updateEnabled(dbKey, true); // Enable the configuration
            executedSteps.add("Updated status to initialized and enabled configuration");

            long executionTime = System.currentTimeMillis() - startTime;
            log.info("Physical database initialization completed successfully in {}ms for dbKey={}", executionTime, dbKey);

            return InitDatabaseResponse.success(
                    null, // JWT secret already exists
                    null, // Service role token already exists
                    null, // Authenticated token already exists
                    DatabaseInitStatus.INITIALIZED.name(),
                    executedSteps,
                    executionTime
            );

        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Failed to initialize physical database: {}", dbKey, e);

            // Update status to init_failed
            try {
                databaseConfigRepository.updateInitStatus(
                        dbKey,
                        DatabaseInitStatus.INIT_FAILED.name(),
                        "Physical database initialization failed: " + e.getMessage(),
                        null,
                        Instant.now()
                );
            } catch (Exception updateEx) {
                log.error("Failed to update init status to init_failed: {}", updateEx.getMessage());
            }

            return InitDatabaseResponse.error(
                    "Physical database initialization failed: " + e.getMessage(),
                    getDetailedErrorMessage(e, executedSteps),
                    executionTime
            );
        } finally {
            // Clean up datasources
            if (newDatabaseDataSource != null) {
                try {
                    newDatabaseDataSource.close();
                } catch (Exception ex) {
                    log.warn("Failed to close newDatabaseDataSource: {}", ex.getMessage());
                }
            }
            if (newDatabaseSuperDataSource != null) {
                try {
                    newDatabaseSuperDataSource.close();
                } catch (Exception ex) {
                    log.warn("Failed to close newDatabaseSuperDataSource: {}", ex.getMessage());
                }
            }
        }
    }

    /**
     * Initialize a database - full flow (kept for backward compatibility; delegates to the two-phase methods).
     * <p>
     * 1. Create the database configuration
     * 2. Initialize the physical database
     */
    @Deprecated
    public InitDatabaseResponse initDatabase(InitDatabaseRequest request) {
        long startTime = System.currentTimeMillis();

        // Phase one: create configuration
        InitDatabaseResponse configResponse = createDatabaseConfig(request);
        if (!configResponse.isSuccess()) {
            return configResponse;
        }

        // Phase two: initialize the physical database. Keep the terminal status from phase two,
        // but preserve phase one's generated keys for legacy callers of this combined endpoint.
        InitDatabaseResponse initResponse = initializePhysicalDatabase(request.getDbKey());
        if (!initResponse.isSuccess()) {
            return initResponse;
        }

        List<String> steps = new ArrayList<>();
        if (configResponse.getSteps() != null) {
            steps.addAll(configResponse.getSteps());
        }
        if (initResponse.getSteps() != null) {
            steps.addAll(initResponse.getSteps());
        }

        return InitDatabaseResponse.success(
                configResponse.getJwtSecret(),
                configResponse.getServiceRoleToken(),
                configResponse.getAuthenticatedToken(),
                initResponse.getInitStatus(),
                steps,
                System.currentTimeMillis() - startTime
        );
    }

    /**
     * Check and create any missing PostgreSQL roles.
     * Queries all roles in a single round-trip and only creates the missing ones.
     *
     * @param dataSource        data source (must have superuser privileges)
     * @param serviceRole       service_role role name
     * @param authenticatedRole authenticated role name
     * @param anonRole          anon role name
     * @param executedSteps     record of executed steps
     */
    private void checkAndCreateMissingRoles(HikariDataSource dataSource,
                                            String serviceRole,
                                            String authenticatedRole,
                                            String anonRole,
                                            List<String> executedSteps) {
        log.info("Checking existing PostgreSQL roles...");

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            // 1. Query existing roles (all three in a single query)
            String checkRolesSql = String.format(
                    "SELECT rolname FROM pg_roles WHERE rolname IN ('%s', '%s', '%s')",
                    serviceRole, authenticatedRole, anonRole
            );

            Set<String> existingRoles = new HashSet<>();
            try (var rs = stmt.executeQuery(checkRolesSql)) {
                while (rs.next()) {
                    existingRoles.add(rs.getString("rolname"));
                }
            }

            log.info("Found existing roles: {}", existingRoles);

            // 2. Determine which roles need to be created
            List<String> rolesToCreate = new ArrayList<>();
            if (!existingRoles.contains(serviceRole)) {
                rolesToCreate.add(serviceRole);
            }
            if (!existingRoles.contains(authenticatedRole)) {
                rolesToCreate.add(authenticatedRole);
            }
            if (!existingRoles.contains(anonRole)) {
                rolesToCreate.add(anonRole);
            }

            // 3. Create any missing roles
            if (rolesToCreate.isEmpty()) {
                log.info("All required roles already exist, skipping role creation");
                executedSteps.add("Verified all PostgreSQL roles exist");
            } else {
                log.info("Creating missing roles: {}", rolesToCreate);
                for (String role : rolesToCreate) {
                    String createRoleSql = String.format("CREATE ROLE %s NOLOGIN NOINHERIT", role);
                    stmt.execute(createRoleSql);
                    log.info("Created role: {}", role);
                }
                executedSteps.add("Created missing PostgreSQL roles: " + String.join(", ", rolesToCreate));
            }

        } catch (Exception e) {
            log.error("Failed to check and create missing roles: {}", e.getMessage(), e);
        }
    }

    /**
     * Idempotently apply the mem-schema (and role grants) to an already-initialized tenant.
     *
     * <p>Use when a tenant was initialized before the {@code mem} schema (Batch A) or the
     * {@code mem.entities} table (Batch B) existed in this codebase. Safe to re-run — every
     * statement in {@code init_mem_schema.sql} uses {@code IF NOT EXISTS} and every policy
     * in {@code init_roles.sql} uses {@code DROP POLICY IF EXISTS} before {@code CREATE}.
     *
     * <p>Also updates {@code database_configs.db_schemas} / {@code db_extra_search_path} to
     * include {@code "mem"} so PostgREST exposes the schema.
     *
     * @param dbKey tenant to migrate
     * @return a summary of executed steps for the response body
     */
    public List<String> initializeMemSchemaForExistingTenant(String dbKey) {
        if (!memEnabled) {
            throw new IllegalStateException(
                    "Mem feature is disabled (nubase.mem.enabled=false); cannot migrate mem schema. "
                            + "Flip the flag to true and restart before invoking this endpoint.");
        }
        List<String> executedSteps = new ArrayList<>();
        DatabaseConfig config = databaseConfigRepository.findByDbKey(dbKey);
        if (config == null) {
            throw new IllegalArgumentException("Database configuration not found for dbKey: " + dbKey);
        }
        if (!DatabaseInitStatus.INITIALIZED.name().equals(config.getInitStatus())) {
            throw new IllegalStateException("Tenant " + dbKey
                    + " is in status " + config.getInitStatus()
                    + "; mem-schema migration only runs against INITIALIZED tenants");
        }

        String dbUser = config.getDbUser();
        String jdbcUrl = config.getJdbcUrl();
        String serviceRole = Role.SERVICE_ROLE.getValue();
        String authenticatedRole = Role.AUTHENTICATED.getValue();
        String anonRole = Role.ANON.getValue();

        HikariDataSource superDs = null;
        HikariDataSource userDs = null;
        try {
            // EncryptionService.decrypt() throws a checked exception — keep it inside the try
            // so we go through the unified failure path.
            String dbPassword = encryptionService.decrypt(config.getDbPasswordEncrypted());

            // 1. Super-user: create pgvector extension. Must succeed — the next step runs
            //    init_mem_schema.sql, which declares vector(N) columns and HNSW indexes that
            //    would otherwise fail with a confusing "type vector does not exist" error and
            //    leave the tenant half-migrated. Behavior mirrors new-tenant init.
            superDs = createDataSource(jdbcUrl, metadataUsername, metadataPassword);
            try (Connection conn = superDs.getConnection();
                 Statement stmt = conn.createStatement()) {
                try {
                    stmt.execute("CREATE EXTENSION IF NOT EXISTS vector");
                    executedSteps.add("Verified pgvector extension");
                } catch (SQLException e) {
                    throw new RuntimeException(
                            "Failed to create pgvector extension on tenant '" + dbKey
                                    + "'. Install pgvector on the Postgres server "
                                    + "(e.g. use the pgvector/pgvector image) before retrying "
                                    + "the mem-schema migration. Underlying error: " + e.getMessage(), e);
                }
            }

            // 2. dbUser: create mem schema and run the table DDL (idempotent).
            userDs = createDataSource(jdbcUrl, dbUser, dbPassword);
            try (Connection conn = userDs.getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE SCHEMA IF NOT EXISTS mem");
                executedSteps.add("Ensured mem schema exists");

                String memSql = loadAndReplacePlaceholders(
                        INIT_MEM_SCHEMA_SQL, serviceRole, authenticatedRole, anonRole, dbUser);
                executeSqlScript(stmt, memSql);
                executedSteps.add("Applied init_mem_schema.sql");
            }

            // 3. Super-user: re-run init_roles.sql so the new mem grants/policies attach
            //    (the file is fully idempotent — DROP POLICY IF EXISTS guards each CREATE).
            try (Connection conn = superDs.getConnection();
                 Statement stmt = conn.createStatement()) {
                String rolesSql = loadAndReplacePlaceholders(
                        INIT_ROLES_SQL, serviceRole, authenticatedRole, anonRole, dbUser);
                executeSqlScript(stmt, rolesSql);
                executedSteps.add("Re-applied init_roles.sql (mem grants & policies)");
            }

            // 4. Patch the routing config so PostgREST exposes /rest/v1 for mem.* tables.
            List<String> schemas = new ArrayList<>(config.getDbSchemas() != null
                    ? config.getDbSchemas() : Collections.emptyList());
            if (!schemas.contains("mem")) {
                schemas.add("mem");
            }
            List<String> extraSearch = new ArrayList<>(config.getDbExtraSearchPath() != null
                    ? config.getDbExtraSearchPath() : Collections.emptyList());
            if (!extraSearch.contains("mem")) {
                extraSearch.add("mem");
            }
            databaseConfigRepository.updateDbSchemas(dbKey, schemas, extraSearch);
            executedSteps.add("Updated database_configs.db_schemas and db_extra_search_path");

            log.info("mem-schema migration completed for tenant {}", dbKey);
            return executedSteps;

        } catch (Exception e) {
            log.error("Mem-schema migration failed for {}: {}", dbKey, e.getMessage(), e);
            throw new RuntimeException("Mem-schema migration failed: " + e.getMessage(), e);
        } finally {
            if (superDs != null) {
                try { superDs.close(); } catch (Exception ignore) {}
            }
            if (userDs != null) {
                try { userDs.close(); } catch (Exception ignore) {}
            }
        }
    }

    /**
     * Apply the mem-schema migration to every {@code INITIALIZED} tenant.
     *
     * @return {@code Map<dbKey, ok | error-message>} so the caller can report per-tenant status
     */
    public Map<String, String> initializeMemSchemaForAllTenants() {
        Map<String, String> results = new LinkedHashMap<>();
        for (DatabaseConfig cfg : databaseConfigRepository.findAllEnabled()) {
            if (!DatabaseInitStatus.INITIALIZED.name().equals(cfg.getInitStatus())) {
                results.put(cfg.getDbKey(), "SKIPPED: status=" + cfg.getInitStatus());
                continue;
            }
            try {
                initializeMemSchemaForExistingTenant(cfg.getDbKey());
                results.put(cfg.getDbKey(), "OK");
            } catch (Exception e) {
                results.put(cfg.getDbKey(), "ERROR: " + e.getMessage());
            }
        }
        return results;
    }

    /**
     * Idempotently apply the assets schema (and role grants) to an already-initialized tenant.
     *
     * <p>Use when a tenant was provisioned before the static-asset CDN module existed. Safe to
     * re-run — every statement in {@code init_assets_schema.sql} uses {@code IF NOT EXISTS}
     * (or {@code DROP ... IF EXISTS} before {@code CREATE}) and the ASSETS grant blocks in
     * init_roles.sql follow the same convention as the mem migration.
     *
     * @param dbKey tenant to migrate
     * @return a summary of executed steps for the response body
     */
    public List<String> initializeAssetsSchemaForExistingTenant(String dbKey) {
        if (!assetsEnabled) {
            throw new IllegalStateException(
                    "Assets feature is disabled (nubase.assets.enabled=false); cannot migrate assets schema. "
                            + "Flip the flag to true and restart before invoking this endpoint.");
        }
        List<String> executedSteps = new ArrayList<>();
        DatabaseConfig config = databaseConfigRepository.findByDbKey(dbKey);
        if (config == null) {
            throw new IllegalArgumentException("Database configuration not found for dbKey: " + dbKey);
        }
        if (!DatabaseInitStatus.INITIALIZED.name().equals(config.getInitStatus())) {
            throw new IllegalStateException("Tenant " + dbKey
                    + " is in status " + config.getInitStatus()
                    + "; assets-schema migration only runs against INITIALIZED tenants");
        }

        String dbUser = config.getDbUser();
        String jdbcUrl = config.getJdbcUrl();
        String serviceRole = Role.SERVICE_ROLE.getValue();
        String authenticatedRole = Role.AUTHENTICATED.getValue();
        String anonRole = Role.ANON.getValue();

        HikariDataSource superDs = null;
        HikariDataSource userDs = null;
        try {
            String dbPassword = encryptionService.decrypt(config.getDbPasswordEncrypted());

            // 1. dbUser: create the assets schema and run the table DDL (idempotent).
            userDs = createDataSource(jdbcUrl, dbUser, dbPassword);
            try (Connection conn = userDs.getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE SCHEMA IF NOT EXISTS assets");
                executedSteps.add("Ensured assets schema exists");

                String assetsSql = loadAndReplacePlaceholders(
                        INIT_ASSETS_SCHEMA_SQL, serviceRole, authenticatedRole, anonRole, dbUser);
                executeSqlScript(stmt, assetsSql);
                executedSteps.add("Applied init_assets_schema.sql");
            }

            // 2. Super-user: re-run init_roles.sql so the new assets grants/policies attach
            //    (the file is fully idempotent — DROP POLICY IF EXISTS guards each CREATE).
            superDs = createDataSource(jdbcUrl, metadataUsername, metadataPassword);
            try (Connection conn = superDs.getConnection();
                 Statement stmt = conn.createStatement()) {
                String rolesSql = loadAndReplacePlaceholders(
                        INIT_ROLES_SQL, serviceRole, authenticatedRole, anonRole, dbUser);
                executeSqlScript(stmt, rolesSql);
                executedSteps.add("Re-applied init_roles.sql (assets grants & policies)");
            }

            return executedSteps;
        } catch (Exception e) {
            log.error("Assets-schema migration failed for {}: {}", dbKey, e.getMessage(), e);
            throw new RuntimeException("Assets-schema migration failed: " + e.getMessage(), e);
        } finally {
            if (superDs != null) {
                try { superDs.close(); } catch (Exception ignore) {}
            }
            if (userDs != null) {
                try { userDs.close(); } catch (Exception ignore) {}
            }
        }
    }

    /**
     * Apply the assets-schema migration to every {@code INITIALIZED} tenant.
     *
     * @return {@code Map<dbKey, ok | error-message>} so the caller can report per-tenant status
     */
    public Map<String, String> initializeAssetsSchemaForAllTenants() {
        Map<String, String> results = new LinkedHashMap<>();
        for (DatabaseConfig cfg : databaseConfigRepository.findAllEnabled()) {
            if (!DatabaseInitStatus.INITIALIZED.name().equals(cfg.getInitStatus())) {
                results.put(cfg.getDbKey(), "SKIPPED: status=" + cfg.getInitStatus());
                continue;
            }
            try {
                initializeAssetsSchemaForExistingTenant(cfg.getDbKey());
                results.put(cfg.getDbKey(), "OK");
            } catch (Exception e) {
                results.put(cfg.getDbKey(), "ERROR: " + e.getMessage());
            }
        }
        return results;
    }

    private void initializeSupabaseRoles(HikariDataSource dataSource, String serviceRole, String authenticatedRole, String anonRole, String dbUser) {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            // 5. Execute the roles SQL
            String rolesSql = loadAndReplacePlaceholders(INIT_ROLES_SQL, serviceRole, authenticatedRole, anonRole, dbUser);
            executeSqlScript(stmt, rolesSql);
            log.info("Created roles and granted permissions");
        } catch (SQLException | IOException e) {
            log.error("Failed to initialize Supabase schemas: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to initialize Supabase schemas: " + e.getMessage(), e);
        }
    }

    private void initSuperExtensions(HikariDataSource newDatabaseSuperDataSource) {
        try (Connection conn = newDatabaseSuperDataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            // Create the pgcrypto extension
            stmt.execute("CREATE EXTENSION IF NOT EXISTS \"pgcrypto\" SCHEMA public");
            log.info("Created extension: pgcrypto");

            // pgvector — only required when the mem schema will be initialized.
            if (memEnabled) {
                try {
                    stmt.execute("CREATE EXTENSION IF NOT EXISTS vector");
                    log.info("Created extension: vector (pgvector)");
                } catch (SQLException ex) {
                    // Fail fast: continuing would let init_mem_schema.sql blow up later with
                    // a confusing "type vector does not exist" error and leave the tenant
                    // half-initialized. Better to surface the root cause now.
                    throw new RuntimeException(
                            "Failed to create pgvector extension (required because nubase.mem.enabled=true). "
                                    + "Install pgvector on the Postgres server "
                                    + "(e.g. use the pgvector/pgvector image), or set nubase.mem.enabled=false. "
                                    + "Underlying error: " + ex.getMessage(), ex);
                }
            } else {
                log.info("Skipping pgvector extension — nubase.mem.enabled=false");
            }
        } catch (SQLException e) {
            log.error("Failed to initialize extensions in new database: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to initialize extensions in new database: " + e.getMessage(), e);
        }
    }

    /**
     * Configure the permissions on the public schema.
     * Sets the public schema's owner to dbUser so that subsequent ALTER DEFAULT PRIVILEGES calls succeed.
     */
    private void configurePublicSchema(HikariDataSource superDataSource, String dbUser) {
        try (Connection conn = superDataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            // Set the public schema's owner to dbUser
            String alterSchemaSql = String.format("ALTER SCHEMA public OWNER TO %s", dbUser);
            stmt.execute(alterSchemaSql);
            log.info("Set public schema owner to: {}", dbUser);
        } catch (SQLException e) {
            log.error("Failed to configure public schema: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to configure public schema: " + e.getMessage(), e);
        }
    }

    /**
     * Create the database and user (using metadataJdbcTemplate with superuser privileges).
     */
    private void createDatabaseAndUser(String dbName, String dbUser, String dbPassword, List<String> executedSteps) {
        log.info("Creating database and user: dbName={}, dbUser={}", dbName, dbUser);

        try {
            // Create the database
            String createDbSql = String.format("CREATE DATABASE %s ENCODING 'UTF8'", SqlSafe.ident(dbName));
            metadataJdbcTemplate.execute(createDbSql);
            log.info("Created database: {}", dbName);
            executedSteps.add("Created database: " + dbName);

            // Create the user
            String createUserSql = String.format("CREATE USER %s WITH PASSWORD %s", SqlSafe.ident(dbUser), SqlSafe.literal(dbPassword));
            metadataJdbcTemplate.execute(createUserSql);
            log.info("Created user: {}", dbUser);
            executedSteps.add("Created user: " + dbUser);

            // Grant privileges
            String grantSql = String.format("GRANT ALL PRIVILEGES ON DATABASE %s TO %s", SqlSafe.ident(dbName), SqlSafe.ident(dbUser));
            metadataJdbcTemplate.execute(grantSql);

            // Grant owner privileges
            String ownerSql = String.format("ALTER DATABASE %s OWNER TO %s", SqlSafe.ident(dbName), SqlSafe.ident(dbUser));
            metadataJdbcTemplate.execute(ownerSql);
            log.info("Granted all privileges on database {} to user {}", dbName, dbUser);

            // Tenant isolation at the connection layer.
            // PostgreSQL grants CONNECT on every new database to PUBLIC by default. Because
            // roles are cluster-global, that default would let ANY tenant's db_user open a
            // session on THIS database and (via the shared service_role/authenticated/anon
            // roles it belongs to) read this tenant's data. Revoke the PUBLIC default and
            // grant CONNECT only to this database's own user. The shared roles are NOLOGIN
            // and are reached via SET ROLE inside the owner's session, so they need no CONNECT.
            metadataJdbcTemplate.execute(String.format("REVOKE CONNECT ON DATABASE %s FROM PUBLIC", dbName));
            metadataJdbcTemplate.execute(String.format("GRANT CONNECT ON DATABASE %s TO %s", dbName, dbUser));
            log.info("Restricted CONNECT on database {} to user {} (revoked PUBLIC)", dbName, dbUser);
            executedSteps.add("Restricted CONNECT on database " + dbName + " to " + dbUser + " (revoked PUBLIC)");

        } catch (Exception e) {
            log.error("Failed to create database and user: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to create database and user: " + e.getMessage(), e);
        }
    }

    /**
     * Initialize the Supabase schemas (connected to the new database).
     */
    private void initializeSupabaseSchemas(DataSource dataSource, String serviceRole, String authenticatedRole, String anonRole, String dbUser) {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            // 1. Create the auth schema
            stmt.execute("CREATE SCHEMA IF NOT EXISTS auth");
            log.info("Created schema: auth");

            // 2. Create the storage schema
            stmt.execute("CREATE SCHEMA IF NOT EXISTS storage");
            log.info("Created schema: storage");

            // 2b. Create the mem schema (AI memory) — only when the feature is enabled
            if (memEnabled) {
                stmt.execute("CREATE SCHEMA IF NOT EXISTS mem");
                log.info("Created schema: mem");
            }

            // 2c. Create the ai_gateway schema (per-project AI gateway) — only when enabled
            if (aiGatewayEnabled) {
                stmt.execute("CREATE SCHEMA IF NOT EXISTS ai_gateway");
                log.info("Created schema: ai_gateway");
            }

            // 2d. Create the assets schema (static asset CDN) — only when enabled
            if (assetsEnabled) {
                stmt.execute("CREATE SCHEMA IF NOT EXISTS assets");
                log.info("Created schema: assets");
            }

            // 3. Execute the auth schema SQL
            String authSql = loadAndReplacePlaceholders(INIT_AUTH_SCHEMA_SQL, serviceRole, authenticatedRole, anonRole, dbUser);
            executeSqlScript(stmt, authSql);
            log.info("Initialized auth schema tables");

            // 4. Execute the storage schema SQL (covers file storage and vector storage)
            String storageSql = loadAndReplacePlaceholders(INIT_STORAGE_SCHEMA_SQL, serviceRole, authenticatedRole, anonRole, dbUser);
            executeSqlScript(stmt, storageSql);
            log.info("Initialized storage schema tables");

            // 5. Execute the mem schema SQL (pgvector vector memory) — only when enabled
            if (memEnabled) {
                String memSql = loadAndReplacePlaceholders(INIT_MEM_SCHEMA_SQL, serviceRole, authenticatedRole, anonRole, dbUser);
                executeSqlScript(stmt, memSql);
                log.info("Initialized mem schema tables");
            } else {
                log.info("Skipping mem schema tables — nubase.mem.enabled=false");
            }

            // 6. Execute the ai_gateway schema SQL (per-project AI gateway) — only when enabled
            if (aiGatewayEnabled) {
                String gatewaySql = loadAndReplacePlaceholders(INIT_AI_GATEWAY_SCHEMA_SQL, serviceRole, authenticatedRole, anonRole, dbUser);
                executeSqlScript(stmt, gatewaySql);
                log.info("Initialized ai_gateway schema tables");
            } else {
                log.info("Skipping ai_gateway schema tables — nubase.ai-gateway.enabled=false");
            }

            // 7. Execute the assets schema SQL (static asset CDN) — only when enabled
            if (assetsEnabled) {
                String assetsSql = loadAndReplacePlaceholders(INIT_ASSETS_SCHEMA_SQL, serviceRole, authenticatedRole, anonRole, dbUser);
                executeSqlScript(stmt, assetsSql);
                log.info("Initialized assets schema tables");
            } else {
                log.info("Skipping assets schema tables — nubase.assets.enabled=false");
            }

        } catch (SQLException | IOException e) {
            log.error("Failed to initialize Supabase schemas: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to initialize Supabase schemas: " + e.getMessage(), e);
        }
    }

    /**
     * Create a HikariDataSource.
     */
    private HikariDataSource createDataSource(String jdbcUrl, String username, String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(5); // Small connection pool used only for initialization
        config.setConnectionTimeout(30000);
        config.setAutoCommit(true);

        return new HikariDataSource(config);
    }

    /**
     * Load a SQL file and replace placeholders.
     */
    private String loadAndReplacePlaceholders(String resourcePath, String serviceRole, String authenticatedRole, String anonRole, String dbUser) throws IOException {
        ClassPathResource resource = new ClassPathResource(resourcePath);
        String sqlContent;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            sqlContent = reader.lines().collect(Collectors.joining("\n"));
        }

        // Strip feature-gated blocks BEFORE placeholder substitution. When a feature is
        // disabled, its schema is never created — leaving its GRANTs/POLICIES in
        // init_roles.sql would fail with 'schema "..." does not exist'.
        if (!memEnabled) {
            sqlContent = stripBlocks(sqlContent, "MEM");
        }
        if (!aiGatewayEnabled) {
            sqlContent = stripBlocks(sqlContent, "AI_GATEWAY");
        }
        if (!assetsEnabled) {
            sqlContent = stripBlocks(sqlContent, "ASSETS");
        }

        // Replace placeholders
        sqlContent = sqlContent.replace("${service_role}", serviceRole);
        sqlContent = sqlContent.replace("${authenticated_role}", authenticatedRole);
        sqlContent = sqlContent.replace("${anon_role}", anonRole);
        sqlContent = sqlContent.replace("${db_user}", dbUser);
        sqlContent = sqlContent.replace("${fts_config}", resolveFtsConfig());
        sqlContent = sqlContent.replace("${embedding_dimensions}",
                String.valueOf(resolveEmbeddingDimensions()));

        log.debug("Loaded SQL file '{}' ({} chars)", resourcePath, sqlContent.length());
        return sqlContent;
    }

    /**
     * Remove every region between {@code -- MEM-BEGIN} (anchored at line start) and
     * {@code -- MEM-END} (anchored at line start) from the supplied SQL.
     *
     * <p>Used when {@code nubase.mem.enabled=false} — the mem schema is never created in
     * that mode, so any DDL touching {@code mem.*} would fail. Each mem-related stanza in
     * {@code init_roles.sql} is wrapped in these markers; non-mem statements pass through
     * untouched.
     *
     * <p>Markers must be on their own line. Unbalanced markers are detected and refused
     * (defensive: an editor accidentally deleting an END would otherwise silently swallow
     * the rest of the file).
     */
    static String stripMemBlocks(String sql) {
        return stripBlocks(sql, "MEM");
    }

    /**
     * Remove every region between {@code -- <MARKER>-BEGIN} (anchored at line start) and
     * {@code -- <MARKER>-END} (anchored at line start) from the supplied SQL. Used to drop
     * feature-gated DDL (MEM, AI_GATEWAY) when the corresponding schema is not created.
     *
     * <p>Markers must be on their own line. Unbalanced markers are detected and refused.
     */
    static String stripBlocks(String sql, String marker) {
        String begin = "-- " + marker + "-BEGIN";
        String end = "-- " + marker + "-END";
        String[] lines = sql.split("\n", -1);
        StringBuilder out = new StringBuilder(sql.length());
        boolean inside = false;
        for (String line : lines) {
            String trimmed = line.stripLeading();
            boolean isBegin = trimmed.startsWith(begin);
            boolean isEnd = trimmed.startsWith(end);
            if (isBegin) {
                if (inside) {
                    throw new IllegalStateException(
                            "Nested " + begin + " found while a previous block was still open");
                }
                inside = true;
                continue; // drop the marker line
            }
            if (isEnd) {
                if (!inside) {
                    throw new IllegalStateException(
                            end + " without a matching " + begin);
                }
                inside = false;
                continue;
            }
            if (!inside) {
                out.append(line).append('\n');
            }
        }
        if (inside) {
            throw new IllegalStateException(
                    "Unclosed " + begin + " block (missing " + end + ")");
        }
        // Trim the trailing newline we always append.
        if (out.length() > 0 && out.charAt(out.length() - 1) == '\n') {
            out.setLength(out.length() - 1);
        }
        return out.toString();
    }

    /**
     * Validate {@link #memEmbeddingDimensions} before interpolating into the {@code vector(N)}
     * DDL. Bad values (negative, zero, pgvector max-overflow) would either be a typo or a
     * misconfiguration — refuse to silently substitute and corrupt the schema.
     */
    private int resolveEmbeddingDimensions() {
        if (memEmbeddingDimensions < MIN_EMBEDDING_DIMENSIONS
                || memEmbeddingDimensions > MAX_EMBEDDING_DIMENSIONS) {
            throw new IllegalStateException(
                    "Invalid nubase.mem.embedding.dimensions=" + memEmbeddingDimensions
                            + " — must be in [" + MIN_EMBEDDING_DIMENSIONS + ", "
                            + MAX_EMBEDDING_DIMENSIONS + "]. Refusing to interpolate into vector(N) DDL.");
        }
        return memEmbeddingDimensions;
    }

    /**
     * Validate {@link #memFtsConfig} against the whitelist before interpolating into DDL.
     * Falls back to {@code simple} on anything unrecognized (with a warning) so a typo in
     * yml can never inject SQL into the GIN index expression.
     */
    private String resolveFtsConfig() {
        if (memFtsConfig == null || !ALLOWED_FTS_CONFIGS.contains(memFtsConfig.toLowerCase())) {
            if (memFtsConfig != null) {
                log.warn("Unknown nubase.mem.search.fts-config '{}' — falling back to 'simple'. Allowed: {}",
                        memFtsConfig, ALLOWED_FTS_CONFIGS);
            }
            return "simple";
        }
        return memFtsConfig.toLowerCase();
    }

    /**
     * Execute a SQL script.
     */
    private void executeSqlScript(Statement stmt, String sqlScript) throws SQLException {
        List<String> statements = splitSqlStatements(sqlScript);
        log.debug("Executing {} SQL statements", statements.size());

        int executedCount = 0;
        try {
            for (String statement : statements) {
                String trimmed = statement.strip();
                if (!trimmed.isEmpty() && !trimmed.startsWith("--")) {
                    stmt.execute(trimmed);
                    executedCount++;
                }
            }
            log.debug("Executed {} SQL statements successfully", executedCount);
        } catch (SQLException e) {
            log.error("Failed to execute SQL statement (executed {}/{}): {}",
                    executedCount, statements.size(), e.getMessage());
            throw new SQLException("SQL execution failed at statement " + (executedCount + 1) + ": " + e.getMessage(), e);
        }
    }

    /**
     * Split a SQL script into individual statements.
     * Handles PostgreSQL function definitions ($$-delimited blocks).
     */
    private List<String> splitSqlStatements(String sqlScript) {
        List<String> statements = new ArrayList<>();
        StringBuilder currentStatement = new StringBuilder();
        boolean inDollarQuote = false;

        String[] lines = sqlScript.split("\n");

        for (String line : lines) {
            String trimmedLine = line.strip();

            // Skip blank lines
            if (trimmedLine.isEmpty()) {
                continue;
            }

            // Skip comment lines
            if (trimmedLine.startsWith("--")) {
                continue;
            }

            // Check for the dollar-quote delimiter (PostgreSQL function syntax)
            if (trimmedLine.contains("$$")) {
                inDollarQuote = !inDollarQuote;
            }

            currentStatement.append(line).append("\n");

            // If not inside a dollar-quoted block and the line ends with a semicolon, the statement is complete
            if (!inDollarQuote && trimmedLine.endsWith(";")) {
                statements.add(currentStatement.toString().strip());
                currentStatement = new StringBuilder();
            }
        }

        // Append any remaining statement
        if (currentStatement.length() > 0) {
            String remaining = currentStatement.toString().strip();
            if (!remaining.isEmpty()) {
                statements.add(remaining);
            }
        }

        return statements;
    }

    /**
     * Generate a random password.
     */
    private String generateRandomPassword() {
        SecureRandom random = new SecureRandom();
        byte[] passwordBytes = new byte[24]; // 192 bits
        random.nextBytes(passwordBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(passwordBytes);
    }

    /**
     * Generate a JWT secret.
     */
    private String generateJwtSecret() {
        SecureRandom random = new SecureRandom();
        byte[] secretBytes = new byte[64]; // 512 bits
        random.nextBytes(secretBytes);
        return Base64.getEncoder().encodeToString(secretBytes);
    }

    /**
     * Generate an API key (JWT token).
     */
    private String generateApiKey(SecretKey jwtSecretKey, String role, String appCode) {
        Date now = new Date();
        Date expiration = new Date(now.getTime() + 315360000000L); // 10 years

        return Jwts.builder()
                .claim("ref", appCode)
                .claim("role", role)
                .claim("iss", "nubase")
                .issuedAt(now)
                .expiration(expiration)
                .signWith(jwtSecretKey)
                .compact();
    }

    /**
     * Build a DatabaseConfig object.
     */
    private DatabaseConfig buildDatabaseConfig(
            String dbKey, String dbName, String appCode, String appName, String description,
            String jdbcUrl, String dbUser, String dbPassword,
            String jwtSecret, String serviceRoleToken, String authenticatedToken,
            String serviceRole, String authenticatedRole, String anonRole,
            Integer poolSize, String createdBy) {

        return DatabaseConfig.builder()
                .dbKey(dbKey)
                .dbName(dbName)
                .description(description != null ? description : "Auto-created database for " + appCode)
                .jdbcUrl(jdbcUrl)
                .dbUser(dbUser)
                .dbPasswordEncrypted(dbPassword) // Will be encrypted by repository
                .dbSchemas(memEnabled
                        ? Arrays.asList("public", "auth", "storage", "mem")
                        : Arrays.asList("public", "auth", "storage"))
                .dbAnonRole(anonRole)
                .dbMaxRows(1000)
                .dbExtraSearchPath(memEnabled
                        ? Arrays.asList("auth", "storage", "mem")
                        : Arrays.asList("auth", "storage"))
                .jwtSecretEncrypted(jwtSecret) // Will be encrypted by repository
                .jwtSecretIsBase64(false)
                .jwtAudience("authenticated")
                .jwtRoleClaimKey(".role")
                .poolSize(poolSize != null ? poolSize : 10)
                .poolTimeoutMs(10000)
                .poolMaxLifetimeMs(1800000)
                .poolConnectionTimeoutMs(30000)
                .poolIdleTimeoutMs(300000)
                .poolMinimumIdle(0)
                .enabled(true)
                .createdBy(createdBy != null ? createdBy : "system")
                .appCode(appCode)
                .appName(appName)
                .schemaName("public") // Default schema for user business data
                .jwtSecret(jwtSecret)
                .serviceRoleToken(serviceRoleToken)
                .authenticatedToken(authenticatedToken)
                .build();
    }

    /**
     * Build a detailed error message.
     */
    private String getDetailedErrorMessage(Exception e, List<String> executedSteps) {
        StringBuilder details = new StringBuilder();
        details.append("Error: ").append(e.getMessage()).append("\n\n");

        if (!executedSteps.isEmpty()) {
            details.append("Successfully completed steps:\n");
            for (String step : executedSteps) {
                details.append("  - ").append(step).append("\n");
            }
        } else {
            details.append("No steps were completed successfully.\n");
        }

        if (e.getCause() != null) {
            details.append("\nRoot cause: ").append(e.getCause().getMessage());
        }

        return details.toString();
    }
}
