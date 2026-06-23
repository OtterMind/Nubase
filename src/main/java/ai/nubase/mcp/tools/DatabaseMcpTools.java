package ai.nubase.mcp.tools;

import ai.nubase.auth.dto.response.admin.InitDatabaseResponse;
import ai.nubase.auth.dto.response.database.ColumnInfo;
import ai.nubase.auth.dto.response.database.ForeignKeyInfo;
import ai.nubase.auth.dto.response.database.TableInfo;
import ai.nubase.auth.service.DatabaseInitService;
import ai.nubase.auth.service.RlsPolicyExportService;
import ai.nubase.auth.service.SqlExecutionService;
import ai.nubase.auth.dto.request.admin.ExecuteSqlRequest;
import ai.nubase.auth.dto.request.admin.ExportRlsPoliciesRequest;
import ai.nubase.auth.dto.response.admin.ExportRlsPoliciesResponse;
import ai.nubase.auth.dto.response.admin.SqlExecutionResponse;
import ai.nubase.common.context.MultiTenancyContext;
import ai.nubase.common.enums.DatabaseInitStatus;
import ai.nubase.mcp.safety.SqlRisk;
import ai.nubase.mcp.safety.SqlRiskClassifier;
import ai.nubase.postgrest.multidb.DatabaseConfig;
import ai.nubase.postgrest.multidb.SchemaCacheManager;
import ai.nubase.postgrest.schema.Column;
import ai.nubase.postgrest.schema.ForeignKey;
import ai.nubase.postgrest.schema.SchemaCache;
import ai.nubase.postgrest.schema.Table;
import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Database Tools for Spring AI MCP Server
 * Compatible with Supabase MCP
 */
@Component
@RequiredArgsConstructor
public class DatabaseMcpTools {
    private static final Logger log = LoggerFactory.getLogger("McpLogger");
    private final SchemaCacheManager schemaCacheManager;
    private final SqlExecutionService sqlExecutionService;
    private final RlsPolicyExportService rlsPolicyExportService;
    private final DatabaseInitService databaseInitService;
    private final SqlRiskClassifier sqlRiskClassifier;
    /**
     * List all tables in specified schemas
     * Compatible with Supabase MCP's list_tables tool
     */
    @Tool(description = "Lists all tables in one or more schemas with their columns, primary keys, and foreign key constraints. " +
            "Parameter: schemas - List of schema names (comma-separated, e.g. 'public,auth'). If not provided, uses current schema.")
    public Map<String, Object> listTables(String schemas) {
        try {
            log.info("MCP_Tool_USED - listTables called with schemas parameter: {}", schemas);
            // Get current database configuration
            DatabaseConfig dbConfig = MultiTenancyContext.getDatabaseConfig();
            if (dbConfig == null || !DatabaseInitStatus.INITIALIZED.name().equals(dbConfig.getInitStatus())) {
                return Map.of("error", "Database context not found, please ensure the database is initialized");
            }

            String databaseKey = dbConfig.getDbKey();
            SchemaCache schemaCache = schemaCacheManager.getSchemaCache(databaseKey);
            schemaCache.reload();
            log.info("MCP_Tool_USED - listTables - databaseKey: {}, available schemas in cache: {}",
                    databaseKey, schemaCache.getTables().keySet());
            // Determine which schemas to query
            List<String> schemaList;
            if (schemas != null && !schemas.isEmpty()) {
                schemaList = Arrays.asList(schemas.split(","));
            } else {
                schemaList = dbConfig.getDbSchemas();
                if (schemaList == null || schemaList.isEmpty()) {
                    schemaList = List.of(dbConfig.getSchemaName());
                }
            }

            log.info("MCP_Tool_USED - listTables called with schemas: {}", schemaList);

            // Filter tables by schema
            List<String> finalSchemaList = schemaList;
            List<TableInfo> tables = schemaCache.getTables().values().stream()
                    .filter(table -> finalSchemaList.contains(table.getSchema()))
                    .map(this::convertToTableInfo)
                    .sorted(Comparator.comparing(TableInfo::getSchema)
                            .thenComparing(TableInfo::getName))
                    .collect(Collectors.toList());
            log.info("MCP_Tool_USED - listTables returning {} tables for schemas: {}", tables.size(), schemaList);

            return Map.of(
                    "tables", tables,
                    "count", tables.size(),
                    "schemas", schemaList
            );


        } catch (Exception e) {
            log.error("Error listing tables", e);
            return Map.of("error", "Failed to list tables: " + e.getMessage());
        }
    }

