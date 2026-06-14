package ai.nubase.mcp.tools;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Configuration
public class McpConfig {

    @Bean
    public ToolCallbackProvider toolProvider(DatabaseMcpTools databaseMcpTools,
                                             MemoryMcpTools memoryMcpTools,
                                             AssetsMcpTools assetsMcpTools,
                                             AuthMcpTools authMcpTools,
                                             StorageMcpTools storageMcpTools,
                                             GatewayMcpTools gatewayMcpTools,
                                             DeploymentsMcpTools deploymentsMcpTools,
                                             ObjectProvider<FunctionsMcpTools> functionsMcpTools,
                                             ObjectProvider<CronMcpTools> cronMcpTools) {
        List<Object> tools = new ArrayList<>();
        tools.add(databaseMcpTools);
        tools.add(memoryMcpTools);
        tools.add(assetsMcpTools);
        tools.add(authMcpTools);
        tools.add(storageMcpTools);
        tools.add(gatewayMcpTools);
        tools.add(deploymentsMcpTools);
        functionsMcpTools.ifAvailable(tools::add);
        cronMcpTools.ifAvailable(tools::add);
        return MethodToolCallbackProvider.builder()
                .toolObjects(tools.toArray())
                .build();
    }

}
