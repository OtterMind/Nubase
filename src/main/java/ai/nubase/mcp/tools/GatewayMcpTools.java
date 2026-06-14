package ai.nubase.mcp.tools;

import ai.nubase.ai.gateway.entity.ApiKey;
import ai.nubase.ai.gateway.repository.ApiKeyRepository;
import ai.nubase.ai.gateway.util.GatewayKeyUtil;
import ai.nubase.common.context.MultiTenancyContext;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class GatewayMcpTools {

    private static final int SECRET_LENGTH = 48;

    private final ApiKeyRepository apiKeyRepository;

    @Tool(description = "List AI Gateway keys for the current project. Full key values and hashes are never returned. Read-only.")
    public Object gatewayListKeys() {
        return Map.of("data", apiKeyRepository.findAll().stream().map(this::toDto).toList());
    }

    @Tool(description = "Issue a new AI Gateway key. The full key is returned once. Parameters: name optional, description optional, expiresAt optional ISO local datetime. Requires service_role MCP apikey.")
    public Object gatewayIssueKey(String name, String description, String expiresAt) {
        Map<String, Object> guard = requireServiceRole("issue gateway key");
        if (guard != null) return guard;
        String appCode = MultiTenancyContext.getAppCode();
        if (appCode == null || appCode.isBlank()) {
            return Map.of("success", false, "error", "project context is required");
        }
        String fullKey = GatewayKeyUtil.generate(appCode, SECRET_LENGTH);
        ApiKey key = ApiKey.builder()
                .keyHash(GatewayKeyUtil.sha256Hex(fullKey))
                .keyPrefix(GatewayKeyUtil.displayPrefix(fullKey))
                .name(name == null || name.isBlank() ? "Untitled key" : name)
                .description(description)
                .scope("all")
                .isActive(true)
                .build();
        if (expiresAt != null && !expiresAt.isBlank()) {
            key.setExpiresAt(LocalDateTime.parse(expiresAt));
        }
        key = apiKeyRepository.save(key);
        Map<String, Object> dto = toDto(key);
        dto.put("apiKey", fullKey);
        return dto;
    }

    @Tool(description = "Revoke an AI Gateway key by id. Parameters: id required. Requires service_role MCP apikey.")
    public Object gatewayRevokeKey(Long id) {
        Map<String, Object> guard = requireServiceRole("revoke gateway key");
        if (guard != null) return guard;
        if (id == null) return Map.of("success", false, "error", "id is required");
        return apiKeyRepository.findById(id).map(key -> {
            key.setIsActive(false);
            key.setRevokedAt(LocalDateTime.now());
            apiKeyRepository.save(key);
            return Map.<String, Object>of("success", true, "id", id);
        }).orElseGet(() -> Map.of("success", false, "error", "gateway key not found"));
    }

    private Map<String, Object> toDto(ApiKey key) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", key.getId());
        dto.put("name", key.getName());
        dto.put("prefix", key.getKeyPrefix());
        dto.put("isActive", Boolean.TRUE.equals(key.getIsActive()));
        dto.put("createdAt", key.getCreatedAt());
        dto.put("lastUsedAt", key.getLastUsedAt());
        dto.put("expiresAt", key.getExpiresAt());
        dto.put("revokedAt", key.getRevokedAt());
        return dto;
    }

    private Map<String, Object> requireServiceRole(String action) {
        if (!MultiTenancyContext.isServiceRole()) {
            return Map.of("success", false, "error", "Cannot " + action + ": service_role MCP apikey is required");
        }
        return null;
    }
}
