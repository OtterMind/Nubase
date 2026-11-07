package ai.nubase.postgrest.query;

import com.alibaba.fastjson2.JSON;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import ai.nubase.common.context.MultiTenancyContext;
import ai.nubase.postgrest.api.Filter;
import ai.nubase.postgrest.api.LogicalCondition;
import ai.nubase.postgrest.api.Preferences;
import ai.nubase.postgrest.multidb.SchemaCacheManager;
import ai.nubase.postgrest.schema.Column;
import ai.nubase.postgrest.schema.Routine;
import ai.nubase.postgrest.schema.RoutineParam;
import ai.nubase.postgrest.schema.SchemaCache;
import ai.nubase.postgrest.schema.Table;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Array;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Query Executor - Executes query plans against PostgreSQL
 * Equivalent to PostgREST's Query module
 */
@Slf4j
@Service
public class QueryExecutor {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final SchemaCacheManager schemaCacheManager;

    public QueryExecutor(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, SchemaCacheManager schemaCacheManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.schemaCacheManager = schemaCacheManager;
    }

    /**
     * Get SchemaCache for current database
     */
    private SchemaCache getSchemaCache() {
        String dbKey = MultiTenancyContext.getDatabaseKey();
        return schemaCacheManager.getSchemaCache(dbKey);
    }

    /**
     * Get column type map for a table
     *
     * @return Map of column name to data type (e.g., "jsonb", "text[]", "integer")
     */
    private Map<String, String> getColumnTypeMap(String schema, String tableName) {
        Map<String, String> typeMap = new HashMap<>();
        SchemaCache schemaCache = getSchemaCache();
        if (schemaCache != null) {
            Table table = schemaCache.getTable(schema, tableName);
            if (table != null && table.getColumns() != null) {
                for (Column col : table.getColumns()) {
                    // Use udtName for precise type (e.g., "jsonb", "_text" for text[])
                    typeMap.put(col.getName(), col.getUdtName() != null ? col.getUdtName() : col.getDataType());
                }
            }
        }
        return typeMap;
    }

    public QueryResult execute(QueryPlan plan, String body, Preferences preferences) {
        log.info("Executing query plan: {}", JSON.toJSONString(plan));
        String sql = buildSQL(plan, body);
        log.info("Executing SQL: {}", sql);
        switch (plan.getType()) {
            case SELECT -> {
                return executeSelect(sql, plan, preferences);
            }
            case INSERT -> {
                return executeInsert(sql, plan, body, preferences);
            }
            case UPSERT -> {
                return executeUpsert(sql, plan, body, preferences);
            }
            case UPDATE -> {
                return executeUpdate(sql, plan, body, preferences);
            }
            case DELETE -> {
                return executeDelete(sql, plan, preferences);
            }
            case CALL_FUNCTION -> {
                return executeRpc(sql, plan, preferences);
            }
            default -> throw new IllegalArgumentException("Unsupported query type: " + plan.getType());
        }
    }

    /**
     * Renders a plan to SQL without executing it. Used by callers that need to run
     * the statement under their own execution settings (e.g. the cron scheduler
     * applying a per-job query timeout) while reusing PostgREST's SQL generation.
     */
    public String buildSqlForPlan(QueryPlan plan, String body) {
        return buildSQL(plan, body);
    }

    private String buildSQL(QueryPlan plan, String body) {
        StringBuilder sql = new StringBuilder();

        switch (plan.getType()) {
            case SELECT -> buildSelectSQL(sql, plan);
            case INSERT -> buildInsertSQL(sql, plan, body);
            case UPSERT -> buildUpsertSQL(sql, plan, body);
            case UPDATE -> buildUpdateSQL(sql, plan, body);
            case DELETE -> buildDeleteSQL(sql, plan);
            case CALL_FUNCTION -> buildRpcSQL(sql, plan);
        }

        return sql.toString();
    }

    private void buildSelectSQL(StringBuilder sql, QueryPlan plan) {
        sql.append("SELECT ");

        // Collect all columns: base table columns + aggregate functions + embedded resource subqueries
        List<String> allColumns = new ArrayList<>();
        List<String> groupByColumns = new ArrayList<>();
        boolean hasJoins = plan.getJoins() != null && !plan.getJoins().isEmpty();
        boolean hasAggregates = plan.isHasAggregates();

        // Use detailed column info if available (includes aggregates)
        if (plan.getSelectColumnsWithInfo() != null && !plan.getSelectColumnsWithInfo().isEmpty()) {
            for (QueryPlan.SelectColumnInfo colInfo : plan.getSelectColumnsWithInfo()) {
                if (colInfo.isAggregate()) {
                    // Build aggregate function: SUM(column) AS alias
                    String aggSql = buildAggregateSql(colInfo, plan.getTable());
                    allColumns.add(aggSql);
                } else {
                    // Regular column
                    String colSql = buildColumnSql(colInfo, plan.getTable());
                    allColumns.add(colSql);
                    // Add to GROUP BY if we have aggregates
                    if (hasAggregates) {
                        groupByColumns.add(quoteQualifiedColumn(colInfo.getQualified()));
                    }
                }
            }
        } else if (plan.getSelectColumns() != null && !plan.getSelectColumns().isEmpty()) {
            // Fallback to simple select columns
            for (String col : plan.getSelectColumns()) {
                String trimmedCol = col != null ? col.strip() : "";
                if ("*".equals(trimmedCol)) {
                    allColumns.add(quote(plan.getTable()) + ".*");
                } else {
                    allColumns.add(quoteQualifiedColumn(col));
                }
            }
        } else {
            allColumns.add(quote(plan.getTable()) + ".*");
        }

        // Add embedded resources as subqueries (PostgREST style)
        // This generates JSON directly in the database for proper nesting
        if (hasJoins) {
            for (JoinClause join : plan.getJoins()) {
                String embeddingName = join.getEmbeddingName() != null
                        ? join.getEmbeddingName()
                        : (join.getAlias() != null ? join.getAlias() : join.getTargetTable());

                allColumns.add(buildEmbeddedSubquery(plan, join, embeddingName));
            }
        }

        // Build SELECT clause
        sql.append(String.join(", ", allColumns));

        // FROM clause
        sql.append(" FROM ");
        if (plan.getSchema() != null) {
            sql.append(quote(plan.getSchema())).append(".");
        }
        sql.append(quote(plan.getTable()));

        // No JOIN clauses needed - we use subqueries instead

        // WHERE clause
        buildWhereClause(sql, plan);

        // GROUP BY clause (if we have aggregates with non-aggregate columns)
        if (hasAggregates && !groupByColumns.isEmpty()) {
            sql.append(" GROUP BY ").append(String.join(", ", groupByColumns));
        }

        // ORDER BY
        if (plan.getOrderBy() != null && !plan.getOrderBy().isEmpty()) {
            sql.append(" ORDER BY ").append(String.join(", ", plan.getOrderBy()));
        }

        // LIMIT and OFFSET
        if (plan.getLimit() != null) {
            sql.append(" LIMIT ").append(plan.getLimit());
        }
        if (plan.getOffset() != null) {
            sql.append(" OFFSET ").append(plan.getOffset());
        }
    }

