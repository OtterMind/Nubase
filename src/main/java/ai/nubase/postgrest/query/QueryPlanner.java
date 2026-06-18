package ai.nubase.postgrest.query;

import ai.nubase.common.context.MultiTenancyContext;
import ai.nubase.postgrest.api.*;
import ai.nubase.postgrest.multidb.SchemaCacheManager;
import ai.nubase.postgrest.schema.Column;
import lombok.extern.slf4j.Slf4j;
import ai.nubase.postgrest.schema.ForeignKey;
import ai.nubase.postgrest.schema.SchemaCache;
import ai.nubase.postgrest.schema.Table;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Query Planner - Builds query execution plans from API requests
 * Equivalent to PostgREST's Plan module
 */
@Slf4j
@Service
public class QueryPlanner {

    private final SchemaCacheManager schemaCacheManager;

    public QueryPlanner(SchemaCacheManager schemaCacheManager) {
        this.schemaCacheManager = schemaCacheManager;
    }

    /**
     * Get SchemaCache for current database
     */
    private SchemaCache getSchemaCache() {
        String dbKey = MultiTenancyContext.getDatabaseKey();
        return schemaCacheManager.getSchemaCache(dbKey);
    }

    public QueryPlan plan(ApiRequest request) {
        QueryPlan.QueryPlanBuilder builder = QueryPlan.builder()
            .schema(request.getSchema())
            .table(request.getTable());

        // Check if this is an RPC call
        if (request.isRpcCall()) {
            buildRpcPlan(builder, request);
            return builder.build();
        }

        switch (request.getMethod()) {
            case "GET", "HEAD" -> buildSelectPlan(builder, request);
            case "POST" -> {
                // POST with on_conflict parameter should be treated as UPSERT
                if (request.getUpsertOption() != null &&
                    request.getUpsertOption().getConflictColumns() != null &&
                    !request.getUpsertOption().getConflictColumns().isEmpty()) {
                    buildUpsertPlan(builder, request);
                } else {
                    buildInsertPlan(builder, request);
                }
            }
            case "PUT" -> buildUpsertPlan(builder, request);
            case "PATCH" -> buildUpdatePlan(builder, request);
            case "DELETE" -> buildDeletePlan(builder, request);
            default -> throw new IllegalArgumentException("Unsupported HTTP method: " + request.getMethod());
        }

        return builder.build();
    }

    private void buildRpcPlan(QueryPlan.QueryPlanBuilder builder, ApiRequest request) {
        builder.type(QueryPlan.QueryType.CALL_FUNCTION);

        // Store RPC parameters in payload
        if (request.getRpcParams() != null) {
            builder.payload(request.getRpcParams());
        }

        // Handle select columns for filtering returned columns
        if (request.getSelect() != null && !request.getSelect().isEmpty()) {
            List<String> columns = new ArrayList<>();
            for (SelectColumn col : request.getSelect()) {
                if (col.getEmbedded() == null || col.getEmbedded().isEmpty()) {
                    columns.add(col.getName());
                }
            }
            builder.selectColumns(columns);
        }

        // Handle ORDER BY for ordering results
        if (request.getOrderBy() != null) {
            builder.orderBy(request.getOrderBy().stream()
                .map(orderBy -> buildOrderByClause(orderBy, request.getTable()))
                .collect(Collectors.toList()));
        }

        applyReadPagination(builder, request.getRange());

        // Handle filters (for filtering function results)
        if (request.getFilters() != null && !request.getFilters().isEmpty()) {
            builder.whereClauses(request.getFilters().stream()
                .map(filter -> buildWhereClause(filter, request.getTable()))
                .collect(Collectors.toList()));
        }

        // Add logical conditions
        if (request.getLogicalConditions() != null && !request.getLogicalConditions().isEmpty()) {
            builder.logicalConditions(request.getLogicalConditions());
        }
    }

