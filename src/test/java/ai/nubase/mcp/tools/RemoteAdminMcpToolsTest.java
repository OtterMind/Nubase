package ai.nubase.mcp.tools;

import ai.nubase.auth.service.AdminService;
import ai.nubase.auth.service.BucketService;
import ai.nubase.auth.service.DatabaseInitService;
import ai.nubase.auth.service.SqlExecutionService;
import ai.nubase.common.context.MultiTenancyContext;
import ai.nubase.mcp.safety.SqlRiskClassifier;
import ai.nubase.cron.service.ScheduledJobAdminService;
import ai.nubase.deploy.service.AppDeploymentRollbackService;
import ai.nubase.deploy.service.AppDeploymentService;
import ai.nubase.functions.service.EdgeFunctionAdminService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class RemoteAdminMcpToolsTest {

    @AfterEach
    void clearContext() {
        MultiTenancyContext.clear();
    }

    @Test
    void authCreateUserRequiresServiceRole() {
        AdminService service = mock(AdminService.class);
        Object result = new AuthMcpTools(service).authCreateUser("a@example.com", null, null, null);

        assertThat(result).isInstanceOf(Map.class);
        assertThat(asMap(result)).containsEntry("success", false);
        verifyNoInteractions(service);
    }

    @Test
    void storageCreateBucketRequiresServiceRole() {
        BucketService service = mock(BucketService.class);
        Object result = new StorageMcpTools(service).storageCreateBucket("uploads", true, null);

        assertThat(result).isInstanceOf(Map.class);
        assertThat(asMap(result)).containsEntry("success", false);
        verifyNoInteractions(service);
    }

    @Test
    void functionsDeleteRequiresServiceRole() {
        EdgeFunctionAdminService service = mock(EdgeFunctionAdminService.class);
        Object result = new FunctionsMcpTools(service).functionsDelete("api");

        assertThat(result).isInstanceOf(Map.class);
        assertThat(asMap(result)).containsEntry("success", false);
        verifyNoInteractions(service);
    }

    @Test
    void cronCreateRequiresServiceRole() {
        ScheduledJobAdminService service = mock(ScheduledJobAdminService.class);
        Object result = new CronMcpTools(service).cronCreate(
                "nightly",
                "0 3 * * *",
                "edge_function",
                null,
                "api",
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        assertThat(result).isInstanceOf(Map.class);
        assertThat(asMap(result)).containsEntry("success", false);
        verifyNoInteractions(service);
    }

    @Test
    void executeSqlRequiresServiceRole() {
        SqlExecutionService sqlExecutionService = mock(SqlExecutionService.class);
        Object result = new DatabaseMcpTools(null, sqlExecutionService, null, null, new SqlRiskClassifier())
                .executeSql("select 1");

        assertThat(result).isInstanceOf(Map.class);
        assertThat(asMap(result)).containsEntry("success", false);
        verifyNoInteractions(sqlExecutionService);
    }

    @Test
    void initDatabaseRequiresServiceRole() {
        DatabaseInitService databaseInitService = mock(DatabaseInitService.class);
        Object result = new DatabaseMcpTools(null, null, null, databaseInitService, new SqlRiskClassifier())
                .initDatabase();

        assertThat(result).isInstanceOf(Map.class);
        assertThat(asMap(result)).containsEntry("success", false);
        verifyNoInteractions(databaseInitService);
    }

    @Test
    void deploymentRollbackRequiresServiceRole() {
        AppDeploymentService deploymentService = mock(AppDeploymentService.class);
        AppDeploymentRollbackService rollbackService = mock(AppDeploymentRollbackService.class);
        Object result = new DeploymentsMcpTools(deploymentService, rollbackService,
                mock(ai.nubase.deploy.service.AppWorkerService.class))
                .deploymentRollback("4e581dbc-07f8-477c-8db1-41b89d65a36e");

        assertThat(result).isInstanceOf(Map.class);
        assertThat(asMap(result)).containsEntry("success", false);
        verifyNoInteractions(rollbackService);
    }

    @Test
    void appWorkerDeleteRequiresServiceRole() {
        AppDeploymentService deploymentService = mock(AppDeploymentService.class);
        AppDeploymentRollbackService rollbackService = mock(AppDeploymentRollbackService.class);
        ai.nubase.deploy.service.AppWorkerService appWorkerService =
                mock(ai.nubase.deploy.service.AppWorkerService.class);
        Object result = new DeploymentsMcpTools(deploymentService, rollbackService, appWorkerService)
                .appWorkerDelete("my-app");

        assertThat(result).isInstanceOf(Map.class);
        assertThat(asMap(result)).containsEntry("success", false);
        verifyNoInteractions(appWorkerService);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object result) {
        return (Map<String, Object>) result;
    }
}