    /**
     * Build SQL for an aggregate function.
     * Examples:
     * SUM("table"."amount") AS "total"
     * COUNT(*) AS "count"
     * AVG("table"."price")
     */
    private String buildAggregateSql(QueryPlan.SelectColumnInfo colInfo, String table) {
        String funcName = colInfo.getAggregateFunction().toUpperCase();
        String colName = colInfo.getName();

        String innerCol;
        if ("*".equals(colName)) {
            innerCol = "*";
        } else {
            innerCol = quoteQualifiedColumn(colInfo.getQualified());
        }

        StringBuilder sb = new StringBuilder();
        sb.append(funcName).append("(").append(innerCol).append(")");

        // Add alias
        String alias = colInfo.getAlias();
        if (alias != null && !alias.isEmpty()) {
            sb.append(" AS ").append(quote(alias));
        } else {
            // Default alias is the function name in lowercase
            sb.append(" AS ").append(quote(funcName.toLowerCase()));
        }

        return sb.toString();
    }

    /**
     * Build SQL for a regular column.
     * Adds alias if specified.
     */
    private String buildColumnSql(QueryPlan.SelectColumnInfo colInfo, String table) {
        String colSql = quoteQualifiedColumn(colInfo.getQualified());

        if (colInfo.getAlias() != null && !colInfo.getAlias().isEmpty()) {
            colSql += " AS " + quote(colInfo.getAlias());
        }

        return colSql;
    }

    /**
     * Build an embedded resource subquery (PostgREST style).
     * <p>
     * For many-to-one relationships (e.g., student_courses -> course):
     * COALESCE((SELECT row_to_json("subq".*) FROM (SELECT ... WHERE fk = pk) AS "subq"), NULL) AS "course"
     * <p>
     * This approach:
     * 1. Generates proper nested JSON directly in PostgreSQL
     * 2. Avoids column name conflicts between tables
     * 3. Handles NULL for LEFT JOIN semantics via COALESCE
     */
    private String buildEmbeddedSubquery(QueryPlan plan, JoinClause join, String embeddingName) {
        StringBuilder subquery = new StringBuilder();

        // Start with COALESCE for NULL handling (LEFT JOIN semantics)
        subquery.append("COALESCE((SELECT row_to_json(").append(quote(embeddingName + "_subq")).append(".*) FROM (");

        // Build inner SELECT
        subquery.append("SELECT ");

        // Determine columns to select from embedded table
        if (join.getSelectColumns() != null && !join.getSelectColumns().isEmpty()) {
            List<String> cols = new ArrayList<>();
            for (String col : join.getSelectColumns()) {
                String colName = col.contains(".") ? col.substring(col.lastIndexOf(".") + 1) : col;
                // Don't quote asterisk
                if ("*".equals(colName)) {
                    cols.add("*");
                } else {
                    cols.add(quote(colName));
                }
            }
            subquery.append(String.join(", ", cols));
        } else {
            subquery.append("*");
        }

        // FROM clause for subquery
        subquery.append(" FROM ");
        if (join.getTargetSchema() != null) {
            subquery.append(quote(join.getTargetSchema())).append(".");
        }
        subquery.append(quote(join.getTargetTable()));

        // WHERE clause - link to parent table
        subquery.append(" WHERE ");
        List<String> conditions = new ArrayList<>();
        for (JoinClause.JoinCondition cond : join.getConditions()) {
            // For subquery, we reference parent table column directly
            // e.g., "courses"."id" = "student_courses"."course_id"
            String leftCol = cond.getLeftColumn();  // parent table column (e.g., student_courses.course_id)
            String rightCol = cond.getRightColumn(); // embedded table column (e.g., course.id)

            // In subquery context, we need to flip and adjust:
            // Parent column stays as-is (references outer query)
            // Embedded column should reference the subquery's table
            String embeddedColName = rightCol.contains(".")
                    ? rightCol.substring(rightCol.lastIndexOf(".") + 1)
                    : rightCol;

            conditions.add(quote(join.getTargetTable()) + "." + quote(embeddedColName) +
                    " " + cond.getOperator() + " " +
                    quoteQualifiedColumn(leftCol));
        }
        subquery.append(String.join(" AND ", conditions));

        // Close subquery
        subquery.append(") AS ").append(quote(embeddingName + "_subq"));
        subquery.append("), NULL) AS ").append(quote(embeddingName));

        return subquery.toString();
    }

    private void buildInsertSQL(StringBuilder sql, QueryPlan plan, String body) {
        sql.append("INSERT INTO ");
        if (plan.getSchema() != null) {
            sql.append(quote(plan.getSchema())).append(".");
        }
        sql.append(quote(plan.getTable()));

        // Get column type map for proper value formatting
        String schema = plan.getSchema() != null ? plan.getSchema() : "public";
        Map<String, String> columnTypeMap = getColumnTypeMap(schema, plan.getTable());

        // Parse JSON body - support both array and single object
        try {
            List<Map<String, Object>> rows;

            // Check if body is an array or single object
            if (body.strip().startsWith("[")) {
                // Parse as array
                rows = objectMapper.readValue(body,
                        new TypeReference<List<Map<String, Object>>>() {
                        });
            } else {
                // Parse as single object and wrap in list
                Map<String, Object> singleRow = objectMapper.readValue(body,
                        new TypeReference<Map<String, Object>>() {
                        });
                rows = List.of(singleRow);
            }

            if (!rows.isEmpty()) {
                // Determine columns to use
                List<String> columns;
                boolean useDefault = plan.isMissingAsDefault() &&
                        plan.getSpecifiedColumns() != null && !plan.getSpecifiedColumns().isEmpty();

                if (useDefault) {
                    // Use specified columns from ?columns= parameter
                    columns = new ArrayList<>(plan.getSpecifiedColumns());
                } else {
                    // Use columns from the first row
                    columns = new ArrayList<>(rows.get(0).keySet());
                }

                sql.append(" (").append(String.join(", ", columns.stream()
                        .map(this::quote).toList())).append(")");
                sql.append(" VALUES ");

                List<String> valueClauses = new ArrayList<>();
                for (Map<String, Object> row : rows) {
                    List<String> values = new ArrayList<>();
                    for (String col : columns) {
                        if (useDefault && !row.containsKey(col)) {
                            // Column is missing and missing=default is set - use DEFAULT
                            values.add("DEFAULT");
                        } else {
                            String colType = columnTypeMap.get(col);
                            values.add(formatValueWithType(row.get(col), colType));
                        }
                    }
                    valueClauses.add("(" + String.join(", ", values) + ")");
                }
                sql.append(String.join(", ", valueClauses));
            }
        } catch (Exception e) {
            log.error("Error parsing JSON body", e);
            throw new RuntimeException("Invalid JSON body", e);
        }

        if (plan.isReturningAll()) {
            sql.append(" RETURNING *");
        }
    }