    private void buildSelectPlan(QueryPlan.QueryPlanBuilder builder, ApiRequest request) {
        builder.type(QueryPlan.QueryType.SELECT);

        // Build SELECT columns
        if (request.getSelect() != null && !request.getSelect().isEmpty()) {
            List<String> columns = new ArrayList<>();
            List<QueryPlan.SelectColumnInfo> columnsWithInfo = new ArrayList<>();
            List<JoinClause> joins = new ArrayList<>();
            boolean hasAggregates = false;

            for (SelectColumn col : request.getSelect()) {
                if (col.getEmbedded() != null && !col.getEmbedded().isEmpty()) {
                    // Handle embedded resources - collect columns from the embedded table
                    JoinClause join = buildJoinForEmbedding(request.getSchema(), request.getTable(), col);
                    if (join != null) {
                        joins.add(join);
                    }
                } else if (col.isAggregate()) {
                    // Handle aggregate function
                    hasAggregates = true;
                    String qualified = qualifyBaseColumn(col.getName(), request.getTable());
                    columnsWithInfo.add(QueryPlan.SelectColumnInfo.builder()
                        .name(col.getName())
                        .alias(col.getAlias())
                        .isAggregate(true)
                        .aggregateFunction(col.getAggregateFunction())
                        .qualified(qualified)
                        .build());
                } else {
                    String qualified = qualifyBaseColumn(col.getName(), request.getTable());
                    columns.add(qualified);
                    columnsWithInfo.add(QueryPlan.SelectColumnInfo.builder()
                        .name(col.getName())
                        .alias(col.getAlias())
                        .isAggregate(false)
                        .qualified(qualified)
                        .build());
                }
            }

            builder.selectColumns(columns);
            builder.selectColumnsWithInfo(columnsWithInfo);
            builder.joins(joins);
            builder.hasAggregates(hasAggregates);
        } else {
            // Select all columns
            Table table = getSchemaCache().getTable(request.getSchema(), request.getTable());
            if (table != null) {
                builder.selectColumns(table.getColumns().stream()
                    .map(Column::getName)
                    .collect(Collectors.toList()));
            }
        }

        // Build WHERE clauses (simple filters)
        if (request.getFilters() != null) {
            builder.whereClauses(request.getFilters().stream()
                .map(filter -> buildWhereClause(filter, request.getTable()))
                .collect(Collectors.toList()));
        }

        // Add logical conditions (or/and groups)
        if (request.getLogicalConditions() != null && !request.getLogicalConditions().isEmpty()) {
            builder.logicalConditions(request.getLogicalConditions());
        }

        // Build ORDER BY
        if (request.getOrderBy() != null) {
            builder.orderBy(request.getOrderBy().stream()
                .map(orderBy -> buildOrderByClause(orderBy, request.getTable()))
                .collect(Collectors.toList()));
        }

        applyReadPagination(builder, request.getRange());
    }

    private void applyReadPagination(QueryPlan.QueryPlanBuilder builder, RangeHeader range) {
        Integer dbMaxRows = resolveDbMaxRows();
        PostgrestRowLimitResolver.ResolvedPagination pagination =
                PostgrestRowLimitResolver.resolve(range, dbMaxRows);
        if (pagination.offset() != null) {
            builder.offset(pagination.offset());
        }
        if (pagination.limit() != null) {
            builder.limit(pagination.limit());
        }
    }

    private Integer resolveDbMaxRows() {
        var config = MultiTenancyContext.getDatabaseConfig();
        return config != null ? config.getDbMaxRows() : null;
    }

    private void buildInsertPlan(QueryPlan.QueryPlanBuilder builder, ApiRequest request) {
        builder.type(QueryPlan.QueryType.INSERT);

        // Parse JSON body for insert
        // This will be handled by the query executor

        // Check if we should return representation
        if (request.getPreferences() != null &&
            request.getPreferences().getReturnPreference() == Preferences.ReturnPreference.REPRESENTATION) {
            builder.returningAll(true);
        }

        // Handle missing=default preference
        if (request.getPreferences() != null &&
            request.getPreferences().getMissingPreference() == Preferences.MissingPreference.DEFAULT) {
            builder.missingAsDefault(true);
        }

        // Store specified columns
        if (request.getColumns() != null && !request.getColumns().isEmpty()) {
            builder.specifiedColumns(request.getColumns());
        }
    }

    private void buildUpsertPlan(QueryPlan.QueryPlanBuilder builder, ApiRequest request) {
        builder.type(QueryPlan.QueryType.UPSERT);

        // Extract conflict columns from upsert option or filters
        if (request.getUpsertOption() != null && request.getUpsertOption().getConflictColumns() != null) {
            builder.conflictColumns(request.getUpsertOption().getConflictColumns());
            builder.ignoreConflict(request.getUpsertOption().getResolution() ==
                UpsertOption.Resolution.IGNORE_DUPLICATES);
        }

        // Check if we should return representation
        if (request.getPreferences() != null &&
            request.getPreferences().getReturnPreference() == Preferences.ReturnPreference.REPRESENTATION) {
            builder.returningAll(true);
        }
    }

