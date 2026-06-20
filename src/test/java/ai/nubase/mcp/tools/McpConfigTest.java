package ai.nubase.mcp.tools;

import org.springframework.beans.factory.ObjectProvider;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class McpConfigTest {

    @Test
    void registersDatabaseMemoryAndAssetsTools() {
        McpConfig config = new McpConfig();
        DatabaseMcpTools databaseMcpTools = mock(DatabaseMcpTools.class);
        MemoryMcpTools memoryMcpTools = new MemoryMcpTools(mock(ai.nubase.mem.service.MemoryService.class));
        AssetsMcpTools assetsMcpTools = new AssetsMcpTools(mock(ai.nubase.assets.service.AssetsService.class));
        AuthMcpTools authMcpTools = new AuthMcpTools(mock(ai.nubase.auth.service.AdminService.class));
        StorageMcpTools storageMcpTools = new StorageMcpTools(mock(ai.nubase.auth.service.BucketService.class));
        GatewayMcpTools gatewayMcpTools = new GatewayMcpTools(mock(ai.nubase.ai.gateway.repository.ApiKeyRepository.class));
        DeploymentsMcpTools deploymentsMcpTools = new DeploymentsMcpTools(
                mock(ai.nubase.deploy.service.AppDeploymentService.class),
                mock(ai.nubase.deploy.service.AppDeploymentRollbackService.class),
                mock(ai.nubase.deploy.service.AppWorkerService.class)
        );
        FunctionsMcpTools functionsMcpTools = mock(FunctionsMcpTools.class);
        CronMcpTools cronMcpTools = mock(CronMcpTools.class);

        ToolCallbackProvider provider = config.toolProvider(
                databaseMcpTools,
                memoryMcpTools,
                assetsMcpTools,
                authMcpTools,
                storageMcpTools,
                gatewayMcpTools,
                deploymentsMcpTools,
                provider(functionsMcpTools),
                provider(cronMcpTools)
        );

        assertThat(provider).isNotNull();
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }

            @Override
            public T getObject() {
                return value;
            }
        };
    }
}
