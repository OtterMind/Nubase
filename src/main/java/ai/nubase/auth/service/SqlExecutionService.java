package ai.nubase.auth.service;

import ai.nubase.auth.dto.request.admin.ExecuteSqlRequest;
import ai.nubase.auth.dto.response.admin.SqlExecutionResponse;
import ai.nubase.common.context.MultiTenancyContext;
import ai.nubase.common.util.SqlSafe;
import ai.nubase.metadata.entity.SqlExecutionRecord;
import ai.nubase.metadata.repository.SqlExecutionRecordRepository;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.function.Consumer;
import java.util.*;

/**
 * Service for executing raw SQL statements.
 * Provides admin-level database access for DDL and DML operations.
 * <p>
 * SECURITY WARNING:
 * - This service executes arbitrary SQL with full database privileges
 * - Should ONLY be accessible via service_role authentication
 * - All operations are logged for audit purposes
 * <p>
 * IMPLEMENTATION NOTE:
 * - Uses direct JDBC Connection to bypass JPA/Hibernate SQL parsing
 * - Avoids framework-level SQL syntax limitations
 * - Supports multiple SQL statements separated by semicolons
 * - Returns query results for SELECT statements
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SqlExecutionService {
    private static final int DRY_RUN_STATEMENT_TIMEOUT_MS = 5_000;

    private final JdbcTemplate jdbcTemplate;
    private final SqlExecutionRecordRepository sqlExecutionRecordRepository;

    /**
     * Execute SQL statement(s) using direct JDBC Connection
     * Bypasses JPA/Hibernate SQL parsing to support all PostgreSQL syntax
     *
     * Supports:
     * - Single SQL statement
     * - Multiple SQL statements (semicolon-separated) executed together
     * - Returns individual results for each statement
     * - Returns results for SELECT queries
     * - Returns affected rows for DML statements
     */
    @Transactional
    public SqlExecutionResponse executeSql(ExecuteSqlRequest request) {
        long startTime = System.currentTimeMillis();
        String appCode = MultiTenancyContext.getAppCode();
        String databaseKey = MultiTenancyContext.getDatabaseKey();
        String schema = MultiTenancyContext.getSchemaName();
        String sql = request.getQuery();

        if (sql == null || sql.strip().isEmpty()) {
            log.warn("Empty SQL statement received");
            SqlExecutionResponse response = SqlExecutionResponse.error("SQL statement is empty", 0);
            persistSqlExecutionRecord(appCode, databaseKey, schema, sql, response, null);
            return response;
        }

        List<String> dbSchemas = MultiTenancyContext.getDatabaseConfig().getDbSchemas();

        try {
            setSearchPath(dbSchemas, schema, false, jdbcTemplate::execute);

            log.info("Executing SQL in schema '{}': {}", schema, sql);

            // Execute SQL using Connection to get full control
            List<SqlExecutionResponse.SqlStatementResult> statementResults = new ArrayList<>();

            // Use DataSourceUtils to get a connection that participates in Spring transactions
            Connection connection = DataSourceUtils.getConnection(jdbcTemplate.getDataSource());
            try (Statement statement = connection.createStatement()) {

                // Execute the entire SQL string (may contain multiple statements)
                boolean isResultSet = statement.execute(sql);
                collectStatementResults(statement, isResultSet, statementResults);

            } finally {
                // Release the connection back to the pool (but don't close if in transaction)
                DataSourceUtils.releaseConnection(connection, jdbcTemplate.getDataSource());
            }

            long executionTime = System.currentTimeMillis() - startTime;

            log.info("SQL executed successfully in schema '{}', processed {} statements in {}ms",
                    schema, statementResults.size(), executionTime);

            SqlExecutionResponse response = SqlExecutionResponse.successWithResults(statementResults, executionTime);
            persistSqlExecutionRecord(appCode, databaseKey, schema, sql, response, null);
            return response;

        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            String errorMsg = String.format("SQL execution error in schema '%s': %s", schema, e.getMessage());
            log.error("executeSql error: {}", errorMsg, e);
            SqlExecutionResponse response = SqlExecutionResponse.error(errorMsg, executionTime);
            persistSqlExecutionRecord(appCode, databaseKey, schema, sql, response, e);
            return response;
        }
    }

    /**
     * Execute SQL inside a short transaction and always roll it back.
     *
     * <p>This is used by MCP dry-run before Ottermind agents are allowed to call
     * executeSql. It validates syntax/runtime behavior against the tenant database
     * without persisting DDL/DML, without writing audit records, and without reloading
     * schema cache.
     */
    public SqlExecutionResponse dryRunSql(ExecuteSqlRequest request) {
        long startTime = System.currentTimeMillis();
        String schema = MultiTenancyContext.getSchemaName();
        String sql = request.getQuery();

        if (sql == null || sql.strip().isEmpty()) {
            log.warn("Empty SQL dry-run statement received");
            return SqlExecutionResponse.error("SQL statement is empty", 0);
        }

        List<String> dbSchemas = MultiTenancyContext.getDatabaseConfig().getDbSchemas();
        Connection connection = null;
        Boolean originalAutoCommit = null;
        try {
            connection = DataSourceUtils.getConnection(jdbcTemplate.getDataSource());
            originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);

            List<SqlExecutionResponse.SqlStatementResult> statementResults = new ArrayList<>();
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(Math.max(1, DRY_RUN_STATEMENT_TIMEOUT_MS / 1000));
                setSearchPath(dbSchemas, schema, true, sqlToRun -> executeStatement(statement, sqlToRun));
                statement.execute("SET LOCAL statement_timeout = '" + DRY_RUN_STATEMENT_TIMEOUT_MS + "ms'");

                log.info("Dry-running SQL in schema '{}': {}", schema, sql);
                boolean isResultSet = statement.execute(sql);
                collectStatementResults(statement, isResultSet, statementResults);
            }

            rollbackQuietly(connection);
            long executionTime = System.currentTimeMillis() - startTime;
            log.info("SQL dry-run succeeded in schema '{}', processed {} statements in {}ms",
                    schema, statementResults.size(), executionTime);
            return SqlExecutionResponse.successWithResults(statementResults, executionTime);
        } catch (Exception e) {
            rollbackQuietly(connection);
            long executionTime = System.currentTimeMillis() - startTime;
            String errorMsg = String.format("SQL dry-run error in schema '%s': %s", schema, e.getMessage());
            log.warn("dryRunSql error: {}", errorMsg, e);
            return SqlExecutionResponse.error(errorMsg, executionTime);
        } finally {
            if (connection != null) {
                if (originalAutoCommit != null) {
                    try {
                        connection.setAutoCommit(originalAutoCommit);
                    } catch (Exception e) {
                        log.warn("Failed to restore autoCommit after SQL dry-run: {}", e.getMessage(), e);
                    }
                }
                DataSourceUtils.releaseConnection(connection, jdbcTemplate.getDataSource());
            }
        }
    }

    private void persistSqlExecutionRecord(String appCode,
                                           String databaseKey,
                                           String schema,
                                           String sql,
                                           SqlExecutionResponse response,
                                           Exception exception) {
        try {
            String executionResult = toJsonSafely(response);
            String errorMessage = response != null ? response.getError() : null;
            if (errorMessage == null && exception != null) {
                errorMessage = exception.getMessage();
            }
            String errorStackTrace = exception == null ? null : toStackTrace(exception);
            boolean success = response != null && response.isSuccess();
            Long executionTimeMs = response != null ? response.getExecutionTimeMs() : null;

            SqlExecutionRecord record = SqlExecutionRecord.builder()
                    .appCode(appCode)
                    .databaseKey(databaseKey)
                    .schemaName(schema)
                    .sqlQuery(sql == null ? "" : sql)
                    .success(success)
                    .executionTimeMs(executionTimeMs)
                    .executionResult(executionResult)
                    .errorMessage(errorMessage)
                    .errorStackTrace(errorStackTrace)
                    .build();

            sqlExecutionRecordRepository.save(record);
        } catch (Exception logException) {
            log.error("Failed to persist SQL execution record: {}", logException.getMessage(), logException);
        }
    }

    private String toJsonSafely(Object object) {
        if (object == null) {
            return null;
        }
        try {
            return JSONUtil.toJsonStr(object);
        } catch (Exception e) {
            return String.valueOf(object);
        }
    }

    private String toStackTrace(Exception exception) {
        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        exception.printStackTrace(printWriter);
        return stringWriter.toString();
    }

    private void setSearchPath(List<String> dbSchemas,
                               String schema,
                               boolean local,
                               Consumer<String> executor) {
        String searchPath = buildSearchPath(dbSchemas, schema);
        if (searchPath != null) {
            executor.accept((local ? "SET LOCAL" : "SET") + " search_path TO " + searchPath);
        }
    }

    private String buildSearchPath(List<String> dbSchemas, String schema) {
        if (!CollectionUtils.isEmpty(dbSchemas)) {
            StringBuilder sp = new StringBuilder();
            boolean hasPublic = false;
            for (String s : dbSchemas) {
                if (sp.length() > 0) sp.append(", ");
                sp.append(SqlSafe.ident(s));
                if ("public".equals(s)) hasPublic = true;
            }
            if (!hasPublic) sp.append(", ").append(SqlSafe.ident("public"));
            return sp.toString();
        }
        if (schema != null && !schema.isBlank()) {
            return SqlSafe.ident(schema) + ", " + SqlSafe.ident("public");
        }
        return null;
    }

    private void executeStatement(Statement statement, String sql) {
        try {
            statement.execute(sql);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void collectStatementResults(Statement statement,
                                         boolean isResultSet,
                                         List<SqlExecutionResponse.SqlStatementResult> statementResults) throws Exception {
        int statementIndex = 0;
        // Process all results using getMoreResults()
        do {
            if (isResultSet) {
                // This statement returned a ResultSet (SELECT query)
                try (ResultSet rs = statement.getResultSet()) {
                    List<Map<String, Object>> rows = convertResultSetToList(rs);

                    statementResults.add(SqlExecutionResponse.SqlStatementResult.builder()
                            .index(statementIndex)
                            .type("query")
                            .rows(rows)
                            .build());

                    log.debug("Statement {} (query) returned {} rows", statementIndex, rows.size());
                }
            } else {
                // This statement returned an update count (INSERT/UPDATE/DELETE/DDL)
                int updateCount = statement.getUpdateCount();

                if (updateCount >= 0) {
                    statementResults.add(SqlExecutionResponse.SqlStatementResult.builder()
                            .index(statementIndex)
                            .type("update")
                            .rowsAffected(updateCount)
                            .build());

                    log.debug("Statement {} (update) affected {} rows", statementIndex, updateCount);
                }
                // updateCount == -1 means no more results
            }

            statementIndex++;

            // Move to next result
            isResultSet = statement.getMoreResults();

        } while (isResultSet || statement.getUpdateCount() != -1);
    }

    private void rollbackQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.rollback();
        } catch (Exception e) {
            log.warn("Failed to rollback SQL dry-run transaction: {}", e.getMessage(), e);
        }
    }

    /**
     * Convert ResultSet to List of Maps
     */
    private List<Map<String, Object>> convertResultSetToList(ResultSet rs) throws Exception {
        List<Map<String, Object>> results = new ArrayList<>();
        ResultSetMetaData metaData = rs.getMetaData();
        int columnCount = metaData.getColumnCount();

        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= columnCount; i++) {
                String columnName = metaData.getColumnName(i);
                Object value = rs.getObject(i);
                row.put(columnName, value);
            }
            results.add(row);
        }

        return results;
    }
}