    private void buildUpdatePlan(QueryPlan.QueryPlanBuilder builder, ApiRequest request) {
        builder.type(QueryPlan.QueryType.UPDATE);

        // Build WHERE clauses for update
        if (request.getFilters() != null) {
            builder.whereClauses(request.getFilters().stream()
                .map(filter -> buildWhereClause(filter, request.getTable()))
                .collect(Collectors.toList()));
        }

        // Add logical conditions (or/and groups)
        if (request.getLogicalConditions() != null && !request.getLogicalConditions().isEmpty()) {
            builder.logicalConditions(request.getLogicalConditions());
        }

        // Check if we should return representation
        if (request.getPreferences() != null &&
            request.getPreferences().getReturnPreference() == Preferences.ReturnPreference.REPRESENTATION) {
            builder.returningAll(true);
        }

        // Handle missing=default preference
        if (request.getPreferences() != null &&
            request.getPreferences().getMissingPreference() == Preferences.MissingPreference.DEFAULT) {
            builder.missingAsDefault(true);
        }

        // Store specified columns
        if (request.getColumns() != null && !request.getColumns().isEmpty()) {
            builder.specifiedColumns(request.getColumns());
        }
    }

    private void buildDeletePlan(QueryPlan.QueryPlanBuilder builder, ApiRequest request) {
        builder.type(QueryPlan.QueryType.DELETE);

        // Build WHERE clauses for delete
        if (request.getFilters() != null) {
            builder.whereClauses(request.getFilters().stream()
                .map(filter -> buildWhereClause(filter, request.getTable()))
                .collect(Collectors.toList()));
        }

        // Add logical conditions (or/and groups)
        if (request.getLogicalConditions() != null && !request.getLogicalConditions().isEmpty()) {
            builder.logicalConditions(request.getLogicalConditions());
        }

        // Check if we should return representation
        if (request.getPreferences() != null &&
            request.getPreferences().getReturnPreference() == Preferences.ReturnPreference.REPRESENTATION) {
            builder.returningAll(true);
        }
    }

    private JoinClause buildJoinForEmbedding(String schema, String table, SelectColumn col) {
        String embeddedTable = col.getName();
        String tableRef = col.getAlias() != null ? col.getAlias() : embeddedTable;
        JoinClause.JoinType joinType = resolveJoinType(col.getHint());

        // Collect embedded columns from the select specification
        // Use alias (tableRef) for column qualification if available
        List<String> embeddedSelectColumns = collectEmbeddedColumns(col.getEmbedded(), tableRef);

        // 1. Try forward foreign key: current_table.fk_column -> embedded_table.id
        // Example: tasks.project_id -> projects.id
        List<ForeignKey> forwardRelationships = getSchemaCache().getRelationships(schema, table);

        for (ForeignKey fk : forwardRelationships) {
            if (fk.getTargetTable().equals(embeddedTable)) {
                List<JoinClause.JoinCondition> conditions = new ArrayList<>();
                for (int i = 0; i < fk.getSourceColumns().size(); i++) {
                    // Use tableRef (alias or original table name) for right column
                    conditions.add(JoinClause.JoinCondition.builder()
                        .leftColumn(table + "." + fk.getSourceColumns().get(i))
                        .rightColumn(tableRef + "." + fk.getTargetColumns().get(i))
                        .operator("=")
                        .build());
                }

                return JoinClause.builder()
                    .type(joinType)
                    .targetSchema(fk.getTargetSchema())
                    .targetTable(fk.getTargetTable())
                    .alias(col.getAlias())
                    .conditions(conditions)
                    .selectColumns(embeddedSelectColumns)
                    .embeddingName(tableRef)
                    .build();
            }
        }

        // 2. Try reverse foreign key: embedded_table.fk_column -> current_table.id (one-to-many)
        // Example: projects <- tasks (tasks.project_id -> projects.id, viewed from projects side)
        List<ForeignKey> reverseRelationships = getSchemaCache().getRelationships(schema, embeddedTable);

        for (ForeignKey fk : reverseRelationships) {
            if (fk.getTargetTable().equals(table)) {
                // Swap the JOIN condition for reverse relationship
                List<JoinClause.JoinCondition> conditions = new ArrayList<>();
                for (int i = 0; i < fk.getSourceColumns().size(); i++) {
                    // Use tableRef (alias or original table name) for right column
                    conditions.add(JoinClause.JoinCondition.builder()
                        .leftColumn(table + "." + fk.getTargetColumns().get(i))
                        .rightColumn(tableRef + "." + fk.getSourceColumns().get(i))
                        .operator("=")
                        .build());
                }

                return JoinClause.builder()
                    .type(joinType)
                    .targetSchema(schema)  // Use current schema for reverse
                    .targetTable(embeddedTable)
                    .alias(col.getAlias())
                    .conditions(conditions)
                    .selectColumns(embeddedSelectColumns)
                    .embeddingName(tableRef)
                    .build();
            }
        }

        return null;
    }

