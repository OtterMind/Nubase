package ai.nubase.deploy.service;

import ai.nubase.assets.service.AssetsService;
import ai.nubase.assets.service.AssetsService.BoundedMarkerReconcileResult;
import ai.nubase.assets.service.AssetsService.BoundedMarkerReconcileStatus;
import ai.nubase.assets.service.AssetsService.BoundedMarkerUploadResult;
import ai.nubase.assets.service.AssetsExceptions.AssetsException;
import ai.nubase.deploy.dto.AppDeploymentDtos.CompleteDeploymentRequest;
import ai.nubase.deploy.dto.AppDeploymentDtos.CreateDeploymentRequest;
import ai.nubase.deploy.dto.AppDeploymentDtos.DeploymentResponse;
import ai.nubase.deploy.dto.AppDeploymentDtos.RecordDeploymentStepRequest;
import ai.nubase.metadata.entity.AppDeployment;
import ai.nubase.metadata.entity.AppDeploymentStep;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BoundedAssetDeploymentService {

    public static final String PROFILE = "bounded-asset-v1";
    public static final String TRANSPORT = "java-http-mcp";
    private static final String CONTENT_TYPE = "application/json";
    private static final String CACHE_CONTROL = "no-store";

    private final AppDeploymentService deploymentService;
    private final AssetsService assetsService;
    private final ObjectMapper objectMapper;

    public StageAssetDeploymentResponse stage(
            String appName,
            String taskId,
            String runId,
            String manifestDigest
    ) {
        byte[] marker = markerBytes(
                appName, taskId, runId, manifestDigest, UUID.randomUUID().toString());
        String artifactDigest = sha256(marker);
        String path = "__goai_e2e/" + runId + "/marker.json";
        Map<String, Object> summary = Map.of(
                "artifactDigest", artifactDigest,
                "manifestDigest", manifestDigest,
                "markerPath", path,
                "profile", PROFILE,
                "taskId", taskId,
                "transport", TRANSPORT
        );

        DeploymentResponse deployment;
        try {
            deployment = deploymentService.create(new CreateDeploymentRequest(
                    appName,
                    summary,
                    "builder-agent",
                    runId
            ));
        } catch (DataIntegrityViolationException ignored) {
            return existingRunResponse(appName, taskId, runId, manifestDigest, path);
        } catch (RuntimeException ignored) {
            return StageAssetDeploymentResponse.failed(
                    null, AppDeployment.STATUS_FAILED, path, "DEPLOYMENT_CREATE_FAILED");
        }
        UUID deploymentId = deployment.id();

        BoundedMarkerUploadResult asset;
        try {
            asset = assetsService.uploadBoundedMarker(path, marker, CONTENT_TYPE, CACHE_CONTROL);
        } catch (AssetsException failure) {
            if (isPreWriteStorageFailure(failure.code())) {
                recordFailure(deploymentId, path, failure.code());
                return StageAssetDeploymentResponse.failed(
                        deploymentId, AppDeployment.STATUS_FAILED, path, failure.code());
            }
            return reconcileUploadFailure(deploymentId, path, artifactDigest, marker.length);
        } catch (RuntimeException ignored) {
            return reconcileUploadFailure(deploymentId, path, artifactDigest, marker.length);
        }
        if (asset == null
                || asset.etag() == null
                || asset.etag().isBlank()
                || !isUsableVersionId(asset.ownershipVersionId())) {
            return reconcileUploadFailure(deploymentId, path, artifactDigest, marker.length);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("artifactDigest", artifactDigest);
        result.put("sizeBytes", asset.sizeBytes());
        if (asset.etag() != null && !asset.etag().isBlank()) {
            result.put("etag", asset.etag());
        }
        result.put("ownershipVersionId", asset.ownershipVersionId());
        try {
            deploymentService.recordStep(deploymentId, new RecordDeploymentStepRequest(
                    1,
                    "assets_upload",
                    path,
                    AppDeploymentStep.STATUS_SUCCEEDED,
                    result,
                    null
            ));
        } catch (RuntimeException ignored) {
            boolean compensated;
            try {
                compensated = assetsService.deleteBoundedMarkerVersion(
                        path, asset.ownershipVersionId(), asset.etag());
            } catch (RuntimeException compensationFailure) {
                compensated = false;
            }
            String errorCode = compensated ? "STEP_RECORD_FAILED" : "ASSET_COMPENSATION_FAILED";
            bestEffortFinishFailed(deploymentId, errorCode);
            return StageAssetDeploymentResponse.failedWithArtifact(
                    deploymentId,
                    AppDeployment.STATUS_FAILED,
                    path,
                    artifactDigest,
                    asset.sizeBytes(),
                    asset.etag(),
                    asset.ownershipVersionId(),
                    errorCode
            );
        }

        try {
            DeploymentResponse completed = deploymentService.complete(deploymentId, new CompleteDeploymentRequest(
                    AppDeployment.STATUS_SUCCEEDED,
                    null,
                    null
            ));
            return new StageAssetDeploymentResponse(
                    true,
                    deploymentId,
                    completed.status(),
                    path,
                    artifactDigest,
                    asset.sizeBytes(),
                    asset.etag(),
                    asset.ownershipVersionId(),
                    null
            );
        } catch (RuntimeException ignored) {
            return StageAssetDeploymentResponse.failedWithArtifact(
                    deploymentId,
                    AppDeployment.STATUS_RUNNING,
                    path,
                    artifactDigest,
                    asset.sizeBytes(),
                    asset.etag(),
                    asset.ownershipVersionId(),
                    "DEPLOYMENT_FINISH_FAILED"
            );
        }
    }

    private StageAssetDeploymentResponse existingRunResponse(
            String appName,
            String taskId,
            String runId,
            String manifestDigest,
            String path
    ) {
        Optional<DeploymentResponse> existing = deploymentService.findBoundedByRunId(runId);
        if (existing.isEmpty()) {
            return StageAssetDeploymentResponse.failed(
                    null, AppDeployment.STATUS_FAILED, path, "DEPLOYMENT_CREATE_FAILED");
        }
        DeploymentResponse deployment = existing.get();
        Map<String, Object> summary = summary(deployment.manifestSummary());
        String artifactDigest = summary.get("artifactDigest") instanceof String value ? value : null;
        boolean sameRequest = appName.equals(deployment.appName())
                && taskId.equals(summary.get("taskId"))
                && manifestDigest.equals(summary.get("manifestDigest"))
                && path.equals(summary.get("markerPath"))
                && artifactDigest != null
                && artifactDigest.matches("^sha256:[a-f0-9]{64}$")
                && PROFILE.equals(summary.get("profile"))
                && TRANSPORT.equals(summary.get("transport"));
        if (!sameRequest) {
            return StageAssetDeploymentResponse.failed(
                    deployment.id(), deployment.status(), path, "RUN_ID_CONFLICT");
        }
        return StageAssetDeploymentResponse.failedWithArtifact(
                deployment.id(),
                deployment.status(),
                path,
                artifactDigest,
                null,
                null,
                null,
                "RUN_ID_ALREADY_USED"
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> summary(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    private byte[] markerBytes(
            String appName,
            String taskId,
            String runId,
            String manifestDigest,
            String ownershipNonce
    ) {
        Map<String, Object> marker = new LinkedHashMap<>();
        marker.put("schemaVersion", "1.0");
        marker.put("appName", appName);
        marker.put("taskId", taskId);
        marker.put("runId", runId);
        marker.put("manifestDigest", manifestDigest);
        marker.put("profile", PROFILE);
        marker.put("ownershipNonce", ownershipNonce);
        try {
            return objectMapper.writeValueAsString(marker).getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialize bounded deployment marker", e);
        }
    }

    private void recordFailure(UUID deploymentId, String path, String errorCode) {
        try {
            deploymentService.recordStep(deploymentId, new RecordDeploymentStepRequest(
                    1,
                    "assets_upload",
                    path,
                    AppDeploymentStep.STATUS_FAILED,
                    null,
                    errorCode
            ));
        } catch (RuntimeException ignored) {
            // The returned error code remains bounded even when metadata recording is unavailable.
        }
        bestEffortFinishFailed(deploymentId, errorCode);
    }

    private StageAssetDeploymentResponse uploadFailureResponse(
            UUID deploymentId,
            String path,
            String artifactDigest,
            long sizeBytes,
            BoundedMarkerReconcileResult reconciliation
    ) {
        BoundedMarkerReconcileStatus status = reconciliation == null
                ? BoundedMarkerReconcileStatus.UNKNOWN
                : reconciliation.status();
        String errorCode = switch (status) {
            case COMPENSATED -> "ASSET_UPLOAD_FAILED_COMPENSATED";
            case OWNERSHIP_MISMATCH -> "ASSET_OWNERSHIP_CONFLICT";
            case COMPENSATION_FAILED -> "ASSET_COMPENSATION_FAILED";
            case UNKNOWN -> "ASSET_STATE_UNKNOWN";
        };
        recordFailure(deploymentId, path, errorCode);
        if (status == BoundedMarkerReconcileStatus.OWNERSHIP_MISMATCH) {
            return StageAssetDeploymentResponse.failed(
                    deploymentId, AppDeployment.STATUS_FAILED, path, errorCode);
        }
        return StageAssetDeploymentResponse.failedWithArtifact(
                deploymentId,
                AppDeployment.STATUS_FAILED,
                path,
                artifactDigest,
                sizeBytes,
                reconciliation == null ? null : reconciliation.ownershipEtag(),
                reconciliation == null ? null : reconciliation.ownershipVersionId(),
                errorCode
        );
    }

    private StageAssetDeploymentResponse reconcileUploadFailure(
            UUID deploymentId,
            String path,
            String artifactDigest,
            long sizeBytes
    ) {
        BoundedMarkerReconcileResult reconciliation;
        try {
            reconciliation = assetsService.reconcileBoundedMarker(path, artifactDigest);
        } catch (RuntimeException reconciliationFailure) {
            reconciliation = BoundedMarkerReconcileResult.unknown();
        }
        return uploadFailureResponse(
                deploymentId, path, artifactDigest, sizeBytes, reconciliation);
    }

    private boolean isPreWriteStorageFailure(String code) {
        return "ASSET_BOUNDED_PRIVATE_STORAGE_REQUIRED".equals(code)
                || "ASSET_VERSIONING_REQUIRED".equals(code)
                || "ASSET_VERSIONING_CHECK_FAILED".equals(code);
    }

    private boolean isUsableVersionId(String versionId) {
        return versionId != null
                && !versionId.isBlank()
                && !"null".equalsIgnoreCase(versionId.trim());
    }

    private void bestEffortFinishFailed(UUID deploymentId, String errorCode) {
        try {
            deploymentService.complete(deploymentId, new CompleteDeploymentRequest(
                    AppDeployment.STATUS_FAILED,
                    null,
                    errorCode
            ));
        } catch (RuntimeException ignored) {
            // The caller receives a bounded failure and can reconcile by deployment ID.
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

    public record StageAssetDeploymentResponse(
            boolean success,
            UUID deploymentId,
            String status,
            String path,
            String artifactDigest,
            Long sizeBytes,
            String ownershipEtag,
            String ownershipVersionId,
            String errorCode
    ) {
        static StageAssetDeploymentResponse failed(
                UUID deploymentId,
                String status,
                String path,
                String errorCode
        ) {
            return new StageAssetDeploymentResponse(
                    false, deploymentId, status, path, null, null, null, null, errorCode);
        }

        static StageAssetDeploymentResponse failedWithArtifact(
                UUID deploymentId,
                String status,
                String path,
                String artifactDigest,
                Long sizeBytes,
                String ownershipEtag,
                String ownershipVersionId,
                String errorCode
        ) {
            return new StageAssetDeploymentResponse(
                    false,
                    deploymentId,
                    status,
                    path,
                    artifactDigest,
                    sizeBytes,
                    ownershipEtag,
                    ownershipVersionId,
                    errorCode
            );
        }
    }
}