    /**
     * Get detailed structure of a specific table
     * Similar to Supabase MCP's describe_table functionality
     */
    @Tool(description = "Get detailed structure information about a specific table including columns, constraints, and relationships. " +
            "Parameters: tableName - The name of the table to describe, schema - Optional schema name (defaults to current schema)")
    public Object getTableStructure(String tableName, String schema) {
        try {
            log.info("MCP_Tool_USED- getTableStructure called for table: {}, schema: {}",
                    tableName, schema != null ? schema : "current");
            // Get current database configuration
            DatabaseConfig dbConfig = MultiTenancyContext.getDatabaseConfig();
            if (dbConfig == null || !DatabaseInitStatus.INITIALIZED.name().equals(dbConfig.getInitStatus())) {
                return Map.of("error", "Database context not found, please ensure the database is initialized");
            }

            // Use provided schema or default to current schema
            String schemaName = schema != null ? schema : dbConfig.getSchemaName();
            String databaseKey = dbConfig.getDbKey();
            SchemaCache schemaCache = schemaCacheManager.getSchemaCache(databaseKey);
            schemaCache.reload();
            // Lookup table
            String tableKey = schemaName + "." + tableName;
            Table tableObj = schemaCache.getTables().get(tableKey);

            if (tableObj == null) {
                return Map.of("error", "Table not found: " + tableKey);
            }

            log.info("MCP_Tool_USED- getTableStructure called for: {}", tableKey);

            return convertToTableInfo(tableObj);

        } catch (Exception e) {
            log.error("Error getting table structure for: {}", tableName, e);
            return Map.of("error", "Failed to get table structure: " + e.getMessage());
        }
    }

    /**
     * Export RLS (Row Level Security) policies
     * Compatible with Supabase MCP's RLS export functionality
     */
    @Tool(description = "Export RLS (Row Level Security) policies for tables. " +
            "Parameters: schemaName - Optional specific schema (null = all schemas), " +
            "tableNames - Optional specific tables (comma-separated), " +
            "includeDropStatements - Optional include DROP POLICY statements (default: false), " +
            "groupBySchema - Optional group policies by schema (default: true)")
    public Object exportRlsPolicies(String schemaName, String tableNames, Boolean includeDropStatements, Boolean groupBySchema) {
        try {
            log.info("MCP_Tool_USED- exportRlsPolicies called");
            DatabaseConfig dbConfig = MultiTenancyContext.getDatabaseConfig();
            if (dbConfig == null || !DatabaseInitStatus.INITIALIZED.name().equals(dbConfig.getInitStatus())) {
                return Map.of("error", "Database context not found, please ensure the database is initialized");
            }

            log.info("MCP Tool - exportRlsPolicies called for schema: {}",
                    schemaName != null ? schemaName : "all");

            ExportRlsPoliciesRequest request = new ExportRlsPoliciesRequest();
            request.setSchemaName(schemaName);
            request.setTableNames(tableNames);
            request.setIncludeDropStatements(includeDropStatements != null ? includeDropStatements : false);
            request.setGroupBySchema(groupBySchema != null ? groupBySchema : true);

            ExportRlsPoliciesResponse response = rlsPolicyExportService.exportRlsPolicies(request);

            log.info("MCP_Tool_USED - exportRlsPolicies completed");
            if (response.isSuccess()) {
                return response;
            } else {
                return Map.of("error", response.getError());
            }

        } catch (Exception e) {
            log.error("Error exporting RLS policies", e);
            return Map.of("error", "Failed to export RLS policies: " + e.getMessage());
        }
    }

