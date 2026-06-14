package ai.nubase.assets.service;

import ai.nubase.assets.dto.AssetFileDTO;
import ai.nubase.assets.dto.AssetSettingsDTO;
import ai.nubase.assets.dto.UpdateAssetSettingsRequest;
import ai.nubase.assets.entity.AssetFile;
import ai.nubase.assets.entity.AssetSettings;
import ai.nubase.assets.repository.AssetFileRepository;
import ai.nubase.assets.repository.AssetSettingsRepository;
import ai.nubase.assets.service.AssetsExceptions.AssetsException;
import ai.nubase.common.config.AuthConfig;
import ai.nubase.common.context.MultiTenancyContext;
import ai.nubase.platform.storage.R2ClientProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.io.InputStream;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Static asset CDN service. Metadata and per-project delivery settings live in the
 * tenant's {@code assets} schema; file bytes live in R2 in one of two modes:
 *
 * <ul>
 *   <li><b>CDN mode</b> ({@code nubase.assets.bucket} set): a dedicated, publicly
 *       accessible bucket with a custom domain in front (e.g. assets.nubase.ai behind
 *       Cloudflare). Keys are {appCode}/{path} and public URLs point straight at the
 *       CDN — reads never touch this backend.</li>
 *   <li><b>Backend mode</b> (bucket empty): the global Storage bucket under the reserved
 *       {appCode}/__assets__/{path} prefix (bucket names are limited to [a-z0-9-], so the
 *       "__assets__" segment can never collide with a Storage bucket), served by
 *       {@code /assets/v1/**}.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssetsService {

    /** Reserved R2 key segment separating CDN assets from Storage buckets (backend mode). */
    private static final String R2_PREFIX_SEGMENT = "__assets__";

    private static final String DEFAULT_CACHE_CONTROL = "public, max-age=3600";

    private static final int MAX_PATH_LENGTH = 1024;

    /** Conservative charset so asset paths are URL-safe without encoding. */
    private static final Pattern PATH_SEGMENT = Pattern.compile("[A-Za-z0-9._-]+");

    private final AssetFileRepository assetFileRepository;
    private final AssetSettingsRepository assetSettingsRepository;
    private final R2ClientProvider r2;
    private final AuthConfig authConfig;

    @Value("${nubase.assets.max-file-size:26214400}")
    private long platformMaxFileSize;

    /** Dedicated public assets bucket; blank = backend mode on the global Storage bucket. */
    @Value("${nubase.assets.bucket:}")
    private String assetsBucket;

    /** Public origin of the assets bucket's custom domain, e.g. https://assets.nubase.ai */
    @Value("${nubase.assets.public-base-url:}")
    private String publicBaseUrl;

    // ==================== Upload / delete ====================

    @Transactional
    public AssetFileDTO upload(String rawPath, byte[] bytes, String contentType, String cacheControl, boolean upsert) {
        String path = normalizePath(rawPath);
        if (bytes == null || bytes.length == 0) {
            throw AssetsExceptions.badRequest("No content provided");
        }

        AssetSettings settings = getOrDefaultSettings();
        long limit = effectiveMaxFileSize(settings);
        if (bytes.length > limit) {
            throw AssetsExceptions.tooLarge(limit);
        }

        AssetFile existing = assetFileRepository.findByPath(path).orElse(null);
        if (existing != null && !upsert) {
            throw AssetsExceptions.conflict(path);
        }

        String effectiveContentType = StringUtils.isBlank(contentType)
                ? MediaType.APPLICATION_OCTET_STREAM_VALUE : contentType;
        String effectiveCacheControl = normalizeCacheControl(cacheControl);

        AssetFile file = existing != null ? existing : new AssetFile();
        file.setPath(path);
        file.setContentType(effectiveContentType);
        file.setSizeBytes(bytes.length);
        file.setCacheControl(effectiveCacheControl);
        file = assetFileRepository.save(file);

        String s3Key = resolveKey(path);
        String servedCacheControl = effectiveCacheControl != null
                ? effectiveCacheControl : settings.getDefaultCacheControl();
        PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(bucketName()).key(s3Key)
                .contentType(effectiveContentType).contentLength((long) bytes.length)
                .cacheControl(servedCacheControl)
                .build();
        PutObjectResponse putResponse = r2.s3().putObject(putRequest, RequestBody.fromBytes(bytes));
        log.info("Asset uploaded to R2: s3Key={}, eTag={}", s3Key, putResponse.eTag());

        file.setEtag(putResponse.eTag());
        file = assetFileRepository.save(file);

        return toDTO(file, settings);
    }

    @Transactional
    public void delete(String rawPath) {
        String path = normalizePath(rawPath);
        AssetFile file = assetFileRepository.findByPath(path)
                .orElseThrow(() -> AssetsExceptions.notFound(path));

        assetFileRepository.delete(file);

        String s3Key = resolveKey(path);
        r2.s3().deleteObject(DeleteObjectRequest.builder().bucket(bucketName()).key(s3Key).build());
        log.info("Asset deleted: s3Key={}", s3Key);
    }

    // ==================== Read ====================

    public List<AssetFileDTO> list(String prefix, String search, Integer limit, Integer offset) {
        AssetSettings settings = getOrDefaultSettings();
        List<AssetFile> files = StringUtils.isNotBlank(prefix)
                ? assetFileRepository.findByPathPrefix(normalizePath(prefix))
                : assetFileRepository.findAllByOrderByPathAsc();

        String keyword = StringUtils.trimToNull(search);
        if (keyword != null) {
            files = files.stream()
                    .filter(f -> StringUtils.containsIgnoreCase(f.getPath(), keyword))
                    .collect(Collectors.toList());
        }
        files.sort(Comparator.comparing(AssetFile::getPath));

        int fromIndex = Math.max(offset == null ? 0 : offset, 0);
        if (fromIndex >= files.size()) {
            return List.of();
        }
        int toIndex = files.size();
        if (limit != null && limit > 0) {
            toIndex = Math.min(fromIndex + limit, files.size());
        }

        return files.subList(fromIndex, toIndex).stream()
                .map(f -> toDTO(f, settings))
                .collect(Collectors.toList());
    }

    public AssetFile getFileOrThrow(String rawPath) {
        String path = normalizePath(rawPath);
        return assetFileRepository.findByPath(path)
                .orElseThrow(() -> AssetsExceptions.notFound(path));
    }

    public AssetFile getPublicFileOrThrow(String rawPath) {
        String path = normalizePath(rawPath);
        return assetFileRepository.findByPath(path)
                .orElseGet(() -> getFallbackFileOrThrow(path));
    }

    /** Open the R2 object stream for an asset. Caller is responsible for consuming/closing it. */
    public InputStream openStream(AssetFile file) {
        String s3Key = resolveKey(file.getPath());
        try {
            return r2.s3().getObject(GetObjectRequest.builder().bucket(bucketName()).key(s3Key).build());
        } catch (NoSuchKeyException e) {
            // Metadata row without a backing object (e.g. a previously interrupted upload).
            throw AssetsExceptions.notFound(file.getPath());
        }
    }

    /** Cache-Control actually sent when serving: per-file override or the project default. */
    public String resolveCacheControl(AssetFile file) {
        if (StringUtils.isNotBlank(file.getCacheControl())) {
            return file.getCacheControl();
        }
        return getOrDefaultSettings().getDefaultCacheControl();
    }

    // ==================== Settings ====================

    public AssetSettingsDTO getSettings() {
        return toDTO(getOrDefaultSettings());
    }

    @Transactional
    public AssetSettingsDTO updateSettings(UpdateAssetSettingsRequest request) {
        AssetSettings settings = getOrDefaultSettings();

        if (request.getDefaultCacheControl() != null) {
            String normalized = normalizeCacheControl(request.getDefaultCacheControl());
            settings.setDefaultCacheControl(normalized != null ? normalized : DEFAULT_CACHE_CONTROL);
        }
        if (request.getCustomBaseUrl() != null) {
            String base = request.getCustomBaseUrl().trim();
            if (base.isEmpty()) {
                settings.setCustomBaseUrl(null);
            } else {
                if (!base.startsWith("http://") && !base.startsWith("https://")) {
                    throw AssetsExceptions.badRequest("customBaseUrl must start with http:// or https://");
                }
                settings.setCustomBaseUrl(StringUtils.stripEnd(base, "/"));
            }
        }
        if (request.getSpaFallbackPath() != null) {
            String fallback = request.getSpaFallbackPath().trim();
            settings.setSpaFallbackPath(fallback.isEmpty() ? null : normalizePath(fallback));
        }
        if (request.getMaxFileSizeBytes() != null) {
            settings.setMaxFileSizeBytes(request.getMaxFileSizeBytes() > 0
                    ? request.getMaxFileSizeBytes() : null);
        }

        return toDTO(assetSettingsRepository.save(settings));
    }

    // ==================== Helpers ====================

    public AssetFileDTO toDTO(AssetFile file) {
        return toDTO(file, getOrDefaultSettings());
    }

    private AssetFileDTO toDTO(AssetFile file, AssetSettings settings) {
        return AssetFileDTO.builder()
                .path(file.getPath())
                .contentType(file.getContentType())
                .sizeBytes(file.getSizeBytes())
                .etag(file.getEtag())
                .cacheControl(file.getCacheControl())
                .createdAt(file.getCreatedAt())
                .updatedAt(file.getUpdatedAt())
                .publicUrl(publicUrl(file.getPath(), settings))
                .build();
    }

    private AssetSettingsDTO toDTO(AssetSettings settings) {
        return AssetSettingsDTO.builder()
                .defaultCacheControl(settings.getDefaultCacheControl())
                .customBaseUrl(settings.getCustomBaseUrl())
                .spaFallbackPath(settings.getSpaFallbackPath())
                .maxFileSizeBytes(settings.getMaxFileSizeBytes())
                .effectiveMaxFileSizeBytes(effectiveMaxFileSize(settings))
                .updatedAt(settings.getUpdatedAt())
                .build();
    }

    /**
     * Public URL of an asset, in priority order:
     * <ol>
     *   <li>project customBaseUrl — the project's own domain/CDN; the user's mapping decides
     *       what sits behind it, so the URL is simply {customBaseUrl}/{path}</li>
     *   <li>platform public-base-url (CDN mode) — the assets bucket's custom domain;
     *       objects are keyed by appCode, so {base}/{appCode}/{path}</li>
     *   <li>backend fallback — {scheme}://{appCode}.{serviceName}/assets/v1/{path}</li>
     * </ol>
     */
    public String publicUrl(String path, AssetSettings settings) {
        if (StringUtils.isNotBlank(settings.getCustomBaseUrl())) {
            return settings.getCustomBaseUrl() + "/" + path;
        }
        String appCode = MultiTenancyContext.getAppCode();
        if (StringUtils.isNotBlank(publicBaseUrl)) {
            return StringUtils.stripEnd(publicBaseUrl, "/") + "/" + appCode + "/" + path;
        }
        return authConfig.getApp().getDomain(appCode) + "/assets/v1/" + path;
    }

    private AssetSettings getOrDefaultSettings() {
        return assetSettingsRepository.findById(AssetSettings.SINGLETON_ID)
                .orElseGet(() -> AssetSettings.builder()
                        .id(AssetSettings.SINGLETON_ID)
                        .defaultCacheControl(DEFAULT_CACHE_CONTROL)
                        .build());
    }

    private AssetFile getFallbackFileOrThrow(String requestedPath) {
        AssetSettings settings = getOrDefaultSettings();
        String fallbackPath = settings.getSpaFallbackPath();
        if (StringUtils.isBlank(fallbackPath) || fallbackPath.equals(requestedPath)) {
            throw AssetsExceptions.notFound(requestedPath);
        }
        return assetFileRepository.findByPath(fallbackPath)
                .orElseThrow(() -> AssetsExceptions.notFound(requestedPath));
    }

    private long effectiveMaxFileSize(AssetSettings settings) {
        Long override = settings.getMaxFileSizeBytes();
        if (override != null && override > 0) {
            return Math.min(override, platformMaxFileSize);
        }
        return platformMaxFileSize;
    }

    /** Dedicated public bucket in CDN mode, otherwise the global Storage bucket. */
    private String bucketName() {
        return StringUtils.isNotBlank(assetsBucket) ? assetsBucket : r2.bucket();
    }

    private String resolveKey(String path) {
        String appCode = MultiTenancyContext.getAppCode();
        if (StringUtils.isBlank(appCode)) {
            throw new IllegalStateException("appCode is not set in MultiTenancyContext");
        }
        // The dedicated bucket holds nothing but assets, so the key is the public URL path
        // ({appCode}/{path}); the shared bucket needs the reserved segment to stay clear
        // of Storage bucket prefixes.
        if (StringUtils.isNotBlank(assetsBucket)) {
            return appCode + "/" + path;
        }
        return appCode + "/" + R2_PREFIX_SEGMENT + "/" + path;
    }

    /**
     * Strip the cacheControl shorthand: blank means "use the project default" (null), pure
     * digits become {@code max-age=N}, anything else passes through verbatim.
     */
    private String normalizeCacheControl(String rawCacheControl) {
        if (StringUtils.isBlank(rawCacheControl)) {
            return null;
        }
        String trimmed = rawCacheControl.trim();
        if (trimmed.matches("\\d+")) {
            return "max-age=" + trimmed;
        }
        return trimmed;
    }

    /**
     * Validate and normalize an asset path: strips a leading slash, requires URL-safe
     * segments ([A-Za-z0-9._-]) separated by single slashes, and rejects "." / ".."
     * segments so a path can never escape the tenant's R2 prefix.
     */
    public static String normalizePath(String rawPath) {
        if (StringUtils.isBlank(rawPath)) {
            throw AssetsExceptions.badRequest("path is required");
        }
        String path = StringUtils.strip(rawPath.trim(), "/");
        if (path.isEmpty() || path.length() > MAX_PATH_LENGTH) {
            throw AssetsExceptions.badRequest("path must be 1-" + MAX_PATH_LENGTH + " characters");
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty()) {
                throw AssetsExceptions.badRequest("path must not contain empty segments: " + path);
            }
            if (".".equals(segment) || "..".equals(segment) || !PATH_SEGMENT.matcher(segment).matches()) {
                throw AssetsExceptions.badRequest(
                        "path segments may only contain letters, digits, '.', '_' and '-': " + path);
            }
        }
        return path;
    }
}
