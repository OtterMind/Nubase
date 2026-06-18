package ai.nubase.postgrest.query;

import ai.nubase.common.context.MultiTenancyContext;
import ai.nubase.postgrest.api.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import ai.nubase.postgrest.multidb.DatabaseConfig;
import ai.nubase.postgrest.multidb.SchemaCacheManager;
import ai.nubase.postgrest.schema.ForeignKey;
import ai.nubase.postgrest.schema.SchemaCache;
import ai.nubase.postgrest.schema.Table;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class QueryPlannerTest {

    @Mock
    private SchemaCacheManager schemaCacheManager;

    @Mock
    private SchemaCache schemaCache;

    private QueryPlanner queryPlanner;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        queryPlanner = new QueryPlanner(schemaCacheManager);

        MultiTenancyContext.ContextData contextData = MultiTenancyContext.ContextData.builder()
            .appCode("test_app")
            .databaseKey("test_db")
            .schemaName("public")
            .build();
        // Mock DatabaseContext to return a default database key
        MultiTenancyContext.setContext(contextData);
        when(schemaCacheManager.getSchemaCache("test_db")).thenReturn(schemaCache);
    }

    @Test
    void testBuildSelectPlan() {
        // Setup
        ApiRequest request = ApiRequest.builder()
            .schema("public")
            .table("users")
            .method("GET")
            .filters(new ArrayList<>())
            .build();

        when(schemaCache.getTable(anyString(), anyString())).thenReturn(
            Table.builder().columns(new ArrayList<>()).build()
        );

        // Execute
        QueryPlan plan = queryPlanner.plan(request);

        // Verify
        assertNotNull(plan);
        assertEquals(QueryPlan.QueryType.SELECT, plan.getType());
        assertEquals("public", plan.getSchema());
        assertEquals("users", plan.getTable());
    }

    @Test
    void testBuildInsertPlan() {
        // Setup
        ApiRequest request = ApiRequest.builder()
            .schema("public")
            .table("users")
            .method("POST")
            .body("{\"name\":\"John\"}")
            .build();

        // Execute
        QueryPlan plan = queryPlanner.plan(request);

        // Verify
        assertNotNull(plan);
        assertEquals(QueryPlan.QueryType.INSERT, plan.getType());
    }

    @Test
    void testBuildUpsertPlan() {
        // Setup
        ApiRequest request = ApiRequest.builder()
            .schema("public")
            .table("users")
            .method("PUT")
            .body("{\"id\":1,\"name\":\"John\"}")
            .upsertOption(UpsertOption.builder()
                .conflictColumns(List.of("id"))
                .resolution(UpsertOption.Resolution.MERGE_DUPLICATES)
                .build())
            .build();

        // Execute
        QueryPlan plan = queryPlanner.plan(request);

        // Verify
        assertNotNull(plan);
        assertEquals(QueryPlan.QueryType.UPSERT, plan.getType());
        assertEquals(List.of("id"), plan.getConflictColumns());
        assertFalse(plan.isIgnoreConflict());
    }

    @Test
    void testBuildUpdatePlan() {
        // Setup
        ApiRequest request = ApiRequest.builder()
            .schema("public")
            .table("users")
            .method("PATCH")
            .body("{\"name\":\"Updated\"}")
            .filters(List.of(
                Filter.builder()
                    .column("id")
                    .operator(Filter.FilterOperator.EQ)
                    .value("1")
                    .build()
            ))
            .build();

        // Execute
        QueryPlan plan = queryPlanner.plan(request);

        // Verify
        assertNotNull(plan);
        assertEquals(QueryPlan.QueryType.UPDATE, plan.getType());
        assertEquals(1, plan.getWhereClauses().size());
    }

    @Test
    void testBuildDeletePlan() {
        // Setup
        ApiRequest request = ApiRequest.builder()
            .schema("public")
            .table("users")
            .method("DELETE")
            .filters(List.of(
                Filter.builder()
                    .column("id")
                    .operator(Filter.FilterOperator.EQ)
                    .value("1")
                    .build()
            ))
            .build();

        // Execute
        QueryPlan plan = queryPlanner.plan(request);

        // Verify
        assertNotNull(plan);
        assertEquals(QueryPlan.QueryType.DELETE, plan.getType());
    }

    @Test
    void testPlanWithOrderBy() {
        // Setup
        ApiRequest request = ApiRequest.builder()
            .schema("public")
            .table("users")
            .method("GET")
            .orderBy(List.of(
                OrderBy.builder()
                    .column("created_at")
                    .direction(OrderBy.Direction.DESC)
                    .build()
            ))
            .build();

        when(schemaCache.getTable(anyString(), anyString())).thenReturn(
            Table.builder().columns(new ArrayList<>()).build()
        );

        // Execute
        QueryPlan plan = queryPlanner.plan(request);

        // Verify
        assertNotNull(plan.getOrderBy());
        assertEquals(1, plan.getOrderBy().size());
        assertTrue(plan.getOrderBy().get(0).contains("DESC"));
    }

    @Test
    void testPlanWithPagination() {
        // Setup
        ApiRequest request = ApiRequest.builder()
            .schema("public")
            .table("users")
            .method("GET")
            .range(RangeHeader.builder()
                .unit("items")
                .start(0L)
                .end(9L)
                .build())
            .build();

        when(schemaCache.getTable(anyString(), anyString())).thenReturn(
            Table.builder().columns(new ArrayList<>()).build()
        );

        // Execute
        QueryPlan plan = queryPlanner.plan(request);

        // Verify
        assertEquals(0L, plan.getOffset());
        assertEquals(10L, plan.getLimit());
    }

    @Test
    void selectWithoutRange_appliesDbMaxRowsFromTenantConfig() {
        MultiTenancyContext.setContext(MultiTenancyContext.ContextData.builder()
                .appCode("test_app")
                .databaseKey("test_db")
                .schemaName("public")
                .databaseConfig(DatabaseConfig.builder().dbMaxRows(1000).build())
                .build());

        ApiRequest request = ApiRequest.builder()
                .schema("public")
                .table("users")
                .method("GET")
                .build();

        when(schemaCache.getTable(anyString(), anyString())).thenReturn(
                Table.builder().columns(new ArrayList<>()).build());

        QueryPlan plan = queryPlanner.plan(request);

        assertEquals(0L, plan.getOffset());
        assertEquals(1000L, plan.getLimit());
    }

    @Test
    void selectWithOversizedRange_capsLimitToDbMaxRows() {
        MultiTenancyContext.setContext(MultiTenancyContext.ContextData.builder()
                .appCode("test_app")
                .databaseKey("test_db")
                .schemaName("public")
                .databaseConfig(DatabaseConfig.builder().dbMaxRows(100).build())
                .build());

        ApiRequest request = ApiRequest.builder()
                .schema("public")
                .table("users")
                .method("GET")
                .range(RangeHeader.builder().unit("items").start(0L).end(999L).build())
                .build();

        when(schemaCache.getTable(anyString(), anyString())).thenReturn(
                Table.builder().columns(new ArrayList<>()).build());

        QueryPlan plan = queryPlanner.plan(request);

        assertEquals(0L, plan.getOffset());
        assertEquals(100L, plan.getLimit());
    }

    @Test
    void testJoinQueryQualifiesBaseTableColumns() {
        ApiRequest request = ApiRequest.builder()
            .schema("public")
            .table("page_schema_assignments")
            .method("GET")
            .select(List.of(
                SelectColumn.builder().name("id").build(),
                SelectColumn.builder().name("page_path").build(),
                SelectColumn.builder().name("schema_id").build(),
                SelectColumn.builder().name("is_active").build(),
                SelectColumn.builder().name("display_order").build(),
                SelectColumn.builder()
                    .name("seo_schemas")
                    .hint("inner")
                    .embedded(List.of(
                        SelectColumn.builder().name("schema_name").build()
                    ))
                    .build()
            ))
            .filters(List.of(
                Filter.builder()
                    .column("page_path")
                    .operator(Filter.FilterOperator.EQ)
                    .value("/fixtures")
                    .build()
            ))
            .orderBy(List.of(
                OrderBy.builder()
                    .column("display_order")
                    .direction(OrderBy.Direction.ASC)
                    .build()
            ))
            .build();

        when(schemaCache.getRelationships("public", "page_schema_assignments")).thenReturn(List.of(
            ForeignKey.builder()
                .sourceSchema("public")
                .sourceTable("page_schema_assignments")
                .sourceColumn("schema_id")
                .targetSchema("public")
                .targetTable("seo_schemas")
                .targetColumn("id")
                .build()
        ));

        QueryPlan plan = queryPlanner.plan(request);

        assertEquals(List.of(
            "page_schema_assignments.id",
            "page_schema_assignments.page_path",
            "page_schema_assignments.schema_id",
            "page_schema_assignments.is_active",
            "page_schema_assignments.display_order"
        ), plan.getSelectColumns());
        assertEquals("page_schema_assignments.page_path", plan.getWhereClauses().get(0).getColumn());
        assertEquals("\"page_schema_assignments\".\"display_order\" ASC", plan.getOrderBy().get(0));
        assertEquals(1, plan.getJoins().size());
        assertEquals(JoinClause.JoinType.INNER, plan.getJoins().get(0).getType());
        assertEquals("page_schema_assignments.schema_id", plan.getJoins().get(0).getConditions().get(0).getLeftColumn());
        assertEquals("seo_schemas.id", plan.getJoins().get(0).getConditions().get(0).getRightColumn());
    }

    @Test
    void testEmbeddedResourceColumnsAreCollected() {
        // Test that embedded resource columns are properly collected in JoinClause
        ApiRequest request = ApiRequest.builder()
            .schema("public")
            .table("page_schema_assignments")
            .method("GET")
            .select(List.of(
                SelectColumn.builder().name("id").build(),
                SelectColumn.builder().name("page_path").build(),
                SelectColumn.builder()
                    .name("seo_schemas")
                    .hint("inner")
                    .embedded(List.of(
                        SelectColumn.builder().name("schema_name").build(),
                        SelectColumn.builder().name("schema_type").build(),
                        SelectColumn.builder().name("description").build()
                    ))
                    .build()
            ))
            .build();

        when(schemaCache.getRelationships("public", "page_schema_assignments")).thenReturn(List.of(
            ForeignKey.builder()
                .sourceSchema("public")
                .sourceTable("page_schema_assignments")
                .sourceColumn("schema_id")
                .targetSchema("public")
                .targetTable("seo_schemas")
                .targetColumn("id")
                .build()
        ));

        QueryPlan plan = queryPlanner.plan(request);

        // Verify base table columns
        assertEquals(List.of(
            "page_schema_assignments.id",
            "page_schema_assignments.page_path"
        ), plan.getSelectColumns());

        // Verify JOIN clause
        assertEquals(1, plan.getJoins().size());
        JoinClause join = plan.getJoins().get(0);

        // Verify embedded columns are collected
        assertNotNull(join.getSelectColumns());
        assertEquals(3, join.getSelectColumns().size());
        assertTrue(join.getSelectColumns().contains("seo_schemas.schema_name"));
        assertTrue(join.getSelectColumns().contains("seo_schemas.schema_type"));
        assertTrue(join.getSelectColumns().contains("seo_schemas.description"));

        // Verify embedding name
        assertEquals("seo_schemas", join.getEmbeddingName());
    }

    @Test
    void testEmbeddedResourceWithAlias() {
        // Test embedded resource with alias
        ApiRequest request = ApiRequest.builder()
            .schema("public")
            .table("tasks")
            .method("GET")
            .select(List.of(
                SelectColumn.builder().name("id").build(),
                SelectColumn.builder()
                    .name("projects")
                    .alias("project_info")
                    .hint("left")
                    .embedded(List.of(
                        SelectColumn.builder().name("name").build()
                    ))
                    .build()
            ))
            .build();

        when(schemaCache.getRelationships("public", "tasks")).thenReturn(List.of(
            ForeignKey.builder()
                .sourceSchema("public")
                .sourceTable("tasks")
                .sourceColumn("project_id")
                .targetSchema("public")
                .targetTable("projects")
                .targetColumn("id")
                .build()
        ));

        QueryPlan plan = queryPlanner.plan(request);

        assertEquals(1, plan.getJoins().size());
        JoinClause join = plan.getJoins().get(0);

        // Verify alias is used as embedding name
        assertEquals("project_info", join.getEmbeddingName());
        assertEquals("project_info", join.getAlias());
        assertEquals(JoinClause.JoinType.LEFT, join.getType());
    }
}
