package ai.nubase.assets.dto;

import lombok.Builder;

import java.time.Instant;

/**
 * Per-project static asset delivery settings.
 */
@Builder
public record AssetSettingsDTO(
        String defaultCacheControl,
        String customBaseUrl,
        String spaFallbackPath,
        Long maxFileSizeBytes,
        long effectiveMaxFileSizeBytes,
        Instant updatedAt
) {
}
