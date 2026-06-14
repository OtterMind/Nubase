package ai.nubase.auth.controller;

import ai.nubase.auth.dto.request.admin.ExecuteSqlRequest;
import ai.nubase.auth.dto.request.admin.ExportRlsPoliciesRequest;
import ai.nubase.auth.dto.request.admin.ExportSchemaDdlRequest;
import ai.nubase.auth.dto.request.admin.InitDatabaseRequest;
import ai.nubase.auth.dto.response.UserResponse;
import ai.nubase.auth.dto.response.admin.ExportRlsPoliciesResponse;
import ai.nubase.auth.dto.response.admin.ExportSchemaDdlResponse;
import ai.nubase.auth.dto.response.admin.InitDatabaseResponse;
import ai.nubase.auth.dto.response.admin.SqlExecutionResponse;
import ai.nubase.auth.service.*;
import ai.nubase.auth.annotation.RequireServiceRole;
import ai.nubase.common.config.oauth.OAuthProperties;
import ai.nubase.auth.dto.request.admin.CreateUserRequest;
import ai.nubase.auth.dto.request.admin.InitSchemaRequest;
import ai.nubase.auth.dto.request.admin.InviteUserRequest;
import ai.nubase.auth.dto.request.admin.UpdateUserByIdRequest;
import ai.nubase.auth.dto.response.admin.AdminUserResponse;
import ai.nubase.auth.dto.response.admin.InitSchemaResponse;
import ai.nubase.auth.dto.response.admin.ListUsersResponse;
import ai.nubase.auth.dto.request.admin.AddMemberRequest;
import ai.nubase.auth.dto.request.admin.SaveSnippetRequest;
import ai.nubase.auth.dto.request.admin.UpdatePlatformUserRequest;
import ai.nubase.auth.dto.request.admin.UpdateProjectRequest;
import ai.nubase.auth.dto.response.admin.PlatformUserResponse;
import ai.nubase.auth.dto.response.admin.ProjectKeysResponse;
import ai.nubase.auth.dto.response.admin.ProjectMemberResponse;
import ai.nubase.auth.dto.response.admin.ProjectSummaryResponse;
import ai.nubase.auth.dto.response.admin.SqlHistoryEntry;
import ai.nubase.auth.dto.response.admin.SqlSnippetResponse;
import ai.nubase.auth.service.PlatformAuthService;
import ai.nubase.metadata.entity.PlatformUser;
import ai.nubase.metadata.entity.PlatformUserProject;
import ai.nubase.metadata.entity.SqlSnippet;
import ai.nubase.metadata.repository.PlatformUserProjectRepository;
import ai.nubase.metadata.repository.PlatformUserRepository;
import ai.nubase.metadata.repository.SqlExecutionRecordRepository;
import ai.nubase.metadata.repository.SqlSnippetRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import ai.nubase.auth.exception.EmailAlreadyExistsException;
import ai.nubase.auth.exception.UserNotFoundException;
import ai.nubase.common.context.MultiTenancyContext;
import ai.nubase.postgrest.multidb.DatabaseConfig;
import cn.hutool.json.JSONUtil;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Admin Controller for user management operations.
 * All endpoints require service_role key authentication.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/auth/v1")// Class-level AOP verification
@Slf4j
public class AdminController {

    private final AdminService adminService;
    private final SqlExecutionService sqlExecutionService;
    private final SchemaInitService schemaInitService;
    private final DatabaseInitService databaseInitService;
    private final SchemaDdlExportService schemaDdlExportService;
    private final RlsPolicyExportService rlsPolicyExportService;
    private final ai.nubase.postgrest.multidb.DatabaseConfigRepository databaseConfigRepository;
    private final PlatformUserRepository platformUserRepository;
    private final PlatformUserProjectRepository platformUserProjectRepository;
    private final SqlSnippetRepository sqlSnippetRepository;
    private final SqlExecutionRecordRepository sqlExecutionRecordRepository;

    /**
     * POST /auth/v1/admin/users - Create user
     */
    @RequireServiceRole
    @PostMapping("/admin/users")
    public ResponseEntity<AdminUserResponse> createUser(@Valid @RequestBody CreateUserRequest request) {
        try {
            request.setEmailConfirm(true);
            UserResponse user = adminService.createUser(request);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(AdminUserResponse.builder().user(user).build());
        } catch (EmailAlreadyExistsException e) {
            log.warn("Failed to create user - email exists: {}", request.getEmail());
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(AdminUserResponse.builder().error(e.getMessage()).build());
        } catch (Exception e) {
            log.error("Failed to create user: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(AdminUserResponse.builder().error(e.getMessage()).build());
        }
    }

    /**
     * GET /auth/v1/admin/users - List users with optional keyword search
     *
     * @param page    Page number (default: 1)
     * @param perPage Users per page (default: 50, max: 100)
     * @param keyword Optional search keyword (searches email, phone, role, and id)
     */
    @RequireServiceRole
    @GetMapping("/admin/users")
    public ResponseEntity<ListUsersResponse> listUsers(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "per_page", defaultValue = "50") int perPage,
            @RequestParam(required = false) String keyword) {

        ListUsersResponse response = adminService.listUsers(page, perPage, keyword);
        return ResponseEntity.ok(response);
    }

