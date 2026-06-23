package ai.nubase.mcp.tools;

import ai.nubase.auth.dto.request.admin.ExecuteSqlRequest;
import ai.nubase.auth.dto.response.admin.SqlExecutionResponse;
import ai.nubase.auth.service.SqlExecutionService;
import ai.nubase.common.context.MultiTenancyContext;
import ai.nubase.common.enums.DatabaseInitStatus;
import ai.nubase.mcp.safety.SqlRiskClassifier;
import ai.nubase.postgrest.multidb.DatabaseConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DatabaseMcpToolsTest {

    private DatabaseMcpTools tools;
    private SqlExecutionService sqlExecutionService;

    @BeforeEach
    void setUp() {
        sqlExecutionService = mock(SqlExecutionService.class);
        tools = new DatabaseMcpTools(null, sqlExecutionService, null, null, new SqlRiskClassifier());
    }

    @AfterEach
    void tearDown() {
        MultiTenancyContext.clear();
    }

    @Test
    void executeSqlWithServiceRoleStillRequiresInitializedDatabase() {
        MultiTenancyContext.setContext(MultiTenancyContext.ContextData.builder()
                .appCode("demo")
                .schemaName("public")
                .jwtSecret("test-secret-key-at-least-32-bytes-long")
                .serviceRole(true)
                .build());

        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) tools.executeSql("select 1");

        assertThat(response).containsEntry("error", "Database context not found, please ensure the database is initialized");
        assertThat(response).doesNotContainKey("results");
    }

    @Test
    void initDatabaseWithServiceRoleStillRequiresDatabaseContext() {
        MultiTenancyContext.setContext(MultiTenancyContext.ContextData.builder()
                .appCode("demo")
                .schemaName("public")
                .jwtSecret("test-secret-key-at-least-32-bytes-long")
                .serviceRole(true)
                .build());

        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) tools.initDatabase();

        assertThat(response).containsEntry("success", false);
        assertThat(response.get("error")).asString().contains("Database context not found");
    }

    @Test
    void executeSqlDryRunRequiresServiceRoleForNonDangerousSql() {
        Object raw = tools.executeSqlDryRun("select * from todos; create table notes(id bigint);");

        assertThat(raw).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) raw;
        assertThat(response).containsEntry("success", false);
        assertThat(response).containsEntry("risk", "SCHEMA_WRITE");
        assertThat(response).containsEntry("statementCount", 2);
        assertThat(response).containsEntry("executable", false);
        assertThat(response.get("error")).asString().contains("service_role");
        verifyNoInteractions(sqlExecutionService);
    }

    @Test
    void executeSqlDryRunMarksDangerousSqlNotExecutable() {
        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) tools.executeSqlDryRun("drop table todos");

        assertThat(response).containsEntry("success", true);
        assertThat(response).containsEntry("risk", "DANGEROUS");
        assertThat(response).containsEntry("executable", false);
        assertThat(response).containsEntry("blocked", true);
        verifyNoInteractions(sqlExecutionService);
    }

    @Test
    void executeSqlDryRunRunsTransactionalValidationForSafeSql() {
        setInitializedServiceRoleContext();
        when(sqlExecutionService.dryRunSql(any(ExecuteSqlRequest.class)))
                .thenReturn(SqlExecutionResponse.successWithResults(List.of(), 12));

        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) tools.executeSqlDryRun("create table todos(id bigint)");

        assertThat(response).containsEntry("success", true);
        assertThat(response).containsEntry("risk", "SCHEMA_WRITE");
        assertThat(response).containsEntry("statementCount", 1);
        assertThat(response).containsEntry("executable", true);
        assertThat(response).containsEntry("executionTimeMs", 12L);
        verify(sqlExecutionService).dryRunSql(any(ExecuteSqlRequest.class));
    }

    @Test
    void executeSqlDryRunReturnsErrorWhenTransactionalValidationFails() {
        setInitializedServiceRoleContext();
        when(sqlExecutionService.dryRunSql(any(ExecuteSqlRequest.class)))
                .thenReturn(SqlExecutionResponse.error("syntax error at or near \"table\"", 3));

        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) tools.executeSqlDryRun("create table");

        assertThat(response).containsEntry("success", false);
        assertThat(response).containsEntry("executable", false);
        assertThat(response.get("error")).asString().contains("syntax error");
        assertThat(response).containsEntry("executionTimeMs", 3L);
    }

    private void setInitializedServiceRoleContext() {
        MultiTenancyContext.setContext(MultiTenancyContext.ContextData.builder()
                .appCode("demo")
                .schemaName("public")
                .jwtSecret("test-secret-key-at-least-32-bytes-long")
                .serviceRole(true)
                .databaseConfig(DatabaseConfig.builder()
                        .dbKey("demo")
                        .schemaName("public")
                        .dbSchemas(List.of("public"))
                        .initStatus(DatabaseInitStatus.INITIALIZED.name())
                        .build())
                .build());
    }
}
