package ai.nubase.mcp.tools;

import ai.nubase.common.context.MultiTenancyContext;
import ai.nubase.deploy.service.AppDeploymentService;
import ai.nubase.deploy.service.AppDeploymentRollbackService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class DeploymentsMcpTools {

    private final AppDeploymentService deploymentService;
    private final AppDeploymentRollbackService rollbackService;

    @Tool(description = "List recent app deployments for the current project. Parameters: limit optional default 50. Read-only.")
    public Object deploymentsList(Integer limit) {
        return deploymentService.list(limit == null ? 50 : limit);
    }

    @Tool(description = "Get one app deployment with recorded steps. Parameters: id required. Read-only.")
    public Object deploymentStatus(String id) {
        if (id == null || id.isBlank()) return Map.of("success", false, "error", "id is required");
        return deploymentService.get(UUID.fromString(id));
    }

    @Tool(description = "List recorded deployment steps/logs for one app deployment. Parameters: id required. Read-only.")
    public Object deploymentLogs(String id) {
        if (id == null || id.isBlank()) return Map.of("success", false, "error", "id is required");
        return deploymentService.logs(UUID.fromString(id));
    }

    @Tool(description = "Rollback supported resources from one app deployment. Parameters: id required. Write operation; currently deletes recorded Assets and cron jobs, and records skipped actions for non-reversible steps.")
    public Object deploymentRollback(String id) {
        if (!MultiTenancyContext.isServiceRole()) {
            return Map.of(
                    "success", false,
                    "error", "deploymentRollback requires service_role project context"
            );
        }
        if (id == null || id.isBlank()) return Map.of("success", false, "error", "id is required");
        return rollbackService.rollback(UUID.fromString(id));
    }
}
