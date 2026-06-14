package ai.nubase.auth.service;

import ai.nubase.auth.dto.response.admin.InitSchemaResponse;
import ai.nubase.auth.dto.request.admin.InitSchemaRequest;
import ai.nubase.common.enums.Role;
import ai.nubase.common.util.SqlSafe;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service for initializing new tenant schemas.
 * Creates schema, roles, and auth tables from SQL templates.
 *
 * SECURITY WARNING:
 * - This service creates new database schemas with full privileges
 * - Should ONLY be accessible via service_role authentication
 * - All operations are logged for audit purposes
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SchemaInitService {

    @PersistenceContext
    private EntityManager entityManager;

    private static final String INIT_SCHEMA_SQL = "db/20251216_init_schema.sql";
    private static final String INIT_ROLE_SQL = "db/20251216_init_role.sql";

    /**
     * Initialize a new tenant schema with auth tables and roles
     */
    @Transactional
    public InitSchemaResponse initSchema(InitSchemaRequest request) {
        long startTime = System.currentTimeMillis();
        List<String> executedSteps = new ArrayList<>();

        try {
            // 1. Determine role names
            String schema = request.getSchema();
            String adminRole = request.getAdminRole() != null ? request.getAdminRole() : Role.SERVICE_ROLE.getValue();
            String userRole = request.getUserRole() != null ? request.getUserRole() : Role.AUTHENTICATED.getValue();
            String anonRole = Role.ANON.getValue();

            log.info("Initializing schema '{}' with admin role '{}' and user role '{}'", schema, adminRole, userRole);

            // 2. Create schema
            executeStep("Create schema '" + schema + "'", () -> {
                createSchema(schema);
            });
            executedSteps.add("Created schema: " + schema);

            // 3. Execute init_schema.sql (creates tables, functions, indexes)
            executeStep("Execute init_schema.sql", () -> {
                try {
                    String schemaSql = loadAndReplacePlaceholders(INIT_SCHEMA_SQL, schema, adminRole, userRole,anonRole);
                    executeSqlScript(schemaSql, schema);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to load init_schema.sql: " + e.getMessage(), e);
                }
            });
            executedSteps.add("Created auth tables (users, sessions, refresh_tokens, identities)");

            // 4. Execute init_role.sql (creates roles and grants permissions)
            executeStep("Execute init_role.sql", () -> {
                try {
                    String roleSql = loadAndReplacePlaceholders(INIT_ROLE_SQL, schema, adminRole, userRole,anonRole);
                    executeSqlScript(roleSql, null); // Role creation doesn't need search_path
                } catch (IOException e) {
                    throw new RuntimeException("Failed to load init_role.sql: " + e.getMessage(), e);
                }
            });
            executedSteps.add("Created roles: " + adminRole + ", " + userRole);
            executedSteps.add("Granted permissions on schema and tables");

            long executionTime = System.currentTimeMillis() - startTime;
            log.info("Schema '{}' initialized successfully in {}ms", schema, executionTime);

            return InitSchemaResponse.success(schema, adminRole, userRole, executedSteps, executionTime);

        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Failed to initialize schema '{}': {}", request.getSchema(), e.getMessage(), e);

            return InitSchemaResponse.error(
                    "Schema initialization failed: " + e.getMessage(),
                    getDetailedErrorMessage(e, executedSteps),
                    executionTime
            );
        }
    }

    /**
     * Create a new PostgreSQL schema
     */
    private void createSchema(String schema) {
        String sql = String.format("CREATE SCHEMA IF NOT EXISTS %s", SqlSafe.ident(schema));
        entityManager.createNativeQuery(sql).executeUpdate();
        log.info("Schema '{}' created", schema);
    }

    /**
     * Load SQL file from classpath and replace placeholders
     */
    private String loadAndReplacePlaceholders(String resourcePath, String schema,
                                              String adminRole, String userRole,String anonRole) throws IOException {
        // Load SQL file
        ClassPathResource resource = new ClassPathResource(resourcePath);
        String sqlContent;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            sqlContent = reader.lines().collect(Collectors.joining("\n"));
        }

        // Replace placeholders
        sqlContent = sqlContent.replace("${schema}", schema);
        sqlContent = sqlContent.replace("${schema_admin_user}", adminRole);
        sqlContent = sqlContent.replace("${schema_user}", userRole);
        sqlContent = sqlContent.replace("${schema_anon_user}", anonRole);

        // Handle db_conn_user (might be in comments, so we replace it but it won't execute if commented)
//        if (dbConnectionUser != null && !dbConnectionUser.isBlank()) {
//            sqlContent = sqlContent.replace("${db_conn_user}", dbConnectionUser);
//        } else {
//            // If not provided, keep it commented out
//            sqlContent = sqlContent.replace("${db_conn_user}", "");
//        }

        log.debug("Loaded SQL file '{}' ({} chars)", resourcePath, sqlContent.length());
        return sqlContent;
    }

    /**
     * Execute SQL script (may contain multiple statements)
     * Splits script into individual statements and executes them one by one
     */
    private void executeSqlScript(String sqlScript, String schema) {
        // Set search_path if schema is provided
        if (schema != null && !schema.isBlank()) {
            String setSearchPath = String.format("SET search_path TO %s, public", SqlSafe.ident(schema));
            entityManager.createNativeQuery(setSearchPath).executeUpdate();
            log.debug("Set search_path to: {}", schema);
        }

        // Split SQL script into individual statements
        // This is a simple splitter - may not work with all complex SQL
        List<String> statements = splitSqlStatements(sqlScript);

        log.debug("Executing {} SQL statements", statements.size());

        int executedCount = 0;
        try {
            for (String statement : statements) {
                String trimmed = statement.strip();
                if (!trimmed.isEmpty() && !trimmed.startsWith("--")) {
                    entityManager.createNativeQuery(trimmed).executeUpdate();
                    executedCount++;
                }
            }
            log.debug("Executed {} SQL statements successfully", executedCount);
        } catch (Exception e) {
            log.error("Failed to execute SQL statement (executed {}/{}): {}",
                    executedCount, statements.size(), e.getMessage());
            throw new RuntimeException("SQL execution failed at statement " + (executedCount + 1) + ": " + e.getMessage(), e);
        }
    }

    /**
     * Split SQL script into individual statements
     * Handles PostgreSQL function definitions ($$-delimited blocks)
     */
    private List<String> splitSqlStatements(String sqlScript) {
        List<String> statements = new ArrayList<>();
        StringBuilder currentStatement = new StringBuilder();
        boolean inDollarQuote = false;
        boolean inSingleLineComment = false;
        boolean inMultiLineComment = false;

        String[] lines = sqlScript.split("\n");

        for (String line : lines) {
            String trimmedLine = line.strip();

            // Skip empty lines
            if (trimmedLine.isEmpty()) {
                continue;
            }

            // Check for single-line comment
            if (trimmedLine.startsWith("--")) {
                continue; // Skip comment lines
            }

            // Check for dollar-quote delimiter (PostgreSQL function syntax)
            if (trimmedLine.contains("$$")) {
                inDollarQuote = !inDollarQuote;
            }

            currentStatement.append(line).append("\n");

            // If we're not in a dollar-quoted block and line ends with semicolon, it's a complete statement
            if (!inDollarQuote && trimmedLine.endsWith(";")) {
                statements.add(currentStatement.toString().strip());
                currentStatement = new StringBuilder();
            }
        }

        // Add any remaining statement
        if (currentStatement.length() > 0) {
            String remaining = currentStatement.toString().strip();
            if (!remaining.isEmpty()) {
                statements.add(remaining);
            }
        }

        return statements;
    }

    /**
     * Execute a step with error handling
     */
    private void executeStep(String stepName, Runnable action) {
        log.info("Executing step: {}", stepName);
        try {
            action.run();
        } catch (Exception e) {
            log.error("Step '{}' failed: {}", stepName, e.getMessage(), e);
            throw new RuntimeException("Step '" + stepName + "' failed: " + e.getMessage(), e);
        }
    }

    /**
     * Get detailed error message with executed steps
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