    /**
     * Execute SQL query
     * Compatible with Supabase MCP's execute_sql tool
     */
    @Tool(description = "Executes raw SQL query in the database. " +
            "Parameter: sqlQuery - The SQL query to execute (DDL, DML, or SELECT). " +
            "Supports multiple SQL statements separated by semicolons. " +
            "Returns individual results for each statement. Requires service_role MCP apikey.")
    public Object executeSql(String sqlQuery) {
        try {
            Map<String, Object> guard = requireServiceRole("execute SQL");
            if (guard != null) {
                return guard;
            }
            log.info("MCP_Tool_USED- executeSql called");
            SqlRisk risk = sqlRiskClassifier.classify(sqlQuery);
            DatabaseConfig dbConfig = MultiTenancyContext.getDatabaseConfig();
            if (dbConfig == null || !DatabaseInitStatus.INITIALIZED.name().equals(dbConfig.getInitStatus())) {
                return Map.of("error", "Database context not found, please ensure the database is initialized");
            }

            ExecuteSqlRequest request = new ExecuteSqlRequest();
            request.setQuery(sqlQuery);

            SqlExecutionResponse response = sqlExecutionService.executeSql(request);
            // After executing SQL, reload schema cache to reflect any changes (especially for DDL statements)
            schemaCacheManager.reloadSchemaCache(dbConfig.getDbKey());
            log.info("MCP_Tool_USED- executeSql completed"+ JSON.toJSONString(response));
            if (response.isSuccess()) {
                // Return results for each statement
                if (response.getResults() != null && !response.getResults().isEmpty()) {
                    return Map.of(
                            "success", true,
                            "risk", risk.name(),
                            "results", response.getResults(),
                            "statementCount", response.getResults().size(),
                            "executionTimeMs", response.getExecutionTimeMs()
                    );
                }
                // Legacy format for backward compatibility (single statement)
                else if (response.getResults() != null) {
                    return Map.of(
                            "success", true,
                            "risk", risk.name(),
                            "results", response.getResults(),
                            "executionTimeMs", response.getExecutionTimeMs()
                    );
                } else {
                    return Map.of(
                            "success", true,
                            "risk", risk.name(),
                            "rowsAffected", response.getRowsAffected() != null ? response.getRowsAffected() : 0,
                            "executionTimeMs", response.getExecutionTimeMs()
                    );
                }
            } else {
                return Map.of(
                        "success", false,
                        "error", response.getError()
                );
            }

        } catch (Exception e) {
            log.error("Error executing SQL", e);
            return Map.of(
                    "success", false,
                    "error", "Failed to execute SQL: " + e.getMessage()
            );
        }
    }

    @Tool(description = "Preview SQL risk and statement count without executing it. Use before executeSql for schema or data changes.")
    public Object executeSqlDryRun(String sqlQuery) {
        SqlRisk risk = sqlRiskClassifier.classify(sqlQuery);
        int statementCount = sqlRiskClassifier.countStatements(sqlQuery);
        if (risk == SqlRisk.DANGEROUS) {
            return Map.of(
                    "success", true,
                    "risk", risk.name(),
                    "statementCount", statementCount,
                    "executable", false,
                    "blocked", true,
                    "error", "Dangerous SQL is blocked and was not transaction-validated"
            );
        }

        if (!MultiTenancyContext.isServiceRole()) {
            return Map.of(
                    "success", false,
                    "risk", risk.name(),
                    "statementCount", statementCount,
                    "executable", false,
                    "error", "Cannot dry-run SQL: service_role MCP apikey is required"
            );
        }

        DatabaseConfig dbConfig = MultiTenancyContext.getDatabaseConfig();
        if (dbConfig == null || !DatabaseInitStatus.INITIALIZED.name().equals(dbConfig.getInitStatus())) {
            return Map.of(
                    "success", false,
                    "risk", risk.name(),
                    "statementCount", statementCount,
                    "executable", false,
                    "error", "Database context not found, please ensure the database is initialized"
            );
        }

        ExecuteSqlRequest request = new ExecuteSqlRequest();
        request.setQuery(sqlQuery);
        SqlExecutionResponse response = sqlExecutionService.dryRunSql(request);
        if (!response.isSuccess()) {
            return Map.of(
                    "success", false,
                    "risk", risk.name(),
                    "statementCount", statementCount,
                    "executable", false,
                    "error", response.getError() != null ? response.getError() : "SQL dry-run failed",
                    "executionTimeMs", response.getExecutionTimeMs() != null ? response.getExecutionTimeMs() : 0
            );
        }
        return Map.of(
                "success", true,
                "risk", risk.name(),
                "statementCount", statementCount,
                "executable", true,
                "results", response.getResults() != null ? response.getResults() : List.of(),
                "executionTimeMs", response.getExecutionTimeMs() != null ? response.getExecutionTimeMs() : 0
        );
    }

