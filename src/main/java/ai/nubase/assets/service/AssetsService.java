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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    private static final String BOUNDED_MARKER_ROOT = "__goai_e2e";
    private static final String BOUNDED_MARKER_PREFIX = BOUNDED_MARKER_ROOT + "/";
    private static final String BOUNDED_ARTIFACT_DIGEST = "bounded-artifact-digest";
    private static final String BOUNDED_VERSION_ID = "bounded-version-id";
    private static final Pattern BOUNDED_MARKER_PATH = Pattern.compile(
            "^" + BOUNDED_MARKER_PREFIX + "run-[a-z0-9][a-z0-9-]{5,63}/marker\\.json$"
    );
    private static final Pattern SHA256_DIGEST = Pattern.compile("^sha256:[a-f0-9]{64}$");

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

    /** Explicit operator proof that backend-mode storage is private and isolated. */
    @Value("${nubase.assets.bounded-private-storage-enabled:false}")
    private boolean boundedPrivateStorageEnabled;

    // ==================== Upload / delete ====================

    @Transactional
    public AssetFileDTO upload(String rawPath, byte[] bytes, String contentType, String cacheControl, boolean upsert) {
        String path = normalizePath(rawPath);
        rejectReservedMarkerPath(path);
        StoredAsset stored = store(path, bytes, contentType, cacheControl, upsert, false);
        return toDTO(stored.file(), stored.settings());
    }

    @Transactional
    public BoundedMarkerUploadResult uploadBoundedMarker(
            String rawPath,
            byte[] bytes,
            String contentType,
            String cacheControl
    ) {
        String path = normalizePath(rawPath);
        requireBoundedMarkerPath(path);
        requireBoundedPrivateStorage();
        StoredAsset stored = store(path, bytes, contentType, cacheControl, false, true);
        AssetFile file = stored.file();
        String versionId = boundedVersionId(file);
        if (!isUsableVersionId(versionId)) {
            throw new IllegalStateException("Bounded marker version ID is unavailable");
        }
        return new BoundedMarkerUploadResult(
                file.getPath(),
                file.getSizeBytes(),
                file.getEtag(),
                versionId
        );
    }

    private StoredAsset store(
            String path,
            byte[] bytes,
            String contentType,
            String cacheControl,
            boolean upsert,
            boolean lockExisting
    ) {
        if (bytes == null || bytes.length == 0) {
            throw AssetsExceptions.badRequest("No content provided");
        }

        AssetSettings settings = getOrDefaultSettings();
        long limit = effectiveMaxFileSize(settings);
        if (bytes.length > limit) {
            throw AssetsExceptions.tooLarge(limit);
        }

        AssetFile existing = lockExisting
                ? assetFileRepository.findByPathForUpdate(path).orElse(null)
                : assetFileRepository.findByPath(path).orElse(null);
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
        String boundedArtifactDigest = null;
        if (lockExisting) {
            boundedArtifactDigest = sha256(bytes);
            file.setMetadata(Map.of(BOUNDED_ARTIFACT_DIGEST, boundedArtifactDigest));
            try {
                file = assetFileRepository.saveAndFlush(file);
            } catch (DataIntegrityViolationException e) {
                throw AssetsExceptions.conflict(path);
            }
        } else {
            file = assetFileRepository.save(file);
        }

        String s3Key = resolveKey(path);
        String bucket = bucketName();
        String servedCacheControl = effectiveCacheControl != null
                ? effectiveCacheControl : settings.getDefaultCacheControl();
        PutObjectRequest.Builder putRequestBuilder = PutObjectRequest.builder()
                .bucket(bucket).key(s3Key)
                .contentType(effectiveContentType).contentLength((long) bytes.length)
                .cacheControl(servedCacheControl);
        if (lockExisting) {
            requireBoundedBucketVersioning(bucket);
            putRequestBuilder
                    .ifNoneMatch("*")
                    .metadata(Map.of(BOUNDED_ARTIFACT_DIGEST, boundedArtifactDigest));
        }
        PutObjectRequest putRequest = putRequestBuilder.build();
        PutObjectResponse putResponse = r2.s3().putObject(putRequest, RequestBody.fromBytes(bytes));
        log.info("Asset uploaded to R2: s3Key={}, eTag={}", s3Key, putResponse.eTag());

        file.setEtag(putResponse.eTag());
        if (lockExisting) {
            if (!isUsableVersionId(putResponse.versionId())) {
                throw new IllegalStateException("Bounded marker version ID is unavailable");
            }
            file.setMetadata(Map.of(
                    BOUNDED_ARTIFACT_DIGEST, boundedArtifactDigest,
                    BOUNDED_VERSION_ID, putResponse.versionId()
            ));
        }
        file = assetFileRepository.save(file);

        return new StoredAsset(file, settings);
    }

    @Transactional
    public void delete(String rawPath) {
        String path = normalizePath(rawPath);
        rejectReservedMarkerPath(path);
        AssetFile file = assetFileRepository.findByPath(path)
                .orElseThrow(() -> AssetsExceptions.notFound(path));

        assetFileRepository.delete(file);

        String s3Key = resolveKey(path);
        r2.s3().deleteObject(DeleteObjectRequest.builder().bucket(bucketName()).key(s3Key).build());
        log.info("Asset deleted: s3Key={}", s3Key);
    }

    @Transactional
    public boolean deleteBoundedMarkerVersion(
            String rawPath,
            String expectedVersionId,
            String expectedEtag
    ) {
        String path = normalizePath(rawPath);
        requireBoundedMarkerPath(path);
        if (!isUsableVersionId(expectedVersionId) || StringUtils.isBlank(expectedEtag)) {
            return false;
        }

        AssetFile file = assetFileRepository.findByPathForUpdate(path).orElse(null);
        if (file == null || StringUtils.isBlank(file.getEtag())
                || !Objects.equals(file.getEtag(), expectedEtag)
                || !Objects.equals(boundedVersionId(file), expectedVersionId)) {
            return false;
        }

        String s3Key = resolveKey(path);
        String bucket = bucketName();
        LatestObjectState beforeDelete = headLatest(bucket, s3Key);
        if (beforeDelete.status() != LatestObjectStatus.FOUND
                || !matchesBoundedVersion(
                beforeDelete.response(),
                boundedArtifactDigest(file),
                expectedEtag,
                expectedVersionId)) {
            return false;
        }
        try {
            r2.s3().deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(s3Key)
                    .versionId(expectedVersionId)
                    .build());
        } catch (RuntimeException e) {
            return false;
        }
        if (headLatest(bucket, s3Key).status() != LatestObjectStatus.NOT_FOUND) {
            return false;
        }
        assetFileRepository.delete(file);
        log.info("Bounded marker version deleted: s3Key={}, versionId={}", s3Key, expectedVersionId);
        return true;
    }

    /**
     * Reconcile an upload whose outcome is unknown. Compensation is allowed only when the
     * exact reserved object carries the unpredictable digest of this marker attempt. The
     * exact version delete prevents a later replacement from being removed after the HEAD.
     */
    @Transactional
    public BoundedMarkerReconcileResult reconcileBoundedMarker(
            String rawPath,
            String expectedArtifactDigest
    ) {
        String path = normalizePath(rawPath);
        requireBoundedMarkerPath(path);
        if (expectedArtifactDigest == null
                || !SHA256_DIGEST.matcher(expectedArtifactDigest).matches()) {
            throw AssetsExceptions.badRequest("Invalid bounded marker artifact digest");
        }

        AssetFile file = assetFileRepository.findByPathForUpdate(path).orElse(null);
        if (file != null && !expectedArtifactDigest.equals(boundedArtifactDigest(file))) {
            return BoundedMarkerReconcileResult.ownershipMismatch();
        }

        String s3Key = resolveKey(path);
        String bucket = bucketName();
        LatestObjectState beforeDelete = headLatest(bucket, s3Key);
        if (beforeDelete.status() != LatestObjectStatus.FOUND) {
            return BoundedMarkerReconcileResult.unknown();
        }
        HeadObjectResponse head = beforeDelete.response();

        if (!expectedArtifactDigest.equals(head.metadata().get(BOUNDED_ARTIFACT_DIGEST))) {
            return BoundedMarkerReconcileResult.ownershipMismatch();
        }
        String observedEtag = head.eTag();
        String observedVersionId = head.versionId();
        if (StringUtils.isBlank(observedEtag) || !isUsableVersionId(observedVersionId)
                || (file != null && StringUtils.isNotBlank(file.getEtag())
                && !Objects.equals(file.getEtag(), observedEtag))
                || (file != null && StringUtils.isNotBlank(boundedVersionId(file))
                && !Objects.equals(boundedVersionId(file), observedVersionId))) {
            return BoundedMarkerReconcileResult.compensationFailed(
                    observedEtag, isUsableVersionId(observedVersionId) ? observedVersionId : null);
        }

        try {
            r2.s3().deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(s3Key)
                    .versionId(observedVersionId)
                    .build());
        } catch (RuntimeException e) {
            return BoundedMarkerReconcileResult.compensationFailed(
                    observedEtag, observedVersionId);
        }
        if (headLatest(bucket, s3Key).status() != LatestObjectStatus.NOT_FOUND) {
            return BoundedMarkerReconcileResult.compensationFailed(
                    observedEtag, observedVersionId);
        }
        if (file != null) {
            assetFileRepository.delete(file);
        }
        log.info("Bounded marker version reconciled and deleted: s3Key={}, versionId={}",
                s3Key, observedVersionId);
        return BoundedMarkerReconcileResult.compensated(observedEtag, observedVersionId);
    }

    // ==================== Read ====================

    public List<AssetFileDTO> list(String prefix, String search, Integer limit, Integer offset) {
        boolean includeReservedMarkers = MultiTenancyContext.isServiceRole();
        String normalizedPrefix = null;
        if (StringUtils.isNotBlank(prefix)) {
            normalizedPrefix = normalizePath(prefix);
            if (!includeReservedMarkers && isReservedMarkerPath(normalizedPrefix)) {
                return List.of();
            }
        }
        AssetSettings settings = getOrDefaultSettings();
        List<AssetFile> files = normalizedPrefix != null
                ? assetFileRepository.findByPathPrefix(normalizedPrefix)
                : assetFileRepository.findAllByOrderByPathAsc();
        files = new ArrayList<>(files);

        if (!includeReservedMarkers) {
            files = files.stream()
                    .filter(file -> !isReservedMarkerPath(file.getPath()))
                    .collect(Collectors.toList());
        }
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
        hideReservedMarkerPath(path);
        return assetFileRepository.findByPath(path)
                .orElseThrow(() -> AssetsExceptions.notFound(path));
    }

    public AssetFile getPublicFileOrThrow(String rawPath) {
        String path = normalizePath(rawPath);
        hideReservedMarkerPath(path);
        return assetFileRepository.findByPath(path)
                .orElseGet(() -> getFallbackFileOrThrow(path));
    }

    /** Open the R2 object stream for an asset. Caller is responsible for consuming/closing it. */
    public InputStream openStream(AssetFile file) {
        String path = normalizePath(file.getPath());
        hideReservedMarkerPath(path);
        String s3Key = resolveKey(path);
        try {
            return r2.s3().getObject(GetObjectRequest.builder().bucket(bucketName()).key(s3Key).build());
        } catch (NoSuchKeyException e) {
            // Metadata row without a backing object (e.g. a previously interrupted upload).
            throw AssetsExceptions.notFound(path);
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
            if (fallback.isEmpty()) {
                settings.setSpaFallbackPath(null);
            } else {
                String normalizedFallback = normalizePath(fallback);
                rejectReservedMarkerPath(normalizedFallback);
                settings.setSpaFallbackPath(normalizedFallback);
            }
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
                .publicUrl(isReservedMarkerPath(file.getPath())
                        ? null
                        : publicUrl(file.getPath(), settings))
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
        String normalizedFallbackPath = normalizePath(fallbackPath);
        hideReservedMarkerPath(normalizedFallbackPath);
        return assetFileRepository.findByPath(normalizedFallbackPath)
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

    private void rejectReservedMarkerPath(String path) {
        if (isReservedMarkerPath(path)) {
            throw AssetsExceptions.badRequest("The __goai_e2e namespace is reserved");
        }
    }

    private void hideReservedMarkerPath(String path) {
        if (isReservedMarkerPath(path)) {
            throw AssetsExceptions.notFound(path);
        }
    }

    private boolean isReservedMarkerPath(String path) {
        return BOUNDED_MARKER_ROOT.equals(path)
                || (path != null && path.startsWith(BOUNDED_MARKER_PREFIX));
    }

    private void requireBoundedMarkerPath(String path) {
        if (!BOUNDED_MARKER_PATH.matcher(path).matches()) {
            throw AssetsExceptions.badRequest("Invalid bounded marker path");
        }
    }

    private void requireBoundedPrivateStorage() {
        if (!boundedPrivateStorageEnabled
                || StringUtils.isNotBlank(assetsBucket)
                || StringUtils.isNotBlank(publicBaseUrl)
                || StringUtils.isNotBlank(r2.publicUrl())) {
            throw AssetsExceptions.boundedPrivateStorageRequired();
        }
    }

    private String boundedArtifactDigest(AssetFile file) {
        if (file.getMetadata() == null) {
            return null;
        }
        Object value = file.getMetadata().get(BOUNDED_ARTIFACT_DIGEST);
        return value instanceof String digest ? digest : null;
    }

    private String boundedVersionId(AssetFile file) {
        if (file.getMetadata() == null) {
            return null;
        }
        Object value = file.getMetadata().get(BOUNDED_VERSION_ID);
        return value instanceof String versionId ? versionId : null;
    }

    private LatestObjectState headLatest(String bucket, String s3Key) {
        try {
            HeadObjectResponse response = r2.s3().headObject(HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(s3Key)
                    .build());
            return new LatestObjectState(LatestObjectStatus.FOUND, response);
        } catch (NoSuchKeyException e) {
            return new LatestObjectState(LatestObjectStatus.NOT_FOUND, null);
        } catch (S3Exception e) {
            LatestObjectStatus status = e.statusCode() == 404
                    ? LatestObjectStatus.NOT_FOUND
                    : LatestObjectStatus.UNKNOWN;
            return new LatestObjectState(status, null);
        } catch (RuntimeException e) {
            return new LatestObjectState(LatestObjectStatus.UNKNOWN, null);
        }
    }

    private boolean matchesBoundedVersion(
            HeadObjectResponse response,
            String expectedArtifactDigest,
            String expectedEtag,
            String expectedVersionId
    ) {
        return response != null
                && expectedArtifactDigest != null
                && expectedArtifactDigest.equals(response.metadata().get(BOUNDED_ARTIFACT_DIGEST))
                && Objects.equals(expectedEtag, response.eTag())
                && Objects.equals(expectedVersionId, response.versionId());
    }

    private boolean isUsableVersionId(String versionId) {
        return StringUtils.isNotBlank(versionId) && !"null".equalsIgnoreCase(versionId.trim());
    }

    private void requireBoundedBucketVersioning(String bucket) {
        GetBucketVersioningResponse response;
        try {
            response = r2.s3().getBucketVersioning(GetBucketVersioningRequest.builder()
                    .bucket(bucket)
                    .build());
        } catch (RuntimeException e) {
            throw AssetsExceptions.versioningCheckFailed();
        }
        if (response == null || response.status() != BucketVersioningStatus.ENABLED) {
            throw AssetsExceptions.versioningRequired();
        }
    }

    private String sha256(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public enum BoundedMarkerReconcileStatus {
        COMPENSATED,
        OWNERSHIP_MISMATCH,
        COMPENSATION_FAILED,
        UNKNOWN
    }

    public record BoundedMarkerReconcileResult(
            BoundedMarkerReconcileStatus status,
            String ownershipEtag,
            String ownershipVersionId
    ) {
        private static BoundedMarkerReconcileResult compensated(
                String ownershipEtag,
                String ownershipVersionId
        ) {
            return new BoundedMarkerReconcileResult(
                    BoundedMarkerReconcileStatus.COMPENSATED,
                    ownershipEtag,
                    ownershipVersionId);
        }

        private static BoundedMarkerReconcileResult ownershipMismatch() {
            return new BoundedMarkerReconcileResult(
                    BoundedMarkerReconcileStatus.OWNERSHIP_MISMATCH, null, null);
        }

        private static BoundedMarkerReconcileResult compensationFailed(
                String ownershipEtag,
                String ownershipVersionId
        ) {
            return new BoundedMarkerReconcileResult(
                    BoundedMarkerReconcileStatus.COMPENSATION_FAILED,
                    ownershipEtag,
                    ownershipVersionId);
        }

        public static BoundedMarkerReconcileResult unknown() {
            return new BoundedMarkerReconcileResult(
                    BoundedMarkerReconcileStatus.UNKNOWN, null, null);
        }
    }

    public record BoundedMarkerUploadResult(
            String path,
            long sizeBytes,
            String etag,
            String ownershipVersionId
    ) {
    }

    private record StoredAsset(AssetFile file, AssetSettings settings) {
    }

    private enum LatestObjectStatus {
        FOUND,
        NOT_FOUND,
        UNKNOWN
    }

    private record LatestObjectState(
            LatestObjectStatus status,
            HeadObjectResponse response
    ) {
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
