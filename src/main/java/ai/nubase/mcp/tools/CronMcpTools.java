package ai.nubase.mcp.tools;

import ai.nubase.common.context.MultiTenancyContext;
import ai.nubase.cron.dto.ScheduledJobDtos.CreateScheduledJobRequest;
import ai.nubase.cron.dto.ScheduledJobDtos.ScheduledJobResponse;
import ai.nubase.cron.dto.ScheduledJobDtos.ScheduledJobRunResponse;
import ai.nubase.cron.dto.ScheduledJobDtos.UpdateScheduledJobRequest;
import ai.nubase.cron.service.ScheduledJobAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
@ConditionalOnBean(ScheduledJobAdminService.class)
public class CronMcpTools {

    private final ScheduledJobAdminService adminService;

    @Tool(description = "List scheduled cron jobs for the current project. Read-only.")
    public Object cronList() {
        return adminService.listJobs().stream().map(ScheduledJobResponse::from).toList();
    }

    @Tool(description = "Get one scheduled cron job by name. Read-only.")
    public Object cronGet(String name) {
        if (name == null || name.isBlank()) return Map.of("success", false, "error", "name is required");
        return ScheduledJobResponse.from(adminService.getJob(name));
    }

    @Tool(description = "Create a scheduled job. targetType is edge_function or db_function. Requires service_role MCP apikey.")
    public Object cronCreate(String name, String cronExpression, String targetType, String description,
                             String functionSlug, String httpMethod, String requestPath, String requestBody,
                             String dbFunctionName, Map<String, Object> dbFunctionArgs,
                             Integer timeoutSeconds, Boolean enabled) {
        Map<String, Object> guard = requireServiceRole("create cron job");
        if (guard != null) return guard;
        if (name == null || name.isBlank()) return Map.of("success", false, "error", "name is required");
        if (cronExpression == null || cronExpression.isBlank()) return Map.of("success", false, "error", "cronExpression is required");
        if (targetType == null || targetType.isBlank()) return Map.of("success", false, "error", "targetType is required");
        var request = new CreateScheduledJobRequest(
                name, description, cronExpression, targetType,
                functionSlug, httpMethod, requestPath, requestBody,
                dbFunctionName, dbFunctionArgs, timeoutSeconds, enabled
        );
        return ScheduledJobResponse.from(adminService.createJob(request));
    }

    @Tool(description = "Update a scheduled job by name. targetType is immutable. Requires service_role MCP apikey.")
    public Object cronUpdate(String name, String cronExpression, String description,
                             String functionSlug, String httpMethod, String requestPath, String requestBody,
                             String dbFunctionName, Map<String, Object> dbFunctionArgs,
                             Integer timeoutSeconds, Boolean enabled) {
        Map<String, Object> guard = requireServiceRole("update cron job");
        if (guard != null) return guard;
        if (name == null || name.isBlank()) return Map.of("success", false, "error", "name is required");
        var request = new UpdateScheduledJobRequest(
                description, cronExpression, functionSlug, httpMethod, requestPath, requestBody,
                dbFunctionName, dbFunctionArgs, timeoutSeconds, enabled
        );
        return ScheduledJobResponse.from(adminService.updateJob(name, request));
    }

    @Tool(description = "Delete a scheduled job by name. Requires service_role MCP apikey.")
    public Object cronDelete(String name) {
        Map<String, Object> guard = requireServiceRole("delete cron job");
        if (guard != null) return guard;
        if (name == null || name.isBlank()) return Map.of("success", false, "error", "name is required");
        adminService.deleteJob(name);
        return Map.of("success", true, "name", name);
    }

    @Tool(description = "List scheduled-job run history. Parameters: name optional, limit optional default 50. Read-only.")
    public Object cronRuns(String name, Integer limit) {
        return adminService.listRuns(name, limit == null ? 50 : limit).stream()
                .map(ScheduledJobRunResponse::from)
                .toList();
    }

    private Map<String, Object> requireServiceRole(String action) {
        if (!MultiTenancyContext.isServiceRole()) {
            return Map.of("success", false, "error", "Cannot " + action + ": service_role MCP apikey is required");
        }
        return null;
    }
}
