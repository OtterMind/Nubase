package ai.nubase.assets.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * PATCH body for asset settings. Null fields are left unchanged; an empty string
 * clears customBaseUrl, and maxFileSizeBytes <= 0 clears the per-project override.
 */
@Getter
@Setter
public class UpdateAssetSettingsRequest {
    private String defaultCacheControl;
    private String customBaseUrl;
    private String spaFallbackPath;
    private Long maxFileSizeBytes;
}