    /**
     * Initialize physical database
     * Initializes the physical database if it hasn't been initialized yet
     */
    @Tool(description = "Initialize the physical database for the current app. " +
            "Checks if the database has already been initialized and performs initialization if needed. " +
            "No parameters required - uses the current database context. Requires service_role MCP apikey.")
    public Object initDatabase() {
        try {
            Map<String, Object> guard = requireServiceRole("initialize database");
            if (guard != null) {
                return guard;
            }
            log.info("MCP_Tool_USED - initDatabase called");
            // Get current database configuration from context
            DatabaseConfig dbConfig = MultiTenancyContext.getDatabaseConfig();
            if (dbConfig == null) {
                return Map.of(
                        "success", false,
                        "error", "Database context not found. Please ensure you are authenticated with a valid database key."
                );
            }

            String dbKey = dbConfig.getDbKey();
            String currentInitStatus = dbConfig.getInitStatus();

            log.info("MCP_Tool_USED - initDatabase called for dbKey: {}, current status: {}", dbKey, currentInitStatus);

            // Check if already initialized
            if (DatabaseInitStatus.INITIALIZED.name().equals(currentInitStatus)) {
                return Map.of(
                        "success", true,
                        "alreadyInitialized", true,
                        "message", "Database '" + dbKey + "' has already been initialized",
                        "dbKey", dbKey,
                        "initStatus", currentInitStatus,
                        "initCompletedAt", dbConfig.getInitCompletedAt() != null ?
                                dbConfig.getInitCompletedAt().toString() : "unknown"
                );
            }

            // Check if initialization is in progress
            if (DatabaseInitStatus.INITIALIZING.name().equals(currentInitStatus)) {
                return Map.of(
                        "success", false,
                        "error", "Database initialization is already in progress for '" + dbKey + "'",
                        "dbKey", dbKey,
                        "initStatus", currentInitStatus,
                        "initStartedAt", dbConfig.getInitStartedAt() != null ?
                                dbConfig.getInitStartedAt().toString() : "unknown"
                );
            }

            // Perform initialization
            log.info("MCP_Tool_USED Starting physical database initialization for dbKey: {}", dbKey);
            InitDatabaseResponse response = databaseInitService.initializePhysicalDatabase(dbKey);

            if (response.isSuccess()) {
                return Map.of(
                        "success", true,
                        "message", "Database initialized successfully",
                        "dbKey", dbKey,
                        "executedSteps", response.getSteps() != null ? response.getSteps() : List.of(),
                        "executionTimeMs", response.getExecutionTimeMs()
                );
            } else {
                return Map.of(
                        "success", false,
                        "error", response.getError(),
                        "errorDetails", response.getErrorDetails() != null ? response.getErrorDetails() : "",
                        "dbKey", dbKey,
                        "executedSteps", response.getSteps() != null ? response.getSteps() : List.of(),
                        "executionTimeMs", response.getExecutionTimeMs()
                );
            }

        } catch (Exception e) {
            log.error("Error initializing database", e);
            return Map.of(
                    "success", false,
                    "error", "Failed to initialize database: " + e.getMessage()
            );
        }
    }

    /**
     * Align with other MCP admin tools and HTTP {@code /auth/v1/admin/sql/execute}:
     * raw SQL and physical DB init run with owner-level JDBC and must not be callable
     * with anon/authenticated project apikeys.
     */
    private Map<String, Object> requireServiceRole(String action) {
        if (!MultiTenancyContext.isServiceRole()) {
            return Map.of("success", false, "error", "Cannot " + action + ": service_role MCP apikey is required");
        }
        return null;
    }

    /**
     * Convert internal Table object to API response DTO
     */
    private TableInfo convertToTableInfo(Table table) {
        // Convert columns
        List<ColumnInfo> columns = table.getColumns() != null ?
                table.getColumns().stream()
                        .map(this::convertToColumnInfo)
                        .collect(Collectors.toList())
                : new ArrayList<>();

        // Convert foreign keys
        List<ForeignKeyInfo> foreignKeyConstraints = new ArrayList<>();
        if (table.getForeignKeys() != null) {
            table.getForeignKeys().forEach((name, fk) -> {
                foreignKeyConstraints.add(convertToForeignKeyInfo(fk));
            });
        }

        return TableInfo.builder()
                .schema(table.getSchema())
                .name(table.getName())
                .comment(table.getDescription())
                .rows(0L) // Estimated rows not available from Table object
                .columns(columns)
                .primaryKeys(table.getPrimaryKey())
                .foreignKeyConstraints(foreignKeyConstraints)
                .build();
    }

    /**
     * Convert internal Column object to API response DTO
     */
    private ColumnInfo convertToColumnInfo(Column column) {
        return ColumnInfo.builder()
                .name(column.getName())
                .comment(column.getDescription())
                .dataType(column.getDataType())
                .format(column.getUdtName())
                .nullable(column.isNullable())
                .defaultValue(column.getDefaultValue())
                .identity(false) // Not available from Column object
                .identityGeneration(null)
                .generated(false)
                .updatable(true) // Assume updatable by default
                .unique(false) // Not available from Column object
                .enums(null)
                .check(null)
                .ordinalPosition(column.getPosition())
                .build();
    }

    /**
     * Convert internal ForeignKey object to API response DTO
     */
    private ForeignKeyInfo convertToForeignKeyInfo(ForeignKey fk) {
        return ForeignKeyInfo.builder()
                .name(fk.getConstraintName())
                .source(fk.getSourceSchema() + "." + fk.getSourceTable())
                .target(fk.getTargetSchema() + "." + fk.getTargetTable())
                .build();
    }
}