    private void buildUpsertSQL(StringBuilder sql, QueryPlan plan, String body) {
        // Build initial INSERT
        buildInsertSQL(sql, plan, body);

        // Remove RETURNING clause temporarily
        String insertSQL = sql.toString();
        if (insertSQL.endsWith(" RETURNING *")) {
            sql.setLength(sql.length() - " RETURNING *".length());
        }

        // Add ON CONFLICT clause
        if (plan.getConflictColumns() != null && !plan.getConflictColumns().isEmpty()) {
            sql.append(" ON CONFLICT (");
            sql.append(String.join(", ", plan.getConflictColumns().stream()
                    .map(this::quote).toList()));
            sql.append(")");

            if (plan.isIgnoreConflict()) {
                sql.append(" DO NOTHING");
            } else {
                // DO UPDATE SET
                sql.append(" DO UPDATE SET ");
                try {
                    // Parse as array or single object
                    Map<String, Object> updates;
                    if (body.strip().startsWith("[")) {
                        List<Map<String, Object>> rows = objectMapper.readValue(body,
                                new TypeReference<List<Map<String, Object>>>() {
                                });
                        updates = rows.isEmpty() ? new java.util.HashMap<>() : rows.get(0);
                    } else {
                        updates = objectMapper.readValue(body,
                                new TypeReference<Map<String, Object>>() {
                                });
                    }
                    List<String> setClauses = new ArrayList<>();
                    // Create a set of trimmed conflict column names for comparison
                    Set<String> conflictColsNormalized = plan.getConflictColumns().stream()
                            .map(String::strip)
                            .collect(java.util.stream.Collectors.toSet());
                    for (String key : updates.keySet()) {
                        // Skip conflict columns (compare trimmed versions)
                        String keyNormalized = key.strip();
                        if (!conflictColsNormalized.contains(keyNormalized)) {
                            setClauses.add(quote(key) + " = EXCLUDED." + quote(key));
                        }
                    }
                    sql.append(String.join(", ", setClauses));
                } catch (Exception e) {
                    log.error("Error parsing JSON for UPSERT", e);
                }
            }
        }

        if (plan.isReturningAll()) {
            sql.append(" RETURNING *");
        }
    }

    private void buildUpdateSQL(StringBuilder sql, QueryPlan plan, String body) {
        sql.append("UPDATE ");
        if (plan.getSchema() != null) {
            sql.append(quote(plan.getSchema())).append(".");
        }
        sql.append(quote(plan.getTable()));

        // Get column type map for proper value formatting
        String schema = plan.getSchema() != null ? plan.getSchema() : "public";
        Map<String, String> columnTypeMap = getColumnTypeMap(schema, plan.getTable());

        // Parse JSON body for SET clause
        try {
            Map<String, Object> updates = objectMapper.readValue(body,
                    new TypeReference<Map<String, Object>>() {
                    });

            if (!updates.isEmpty()) {
                sql.append(" SET ");
                List<String> setClauses = new ArrayList<>();
                for (Map.Entry<String, Object> entry : updates.entrySet()) {
                    String colType = columnTypeMap.get(entry.getKey());
                    setClauses.add(quote(entry.getKey()) + " = " + formatValueWithType(entry.getValue(), colType));
                }
                sql.append(String.join(", ", setClauses));
            }
        } catch (Exception e) {
            log.error("Error parsing JSON body", e);
            throw new RuntimeException("Invalid JSON body", e);
        }

        buildWhereClause(sql, plan);

        if (plan.isReturningAll()) {
            sql.append(" RETURNING *");
        }
    }

    private void buildDeleteSQL(StringBuilder sql, QueryPlan plan) {
        sql.append("DELETE FROM ");
        if (plan.getSchema() != null) {
            sql.append(quote(plan.getSchema())).append(".");
        }
        sql.append(quote(plan.getTable()));

        buildWhereClause(sql, plan);

        if (plan.isReturningAll()) {
            sql.append(" RETURNING *");
        }
    }

    /**
     * Build SQL for RPC (function call).
     * PostgREST wraps function calls in a SELECT to handle various return types:
     * - Scalar: SELECT function(params)
     * - SETOF/TABLE: SELECT * FROM function(params)
     * - VOID: SELECT function(params) with no result expected
     */
    private void buildRpcSQL(StringBuilder sql, QueryPlan plan) {
        String functionName = plan.getTable();
        String schema = plan.getSchema();
        Map<String, Object> params = plan.getPayload();

        // Get routine metadata to determine return type
        SchemaCache schemaCache = getSchemaCache();
        Routine routine = schemaCache != null ? schemaCache.getRoutine(schema, functionName) : null;

        boolean returnsSet = routine != null && routine.isReturnsSet();
        String returnType = routine != null ? routine.getReturnType() : null;

        // Build parameter list
        String paramList = buildRpcParams(params, routine);

        // Build the function call
        String qualifiedName = schema != null ? quote(schema) + "." + quote(functionName) : quote(functionName);
        String functionCall = qualifiedName + "(" + paramList + ")";

        if (returnsSet || (returnType != null && returnType.toLowerCase().startsWith("table"))) {
            // Function returns a set of rows - use SELECT * FROM
            sql.append("SELECT ");

            // Handle select columns
            if (plan.getSelectColumns() != null && !plan.getSelectColumns().isEmpty()) {
                List<String> quotedCols = new ArrayList<>();
                for (String col : plan.getSelectColumns()) {
                    if ("*".equals(col)) {
                        quotedCols.add("*");
                    } else {
                        quotedCols.add(quote(col));
                    }
                }
                sql.append(String.join(", ", quotedCols));
            } else {
                sql.append("*");
            }

            sql.append(" FROM ").append(functionCall);

            // Add WHERE clause for filtering results
            buildRpcWhereClause(sql, plan);

            // Add ORDER BY
            if (plan.getOrderBy() != null && !plan.getOrderBy().isEmpty()) {
                sql.append(" ORDER BY ").append(String.join(", ", plan.getOrderBy()));
            }

            // Add LIMIT and OFFSET
            if (plan.getLimit() != null) {
                sql.append(" LIMIT ").append(plan.getLimit());
            }
            if (plan.getOffset() != null) {
                sql.append(" OFFSET ").append(plan.getOffset());
            }
        } else {
            // Scalar or single record - use SELECT function()
            sql.append("SELECT ").append(functionCall);

            // For scalar functions, we can alias the result
            if (returnType != null && !returnType.toLowerCase().contains("record")) {
                sql.append(" AS result");
            }
        }
    }

    /**
     * Build WHERE clause for RPC results (when filtering set-returning functions).
     */
    private void buildRpcWhereClause(StringBuilder sql, QueryPlan plan) {
        List<String> allConditions = new ArrayList<>();

        // Build simple WHERE clauses
        if (plan.getWhereClauses() != null && !plan.getWhereClauses().isEmpty()) {
            for (WhereClause clause : plan.getWhereClauses()) {
                // For RPC results, columns are not qualified with table name
                WhereClause unqualified = WhereClause.builder()
                        .column(unqualifyColumn(clause.getColumn()))
                        .operator(clause.getOperator())
                        .value(clause.getValue())
                        .negate(clause.isNegate())
                        .operatorType(clause.getOperatorType())
                        .build();
                String condition = buildSingleWhereCondition(unqualified);
                allConditions.add(condition);
            }
        }

        // Build logical conditions
        if (plan.getLogicalConditions() != null && !plan.getLogicalConditions().isEmpty()) {
            for (LogicalCondition logicalCond : plan.getLogicalConditions()) {
                String condition = buildLogicalConditionSql(logicalCond, null); // null baseTable for RPC
                if (condition != null && !condition.isEmpty()) {
                    allConditions.add(condition);
                }
            }
        }

        if (!allConditions.isEmpty()) {
            sql.append(" WHERE ");
            sql.append(String.join(" AND ", allConditions));
        }
    }