    /**
     * GET /auth/v1/admin/users/{userId} - Get user details
     */
    @RequireServiceRole
    @GetMapping("/admin/users/{userId}")
    public ResponseEntity<AdminUserResponse> getUserById(@PathVariable UUID userId) {
        try {
            UserResponse user = adminService.getUserById(userId);
            return ResponseEntity.ok(AdminUserResponse.builder().user(user).build());
        } catch (UserNotFoundException e) {
            log.warn("User not found: {}", userId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(AdminUserResponse.builder().error(e.getMessage()).build());
        } catch (Exception e) {
            log.error("Failed to get user {}: {}", userId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(AdminUserResponse.builder().error("Internal server error").build());
        }
    }

    /**
     * PUT /auth/v1/admin/users/{userId} - Update user
     */
    @RequireServiceRole
    @PutMapping("/admin/users/{userId}")
    public ResponseEntity<AdminUserResponse> updateUser(
            @PathVariable UUID userId,
            @Valid @RequestBody UpdateUserByIdRequest request) {

        try {
            UserResponse user = adminService.updateUserById(userId, request);
            return ResponseEntity.ok(AdminUserResponse.builder().user(user).build());
        } catch (UserNotFoundException e) {
            log.warn("User not found for update: {}", userId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(AdminUserResponse.builder().error(e.getMessage()).build());
        } catch (EmailAlreadyExistsException e) {
            log.warn("Failed to update user {} - email exists", userId);
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(AdminUserResponse.builder().error(e.getMessage()).build());
        } catch (Exception e) {
            log.error("Failed to update user {}: {}", userId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(AdminUserResponse.builder().error(e.getMessage()).build());
        }
    }

    /**
     * DELETE /auth/v1/admin/users/{userId} - Delete user
     */
    @RequireServiceRole
    @DeleteMapping("/admin/users/{userId}")
    public ResponseEntity<AdminUserResponse> deleteUser(
            @PathVariable UUID userId,
            @RequestParam(name = "should_soft_delete", defaultValue = "false") boolean shouldSoftDelete) {

        try {
            UserResponse user = adminService.deleteUserById(userId, shouldSoftDelete);
            return ResponseEntity.ok(AdminUserResponse.builder().user(user).build());
        } catch (UserNotFoundException e) {
            log.warn("User not found for deletion: {}", userId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(AdminUserResponse.builder().error(e.getMessage()).build());
        } catch (Exception e) {
            log.error("Failed to delete user {}: {}", userId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(AdminUserResponse.builder().error("Internal server error").build());
        }
    }

    /**
     * POST /auth/v1/invite - Invite user
     */
    @RequireServiceRole
    @PostMapping("/invite")
    public ResponseEntity<AdminUserResponse> inviteUser(@Valid @RequestBody InviteUserRequest request) {
        try {
            UserResponse user = adminService.inviteUserByEmail(request);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(AdminUserResponse.builder().user(user).build());
        } catch (EmailAlreadyExistsException e) {
            log.warn("Failed to invite user - already registered: {}", request.getEmail());
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(AdminUserResponse.builder().error(e.getMessage()).build());
        } catch (Exception e) {
            log.error("Failed to invite user: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(AdminUserResponse.builder().error(e.getMessage()).build());
        }
    }

    /**
     * POST /auth/v1/admin/sql/execute - Execute SQL statement
     * <p>
     * WARNING: This is a powerful and dangerous operation.
     * - Only accessible with service_role authentication
     * - Can execute ANY SQL statement (DDL, DML, queries)
     * - Use with extreme caution
     * <p>
     * Examples:
     * - CREATE TABLE custom_table (id UUID PRIMARY KEY, name VARCHAR(100))
     * - ALTER TABLE users ADD COLUMN nickname VARCHAR(100)
     * - SELECT * FROM users WHERE role = 'admin'
     * - INSERT INTO custom_table VALUES (gen_random_uuid(), 'test')
     */
    @RequireServiceRole
    @PostMapping("/admin/sql/execute")
    public ResponseEntity<SqlExecutionResponse> executeSql(@Valid @RequestBody ExecuteSqlRequest request) {
        SqlExecutionResponse response = sqlExecutionService.executeSql(request);
        if (response.isSuccess()) {
            return ResponseEntity.ok(response);
        } else {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }
    }

    /**
     * POST /auth/v1/admin/init/schema - Initialize a new tenant schema
     * <p>
     * WARNING: This is a powerful operation that creates database schemas.
     * - Only accessible with service_role authentication
     * - Creates schema, roles, and auth tables from SQL templates
     * - Use with extreme caution
     * <p>
     * Example request body:
     * {
     * "schema": "tenant1",
     * "adminRole": "tenant1_admin",  // optional, defaults to {schema}_admin
     * "userRole": "tenant1_authenticated",  // optional, defaults to {schema}_authenticated
     * "dbConnectionUser": "postgres"  // optional
     * }
     */
    @RequireServiceRole
    @PostMapping("/admin/init/schema")
    public ResponseEntity<InitSchemaResponse> initSchema(@Valid @RequestBody InitSchemaRequest request) {
        log.warn("Schema initialization requested for schema: {}", request.getSchema());

        InitSchemaResponse response = schemaInitService.initSchema(request);

        if (response.isSuccess()) {
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } else {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }
    }

    /**
     * POST /auth/v1/admin/init/mem-schema — backfill the {@code mem} schema (and entities table,
     * grants, RLS policies) onto already-initialized tenants.
     *
     * <p>Idempotent: re-running on a tenant that already has the latest mem schema is a no-op.
     * Pass {@code dbKey} to target a single tenant; omit it to migrate every {@code INITIALIZED}
     * tenant.
     *
     * <p>Returns a per-tenant status map: {@code {dbKey: "OK" | "ERROR: …" | "SKIPPED: …"}}.
     */
    @RequireServiceRole
    @PostMapping("/admin/init/mem-schema")
    public ResponseEntity<Map<String, Object>> initMemSchema(
            @RequestParam(required = false) String dbKey) {
        if (dbKey != null && !dbKey.isBlank()) {
            log.warn("Mem-schema migration requested for tenant: {}", dbKey);
            try {
                List<String> steps = databaseInitService.initializeMemSchemaForExistingTenant(dbKey);
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "dbKey", dbKey,
                        "steps", steps));
            } catch (Exception e) {
                log.error("Mem-schema migration failed for {}: {}", dbKey, e.getMessage(), e);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                        "success", false,
                        "dbKey", dbKey,
                        "error", e.getMessage()));
            }
        }
        log.warn("Mem-schema migration requested for ALL initialized tenants");
        Map<String, String> results = databaseInitService.initializeMemSchemaForAllTenants();
        long okCount = results.values().stream().filter("OK"::equals).count();
        return ResponseEntity.ok(Map.of(
                "success", true,
                "total", results.size(),
                "ok", okCount,
                "results", results));
    }

    /**
     * POST /auth/v1/admin/init/assets-schema — backfill the {@code assets} schema (static
     * asset CDN: files + settings tables, grants, RLS policies) onto already-initialized
     * tenants.
     *
     * <p>Idempotent: re-running on a tenant that already has the assets schema is a no-op.
     * Pass {@code dbKey} to target a single tenant; omit it to migrate every {@code INITIALIZED}
     * tenant.
     *
     * <p>Returns a per-tenant status map: {@code {dbKey: "OK" | "ERROR: …" | "SKIPPED: …"}}.
     */
    @RequireServiceRole
    @PostMapping("/admin/init/assets-schema")
    public ResponseEntity<Map<String, Object>> initAssetsSchema(
            @RequestParam(required = false) String dbKey) {
        if (dbKey != null && !dbKey.isBlank()) {
            log.warn("Assets-schema migration requested for tenant: {}", dbKey);
            try {
                List<String> steps = databaseInitService.initializeAssetsSchemaForExistingTenant(dbKey);
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "dbKey", dbKey,
                        "steps", steps));
            } catch (Exception e) {
                log.error("Assets-schema migration failed for {}: {}", dbKey, e.getMessage(), e);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                        "success", false,
                        "dbKey", dbKey,
                        "error", e.getMessage()));
            }
        }
        log.warn("Assets-schema migration requested for ALL initialized tenants");
        Map<String, String> results = databaseInitService.initializeAssetsSchemaForAllTenants();
        long okCount = results.values().stream().filter("OK"::equals).count();
        return ResponseEntity.ok(Map.of(
                "success", true,
                "total", results.size(),
                "ok", okCount,
                "results", results));
    }

    /**
     * POST /auth/v1/admin/init/database - Initialize database with Supabase schema structure
     * <p>
     * WARNING: This is a powerful operation that creates physical databases.
     * - Only accessible with service_role authentication
     * - Creates a new PostgreSQL database with dedicated user
     * - Initializes Supabase standard schemas: auth, storage, public
     * - Generates JWT secret and API keys
     * - Saves configuration to database_configs
     * - Use with extreme caution
     * <p>
     * Complete Flow:
     * 1. Create new PostgreSQL database
     * 2. Create dedicated database user with random password
     * 3. Grant permissions
     * 4. Initialize Supabase schemas (auth, storage, public)
     * 5. Create auth and storage tables
     * 6. Setup roles and RLS policies
     * 7. Generate JWT secret and API keys
     * 8. Save to database_configs
     * <p>
     * Supabase Standard Architecture:
     * - auth schema: authentication tables (users, sessions, refresh_tokens, identities)
     * - storage schema: file storage tables (buckets, objects)
     * - public schema: user business tables (default PostgreSQL schema)
     * <p>
     * Example request body:
     * {
     * "dbKey": "tenant1",                    // required - unique database identifier
     * "dbName": "tenant1_db",                // required - physical database name
     * "appCode": "tenant1",                  // required - application/tenant code
     * "appName": "Tenant 1 Application",     // optional - display name
     * "description": "Database for Tenant 1", // optional
     * "serviceRole": "service_role",         // optional, defaults to "service_role"
     * "authenticatedRole": "authenticated",  // optional, defaults to "authenticated"
     * "anonRole": "anon",                    // optional, defaults to "anon"
     * "poolSize": 10,                        // optional, defaults to 10
     * "createdBy": "admin"                   // optional
     * }
     * <p>
     * Note:
     * - Uses metadataJdbcTemplate with superuser privileges to create database and user
     * - PostgreSQL host and port are configured in application.yml (pgrst.multidb.postgres.host/port)
     * - No need to provide admin credentials or connection details
     */
    @RequireServiceRole
    @PostMapping("/admin/init/database")
    public ResponseEntity<InitDatabaseResponse> initDatabase(@Valid @RequestBody InitDatabaseRequest request,
                                                             HttpServletRequest httpRequest) {
        log.warn("Database initialization requested with Supabase schema structure");

        InitDatabaseResponse response = databaseInitService.initDatabase(request);

        if (response.isSuccess()) {
            recordProjectOwnership(httpRequest, request.getDbKey());
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } else {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }
    }

    /**
     * POST /auth/v1/admin/init/database_config - Create database configuration entry
     * @param request
     * @return
     */
    @RequireServiceRole
    @PostMapping("/admin/init/database_config")
    public ResponseEntity<InitDatabaseResponse> initDatabaseConfig(@Valid @RequestBody InitDatabaseRequest request,
                                                                   HttpServletRequest httpRequest) {
        log.warn("Database config initialization");
        InitDatabaseResponse response = databaseInitService.createDatabaseConfig(request);

        if (response.isSuccess()) {
            recordProjectOwnership(httpRequest, request.getDbKey());
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } else {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }
    }

    /**
     * POST /auth/v1/admin/schema/export-ddl - Export schema DDL statements
     * <p>
     * Exports complete DDL statements for all tables in a schema, including:
     * - Table structure with column definitions and data types
     * - Column comments
     * - Primary keys
     * - Foreign keys
     * - Unique constraints
     * - Check constraints
     * - Indexes
     * <p>
     * Example request body:
     * {
     * "schemaName": "auth",                    // required - schema name to export
     * "includeDropStatements": false,          // optional - include DROP TABLE statements (default: false)
     * "includeIfNotExists": true,              // optional - include IF NOT EXISTS clause (default: true)
     * "tableNames": "users,sessions"           // optional - export specific tables only (comma-separated)
     * }
     * <p>
     * Example response:
     * {
     * "success": true,
     * "schemaName": "auth",
     * "tableDdls": {
     * "users": "CREATE TABLE IF NOT EXISTS auth.users (\n  id UUID PRIMARY KEY...",
     * "sessions": "CREATE TABLE IF NOT EXISTS auth.sessions (\n  id UUID PRIMARY KEY..."
     * },
     * "tableOrder": ["users", "sessions"],
     * "tableCount": 2,
     * "executionTimeMs": 145
     * }
     */
    @RequireServiceRole
    @PostMapping("/admin/schema/export-ddl")
    public ResponseEntity<ExportSchemaDdlResponse> exportSchemaDdl(@Valid @RequestBody ExportSchemaDdlRequest request) {
        log.info("Schema DDL export requested for schema: {}", request.getSchemaName());

        ExportSchemaDdlResponse response = schemaDdlExportService.exportSchemaDdl(request);

        if (response.isSuccess()) {
            return ResponseEntity.ok(response);
        } else {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }
    }

    /**
     * POST /auth/v1/admin/rls/export-policies - Export RLS (Row Level Security) policies
     * <p>
     * Exports all RLS policies for tables in the database, including:
     * - ALTER TABLE ... ENABLE ROW LEVEL SECURITY statements
     * - CREATE POLICY statements with USING and WITH CHECK clauses
     * - Role assignments (TO clause)
     * - Command types (FOR SELECT/INSERT/UPDATE/DELETE/ALL)
     * <p>
     * Example request body:
     * {
     * "schemaName": "auth",                    // optional - specific schema (null = all schemas)
     * "tableNames": "users,sessions",          // optional - specific tables (comma-separated)
     * "includeDropStatements": false,          // optional - include DROP POLICY statements (default: false)
     * "groupBySchema": true                    // optional - group policies by schema (default: true)
     * }
     * <p>
     * Example response (single schema):
     * {
     * "success": true,
     * "schemaName": "auth",
     * "rlsPolicySql": "ALTER TABLE auth.users ENABLE ROW LEVEL SECURITY;\n\nCREATE POLICY user_select_own ON auth.users FOR SELECT\n    USING ((auth.uid() = id));\n\n...",
     * "tablesWithRls": [
     * {
     * "schemaName": "auth",
     * "tableName": "users",
     * "policyCount": 3,
     * "rlsEnabled": true,
     * "rlsForced": false,
     * "policyNames": ["user_select_own", "user_update_own", "admin_all"]
     * }
     * ],
     * "tableCount": 4,
     * "policyCount": 12,
     * "executionTimeMs": 85
     * }
     * <p>
     * Example response (grouped by schema):
     * {
     * "success": true,
     * "schemaName": "all",
     * "rlsPoliciesBySchema": {
     * "auth": "ALTER TABLE auth.users ENABLE ROW LEVEL SECURITY;\n...",
     * "storage": "ALTER TABLE storage.objects ENABLE ROW LEVEL SECURITY;\n...",
     * "public": "ALTER TABLE public.profiles ENABLE ROW LEVEL SECURITY;\n..."
     * },
     * "tablesWithRls": [...],
     * "tableCount": 8,
     * "policyCount": 25,
     * "executionTimeMs": 145
     * }
     * <p>
     * Use cases:
     * - Export RLS policies for backup
     * - Migrate RLS policies to another database
     * - Documentation and auditing
     * - Compare RLS configurations across environments
     */
    @RequireServiceRole
    @PostMapping("/admin/rls/export-policies")
    public ResponseEntity<ExportRlsPoliciesResponse> exportRlsPolicies(@Valid @RequestBody ExportRlsPoliciesRequest request) {
        log.info("RLS policies export requested for schema: {}",
                request.getSchemaName() != null ? request.getSchemaName() : "all");

        ExportRlsPoliciesResponse response = rlsPolicyExportService.exportRlsPolicies(request);

        if (response.isSuccess()) {
            return ResponseEntity.ok(response);
        } else {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }
    }

    /**
     * GET /auth/v1/admin/projects - List tenant projects visible to the caller.
     *
     * Cross-tenant endpoint authenticated by {@link ai.nubase.common.multitenancy.AdminInitAuthFilter}.
     * Two auth paths supported:
     *  - Metadata service-role-key (string equality) → no platformUserId stashed → treated as super-admin
     *  - Platform JWT → filter stashes platformUserId; we look up the user's role and scope the result
     *
     * Scoping:
     *  - super_admin or metadata key  → returns every enabled tenant
     *  - regular user                 → returns only tenants joined via platform_user_projects
     */
    @GetMapping("/admin/projects")
    public ResponseEntity<List<ProjectSummaryResponse>> listProjects(HttpServletRequest request) {
        UUID platformUserId = (UUID) request.getAttribute("platformUserId");
        List<ai.nubase.postgrest.multidb.DatabaseConfig> configs;
        if (platformUserId == null) {
            configs = databaseConfigRepository.findAllEnabled();
        } else {
            PlatformUser user = platformUserRepository.findById(platformUserId).orElse(null);
            if (user != null && PlatformAuthService.PLATFORM_ROLE_SUPER_ADMIN.equalsIgnoreCase(user.getRole())) {
                configs = databaseConfigRepository.findAllEnabled();
            } else {
                configs = databaseConfigRepository.findEnabledByUserId(platformUserId);
            }
        }
        List<ProjectSummaryResponse> projects = configs.stream()
                .map(c -> ProjectSummaryResponse.builder()
                        .ref(c.getAppCode() != null ? c.getAppCode() : c.getDbKey())
                        .name(c.getAppName() != null ? c.getAppName() : c.getDbName())
                        .description(c.getDescription())
                        .schemaName(c.getSchemaName())
                        .initStatus(c.getInitStatus())
                        .healthStatus(c.getHealthStatus())
                        .enabled(c.getEnabled())
                        .createdAt(c.getCreatedAt())
                        .updatedAt(c.getUpdatedAt())
                        .apikey(c.getServiceRoleToken())
                        .build())
                .toList();
        return ResponseEntity.ok(projects);
    }

    /**
     * POST /auth/v1/admin/projects/{ref}/provision
     *
     * Drive phase 2 of project initialisation for a config that is currently in
     * PENDING_INIT / INIT_FAILED. Authenticated via AdminInitAuthFilter (platform JWT
     * or metadata service-role-key). Access rules:
     *   - metadata key → allowed
     *   - super_admin → allowed
     *   - regular user → must own the project (via platform_user_projects)
     */
    @PostMapping("/admin/projects/{ref}/provision")
    public ResponseEntity<?> provisionProject(@PathVariable("ref") String ref,
                                              HttpServletRequest request) {
        DatabaseConfig config = databaseConfigRepository.findByAppCode(ref);
        if (config == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(java.util.Map.of("error", "project_not_found", "ref", ref));
        }

        UUID platformUserId = (UUID) request.getAttribute("platformUserId");
        if (platformUserId != null) {
            PlatformUser user = platformUserRepository.findById(platformUserId).orElse(null);
            boolean superAdmin = user != null
                    && PlatformAuthService.PLATFORM_ROLE_SUPER_ADMIN.equalsIgnoreCase(user.getRole());
            if (!superAdmin) {
                boolean owns = platformUserProjectRepository.existsByUserIdAndDbKey(
                        platformUserId, config.getDbKey());
                if (!owns) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN)
                            .body(java.util.Map.of("error", "not_project_owner"));
                }
            }
        }

        log.info("Provisioning project ref={} (dbKey={})", ref, config.getDbKey());
        InitDatabaseResponse response = databaseInitService.initializePhysicalDatabase(config.getDbKey());

        // Make sure the caller (if a platform user) is recorded as the owner of the freshly
        // provisioned project, since pre-existing PENDING_INIT projects predate ownership tracking.
        if (response.isSuccess()) {
            recordProjectOwnership(request, config.getDbKey());
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    // ==================== Project Members ====================

    /**
     * GET /auth/v1/admin/projects/{ref}/members — list a project's members.
     * Visible to super_admin, the caller if they are already a member, or
     * (in metadata-key mode) anyone with the metadata service-role key.
     */
    @GetMapping("/admin/projects/{ref}/members")
    public ResponseEntity<?> listMembers(@PathVariable("ref") String ref, HttpServletRequest request) {
        DatabaseConfig config = databaseConfigRepository.findByAppCode(ref);
        if (config == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "project_not_found", "ref", ref));
        }
        ResponseEntity<?> denied = ensureCanManageMembers(request, config.getDbKey(), true /* readOnly */);
        if (denied != null) return denied;

        List<PlatformUserProject> mappings = platformUserProjectRepository.findByDbKey(config.getDbKey());
        Map<UUID, PlatformUser> byId = platformUserRepository
                .findAllById(mappings.stream().map(PlatformUserProject::getUserId).toList())
                .stream().collect(java.util.stream.Collectors.toMap(PlatformUser::getId, u -> u));
        List<ProjectMemberResponse> out = mappings.stream()
                .map(m -> {
                    PlatformUser u = byId.get(m.getUserId());
                    return ProjectMemberResponse.builder()
                            .userId(m.getUserId().toString())
                            .email(u != null ? u.getEmail() : null)
                            .fullName(u != null ? u.getFullName() : null)
                            .role(m.getRole())
                            .addedAt(m.getCreatedAt())
                            .build();
                })
                .toList();
        return ResponseEntity.ok(out);
    }

    /**
     * POST /auth/v1/admin/projects/{ref}/members — add a platform user to a project by email.
     * Only super_admin or an existing owner can add members.
     */
    @PostMapping("/admin/projects/{ref}/members")
    @Transactional("metadataTransactionManager")
    public ResponseEntity<?> addMember(@PathVariable("ref") String ref,
                                       @Valid @RequestBody AddMemberRequest body,
                                       HttpServletRequest request) {
        DatabaseConfig config = databaseConfigRepository.findByAppCode(ref);
        if (config == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "project_not_found"));
        }
        ResponseEntity<?> denied = ensureCanManageMembers(request, config.getDbKey(), false);
        if (denied != null) return denied;

        PlatformUser invitee = platformUserRepository
                .findByEmailIgnoreCase(body.getEmail().trim())
                .orElse(null);
        if (invitee == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "user_not_found", "hint", "User must sign up via /platform/signup first."));
        }
        String role = body.getRole() != null && !body.getRole().isBlank() ? body.getRole() : "member";
        if (platformUserProjectRepository.existsByUserIdAndDbKey(invitee.getId(), config.getDbKey())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "already_member"));
        }
        platformUserProjectRepository.save(PlatformUserProject.builder()
                .userId(invitee.getId())
                .dbKey(config.getDbKey())
                .role(role)
                .build());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ProjectMemberResponse.builder()
                        .userId(invitee.getId().toString())
                        .email(invitee.getEmail())
                        .fullName(invitee.getFullName())
                        .role(role)
                        .build());
    }

    /**
     * DELETE /auth/v1/admin/projects/{ref}/members/{userId} — remove a member.
     * Only super_admin or an existing owner can remove (you can also remove yourself).
     */
    @DeleteMapping("/admin/projects/{ref}/members/{userId}")
    @Transactional("metadataTransactionManager")
    public ResponseEntity<?> removeMember(@PathVariable("ref") String ref,
                                          @PathVariable("userId") UUID userId,
                                          HttpServletRequest request) {
        DatabaseConfig config = databaseConfigRepository.findByAppCode(ref);
        if (config == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "project_not_found"));
        }
        UUID callerId = (UUID) request.getAttribute("platformUserId");
        boolean removingSelf = callerId != null && callerId.equals(userId);
        if (!removingSelf) {
            ResponseEntity<?> denied = ensureCanManageMembers(request, config.getDbKey(), false);
            if (denied != null) return denied;
        }
        platformUserProjectRepository.deleteByUserIdAndDbKey(userId, config.getDbKey());
        return ResponseEntity.noContent().build();
    }

    /**
     * Check whether the caller may manage members of the project identified by {@code dbKey}.
     * Returns null on success or a ready-to-send error response otherwise.
     *
     * @param readOnly when true, owners AND non-owner members are allowed (for list view).
     */
    private ResponseEntity<?> ensureCanManageMembers(HttpServletRequest request, String dbKey, boolean readOnly) {
        UUID callerId = (UUID) request.getAttribute("platformUserId");
        if (callerId == null) {
            // Metadata service-role-key path — trusted, no further check.
            return null;
        }
        PlatformUser caller = platformUserRepository.findById(callerId).orElse(null);
        if (caller != null && PlatformAuthService.PLATFORM_ROLE_SUPER_ADMIN.equalsIgnoreCase(caller.getRole())) {
            return null;
        }
        PlatformUserProject mapping = platformUserProjectRepository
                .findByUserIdAndDbKey(callerId, dbKey)
                .orElse(null);
        if (mapping == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "not_a_member"));
        }
        if (readOnly) return null;
        if (!"owner".equalsIgnoreCase(mapping.getRole())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "not_project_owner"));
        }
        return null;
    }

    // ==================== Platform User Admin (super_admin only) ====================

    /**
     * GET /auth/v1/admin/platform/users — list all platform users. super_admin only.
     */
    @GetMapping("/admin/platform/users")
    public ResponseEntity<?> listPlatformUsers(HttpServletRequest request) {
        ResponseEntity<?> denied = ensureSuperAdmin(request);
        if (denied != null) return denied;
        List<PlatformUserResponse> users = platformUserRepository.findAll().stream()
                .map(u -> PlatformUserResponse.builder()
                        .id(u.getId().toString())
                        .email(u.getEmail())
                        .fullName(u.getFullName())
                        .role(u.getRole())
                        .isActive(u.getIsActive())
                        .lastSignedInAt(u.getLastSignedInAt())
                        .createdAt(u.getCreatedAt())
                        .build())
                .toList();
        return ResponseEntity.ok(users);
    }

    /**
     * PUT /auth/v1/admin/platform/users/{id} — change role / active flag. super_admin only.
     */
    @PutMapping("/admin/platform/users/{id}")
    @Transactional("metadataTransactionManager")
    public ResponseEntity<?> updatePlatformUser(@PathVariable("id") UUID id,
                                                @RequestBody UpdatePlatformUserRequest body,
                                                HttpServletRequest request) {
        ResponseEntity<?> denied = ensureSuperAdmin(request);
        if (denied != null) return denied;
        PlatformUser u = platformUserRepository.findById(id).orElse(null);
        if (u == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "user_not_found"));
        }
        if (body.getRole() != null && !body.getRole().isBlank()) u.setRole(body.getRole());
        if (body.getIsActive() != null) u.setIsActive(body.getIsActive());
        platformUserRepository.save(u);
        return ResponseEntity.ok(PlatformUserResponse.builder()
                .id(u.getId().toString())
                .email(u.getEmail())
                .fullName(u.getFullName())
                .role(u.getRole())
                .isActive(u.getIsActive())
                .lastSignedInAt(u.getLastSignedInAt())
                .createdAt(u.getCreatedAt())
                .build());
    }

    /**
     * DELETE /auth/v1/admin/platform/users/{id} — hard delete. super_admin only.
     */
    @DeleteMapping("/admin/platform/users/{id}")
    @Transactional("metadataTransactionManager")
    public ResponseEntity<?> deletePlatformUser(@PathVariable("id") UUID id, HttpServletRequest request) {
        ResponseEntity<?> denied = ensureSuperAdmin(request);
        if (denied != null) return denied;
        UUID callerId = (UUID) request.getAttribute("platformUserId");
        if (callerId != null && callerId.equals(id)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "cannot_delete_self"));
        }
        if (!platformUserRepository.existsById(id)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "user_not_found"));
        }
        platformUserRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<?> ensureSuperAdmin(HttpServletRequest request) {
        UUID callerId = (UUID) request.getAttribute("platformUserId");
        if (callerId == null) return null; // metadata key → trusted
        PlatformUser caller = platformUserRepository.findById(callerId).orElse(null);
        if (caller == null
                || !PlatformAuthService.PLATFORM_ROLE_SUPER_ADMIN.equalsIgnoreCase(caller.getRole())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "super_admin_required"));
        }
        return null;
    }

    // ==================== SQL snippets & history ====================

    /**
     * GET /auth/v1/admin/projects/{ref}/snippets — list the calling platform user's saved
     * snippets for this project. Metadata-key callers see no snippets (no user context).
     */
    @GetMapping("/admin/projects/{ref}/snippets")
    public ResponseEntity<?> listSnippets(@PathVariable("ref") String ref, HttpServletRequest request) {
        DatabaseConfig config = databaseConfigRepository.findByAppCode(ref);
        if (config == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "project_not_found"));
        }
        ResponseEntity<?> denied = ensureCanManageMembers(request, config.getDbKey(), true);
        if (denied != null) return denied;

        UUID callerId = (UUID) request.getAttribute("platformUserId");
        if (callerId == null) return ResponseEntity.ok(List.of());

        List<SqlSnippetResponse> out = sqlSnippetRepository
                .findByPlatformUserIdAndDbKeyOrderByUpdatedAtDesc(callerId, config.getDbKey())
                .stream()
                .map(s -> SqlSnippetResponse.builder()
                        .id(s.getId())
                        .name(s.getName())
                        .query(s.getQuery())
                        .createdAt(s.getCreatedAt())
                        .updatedAt(s.getUpdatedAt())
                        .build())
                .toList();
        return ResponseEntity.ok(out);
    }

    @PostMapping("/admin/projects/{ref}/snippets")
    @Transactional("metadataTransactionManager")
    public ResponseEntity<?> createSnippet(@PathVariable("ref") String ref,
                                           @Valid @RequestBody SaveSnippetRequest body,
                                           HttpServletRequest request) {
        DatabaseConfig config = databaseConfigRepository.findByAppCode(ref);
        if (config == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "project_not_found"));
        }
        ResponseEntity<?> denied = ensureCanManageMembers(request, config.getDbKey(), true);
        if (denied != null) return denied;
        UUID callerId = (UUID) request.getAttribute("platformUserId");
        if (callerId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "platform_user_required"));
        }
        SqlSnippet saved = sqlSnippetRepository.save(SqlSnippet.builder()
                .platformUserId(callerId)
                .dbKey(config.getDbKey())
                .name(body.getName().trim())
                .query(body.getQuery())
                .build());
        return ResponseEntity.status(HttpStatus.CREATED).body(toSnippetResponse(saved));
    }

    @PutMapping("/admin/projects/{ref}/snippets/{id}")
    @Transactional("metadataTransactionManager")
    public ResponseEntity<?> updateSnippet(@PathVariable("ref") String ref,
                                           @PathVariable("id") Long id,
                                           @Valid @RequestBody SaveSnippetRequest body,
                                           HttpServletRequest request) {
        DatabaseConfig config = databaseConfigRepository.findByAppCode(ref);
        if (config == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "project_not_found"));
        }
        UUID callerId = (UUID) request.getAttribute("platformUserId");
        if (callerId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "platform_user_required"));
        }
        SqlSnippet snippet = sqlSnippetRepository.findById(id).orElse(null);
        if (snippet == null
                || !snippet.getPlatformUserId().equals(callerId)
                || !snippet.getDbKey().equals(config.getDbKey())) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "snippet_not_found"));
        }
        snippet.setName(body.getName().trim());
        snippet.setQuery(body.getQuery());
        return ResponseEntity.ok(toSnippetResponse(sqlSnippetRepository.save(snippet)));
    }

    @DeleteMapping("/admin/projects/{ref}/snippets/{id}")
    @Transactional("metadataTransactionManager")
    public ResponseEntity<?> deleteSnippet(@PathVariable("ref") String ref,
                                           @PathVariable("id") Long id,
                                           HttpServletRequest request) {
        DatabaseConfig config = databaseConfigRepository.findByAppCode(ref);
        if (config == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "project_not_found"));
        }
        UUID callerId = (UUID) request.getAttribute("platformUserId");
        if (callerId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "platform_user_required"));
        }
        SqlSnippet snippet = sqlSnippetRepository.findById(id).orElse(null);
        if (snippet == null
                || !snippet.getPlatformUserId().equals(callerId)
                || !snippet.getDbKey().equals(config.getDbKey())) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "snippet_not_found"));
        }
        sqlSnippetRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private SqlSnippetResponse toSnippetResponse(SqlSnippet s) {
        return SqlSnippetResponse.builder()
                .id(s.getId())
                .name(s.getName())
                .query(s.getQuery())
                .createdAt(s.getCreatedAt())
                .updatedAt(s.getUpdatedAt())
                .build();
    }

    /**
     * GET /auth/v1/admin/projects/{ref}/sql/history — last N execution records for this project.
     */
    @GetMapping("/admin/projects/{ref}/sql/history")
    public ResponseEntity<?> sqlHistory(@PathVariable("ref") String ref,
                                        @RequestParam(value = "limit", defaultValue = "50") int limit,
                                        HttpServletRequest request) {
        DatabaseConfig config = databaseConfigRepository.findByAppCode(ref);
        if (config == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "project_not_found"));
        }
        ResponseEntity<?> denied = ensureCanManageMembers(request, config.getDbKey(), true);
        if (denied != null) return denied;

        int capped = Math.max(1, Math.min(200, limit));
        List<SqlHistoryEntry> out = sqlExecutionRecordRepository
                .findByDatabaseKeyOrderByCreatedAtDesc(config.getDbKey(), PageRequest.of(0, capped))
                .stream()
                .map(r -> SqlHistoryEntry.builder()
                        .id(r.getId())
                        .query(r.getSqlQuery())
                        .success(r.getSuccess())
                        .executionTimeMs(r.getExecutionTimeMs())
                        .errorMessage(r.getErrorMessage())
                        .createdAt(r.getCreatedAt())
                        .build())
                .toList();
        return ResponseEntity.ok(out);
    }

    // ==================== Project settings (Studio Settings page) ====================

    /**
     * PATCH /auth/v1/admin/projects/{ref} — rename, edit description, pause/resume.
     * Access: super_admin or project owner.
     */
    @PatchMapping("/admin/projects/{ref}")
    public ResponseEntity<?> updateProject(@PathVariable("ref") String ref,
                                           @RequestBody UpdateProjectRequest body,
                                           HttpServletRequest request) {
        DatabaseConfig config = databaseConfigRepository.findByAppCode(ref);
        if (config == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "project_not_found"));
        }
        ResponseEntity<?> denied = ensureCanManageMembers(request, config.getDbKey(), false);
        if (denied != null) return denied;

        if (body.getAppName() != null || body.getDescription() != null) {
            databaseConfigRepository.updateProjectMetadata(
                    config.getDbKey(), body.getAppName(), body.getDescription());
        }
        if (body.getEnabled() != null) {
            databaseConfigRepository.updateEnabled(config.getDbKey(), body.getEnabled());
        }

        DatabaseConfig refreshed = databaseConfigRepository.findByAppCode(ref);
        return ResponseEntity.ok(ProjectSummaryResponse.builder()
                .ref(refreshed.getAppCode() != null ? refreshed.getAppCode() : refreshed.getDbKey())
                .name(refreshed.getAppName() != null ? refreshed.getAppName() : refreshed.getDbName())
                .description(refreshed.getDescription())
                .schemaName(refreshed.getSchemaName())
                .initStatus(refreshed.getInitStatus())
                .healthStatus(refreshed.getHealthStatus())
                .enabled(refreshed.getEnabled())
                .createdAt(refreshed.getCreatedAt())
                .updatedAt(refreshed.getUpdatedAt())
                .apikey(refreshed.getServiceRoleToken())
                .build());
    }

    /**
     * DELETE /auth/v1/admin/projects/{ref} — soft delete: marks the project disabled so
     * it drops out of /admin/projects. The physical Postgres database is left intact so
     * an operator can recover or hard-drop it manually.
     * Access: super_admin or project owner.
     */
    @DeleteMapping("/admin/projects/{ref}")
    public ResponseEntity<?> deleteProject(@PathVariable("ref") String ref, HttpServletRequest request) {
        DatabaseConfig config = databaseConfigRepository.findByAppCode(ref);
        if (config == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "project_not_found"));
        }
        ResponseEntity<?> denied = ensureCanManageMembers(request, config.getDbKey(), false);
        if (denied != null) return denied;

        databaseConfigRepository.updateEnabled(config.getDbKey(), false);
        return ResponseEntity.noContent().build();
    }

    /**
     * GET /auth/v1/admin/projects/{ref}/keys — both tokens so the Settings page can
     * show service_role and authenticated keys without re-fetching the project list.
     * Access: super_admin or any member.
     */
    @GetMapping("/admin/projects/{ref}/keys")
    public ResponseEntity<?> getProjectKeys(@PathVariable("ref") String ref, HttpServletRequest request) {
        DatabaseConfig config = databaseConfigRepository.findByAppCode(ref);
        if (config == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "project_not_found"));
        }
        ResponseEntity<?> denied = ensureCanManageMembers(request, config.getDbKey(), true);
        if (denied != null) return denied;

        return ResponseEntity.ok(ProjectKeysResponse.builder()
                .serviceRoleToken(config.getServiceRoleToken())
                .authenticatedToken(config.getAuthenticatedToken())
                .build());
    }

    /**
     * Auto-bind a newly created project to its creator (if the request came through
     * a platform JWT). Called after init/database* succeeds.
     */
    @Transactional("metadataTransactionManager")
    void recordProjectOwnership(HttpServletRequest request, String dbKey) {
        UUID platformUserId = (UUID) request.getAttribute("platformUserId");
        if (platformUserId == null || dbKey == null) {
            return; // metadata-key path or missing dbKey — no per-user mapping
        }
        if (platformUserProjectRepository.existsByUserIdAndDbKey(platformUserId, dbKey)) {
            return;
        }
        platformUserProjectRepository.save(PlatformUserProject.builder()
                .userId(platformUserId)
                .dbKey(dbKey)
                .role("owner")
                .build());
        log.info("Recorded project ownership: user_id={}, db_key={}", platformUserId, dbKey);
    }

    // ==================== OAuth Configuration Management ====================

    /**
     * GET /auth/v1/admin/oauth - Get OAuth configuration from database
     * <p>
     * Returns OAuth providers configuration for a specific app/database.
     * Query by app_code or db_key.
     * <p>
     * Request parameters:
     * - appCode: Application code (tenant identifier)
     * - dbKey: Database key (alternative to appCode)
     * <p>
     * Response example:
     * {
     * "success": true,
     * "appCode": "tenant1",
     * "dbKey": "tenant1_db",
     * "oauthConfig": {
     * "providers": {
     * "google": {
     * "enabled": true,
     * "clientId": "xxx.apps.googleusercontent.com",
     * "clientSecret": "***",  // masked
     * "redirectUri": "http://localhost:9999/auth/v1/callback",
     * "scope": "openid profile email"
     * }
     * },
     * "emailConfirmationRequired": false
     * }
     * }
     */
    @RequireServiceRole
    @GetMapping("/admin/oauth")
    public ResponseEntity<OAuthProperties> getOAuthConfig(
            @RequestParam(required = false) String appCode,
            @RequestParam(required = false) String dbKey) {
        log.info("OAuth configuration query requested for appCode={}, dbKey={}", appCode, dbKey);
        OAuthProperties authProperties =  MultiTenancyContext.getOAuthProperties();
        return ResponseEntity.ok(authProperties);
    }

    /**
     * PUT /auth/v1/admin/oauth - Update OAuth configuration in database
     * <p>
     * Updates OAuth providers configuration for a specific app/database.
     * The configuration is stored in database_configs.oauth_config field.
     * <p>
     * Request parameters:
     * - appCode: Application code (tenant identifier)
     * - dbKey: Database key (alternative to appCode)
     * <p>
     * Request body example:
     * {
     * "providers": {
     * "google": {
     * "enabled": true,
     * "clientId": "new-client-id.apps.googleusercontent.com",
     * "clientSecret": "new-client-secret",
     * "redirectUri": "http://localhost:9999/auth/v1/callback",
     * "scope": "openid profile email"
     * }
     * },
     * "emailConfirmationRequired": false
     * }
     * <p>
     * Response:
     * {
     * "success": true,
     * "message": "OAuth configuration updated successfully",
     * "appCode": "tenant1",
     * "dbKey": "tenant1_db"
     * }
     */
    @RequireServiceRole
    @PutMapping("/admin/oauth")
    public ResponseEntity<Void> updateOAuthConfig(@RequestBody OAuthProperties request) {

        try {
            DatabaseConfig databaseConfig = null;
            if (MultiTenancyContext.getDatabaseKey() != null) {
                databaseConfig = databaseConfigRepository.findByDbKey(MultiTenancyContext.getDatabaseKey());
            }
            if (databaseConfig == null) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(null);
            }
            databaseConfigRepository.updateOauth(
                    MultiTenancyContext.getDatabaseKey(),
                    JSONUtil.toJsonStr(request)
            );
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("Failed to update OAuth configuration: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
    }

}
