package ai.nubase.mcp.tools;

import ai.nubase.auth.dto.request.admin.ExecuteSqlRequest;
import ai.nubase.auth.dto.response.admin.SqlExecutionResponse;
import ai.nubase.auth.service.SqlExecutionService;
import ai.nubase.common.context.MultiTenancyContext;
import ai.nubase.common.enums.DatabaseInitStatus;
import ai.nubase.mcp.safety.SqlRiskClassifier;
import ai.nubase.postgrest.multidb.DatabaseConfig;
import ai.nubase.postgrest.multidb.SchemaCacheManager;
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
    private SchemaCacheManager schemaCacheManager;

    @BeforeEach
    void setUp() {
        sqlExecutionService = mock(SqlExecutionService.class);
        schemaCacheManager = mock(SchemaCacheManager.class);
        tools = new DatabaseMcpTools(schemaCacheManager, sqlExecutionService, null, null, new SqlRiskClassifier());
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

        assertThat(response).containsEntry("success", false);
        assertThat(response).containsEntry("risk", "DANGEROUS");
        assertThat(response).containsEntry("executable", false);
        assertThat(response).containsEntry("blocked", true);
        assertThat(response).containsEntry("code", "SQL_RISK_BLOCKED");
        verifyNoInteractions(sqlExecutionService);
    }

    @Test
    void executeSqlBlocksDangerousAndUnknownSqlBeforeExecution() {
        setInitializedServiceRoleContext();

        @SuppressWarnings("unchecked")
        Map<String, Object> dangerous = (Map<String, Object>) tools.executeSql("copy todos to program 'external-command'");
        @SuppressWarnings("unchecked")
        Map<String, Object> unboundedDelete = (Map<String, Object>) tools.executeSql("delete from only (todos) * as t");
        @SuppressWarnings("unchecked")
        Map<String, Object> privilegedAlter = (Map<String, Object>) tools.executeSql("alter role app_user bypassrls");
        @SuppressWarnings("unchecked")
        Map<String, Object> unknown = (Map<String, Object>) tools.executeSql("listen app_events");
        @SuppressWarnings("unchecked")
        Map<String, Object> carriageReturnBypass = (Map<String, Object>) tools.executeSql(
                "select 1 -- harmless\r; drop table victims");
        @SuppressWarnings("unchecked")
        Map<String, Object> ambiguousBackslashBatch = (Map<String, Object>) tools.executeSql(
                "SELECT 'a\\'; SELECT '; DROP TABLE victims; --'");
        @SuppressWarnings("unchecked")
        Map<String, Object> benignPlainBackslash = (Map<String, Object>) tools.executeSql(
                "select 'plain\\path'");

        assertThat(dangerous).containsEntry("success", false).containsEntry("risk", "DANGEROUS");
        assertThat(unboundedDelete).containsEntry("success", false).containsEntry("risk", "DANGEROUS");
        assertThat(privilegedAlter).containsEntry("success", false).containsEntry("risk", "DANGEROUS");
        assertThat(unknown).containsEntry("success", false).containsEntry("risk", "UNKNOWN");
        assertThat(carriageReturnBypass).containsEntry("success", false).containsEntry("risk", "DANGEROUS");
        assertThat(ambiguousBackslashBatch).containsEntry("success", false).containsEntry("risk", "UNKNOWN");
        assertThat(benignPlainBackslash).containsEntry("success", false).containsEntry("risk", "UNKNOWN");
        verifyNoInteractions(sqlExecutionService);
    }

    @Test
    void executeSqlAndDryRunBlockExecutableDdlBeforeExecution() {
        setInitializedServiceRoleContext();

        @SuppressWarnings("unchecked")
        Map<String, Object> function = (Map<String, Object>) tools.executeSql(
                "create function emit_event() returns void language plpgsql "
                        + "as $$ begin perform pg_notify('events', 'created;drop table todos'); end $$");
        @SuppressWarnings("unchecked")
        Map<String, Object> destructiveAlter = (Map<String, Object>) tools.executeSql(
                "alter table todos rename to archived_todos");
        @SuppressWarnings("unchecked")
        Map<String, Object> defaultExpression = (Map<String, Object>) tools.executeSqlDryRun(
                "alter table todos add column event_id bigint default emit_event()");

        assertThat(function)
                .containsEntry("success", false)
                .containsEntry("risk", "DANGEROUS")
                .containsEntry("code", "SQL_RISK_BLOCKED");
        assertThat(destructiveAlter)
                .containsEntry("success", false)
                .containsEntry("risk", "DANGEROUS")
                .containsEntry("code", "SQL_RISK_BLOCKED");
        assertThat(defaultExpression)
                .containsEntry("success", false)
                .containsEntry("risk", "DANGEROUS")
                .containsEntry("code", "SQL_RISK_BLOCKED");
        verifyNoInteractions(sqlExecutionService, schemaCacheManager);
    }

    @Test
    void executeSqlDryRunBlocksUnknownSqlBeforeTransactionalValidation() {
        setInitializedServiceRoleContext();

        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) tools.executeSqlDryRun("listen app_events");

        assertThat(response).containsEntry("success", false);
        assertThat(response).containsEntry("risk", "UNKNOWN");
        assertThat(response).containsEntry("code", "SQL_RISK_BLOCKED");
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
                .thenReturn(SqlExecutionResponse.error("sensitive driver detail", 3));

        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) tools.executeSqlDryRun("create table");

        assertThat(response).containsEntry("success", false);
        assertThat(response).containsEntry("executable", false);
        assertThat(response).containsEntry("code", "SQL_DRY_RUN_FAILED");
        assertThat(response).containsEntry("error", "SQL dry-run failed");
        assertThat(response.get("error")).asString().doesNotContain("sensitive driver detail");
        assertThat(response).containsEntry("executionTimeMs", 3L);
    }

    @Test
    void executeSqlDoesNotExposeServiceError() {
        setInitializedServiceRoleContext();
        when(sqlExecutionService.executeSql(any(ExecuteSqlRequest.class)))
                .thenReturn(SqlExecutionResponse.error("sensitive driver detail", 4));

        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) tools.executeSql("select 1");

        assertThat(response).containsEntry("success", false);
        assertThat(response).containsEntry("code", "SQL_EXECUTION_FAILED");
        assertThat(response).containsEntry("error", "SQL execution failed");
        assertThat(response.get("error")).asString().doesNotContain("sensitive driver detail");
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
