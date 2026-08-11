package ai.nubase.mcp.tools;

import ai.nubase.common.context.MultiTenancyContext;
import ai.nubase.deploy.service.AppDeploymentRollbackService;
import ai.nubase.deploy.service.AppDeploymentService;
import ai.nubase.deploy.service.AppWorkerService;
import ai.nubase.deploy.service.BoundedAssetDeploymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class DeploymentsMcpTools {

    private static final Pattern APP_NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9-]{2,63}$");
    private static final Pattern TASK_ID_PATTERN = Pattern.compile("^task-[a-z0-9][a-z0-9-]{5,63}$");
    private static final Pattern RUN_ID_PATTERN = Pattern.compile("^run-[a-z0-9][a-z0-9-]{5,63}$");
    private static final Pattern SHA256_PATTERN = Pattern.compile("^sha256:[a-f0-9]{64}$");

    private final AppDeploymentService deploymentService;
    private final AppDeploymentRollbackService rollbackService;
    private final AppWorkerService appWorkerService;
    private final BoundedAssetDeploymentService boundedAssetDeploymentService;

    @Tool(description = "Stage one server-generated marker asset as a bounded sandbox deployment. Parameters: appName, taskId, runId, and manifestDigest (sha256:...) required. The server creates a unique __goai_e2e/{runId}/marker.json path with upsert disabled, records the truthful deployment step, and returns only sanitized metadata. Write operation; requires service_role project context.")
    public Object deploymentStageAsset(String appName, String taskId, String runId, String manifestDigest) {
        Map<String, Object> guard = requireServiceRole("deploymentStageAsset");
        if (guard != null) return guard;
        if (!matches(APP_NAME_PATTERN, appName)) {
            return error("appName must match " + APP_NAME_PATTERN.pattern());
        }
        if (!matches(TASK_ID_PATTERN, taskId)) {
            return error("taskId must match " + TASK_ID_PATTERN.pattern());
        }
        if (!matches(RUN_ID_PATTERN, runId)) {
            return error("runId must match " + RUN_ID_PATTERN.pattern());
        }
        if (!matches(SHA256_PATTERN, manifestDigest)) {
            return error("manifestDigest must be a lowercase sha256 digest");
        }
        try {
            return boundedAssetDeploymentService.stage(appName, taskId, runId, manifestDigest);
        } catch (RuntimeException ignored) {
            return error("DEPLOYMENT_STAGE_FAILED", "Bounded deployment stage failed");
        }
    }

    @Tool(description = "List recent app deployments for the current project. Parameters: limit optional default 50. Read-only.")
    public Object deploymentsList(Integer limit) {
        return deploymentService.list(limit == null ? 50 : limit);
    }

    @Tool(description = "Get one app deployment with recorded steps. Parameters: id required. Read-only.")
    public Object deploymentStatus(String id) {
        UUID deploymentId = parseUuid(id);
        if (deploymentId == null) return error("id must be a valid UUID");
        return deploymentService.get(deploymentId);
    }

    @Tool(description = "List recorded deployment steps/logs for one app deployment. Parameters: id required. Read-only.")
    public Object deploymentLogs(String id) {
        UUID deploymentId = parseUuid(id);
        if (deploymentId == null) return error("id must be a valid UUID");
        return deploymentService.logs(deploymentId);
    }

    @Tool(description = "Rollback supported resources from one app deployment. Parameters: id required. Write operation; deletes only recorded reversible resources and reports partial compensation when any succeeded step cannot be restored.")
    public Object deploymentRollback(String id) {
        Map<String, Object> guard = requireServiceRole("deploymentRollback");
        if (guard != null) return guard;
        UUID deploymentId = parseUuid(id);
        if (deploymentId == null) return error("id must be a valid UUID");
        try {
            return rollbackService.rollback(deploymentId);
        } catch (RuntimeException ignored) {
            return error("DEPLOYMENT_ROLLBACK_FAILED", "Deployment rollback failed");
        }
    }

    @Tool(description = "List the app workers (Cloudflare Workers) this project has deployed, with their latest version, preview URL and deployment status. Scoped to the current project. Read-only.")
    public Object appWorkersList() {
        return appWorkerService.list();
    }

    @Tool(description = "Get one deployed app worker for this project, enriched with live provider state. Parameters: workerName required. Read-only.")
    public Object appWorkerStatus(String workerName) {
        if (workerName == null || workerName.isBlank()) {
            return error("workerName is required");
        }
        return appWorkerService.get(workerName);
    }

    @Tool(description = "Delete (undeploy) one app worker owned by this project. Parameters: workerName required. Write operation; requires service_role project context and only affects workers this project has deployed.")
    public Object appWorkerDelete(String workerName) {
        Map<String, Object> guard = requireServiceRole("appWorkerDelete");
        if (guard != null) return guard;
        if (workerName == null || workerName.isBlank()) {
            return error("workerName is required");
        }
        return appWorkerService.delete(workerName);
    }

    private Map<String, Object> requireServiceRole(String toolName) {
        if (!MultiTenancyContext.isServiceRole()) {
            return error(toolName + " requires service_role project context");
        }
        return null;
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static boolean matches(Pattern pattern, String value) {
        return value != null && pattern.matcher(value).matches();
    }

    private static Map<String, Object> error(String message) {
        return Map.of("success", false, "error", message);
    }

    private static Map<String, Object> error(String code, String message) {
        return Map.of("success", false, "code", code, "error", message);
    }
}
