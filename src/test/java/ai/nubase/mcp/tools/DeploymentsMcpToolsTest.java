package ai.nubase.mcp.tools;

import ai.nubase.common.context.MultiTenancyContext;
import ai.nubase.deploy.service.AppDeploymentRollbackService;
import ai.nubase.deploy.service.AppDeploymentService;
import ai.nubase.deploy.service.AppWorkerService;
import ai.nubase.deploy.service.BoundedAssetDeploymentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DeploymentsMcpToolsTest {

    private static final String MANIFEST_DIGEST =
            "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private BoundedAssetDeploymentService boundedService;
    private DeploymentsMcpTools tools;

    @BeforeEach
    void setUp() {
        boundedService = mock(BoundedAssetDeploymentService.class);
        tools = new DeploymentsMcpTools(
                mock(AppDeploymentService.class),
                mock(AppDeploymentRollbackService.class),
                mock(AppWorkerService.class),
                boundedService
        );
    }

    @AfterEach
    void tearDown() {
        MultiTenancyContext.clear();
    }

    @Test
    void deploymentStageAssetRequiresServiceRole() {
        Object response = tools.deploymentStageAsset(
                "goai-bounded-demo",
                "task-bounded-demo-001",
                "run-bounded-demo-001",
                MANIFEST_DIGEST
        );

        assertThat(response).isInstanceOf(Map.class);
        assertThat(asMap(response)).containsEntry("success", false);
        verifyNoInteractions(boundedService);
    }

    @Test
    void deploymentStageAssetRejectsInvalidCorrelationBeforeCallingService() {
        serviceRoleContext();

        Object response = tools.deploymentStageAsset(
                "goai-bounded-demo",
                "invalid-task",
                "run-bounded-demo-001",
                MANIFEST_DIGEST
        );

        assertThat(response).isInstanceOf(Map.class);
        assertThat(asMap(response)).containsEntry("success", false);
        verifyNoInteractions(boundedService);
    }

    @Test
    void deploymentStageAssetDelegatesValidatedRequest() {
        serviceRoleContext();
        var expected = new BoundedAssetDeploymentService.StageAssetDeploymentResponse(
                true,
                null,
                "succeeded",
                "__goai_e2e/run-bounded-demo-001/marker.json",
                MANIFEST_DIGEST,
                128L,
                "etag-1",
                "version-1",
                null
        );
        when(boundedService.stage(
                "goai-bounded-demo",
                "task-bounded-demo-001",
                "run-bounded-demo-001",
                MANIFEST_DIGEST
        )).thenReturn(expected);

        Object response = tools.deploymentStageAsset(
                "goai-bounded-demo",
                "task-bounded-demo-001",
                "run-bounded-demo-001",
                MANIFEST_DIGEST
        );

        assertThat(response).isSameAs(expected);
        verify(boundedService).stage(
                "goai-bounded-demo",
                "task-bounded-demo-001",
                "run-bounded-demo-001",
                MANIFEST_DIGEST
        );
    }

    @Test
    void readToolsRejectInvalidUuidWithoutCallingServices() {
        AppDeploymentService deploymentService = mock(AppDeploymentService.class);
        tools = new DeploymentsMcpTools(
                deploymentService,
                mock(AppDeploymentRollbackService.class),
                mock(AppWorkerService.class),
                boundedService
        );

        assertThat(asMap(tools.deploymentStatus("not-a-uuid")))
                .containsEntry("success", false);
        assertThat(asMap(tools.deploymentLogs("not-a-uuid")))
                .containsEntry("success", false);
        verifyNoInteractions(deploymentService);
    }

    @Test
    void deploymentStageAssetMapsInternalFailureToBoundedError() {
        serviceRoleContext();
        doThrow(new IllegalStateException("sensitive internal detail"))
                .when(boundedService)
                .stage(
                        "goai-bounded-demo",
                        "task-bounded-demo-001",
                        "run-bounded-demo-001",
                        MANIFEST_DIGEST
                );

        Object response = tools.deploymentStageAsset(
                "goai-bounded-demo",
                "task-bounded-demo-001",
                "run-bounded-demo-001",
                MANIFEST_DIGEST
        );

        assertThat(asMap(response))
                .containsEntry("success", false)
                .containsEntry("code", "DEPLOYMENT_STAGE_FAILED");
        assertThat(response.toString()).doesNotContain("sensitive internal detail");
    }

    private void serviceRoleContext() {
        MultiTenancyContext.setContext(MultiTenancyContext.ContextData.builder()
                .appCode("goai_sandbox")
                .serviceRole(true)
                .build());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }
}