    /**
     * Remove table qualification from column name.
     */
    private String unqualifyColumn(String column) {
        if (column != null && column.contains(".")) {
            return column.substring(column.lastIndexOf(".") + 1);
        }
        return column;
    }

    /**
     * Build parameter list for RPC call.
     * Supports both positional and named parameters.
     */
    private String buildRpcParams(Map<String, Object> params, Routine routine) {
        if (params == null || params.isEmpty()) {
            return "";
        }

        List<String> paramStrings = new ArrayList<>();

        if (routine != null && routine.getParameters() != null && !routine.getParameters().isEmpty()) {
            // Use named parameters based on routine metadata
            for (RoutineParam routineParam : routine.getParameters()) {
                String paramName = routineParam.getName();
                if (paramName != null && params.containsKey(paramName)) {
                    Object value = params.get(paramName);
                    paramStrings.add(quote(paramName) + " => " + formatRpcValue(value, routineParam.getDataType()));
                }
            }

            // Add any extra parameters not in routine metadata (variadic or dynamic)
            for (Map.Entry<String, Object> entry : params.entrySet()) {
                boolean found = routine.getParameters().stream()
                        .anyMatch(p -> entry.getKey().equals(p.getName()));
                if (!found) {
                    paramStrings.add(quote(entry.getKey()) + " => " + formatRpcValue(entry.getValue(), null));
                }
            }
        } else {
            // No routine metadata - use named parameters directly
            for (Map.Entry<String, Object> entry : params.entrySet()) {
                paramStrings.add(quote(entry.getKey()) + " => " + formatRpcValue(entry.getValue(), null));
            }
        }

        return String.join(", ", paramStrings);
    }

    /**
     * Format a value for RPC parameter.
     */
    private String formatRpcValue(Object value, String dataType) {
        if (value == null) {
            return "NULL";
        }

        // Handle based on data type if known
        if (dataType != null) {
            String lowerType = dataType.toLowerCase();
            if (lowerType.contains("json")) {
                try {
                    String json = objectMapper.writeValueAsString(value);
                    String suffix = lowerType.contains("jsonb") ? "::jsonb" : "::json";
                    return "'" + json.replace("'", "''") + "'" + suffix;
                } catch (Exception e) {
                    log.warn("Failed to serialize JSON for RPC param", e);
                }
            }
        }

        // Use standard formatting
        return formatValue(value);
    }

