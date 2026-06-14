package ai.nubase.mcp.tools;

import ai.nubase.assets.dto.AssetFileDTO;
import ai.nubase.assets.service.AssetsExceptions.AssetsException;
import ai.nubase.assets.service.AssetsService;
import ai.nubase.common.context.MultiTenancyContext;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Static asset CDN tools for the Spring AI MCP server. Lets a coding agent publish the
 * static assets of the app it is building (images, css, js, fonts) straight to the
 * project's public CDN endpoint (/assets/v1/**).
 */
@Component
@RequiredArgsConstructor
public class AssetsMcpTools {

    private final AssetsService assetsService;

    @Value("${nubase.assets.enabled:true}")
    private boolean assetsEnabled;

    @Tool(description = "Upload a static asset to the project's public CDN (served at /assets/v1/{path}). "
            + "Parameters: path required (e.g. 'img/logo.png'); exactly one of content (UTF-8 text, for "
            + "css/js/html/svg) or contentBase64 (binary files); contentType optional MIME type; "
            + "cacheControl optional (seconds or a full Cache-Control value, default: project setting); "
            + "upsert optional, defaults true. Requires the service_role apikey. Returns the public URL.")
    public Map<String, Object> assetsUpload(String path, String content, String contentBase64,
                                            String contentType, String cacheControl, Boolean upsert) {
        Map<String, Object> guard = checkEnabledAndServiceRole();
        if (guard != null) {
            return guard;
        }
        if (isBlank(path)) {
            return Map.of("success", false, "error", "path is required");
        }
        boolean hasText = !isBlank(content);
        boolean hasBase64 = !isBlank(contentBase64);
        if (hasText == hasBase64) {
            return Map.of("success", false, "error", "Provide exactly one of content or contentBase64");
        }
        byte[] bytes;
        if (hasBase64) {
            try {
                bytes = Base64.getDecoder().decode(contentBase64);
            } catch (IllegalArgumentException e) {
                return Map.of("success", false, "error", "contentBase64 is not valid base64");
            }
        } else {
            bytes = content.getBytes(StandardCharsets.UTF_8);
        }
        try {
            AssetFileDTO dto = assetsService.upload(path, bytes, contentType, cacheControl,
                    upsert == null || upsert);
            return Map.of("success", true, "file", dto, "url", dto.publicUrl());
        } catch (AssetsException e) {
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    @Tool(description = "List the project's static CDN assets with their public URLs. "
            + "Parameters: prefix optional path prefix filter (e.g. 'img/'), search optional keyword, "
            + "limit optional max results (default 100).")
    public Map<String, Object> assetsList(String prefix, String search, Integer limit) {
        if (!assetsEnabled) {
            return Map.of("success", false, "error", "Assets module is disabled (nubase.assets.enabled=false)");
        }
        try {
            List<AssetFileDTO> files = assetsService.list(blankToNull(prefix), blankToNull(search),
                    limit == null ? 100 : limit, null);
            return Map.of("success", true, "files", files, "count", files.size());
        } catch (AssetsException e) {
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    @Tool(description = "Delete a static asset from the project's public CDN. "
            + "Parameters: path required (e.g. 'img/logo.png'). Requires the service_role apikey.")
    public Map<String, Object> assetsDelete(String path) {
        Map<String, Object> guard = checkEnabledAndServiceRole();
        if (guard != null) {
            return guard;
        }
        if (isBlank(path)) {
            return Map.of("success", false, "error", "path is required");
        }
        try {
            assetsService.delete(path);
            return Map.of("success", true, "path", path);
        } catch (AssetsException e) {
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    @Tool(description = "Update static asset delivery settings. Parameters: defaultCacheControl optional, customBaseUrl optional, spaFallbackPath optional, maxFileSizeBytes optional. Requires the service_role apikey.")
    public Map<String, Object> assetsUpdateSettings(String defaultCacheControl, String customBaseUrl,
                                                    String spaFallbackPath, Long maxFileSizeBytes) {
        Map<String, Object> guard = checkEnabledAndServiceRole();
        if (guard != null) {
            return guard;
        }
        var request = new ai.nubase.assets.dto.UpdateAssetSettingsRequest();
        request.setDefaultCacheControl(defaultCacheControl);
        request.setCustomBaseUrl(customBaseUrl);
        request.setSpaFallbackPath(spaFallbackPath);
        request.setMaxFileSizeBytes(maxFileSizeBytes);
        try {
            return Map.of("success", true, "settings", assetsService.updateSettings(request));
        } catch (AssetsException e) {
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    private Map<String, Object> checkEnabledAndServiceRole() {
        if (!assetsEnabled) {
            return Map.of("success", false, "error", "Assets module is disabled (nubase.assets.enabled=false)");
        }
        if (!MultiTenancyContext.isServiceRole()) {
            return Map.of("success", false, "error",
                    "Mutating asset tools require connecting MCP with the project's service_role apikey");
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value;
    }
}