    /**
     * Collect column names from embedded select columns.
     * Qualifies each column with the embedded table name.
     *
     * @param embedded List of SelectColumn from the embedded resource
     * @param embeddedTable The name of the embedded table
     * @return List of qualified column names (e.g., ["seo_schemas.schema_name", "seo_schemas.schema_type"])
     */
    private List<String> collectEmbeddedColumns(List<SelectColumn> embedded, String embeddedTable) {
        if (embedded == null || embedded.isEmpty()) {
            return null; // Will select all columns from the joined table
        }

        List<String> columns = new ArrayList<>();
        for (SelectColumn col : embedded) {
            // Skip nested embeddings for now (would need recursive handling)
            if (col.getEmbedded() == null || col.getEmbedded().isEmpty()) {
                String colName = col.getName();
                // If embedded resource requests all columns (*), return null to use table.* syntax
                if ("*".equals(colName)) {
                    return null;
                }
                columns.add(embeddedTable + "." + colName);
            }
        }
        return columns.isEmpty() ? null : columns;
    }

    /**
     * Parse embedding hint to JOIN type.
     * Examples:
     * - inner -> INNER
     * - left -> LEFT
     * - fk_name!inner -> INNER
     * Unknown hints keep default LEFT join.
     */
    private JoinClause.JoinType resolveJoinType(String hint) {
        if (hint == null || hint.isBlank()) {
            return JoinClause.JoinType.LEFT;
        }

        String[] tokens = hint.split("!");
        for (String token : tokens) {
            String normalized = token.toLowerCase(Locale.ROOT).strip();
            switch (normalized) {
                case "inner":
                    return JoinClause.JoinType.INNER;
                case "left":
                    return JoinClause.JoinType.LEFT;
                case "right":
                    return JoinClause.JoinType.RIGHT;
                case "full":
                    return JoinClause.JoinType.FULL;
                default:
                    // Non-join hint (e.g. relationship name), continue scanning.
            }
        }

        return JoinClause.JoinType.LEFT;
    }

    private WhereClause buildWhereClause(Filter filter, String baseTable) {
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
            case FTS -> "@@";
            case PLFTS -> "@@";
            case PHFTS -> "@@";
            case WFTS -> "@@";
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

        return WhereClause.builder()
            .column(qualifyBaseColumn(filter.getColumn(), baseTable))
            .operator(operator)
            .value(filter.getValue())
            .negate(filter.isNegate())
            .logicalOperator(WhereClause.LogicalOperator.AND)
            .operatorType(filter.getOperator().name())  // Store original operator type
            .build();
    }

    private String buildOrderByClause(OrderBy orderBy, String baseTable) {
        StringBuilder sb = new StringBuilder();
        // Quote column name, supporting table-qualified columns (e.g., tasks.created_at)
        sb.append(quoteQualifiedColumn(qualifyBaseColumn(orderBy.getColumn(), baseTable)));
        sb.append(" ").append(orderBy.getDirection().name());

        if (orderBy.getNullsOrder() != null) {
            sb.append(" NULLS ").append(orderBy.getNullsOrder().name());
        }

        return sb.toString();
    }

    /**
     * Qualify unqualified columns with base table to avoid ambiguous column references in JOIN queries.
     * Keeps already qualified columns and special expressions unchanged.
     */
    private String qualifyBaseColumn(String column, String baseTable) {
        if (column == null) {
            return null;
        }

        String trimmed = column.strip();
        if (trimmed.isEmpty() || "*".equals(trimmed)) {
            return trimmed;
        }

        if (trimmed.contains(".") || trimmed.contains("(") || trimmed.contains(")") ||
            trimmed.contains("->") || trimmed.contains("::")) {
            return trimmed;
        }

        return baseTable + "." + trimmed;
    }

    /**
     * Quote a potentially qualified column name (table.column)
     * Converts "table.column" to "table"."column"
     * Keeps "column" as "column"
     */
    private String quoteQualifiedColumn(String column) {
        if (column.contains(".")) {
            String[] parts = column.split("\\.", 2);
            return quote(parts[0]) + "." + quote(parts[1]);
        } else {
            return quote(column);
        }
    }

    /**
     * Quote PostgreSQL identifier (table/column name) to handle special characters
     */
    private String quote(String identifier) {
        if (identifier == null) {
            throw new IllegalArgumentException("Identifier cannot be null");
        }
        String trimmed = trimNullChars(identifier);
        String escaped = trimmed.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
    }

    /**
     * Remove null characters from string
     */
    private String trimNullChars(String str) {
        int nullIndex = str.indexOf('\0');
        return nullIndex >= 0 ? str.substring(0, nullIndex) : str;
    }
}