    /**
     * Execute RPC (function call) and return results.
     */
    private QueryResult executeRpc(String sql, QueryPlan plan, Preferences preferences) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);

        // Convert PostgreSQL arrays to Java Lists
        rows = convertPgArraysToLists(rows);

        QueryResult.QueryResultBuilder builder = QueryResult.builder()
                .data(rows)
                .totalCount(rows.size());

        // PostgREST answers scalar functions with the bare value (`true`, `42`),
        // and SETOF scalar functions with an array of values, not wrapped rows.
        SchemaCache schemaCache = getSchemaCache();
        Routine routine = schemaCache != null ? schemaCache.getRoutine(plan.getSchema(), plan.getTable()) : null;
        if (returnsScalar(routine)) {
            if (!routine.isReturnsSet()) {
                Object value = "void".equalsIgnoreCase(routine.getReturnType().trim()) || rows.isEmpty()
                        ? null
                        : rows.get(0).get("result");
                builder.scalar(true).scalarValue(value);
            } else if (rows.stream().allMatch(row -> row.size() == 1)) {
                List<Object> values = new ArrayList<>(rows.size());
                for (Map<String, Object> row : rows) {
                    values.add(row.values().iterator().next());
                }
                builder.scalar(true).scalarValue(values);
            }
        }

        return builder.build();
    }

    /**
     * True when the function returns a base or domain type (or a set of them),
     * which PostgREST serializes as bare JSON values. Composite, record and
     * TABLE(...) results keep the row shape.
     */
    static boolean returnsScalar(Routine routine) {
        if (routine == null || routine.isReturnsComposite() || routine.getReturnType() == null) {
            return false;
        }
        String type = routine.getReturnType().trim().toLowerCase(java.util.Locale.ROOT);
        return !type.isEmpty() && !type.startsWith("table") && !type.contains("record");
    }

    private void buildWhereClause(StringBuilder sql, QueryPlan plan) {
        List<String> allConditions = new ArrayList<>();

        // Build simple WHERE clauses (AND-combined)
        if (plan.getWhereClauses() != null && !plan.getWhereClauses().isEmpty()) {
            for (WhereClause clause : plan.getWhereClauses()) {
                String condition = buildSingleWhereCondition(clause);
                allConditions.add(condition);
            }
        }

        // Build logical conditions (or/and groups)
        if (plan.getLogicalConditions() != null && !plan.getLogicalConditions().isEmpty()) {
            for (LogicalCondition logicalCond : plan.getLogicalConditions()) {
                String condition = buildLogicalConditionSql(logicalCond, plan.getTable());
                if (condition != null && !condition.isEmpty()) {
                    allConditions.add(condition);
                }
            }
        }

        // Combine all conditions with AND
        if (!allConditions.isEmpty()) {
            sql.append(" WHERE ");
            sql.append(String.join(" AND ", allConditions));
        }
    }

    /**
     * Build SQL for a single WhereClause
     */
    private String buildSingleWhereCondition(WhereClause clause) {
        String condition;

        // Handle quantified filters (any/all) first
        if (clause.getQuantifier() != null) {
            condition = buildQuantifiedCondition(clause);
        }
        // Handle full-text search operators specially
        else if ("@@".equals(clause.getOperator())) {
            condition = buildFullTextSearchCondition(clause);
        } else if ("IN".equals(clause.getOperator())) {
            // Handle IN operator specially
            condition = buildInCondition(clause);
        } else if ("LIKE".equals(clause.getOperator()) || "ILIKE".equals(clause.getOperator())) {
            // Handle LIKE/ILIKE operators - convert * to %
            condition = buildLikeCondition(clause);
        } else if ("IS".equals(clause.getOperator())) {
            // Handle IS operator - special values should not be quoted
            condition = buildIsCondition(clause);
        } else if ("@>".equals(clause.getOperator()) || "<@".equals(clause.getOperator()) || "&&".equals(clause.getOperator())) {
            // Handle array/range operators
            condition = buildArrayCondition(clause);
        } else {
            condition = quoteQualifiedColumn(clause.getColumn()) + " " +
                    clause.getOperator() + " " +
                    formatValue(clause.getValue());
        }

        if (clause.isNegate()) {
            condition = "NOT (" + condition + ")";
        }

        return condition;
    }

    /**
     * Build SQL for a LogicalCondition (recursive for nested or/and)
     */
    private String buildLogicalConditionSql(LogicalCondition condition, String baseTable) {
        if (condition == null) {
            return null;
        }

        String result;

        switch (condition.getType()) {
            case FILTER -> {
                // Single filter condition
                Filter filter = condition.getFilter();
                if (filter == null) {
                    return null;
                }
                WhereClause clause = convertFilterToWhereClause(filter, baseTable);
                result = buildSingleWhereCondition(clause);
            }
            case OR -> {
                // OR group of conditions
                if (condition.getConditions() == null || condition.getConditions().isEmpty()) {
                    return null;
                }
                List<String> subConditions = new ArrayList<>();
                for (LogicalCondition subCond : condition.getConditions()) {
                    String subSql = buildLogicalConditionSql(subCond, baseTable);
                    if (subSql != null && !subSql.isEmpty()) {
                        subConditions.add(subSql);
                    }
                }
                if (subConditions.isEmpty()) {
                    return null;
                }
                result = "(" + String.join(" OR ", subConditions) + ")";
            }
            case AND -> {
                // AND group of conditions
                if (condition.getConditions() == null || condition.getConditions().isEmpty()) {
                    return null;
                }
                List<String> subConditions = new ArrayList<>();
                for (LogicalCondition subCond : condition.getConditions()) {
                    String subSql = buildLogicalConditionSql(subCond, baseTable);
                    if (subSql != null && !subSql.isEmpty()) {
                        subConditions.add(subSql);
                    }
                }
                if (subConditions.isEmpty()) {
                    return null;
                }
                result = "(" + String.join(" AND ", subConditions) + ")";
            }
            default -> {
                return null;
            }
        }

        // Apply negation if needed
        if (condition.isNegate() && result != null) {
            result = "NOT " + result;
        }

        return result;
    }

    /**
     * Convert a Filter to WhereClause for SQL generation
     */
    private WhereClause convertFilterToWhereClause(Filter filter, String baseTable) {
        String operator = switch (filter.getOperator()) {
            case EQ -> "=";
            case NEQ -> "!=";
            case GT -> ">";
            case GTE -> ">=";
            case LT -> "<";
            case LTE -> "<=";
            case LIKE -> "LIKE";
            case ILIKE -> "ILIKE";
            case MATCH -> "~";
            case IMATCH -> "~*";
            case IN -> "IN";
            case IS -> "IS";
            case FTS, PLFTS, PHFTS, WFTS -> "@@";
            case CS -> "@>";
            case CD -> "<@";
            case OV -> "&&";
            case SL -> "<<";
            case SR -> ">>";
            case NXR -> "&<";
            case NXL -> "&>";
            case ADJ -> "-|-";
            case ISDISTINCT -> "IS DISTINCT FROM";
        };

        // Qualify column with base table if not already qualified
        String column = filter.getColumn();
        if (!column.contains(".")) {
            column = baseTable + "." + column;
        }

        return WhereClause.builder()
                .column(column)
                .operator(operator)
                .value(filter.getValue())
                .negate(filter.isNegate())
                .operatorType(filter.getOperator().name())
                .quantifier(filter.getQuantifier())
                .build();
    }

    private String buildFullTextSearchCondition(WhereClause clause) {
        String column = quoteQualifiedColumn(clause.getColumn());
        String searchValue = clause.getValue().toString();

        // Choose correct tsquery function based on operator type
        String tsqueryFunc = switch (clause.getOperatorType()) {
            case "PLFTS" -> "plainto_tsquery";
            case "PHFTS" -> "phraseto_tsquery";
            case "WFTS" -> "websearch_to_tsquery";
            default -> "to_tsquery"; // FTS
        };

        return column + " @@ " + tsqueryFunc + "(" + formatValue(searchValue) + ")";
    }

    private String buildInCondition(WhereClause clause) {
        String column = quoteQualifiedColumn(clause.getColumn());
        String valueStr = clause.getValue().toString();

        // Parse IN values: (value1,value2,value3) or value1,value2,value3
        // Remove parentheses if present
        if (valueStr.startsWith("(") && valueStr.endsWith(")")) {
            valueStr = valueStr.substring(1, valueStr.length() - 1);
        }

        // Split by comma and format each value
        String[] values = valueStr.split(",");
        List<String> formattedValues = new ArrayList<>();
        for (String value : values) {
            formattedValues.add(formatValue(value.strip()));
        }

        return column + " IN (" + String.join(", ", formattedValues) + ")";
    }

    private String buildLikeCondition(WhereClause clause) {
        String column = quoteQualifiedColumn(clause.getColumn());
        String valueStr = clause.getValue().toString();

        // PostgREST uses * as wildcard in API, SQL LIKE uses %
        // Convert: *Tablet* -> %Tablet%
        String sqlValue = valueStr.replace("*", "%");

        return column + " " + clause.getOperator() + " " + formatValue(sqlValue);
    }

    private String buildIsCondition(WhereClause clause) {
        String column = quoteQualifiedColumn(clause.getColumn());

        // Handle null value directly
        if (clause.getValue() == null) {
            return column + " IS NULL";
        }

        String valueStr = clause.getValue().toString().toLowerCase();

        // IS operator special values should NOT be quoted
        // PostgREST: ?age=is.null, ?active=is.true, ?status=is.unknown
        String sqlValue = switch (valueStr) {
            case "null" -> "NULL";
            case "true" -> "TRUE";
            case "false" -> "FALSE";
            case "unknown" -> "UNKNOWN";
            default -> formatValue(clause.getValue()); // Regular value, quote it
        };

        return column + " IS " + sqlValue;
    }

    private String buildArrayCondition(WhereClause clause) {
        String column = quoteQualifiedColumn(clause.getColumn());
        String valueStr = clause.getValue().toString();

        // PostgREST array syntax: {value1,value2,value3} or [start,end] for ranges
        // Convert to PostgreSQL array literal
        String arrayValue;

        if (valueStr.startsWith("{") && valueStr.endsWith("}")) {
            // Array format: {value1,value2,value3} -> ARRAY['value1','value2','value3']
            String content = valueStr.substring(1, valueStr.length() - 1);
            String[] values = content.split(",");
            List<String> formattedValues = new ArrayList<>();
            for (String value : values) {
                formattedValues.add(formatValue(value.strip()));
            }
            arrayValue = "ARRAY[" + String.join(", ", formattedValues) + "]";
        } else if (valueStr.startsWith("[") && valueStr.endsWith("]")) {
            // Range format: [2017-01-01,2017-06-30] -> keep as-is with quotes
            arrayValue = formatValue(valueStr);
        } else {
            // Fallback: treat as regular value
            arrayValue = formatValue(valueStr);
        }

        return column + " " + clause.getOperator() + " " + arrayValue;
    }

    /**
     * Build SQL for quantified filters (any/all).
     * PostgREST syntax: column=op(any).{val1,val2} or column=op(all).{val1,val2}
     * SQL output: column op ANY(ARRAY[val1, val2]) or column op ALL(ARRAY[val1, val2])
     * <p>
     * Supported operators with quantifiers:
     * - eq, neq, gt, gte, lt, lte: Standard comparison
     * - like, ilike: Pattern matching (converts * to %)
     * - match, imatch: Regex matching
     */
    private String buildQuantifiedCondition(WhereClause clause) {
        String column = quoteQualifiedColumn(clause.getColumn());
        String operator = clause.getOperator();
        String valueStr = clause.getValue().toString();
        Filter.Quantifier quantifier = clause.getQuantifier();

        // Parse the array values from {val1,val2,...} format
        List<String> formattedValues = parseQuantifierArrayValues(valueStr, operator);

        // Build the ARRAY literal
        String arrayLiteral = "ARRAY[" + String.join(", ", formattedValues) + "]";

        // Determine the quantifier SQL keyword
        String quantifierSql = (quantifier == Filter.Quantifier.ANY) ? "ANY" : "ALL";

        // Generate the SQL condition
        // Format: column OPERATOR QUANTIFIER(ARRAY[...])
        return column + " " + operator + " " + quantifierSql + "(" + arrayLiteral + ")";
    }

    /**
     * Parse array values from PostgREST quantifier syntax: {val1,val2,...}
     * Also handles LIKE/ILIKE pattern conversion (* -> %)
     */
    private List<String> parseQuantifierArrayValues(String valueStr, String operator) {
        List<String> formattedValues = new ArrayList<>();

        // Remove curly braces if present
        String content = valueStr;
        if (valueStr.startsWith("{") && valueStr.endsWith("}")) {
            content = valueStr.substring(1, valueStr.length() - 1);
        }

        // Split by comma (respecting escaped commas if needed)
        String[] values = splitQuantifierValues(content);

        for (String value : values) {
            String trimmedValue = value.strip();

            // For LIKE/ILIKE operators, convert * to %
            if ("LIKE".equals(operator) || "ILIKE".equals(operator)) {
                trimmedValue = trimmedValue.replace("*", "%");
            }

            formattedValues.add(formatValue(trimmedValue));
        }

        return formattedValues;
    }

    /**
     * Split quantifier values, handling potential edge cases.
     * PostgREST uses comma as separator within curly braces.
     */
    private String[] splitQuantifierValues(String content) {
        // Simple split by comma - PostgREST doesn't support escaped commas in quantifier arrays
        return content.split(",");
    }

    private QueryResult executeSelect(String sql, QueryPlan plan, Preferences preferences) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);

        // Convert PostgreSQL arrays to Java Lists for proper JSON serialization
        rows = convertPgArraysToLists(rows);

        // Convert flat rows to nested structure for embedded resources (PostgREST compatible)
        if (plan.getJoins() != null && !plan.getJoins().isEmpty()) {
            rows = convertToNestedStructure(rows, plan);
        }

        QueryResult.QueryResultBuilder builder = QueryResult.builder()
                .data(rows)
                .totalCount(rows.size());

        // Get exact count if requested
        if (preferences != null &&
                preferences.getCountPreference() == Preferences.CountPreference.EXACT) {
            String countSQL = buildCountSQL(plan);
            Integer count = jdbcTemplate.queryForObject(countSQL, Integer.class);
            builder.totalCount(count != null ? count : 0);
        }

        return builder.build();
    }

    private QueryResult executeInsert(String sql, QueryPlan plan, String body, Preferences preferences) {
        if (plan.isReturningAll()) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
            // Convert PostgreSQL arrays to Java Lists for proper JSON serialization
            rows = convertPgArraysToLists(rows);
            return QueryResult.builder()
                    .data(rows)
                    .totalCount(rows.size())
                    .build();
        } else {
            int affected = jdbcTemplate.update(sql);
            return QueryResult.builder()
                    .totalCount(affected)
                    .build();
        }
    }

    private QueryResult executeUpsert(String sql, QueryPlan plan, String body, Preferences preferences) {
        if (plan.isReturningAll()) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
            // Convert PostgreSQL arrays to Java Lists for proper JSON serialization
            rows = convertPgArraysToLists(rows);
            return QueryResult.builder()
                    .data(rows)
                    .totalCount(rows.size())
                    .build();
        } else {
            int affected = jdbcTemplate.update(sql);
            return QueryResult.builder()
                    .totalCount(affected)
                    .build();
        }
    }

    private QueryResult executeUpdate(String sql, QueryPlan plan, String body, Preferences preferences) {
        if (plan.isReturningAll()) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
            // Convert PostgreSQL arrays to Java Lists for proper JSON serialization
            rows = convertPgArraysToLists(rows);
            return QueryResult.builder()
                    .data(rows)
                    .totalCount(rows.size())
                    .build();
        } else {
            int affected = jdbcTemplate.update(sql);
            return QueryResult.builder()
                    .totalCount(affected)
                    .build();
        }
    }

    private QueryResult executeDelete(String sql, QueryPlan plan, Preferences preferences) {
        if (plan.isReturningAll()) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
            // Convert PostgreSQL arrays to Java Lists for proper JSON serialization
            rows = convertPgArraysToLists(rows);
            return QueryResult.builder()
                    .data(rows)
                    .totalCount(rows.size())
                    .build();
        } else {
            int affected = jdbcTemplate.update(sql);
            return QueryResult.builder()
                    .totalCount(affected)
                    .build();
        }
    }

    private String buildCountSQL(QueryPlan plan) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM ");
        if (plan.getSchema() != null) {
            sql.append(quote(plan.getSchema())).append(".");
        }
        sql.append(quote(plan.getTable()));
        buildWhereClause(sql, plan);
        return sql.toString();
    }

    /**
     * Quote PostgreSQL identifier (table/column name) to handle special characters
     * Follows PostgreSQL standard: https://www.postgresql.org/docs/current/sql-syntax-lexical.html
     * Implementation matches PostgREST's escapeIdent function
     */
    private String quote(String identifier) {
        if (identifier == null) {
            throw new IllegalArgumentException("Identifier cannot be null");
        }
        // Trim whitespace and remove null characters (security measure, matches PostgREST)
        // Use stripAllWhitespace() to handle ALL whitespace including non-breaking spaces
        // Note: Java's strip() does NOT remove U+00A0, U+2007, U+202F (non-breaking spaces)
        String trimmed = stripAllWhitespace(trimNullChars(identifier));
        // Escape internal double quotes by doubling them
        String escaped = trimmed.replace("\"", "\"\"");
        // Wrap in double quotes
        return "\"" + escaped + "\"";
    }

    /**
     * Remove null characters from string
     * PostgreSQL doesn't allow null bytes in identifiers
     * Matches PostgREST's trimNullChars function
     */
    private String trimNullChars(String str) {
        int nullIndex = str.indexOf('\0');
        return nullIndex >= 0 ? str.substring(0, nullIndex) : str;
    }

    /**
     * Strip ALL whitespace including non-breaking spaces.
     * Java's strip() does NOT remove non-breaking spaces (U+00A0, U+2007, U+202F)
     * because Character.isWhitespace() explicitly excludes them.
     * This method handles all Unicode whitespace characters.
     */
    private String stripAllWhitespace(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        // First use strip() for standard whitespace
        String result = str.strip();
        // Then handle non-breaking spaces that strip() misses
        // Strip from start
        int start = 0;
        while (start < result.length() && isAnyWhitespace(result.charAt(start))) {
            start++;
        }
        // Strip from end
        int end = result.length();
        while (end > start && isAnyWhitespace(result.charAt(end - 1))) {
            end--;
        }
        return result.substring(start, end);
    }

    /**
     * Check if character is any kind of whitespace, including non-breaking spaces
     */
    private boolean isAnyWhitespace(char c) {
        return Character.isWhitespace(c) ||
                c == '\u00A0' ||  // Non-breaking space
                c == '\u2007' ||  // Figure space
                c == '\u202F' ||  // Narrow no-break space
                c == '\u3000' ||  // Ideographic space
                c == '\uFEFF';    // Zero-width no-break space (BOM)
    }

    /**
     * Quote a potentially qualified column name (table.column)
     * Converts "table.column" to "table"."column"
     * Keeps "column" as "column"
     * Special handling for "*" - asterisk should not be quoted
     */
    private String quoteQualifiedColumn(String column) {
        if (column == null) {
            throw new IllegalArgumentException("Column cannot be null");
        }
        // Use stripAllWhitespace() to handle ALL whitespace including non-breaking spaces
        String trimmed = stripAllWhitespace(column);

        // Handle standalone asterisk - don't quote it
        if ("*".equals(trimmed)) {
            return "*";
        }

        if (trimmed.contains(".")) {
            String[] parts = trimmed.split("\\.", 2);
            // Handle table.* syntax - don't quote the asterisk
            if ("*".equals(stripAllWhitespace(parts[1]))) {
                return quote(parts[0]) + ".*";
            }
            return quote(parts[0]) + "." + quote(parts[1]);
        } else {
            return quote(trimmed);
        }
    }

    /**
     * Convert query result rows to nested structure for embedded resources.
     * <p>
     * With the PostgREST-style subquery approach, embedded resources are returned
     * as JSON objects directly from PostgreSQL (via row_to_json). This method
     * handles parsing those JSON values.
     * <p>
     * Example input (from PostgreSQL):
     * {
     * "id": 1,
     * "student_id": "abc",
     * "course": "{\"id\": \"xyz\", \"name\": \"Math\"}"  // JSON string from row_to_json
     * }
     * <p>
     * Example output:
     * {
     * "id": 1,
     * "student_id": "abc",
     * "course": {
     * "id": "xyz",
     * "name": "Math"
     * }
     * }
     */
    private List<Map<String, Object>> convertToNestedStructure(List<Map<String, Object>> rows, QueryPlan plan) {
        if (rows == null || rows.isEmpty() || plan.getJoins() == null || plan.getJoins().isEmpty()) {
            return rows;
        }

        // Collect all embedding names
        Set<String> embeddingNames = new HashSet<>();
        for (JoinClause join : plan.getJoins()) {
            String embeddingName = join.getEmbeddingName();
            if (embeddingName == null) {
                embeddingName = join.getAlias() != null ? join.getAlias() : join.getTargetTable();
            }
            embeddingNames.add(embeddingName);
        }

        List<Map<String, Object>> nestedRows = new ArrayList<>();

        for (Map<String, Object> row : rows) {
            Map<String, Object> nestedRow = new HashMap<>();

            // Process each column in the row
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                String columnName = entry.getKey();
                Object value = entry.getValue();

                // Check if this is an embedded resource (row_to_json result)
                if (embeddingNames.contains(columnName)) {
                    nestedRow.put(columnName, parseEmbeddedValue(value, columnName));
                } else {
                    // Regular column - add directly
                    nestedRow.put(columnName, value);
                }
            }

            nestedRows.add(nestedRow);
        }

        return nestedRows;
    }

    /**
     * Parse embedded resource value from PostgreSQL row_to_json result.
     * Handles: null, Map (already parsed), String (JSON), PGobject (PostgreSQL JSON type)
     */
    private Object parseEmbeddedValue(Object value, String embeddingName) {
        if (value == null) {
            return null;
        }

        // Already a Map (some JDBC drivers auto-parse JSON)
        if (value instanceof Map) {
            return value;
        }

        // JSON string - parse it
        if (value instanceof String) {
            String jsonStr = (String) value;
            if (jsonStr.isEmpty() || "null".equalsIgnoreCase(jsonStr)) {
                return null;
            }
            try {
                return objectMapper.readValue(jsonStr, Map.class);
            } catch (Exception e) {
                log.warn("Failed to parse JSON for embedded resource {}: {}", embeddingName, e.getMessage());
                return value;
            }
        }

        // PostgreSQL PGobject (json/jsonb type) - get string value and parse
        if (value.getClass().getName().equals("org.postgresql.util.PGobject")) {
            try {
                String jsonStr = value.toString();
                if (jsonStr == null || jsonStr.isEmpty() || "null".equalsIgnoreCase(jsonStr)) {
                    return null;
                }
                return objectMapper.readValue(jsonStr, Map.class);
            } catch (Exception e) {
                log.warn("Failed to parse PGobject JSON for embedded resource {}: {}", embeddingName, e.getMessage());
                return value;
            }
        }

        // Unknown type - return as-is
        log.debug("Unknown embedded value type for {}: {}", embeddingName, value.getClass().getName());
        return value;
    }

    /**
     * Convert PostgreSQL special types to Java objects for proper JSON serialization.
     * This handles:
     * 1. PostgreSQL Array -> Java List
     * 2. PostgreSQL JSONB/JSON (PGobject) -> Java Map/List (native JSON)
     * <p>
     * This ensures PostgREST-compatible response format where JSONB columns
     * are returned as native JSON objects/arrays, not as escaped strings.
     */
    private List<Map<String, Object>> convertPgArraysToLists(List<Map<String, Object>> rows) {
        List<Map<String, Object>> convertedRows = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> convertedRow = new HashMap<>();
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                Object value = entry.getValue();
                Object convertedValue = convertPgValue(value);
                convertedRow.put(entry.getKey(), convertedValue);
            }
            convertedRows.add(convertedRow);
        }
        return convertedRows;
    }

    /**
     * Convert a single PostgreSQL value to a Java-friendly type.
     * Handles Array, PGobject (json/jsonb), and nested structures.
     */
    private Object convertPgValue(Object value) {
        if (value == null) {
            return null;
        }

        // Debug logging for troubleshooting
        if (log.isDebugEnabled()) {
            log.debug("convertPgValue: class={}, value={}", value.getClass().getName(), value);
        }

        // Handle PostgreSQL Array type
        if (value instanceof Array) {
            try {
                Array pgArray = (Array) value;
                Object[] arrayData = (Object[]) pgArray.getArray();
                // Recursively convert array elements (they might be PGobjects too)
                List<Object> convertedList = new ArrayList<>();
                for (Object element : arrayData) {
                    convertedList.add(convertPgValue(element));
                }
                return convertedList;
            } catch (SQLException e) {
                log.error("Error converting PostgreSQL array to List", e);
                return value;
            }
        }

        // Handle PostgreSQL PGobject (json/jsonb type)
        // PGobject.getType() returns "json" or "jsonb"
        // Also handle any object with getType() and getValue() methods (for flexibility)
        boolean isPGobject = isPGobjectLike(value);
        if (log.isDebugEnabled()) {
            log.debug("isPGobjectLike check: class={}, result={}", value.getClass().getName(), isPGobject);
        }
        if (isPGobject) {
            try {
                // Use reflection to avoid compile-time dependency on PostgreSQL driver
                java.lang.reflect.Method getTypeMethod = value.getClass().getMethod("getType");
                java.lang.reflect.Method getValueMethod = value.getClass().getMethod("getValue");

                String type = (String) getTypeMethod.invoke(value);
                String jsonStr = (String) getValueMethod.invoke(value);

                log.debug("PGobject detected: type={}, value={}", type, jsonStr);

                if (type != null && (type.equals("json") || type.equals("jsonb"))) {
                    if (jsonStr == null || jsonStr.isEmpty() || "null".equalsIgnoreCase(jsonStr)) {
                        return null;
                    }
                    // Parse JSON string to native Java object (Map or List)
                    Object parsed = objectMapper.readValue(jsonStr, Object.class);
                    log.debug("PGobject converted to: {}", parsed.getClass().getName());
                    return parsed;
                }
            } catch (Exception e) {
                log.warn("Failed to convert PGobject to native JSON: {}", e.getMessage());
                // Fallback: return the string representation
                return value.toString();
            }
        }

        // Handle Map values (might contain nested PGobjects)
        if (value instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> mapValue = (Map<String, Object>) value;
            Map<String, Object> convertedMap = new HashMap<>();
            for (Map.Entry<String, Object> entry : mapValue.entrySet()) {
                convertedMap.put(entry.getKey(), convertPgValue(entry.getValue()));
            }
            return convertedMap;
        }

        // Handle List values (might contain nested PGobjects)
        if (value instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> listValue = (List<Object>) value;
            List<Object> convertedList = new ArrayList<>();
            for (Object element : listValue) {
                convertedList.add(convertPgValue(element));
            }
            return convertedList;
        }

        // Return other values as-is
        return value;
    }

    /**
     * Check if an object is PGobject-like (has getType() and getValue() methods).
     * This allows us to handle PostgreSQL's PGobject without compile-time dependency,
     * and also supports testing with mock objects.
     */
    private boolean isPGobjectLike(Object value) {
        if (value == null) {
            return false;
        }

        // Check by class name first (most common case)
        String className = value.getClass().getName();
        if (className.equals("org.postgresql.util.PGobject")) {
            return true;
        }

        // Check by interface/methods (for mock objects in tests)
        try {
            value.getClass().getMethod("getType");
            value.getClass().getMethod("getValue");
            // Has both methods - check if getType returns json/jsonb
            java.lang.reflect.Method getTypeMethod = value.getClass().getMethod("getType");
            Object type = getTypeMethod.invoke(value);
            if (type instanceof String) {
                String typeStr = (String) type;
                return "json".equals(typeStr) || "jsonb".equals(typeStr);
            }
        } catch (Exception e) {
            // Doesn't have the required methods
        }
        return false;
    }

    /**
     * Format value with column type awareness.
     * This is the preferred method for INSERT/UPDATE operations where we know the target column type.
     *
     * @param value      The value to format
     * @param columnType The PostgreSQL column type (e.g., "jsonb", "json", "_text" for text[], "integer")
     * @return Formatted SQL value string
     */
    private String formatValueWithType(Object value, String columnType) {
        if (value == null) {
            return "NULL";
        }

        // Check if the column is JSONB or JSON type
        boolean isJsonColumn = columnType != null &&
                (columnType.equalsIgnoreCase("jsonb") || columnType.equalsIgnoreCase("json"));

        // Check if the column is a PostgreSQL array type (starts with "_")
        boolean isArrayColumn = columnType != null && columnType.startsWith("_");

        if (value instanceof List<?>) {
            List<?> list = (List<?>) value;

            if (isJsonColumn) {
                // Target column is JSONB/JSON - serialize as JSON array
                try {
                    String json = objectMapper.writeValueAsString(list);
                    String suffix = columnType.equalsIgnoreCase("jsonb") ? "::jsonb" : "::json";
                    return "'" + json.replace("'", "''") + "'" + suffix;
                } catch (Exception e) {
                    log.error("Error serializing List to JSON for JSONB column", e);
                    return "'" + list.toString().replace("'", "''") + "'::jsonb";
                }
            } else if (isArrayColumn || columnType == null) {
                // Target column is PostgreSQL array or unknown - use ARRAY[] syntax
                if (list.isEmpty()) {
                    return "ARRAY[]";
                }
                List<String> formattedElements = new ArrayList<>();
                for (Object element : list) {
                    formattedElements.add(formatValue(element));
                }
                return "ARRAY[" + String.join(", ", formattedElements) + "]";
            } else {
                // Unknown column type with List value - default to JSONB for safety
                try {
                    String json = objectMapper.writeValueAsString(list);
                    return "'" + json.replace("'", "''") + "'::jsonb";
                } catch (Exception e) {
                    log.error("Error serializing List to JSON", e);
                    return "'" + list.toString().replace("'", "''") + "'";
                }
            }
        } else if (value instanceof Map<?, ?>) {
            // JSON objects should always be JSONB
            try {
                String json = objectMapper.writeValueAsString(value);
                String suffix = (columnType != null && columnType.equalsIgnoreCase("json")) ? "::json" : "::jsonb";
                return "'" + json.replace("'", "''") + "'" + suffix;
            } catch (Exception e) {
                log.error("Error serializing Map to JSON", e);
                return "'" + value.toString().replace("'", "''") + "'";
            }
        } else {
            // For other types, use the standard formatValue
            return formatValue(value);
        }
    }

    private String formatValue(Object value) {
        if (value == null) {
            return "NULL";
        } else if (value instanceof String) {
            return "'" + ((String) value).replace("'", "''") + "'";
        } else if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        } else if (value instanceof List<?>) {
            // Handle JSON arrays -> PostgreSQL array syntax (default behavior without type info)
            List<?> list = (List<?>) value;
            if (list.isEmpty()) {
                return "ARRAY[]";
            }
            List<String> formattedElements = new ArrayList<>();
            for (Object element : list) {
                formattedElements.add(formatValue(element));
            }
            return "ARRAY[" + String.join(", ", formattedElements) + "]";
        } else if (value instanceof Map<?, ?>) {
            // Handle JSON objects -> PostgreSQL JSONB
            try {
                String json = objectMapper.writeValueAsString(value);
                return "'" + json.replace("'", "''") + "'::jsonb";
            } catch (Exception e) {
                log.error("Error serializing Map to JSON", e);
                return "'" + value.toString().replace("'", "''") + "'";
            }
        } else {
            // Fallback: convert to string
            return "'" + value.toString().replace("'", "''") + "'";
        }
    }
}
