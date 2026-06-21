package ai.nubase.mcp.tools;

import ai.nubase.common.context.MultiTenancyContext;
import ai.nubase.deploy.service.AppDeploymentService;
import ai.nubase.deploy.service.AppDeploymentRollbackService;
import ai.nubase.deploy.service.AppWorkerService;
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
    private final AppWorkerService appWorkerService;

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

    @Tool(description = "List the app workers (Cloudflare Workers) this project has deployed, with their latest version, preview URL and deployment status. Scoped to the current project. Read-only.")
    public Object appWorkersList() {
        return appWorkerService.list();
    }

    @Tool(description = "Get one deployed app worker for this project, enriched with live provider state. Parameters: workerName required. Read-only.")
    public Object appWorkerStatus(String workerName) {
        if (workerName == null || workerName.isBlank()) {
            return Map.of("success", false, "error", "workerName is required");
        }
        return appWorkerService.get(workerName);
    }

    @Tool(description = "Delete (undeploy) one app worker owned by this project. Parameters: workerName required. Write operation; requires service_role project context and only affects workers this project has deployed.")
    public Object appWorkerDelete(String workerName) {
        if (!MultiTenancyContext.isServiceRole()) {
            return Map.of(
                    "success", false,
                    "error", "appWorkerDelete requires service_role project context"
            );
        }
        if (workerName == null || workerName.isBlank()) {
            return Map.of("success", false, "error", "workerName is required");
        }
        return appWorkerService.delete(workerName);
    }
}
