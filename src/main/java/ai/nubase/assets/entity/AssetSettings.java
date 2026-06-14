package ai.nubase.assets.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.*;

import java.time.Instant;

/**
 * Per-project static asset delivery settings (assets schema, single row, id = 1).
 */
@Entity
@Table(name = "settings", schema = "assets")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AssetSettings {

    public static final short SINGLETON_ID = 1;

    @Id
    @Column(name = "id")
    private Short id;

    /** Cache-Control applied when a file has no per-file override. */
    @Column(name = "default_cache_control", nullable = false)
    private String defaultCacheControl;

    /**
     * Optional external CDN/custom domain prefix for public URLs (e.g. https://cdn.myapp.io).
     * When set, public URLs are built as {custom_base_url}/{path} — the owner's CDN/domain
     * mapping decides what sits behind the prefix.
     */
    @Column(name = "custom_base_url")
    private String customBaseUrl;

    /** Optional per-project max asset size override; null means the platform default. */
    @Column(name = "max_file_size_bytes")
    private Long maxFileSizeBytes;

    /** Optional SPA fallback path served when a public asset path is missing. */
    @Column(name = "spa_fallback_path")
    private String spaFallbackPath;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
