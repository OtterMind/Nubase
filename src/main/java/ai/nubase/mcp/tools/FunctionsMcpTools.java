package ai.nubase.mcp.tools;

import ai.nubase.common.context.MultiTenancyContext;
import ai.nubase.functions.dto.EdgeFunctionDtos.CreateFunctionRequest;
import ai.nubase.functions.dto.EdgeFunctionDtos.DeployFunctionRequest;
import ai.nubase.functions.dto.EdgeFunctionDtos.EdgeFunctionResponse;
import ai.nubase.functions.dto.EdgeFunctionDtos.EdgeFunctionVersionResponse;
import ai.nubase.functions.dto.EdgeFunctionDtos.FunctionSecretResponse;
import ai.nubase.functions.dto.EdgeFunctionDtos.InvocationLogResponse;
import ai.nubase.functions.dto.EdgeFunctionDtos.SetFunctionSecretsRequest;
import ai.nubase.functions.dto.EdgeFunctionDtos.UpdateFunctionRequest;
import ai.nubase.functions.service.EdgeFunctionAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
@ConditionalOnBean(EdgeFunctionAdminService.class)
public class FunctionsMcpTools {

    private final EdgeFunctionAdminService adminService;

    @Tool(description = "List Edge Functions for the current project. Read-only.")
    public Object functionsList() {
        return adminService.listFunctions().stream().map(EdgeFunctionResponse::from).toList();
    }

    @Tool(description = "Create or update Edge Function metadata. Parameters: name required, description optional, verifyJwt optional, enabled optional, entrypoint optional. Requires service_role MCP apikey.")
    public Object functionsCreate(String name, String description, Boolean verifyJwt, Boolean enabled, String entrypoint) {
        Map<String, Object> guard = requireServiceRole("create edge function");
        if (guard != null) return guard;
        if (name == null || name.isBlank()) return Map.of("success", false, "error", "name is required");
        var request = new CreateFunctionRequest(name, name, description, verifyJwt, enabled, false, null, entrypoint);
        return EdgeFunctionResponse.from(adminService.createFunction(request));
    }

    @Tool(description = "Update Edge Function metadata by slug. Parameters: slug required, name/description/verifyJwt/enabled/entrypoint optional. Requires service_role MCP apikey.")
    public Object functionsUpdate(String slug, String name, String description, Boolean verifyJwt, Boolean enabled, String entrypoint) {
        Map<String, Object> guard = requireServiceRole("update edge function");
        if (guard != null) return guard;
        if (slug == null || slug.isBlank()) return Map.of("success", false, "error", "slug is required");
        var request = new UpdateFunctionRequest(name, description, verifyJwt, enabled, false, null, entrypoint);
        return EdgeFunctionResponse.from(adminService.updateFunction(slug, request));
    }

    @Tool(description = "Deploy an Edge Function source bundle. Parameters: slug required, sourceHash required, artifactType optional, sourceBundleBase64 optional. Requires service_role MCP apikey. Local source directories should use nubase_cli functions_deploy instead.")
    public Object functionsDeployBundle(String slug, String sourceHash, String artifactType, String sourceBundleBase64) {
        Map<String, Object> guard = requireServiceRole("deploy edge function");
        if (guard != null) return guard;
        if (slug == null || slug.isBlank()) return Map.of("success", false, "error", "slug is required");
        if (sourceHash == null || sourceHash.isBlank()) return Map.of("success", false, "error", "sourceHash is required");
        var request = new DeployFunctionRequest(sourceHash, null, artifactType == null ? "source_bundle" : artifactType, sourceBundleBase64);
        return EdgeFunctionVersionResponse.from(adminService.deploy(slug, request));
    }

    @Tool(description = "Delete an Edge Function by slug. Requires service_role MCP apikey.")
    public Object functionsDelete(String slug) {
        Map<String, Object> guard = requireServiceRole("delete edge function");
        if (guard != null) return guard;
        if (slug == null || slug.isBlank()) return Map.of("success", false, "error", "slug is required");
        adminService.deleteFunction(slug);
        return Map.of("success", true, "slug", slug);
    }

    @Tool(description = "List Edge Function invocation logs. Parameters: slug optional, limit optional default 100. Read-only.")
    public Object functionsLogs(String slug, Integer limit) {
        return adminService.listInvocations(slug, limit == null ? 100 : limit).stream()
                .map(InvocationLogResponse::from)
                .toList();
    }

    @Tool(description = "List secret names for an Edge Function. Secret values are never returned. Parameters: slug required.")
    public Object functionsSecretsList(String slug) {
        if (slug == null || slug.isBlank()) return Map.of("success", false, "error", "slug is required");
        return adminService.listSecrets(slug).stream().map(FunctionSecretResponse::from).toList();
    }

    @Tool(description = "Set Edge Function secrets from a map of KEY/value strings. Values are not returned. Parameters: slug required, secrets required. Requires service_role MCP apikey.")
    public Object functionsSecretsSet(String slug, Map<String, String> secrets) {
        Map<String, Object> guard = requireServiceRole("set edge function secrets");
        if (guard != null) return guard;
        if (slug == null || slug.isBlank()) return Map.of("success", false, "error", "slug is required");
        if (secrets == null || secrets.isEmpty()) return Map.of("success", false, "error", "secrets are required");
        return adminService.setSecrets(slug, new SetFunctionSecretsRequest(secrets))
                .stream().map(FunctionSecretResponse::from).toList();
    }

    private Map<String, Object> requireServiceRole(String action) {
        if (!MultiTenancyContext.isServiceRole()) {
            return Map.of("success", false, "error", "Cannot " + action + ": service_role MCP apikey is required");
        }
        return null;
    }
}
