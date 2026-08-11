package ai.nubase.deploy.service;

import ai.nubase.assets.service.AssetsService;
import ai.nubase.assets.service.AssetsService.BoundedMarkerReconcileResult;
import ai.nubase.assets.service.AssetsService.BoundedMarkerReconcileStatus;
import ai.nubase.assets.service.AssetsService.BoundedMarkerUploadResult;
import ai.nubase.assets.service.AssetsExceptions;
import ai.nubase.deploy.dto.AppDeploymentDtos.CompleteDeploymentRequest;
import ai.nubase.deploy.dto.AppDeploymentDtos.CreateDeploymentRequest;
import ai.nubase.deploy.dto.AppDeploymentDtos.DeploymentResponse;
import ai.nubase.deploy.dto.AppDeploymentDtos.RecordDeploymentStepRequest;
import ai.nubase.metadata.entity.AppDeployment;
import ai.nubase.metadata.entity.AppDeploymentStep;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BoundedAssetDeploymentServiceTest {

    private static final String RUN_ID = "run-bounded-demo-001";
    private static final String TASK_ID = "task-bounded-demo-001";
    private static final String MANIFEST_DIGEST =
            "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private AppDeploymentService deploymentService;
    private AssetsService assetsService;
    private ObjectMapper objectMapper;
    private BoundedAssetDeploymentService service;

    @BeforeEach
    void setUp() {
        deploymentService = mock(AppDeploymentService.class);
        assetsService = mock(AssetsService.class);
        objectMapper = new ObjectMapper();
        service = new BoundedAssetDeploymentService(deploymentService, assetsService, objectMapper);
    }

    @Test
    void stagesServerGeneratedMarkerAndRecordsTruthfulStep() throws Exception {
        UUID deploymentId = UUID.randomUUID();
        String path = "__goai_e2e/" + RUN_ID + "/marker.json";
        when(deploymentService.create(any(CreateDeploymentRequest.class)))
                .thenReturn(deployment(deploymentId, AppDeployment.STATUS_RUNNING, null));
        when(assetsService.uploadBoundedMarker(
                        eq(path), any(byte[].class), eq("application/json"), eq("no-store")))
                .thenReturn(new BoundedMarkerUploadResult(
                        path,
                        128,
                        "etag-1",
                        "version-1"));
        when(deploymentService.complete(eq(deploymentId), any(CompleteDeploymentRequest.class)))
                .thenReturn(deployment(
                        deploymentId,
                        AppDeployment.STATUS_SUCCEEDED,
                        "http://sandbox.example/assets/v1/" + path));

        var response = service.stage("goai-bounded-demo", TASK_ID, RUN_ID, MANIFEST_DIGEST);

        assertThat(response.success()).isTrue();
        assertThat(response.deploymentId()).isEqualTo(deploymentId);
        assertThat(response.status()).isEqualTo(AppDeployment.STATUS_SUCCEEDED);
        assertThat(response.path()).isEqualTo(path);
        assertThat(response.artifactDigest()).matches("sha256:[a-f0-9]{64}");
        assertThat(response.sizeBytes()).isEqualTo(128);
        assertThat(response.ownershipVersionId()).isEqualTo("version-1");
        assertThat(response.errorCode()).isNull();

        ArgumentCaptor<CreateDeploymentRequest> createCaptor =
                ArgumentCaptor.forClass(CreateDeploymentRequest.class);
        verify(deploymentService).create(createCaptor.capture());
        assertThat(createCaptor.getValue().agentId()).isEqualTo("builder-agent");
        assertThat(createCaptor.getValue().runId()).isEqualTo(RUN_ID);
        assertThat(createCaptor.getValue().manifestSummary())
                .containsEntry("profile", BoundedAssetDeploymentService.PROFILE)
                .containsEntry("transport", BoundedAssetDeploymentService.TRANSPORT)
                .containsEntry("taskId", TASK_ID)
                .containsEntry("manifestDigest", MANIFEST_DIGEST)
                .containsEntry("artifactDigest", response.artifactDigest());

        ArgumentCaptor<byte[]> markerCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(assetsService).uploadBoundedMarker(
                eq(path), markerCaptor.capture(), eq("application/json"), eq("no-store"));
        Map<String, Object> marker = objectMapper.readValue(
                markerCaptor.getValue(), new TypeReference<Map<String, Object>>() {});
        assertThat(marker).containsOnlyKeys(
                "schemaVersion", "appName", "taskId", "runId", "manifestDigest", "profile",
                "ownershipNonce");
        assertThat(marker).containsEntry("taskId", TASK_ID).containsEntry("runId", RUN_ID);
        assertThatCode(() -> UUID.fromString((String) marker.get("ownershipNonce")))
                .doesNotThrowAnyException();

        ArgumentCaptor<RecordDeploymentStepRequest> stepCaptor =
                ArgumentCaptor.forClass(RecordDeploymentStepRequest.class);
        verify(deploymentService).recordStep(eq(deploymentId), stepCaptor.capture());
        assertThat(stepCaptor.getValue().stepName()).isEqualTo("assets_upload");
        assertThat(stepCaptor.getValue().targetName()).isEqualTo(path);
        assertThat(stepCaptor.getValue().status()).isEqualTo(AppDeploymentStep.STATUS_SUCCEEDED);
        assertThat(stepCaptor.getValue().result())
                .containsEntry("artifactDigest", response.artifactDigest())
                .containsEntry("sizeBytes", 128L)
                .containsEntry("etag", "etag-1")
                .containsEntry("ownershipVersionId", "version-1");
        ArgumentCaptor<CompleteDeploymentRequest> completeCaptor =
                ArgumentCaptor.forClass(CompleteDeploymentRequest.class);
        verify(deploymentService).complete(eq(deploymentId), completeCaptor.capture());
        assertThat(completeCaptor.getValue().publicUrl()).isNull();
    }

    @Test
    void failsBeforeWriteWhenPrivateStorageProofIsMissing() {
        UUID deploymentId = UUID.randomUUID();
        when(deploymentService.create(any(CreateDeploymentRequest.class)))
                .thenReturn(deployment(deploymentId, AppDeployment.STATUS_RUNNING, null));
        when(assetsService.uploadBoundedMarker(anyString(), any(byte[].class), anyString(), anyString()))
                .thenThrow(AssetsExceptions.boundedPrivateStorageRequired());

        var response = service.stage("goai-bounded-demo", TASK_ID, RUN_ID, MANIFEST_DIGEST);

        assertThat(response.success()).isFalse();
        assertThat(response.errorCode()).isEqualTo("ASSET_BOUNDED_PRIVATE_STORAGE_REQUIRED");
        verify(assetsService, org.mockito.Mockito.never())
                .reconcileBoundedMarker(anyString(), anyString());
        verify(deploymentService).complete(
                eq(deploymentId),
                eq(new CompleteDeploymentRequest(
                        AppDeployment.STATUS_FAILED,
                        null,
                        "ASSET_BOUNDED_PRIVATE_STORAGE_REQUIRED")));
    }

    @Test
    void failsBeforeWriteWhenBucketVersioningIsUnavailable() {
        UUID deploymentId = UUID.randomUUID();
        String path = "__goai_e2e/" + RUN_ID + "/marker.json";
        when(deploymentService.create(any(CreateDeploymentRequest.class)))
                .thenReturn(deployment(deploymentId, AppDeployment.STATUS_RUNNING, null));
        when(assetsService.uploadBoundedMarker(anyString(), any(byte[].class), anyString(), anyString()))
                .thenThrow(AssetsExceptions.versioningRequired());

        var response = service.stage("goai-bounded-demo", TASK_ID, RUN_ID, MANIFEST_DIGEST);

        assertThat(response.success()).isFalse();
        assertThat(response.errorCode()).isEqualTo("ASSET_VERSIONING_REQUIRED");
        ArgumentCaptor<RecordDeploymentStepRequest> stepCaptor =
                ArgumentCaptor.forClass(RecordDeploymentStepRequest.class);
        verify(deploymentService).recordStep(eq(deploymentId), stepCaptor.capture());
        assertThat(stepCaptor.getValue().status()).isEqualTo(AppDeploymentStep.STATUS_FAILED);
        assertThat(stepCaptor.getValue().errorMessage()).isEqualTo("ASSET_VERSIONING_REQUIRED");
        verify(deploymentService).complete(
                eq(deploymentId),
                eq(new CompleteDeploymentRequest(
                        AppDeployment.STATUS_FAILED, null, "ASSET_VERSIONING_REQUIRED")));
        verify(assetsService, org.mockito.Mockito.never())
                .reconcileBoundedMarker(anyString(), anyString());
        verify(assetsService, org.mockito.Mockito.never())
                .deleteBoundedMarkerVersion(anyString(), anyString(), anyString());
    }

    @Test
    void reportsUnknownWhenPutMayHaveSucceededBeforeClientTimeout() {
        UUID deploymentId = UUID.randomUUID();
        String path = "__goai_e2e/" + RUN_ID + "/marker.json";
        when(deploymentService.create(any(CreateDeploymentRequest.class)))
                .thenReturn(deployment(deploymentId, AppDeployment.STATUS_RUNNING, null));
        when(assetsService.uploadBoundedMarker(anyString(), any(byte[].class), anyString(), anyString()))
                .thenThrow(new IllegalStateException("put response timed out"));
        when(assetsService.reconcileBoundedMarker(eq(path), anyString()))
                .thenReturn(BoundedMarkerReconcileResult.unknown());

        var response = service.stage("goai-bounded-demo", TASK_ID, RUN_ID, MANIFEST_DIGEST);

        assertThat(response.success()).isFalse();
        assertThat(response.errorCode()).isEqualTo("ASSET_STATE_UNKNOWN");
        assertThat(response.artifactDigest()).matches("sha256:[a-f0-9]{64}");
        assertThat(response.ownershipEtag()).isNull();
        assertThat(response.ownershipVersionId()).isNull();
        verify(deploymentService).complete(eq(deploymentId), eq(new CompleteDeploymentRequest(
                AppDeployment.STATUS_FAILED, null, "ASSET_STATE_UNKNOWN")));
    }

    @Test
    void neverRecordsSuccessWhenProviderReturnsNullVersionOwnership() {
        UUID deploymentId = UUID.randomUUID();
        String path = "__goai_e2e/" + RUN_ID + "/marker.json";
        when(deploymentService.create(any(CreateDeploymentRequest.class)))
                .thenReturn(deployment(deploymentId, AppDeployment.STATUS_RUNNING, null));
        when(assetsService.uploadBoundedMarker(anyString(), any(byte[].class), anyString(), anyString()))
                .thenReturn(new BoundedMarkerUploadResult(
                        path, 10, "etag-1", "null"));
        when(assetsService.reconcileBoundedMarker(eq(path), anyString()))
                .thenReturn(BoundedMarkerReconcileResult.unknown());

        var response = service.stage("goai-bounded-demo", TASK_ID, RUN_ID, MANIFEST_DIGEST);

        assertThat(response.success()).isFalse();
        assertThat(response.errorCode()).isEqualTo("ASSET_STATE_UNKNOWN");
        assertThat(response.ownershipVersionId()).isNull();
        ArgumentCaptor<RecordDeploymentStepRequest> stepCaptor =
                ArgumentCaptor.forClass(RecordDeploymentStepRequest.class);
        verify(deploymentService).recordStep(eq(deploymentId), stepCaptor.capture());
        assertThat(stepCaptor.getValue().status()).isEqualTo(AppDeploymentStep.STATUS_FAILED);
    }

    @Test
    void compensatesOwnedMarkerWhenDatabaseCommitOutcomeIsUnknown() {
        UUID deploymentId = UUID.randomUUID();
        String path = "__goai_e2e/" + RUN_ID + "/marker.json";
        when(deploymentService.create(any(CreateDeploymentRequest.class)))
                .thenReturn(deployment(deploymentId, AppDeployment.STATUS_RUNNING, null));
        when(assetsService.uploadBoundedMarker(anyString(), any(byte[].class), anyString(), anyString()))
                .thenThrow(new IllegalStateException("commit outcome unknown"));
        when(assetsService.reconcileBoundedMarker(eq(path), anyString()))
                .thenReturn(new BoundedMarkerReconcileResult(
                        BoundedMarkerReconcileStatus.COMPENSATED,
                        "etag-owned",
                        "version-owned"));

        var response = service.stage("goai-bounded-demo", TASK_ID, RUN_ID, MANIFEST_DIGEST);

        assertThat(response.success()).isFalse();
        assertThat(response.errorCode()).isEqualTo("ASSET_UPLOAD_FAILED_COMPENSATED");
        assertThat(response.artifactDigest()).matches("sha256:[a-f0-9]{64}");
        assertThat(response.ownershipEtag()).isEqualTo("etag-owned");
        assertThat(response.ownershipVersionId()).isEqualTo("version-owned");
        verify(deploymentService).complete(eq(deploymentId), eq(new CompleteDeploymentRequest(
                AppDeployment.STATUS_FAILED, null, "ASSET_UPLOAD_FAILED_COMPENSATED")));
    }

    @Test
    void reportsCompensationFailureWhenOwnedMarkerCannotBeDeleted() {
        UUID deploymentId = UUID.randomUUID();
        String path = "__goai_e2e/" + RUN_ID + "/marker.json";
        when(deploymentService.create(any(CreateDeploymentRequest.class)))
                .thenReturn(deployment(deploymentId, AppDeployment.STATUS_RUNNING, null));
        when(assetsService.uploadBoundedMarker(anyString(), any(byte[].class), anyString(), anyString()))
                .thenThrow(new IllegalStateException("commit failed"));
        when(assetsService.reconcileBoundedMarker(eq(path), anyString()))
                .thenReturn(new BoundedMarkerReconcileResult(
                        BoundedMarkerReconcileStatus.COMPENSATION_FAILED,
                        "etag-owned",
                        "version-owned"));

        var response = service.stage("goai-bounded-demo", TASK_ID, RUN_ID, MANIFEST_DIGEST);

        assertThat(response.success()).isFalse();
        assertThat(response.errorCode()).isEqualTo("ASSET_COMPENSATION_FAILED");
        assertThat(response.ownershipEtag()).isEqualTo("etag-owned");
        assertThat(response.ownershipVersionId()).isEqualTo("version-owned");
        verify(deploymentService).complete(eq(deploymentId), eq(new CompleteDeploymentRequest(
                AppDeployment.STATUS_FAILED, null, "ASSET_COMPENSATION_FAILED")));
    }

    @Test
    void doesNotClaimOrDeletePreExistingMarkerAfterConditionalPutFails() {
        UUID deploymentId = UUID.randomUUID();
        String path = "__goai_e2e/" + RUN_ID + "/marker.json";
        when(deploymentService.create(any(CreateDeploymentRequest.class)))
                .thenReturn(deployment(deploymentId, AppDeployment.STATUS_RUNNING, null));
        when(assetsService.uploadBoundedMarker(anyString(), any(byte[].class), anyString(), anyString()))
                .thenThrow(new IllegalStateException("conditional put rejected"));
        when(assetsService.reconcileBoundedMarker(eq(path), anyString()))
                .thenReturn(new BoundedMarkerReconcileResult(
                        BoundedMarkerReconcileStatus.OWNERSHIP_MISMATCH, null, null));

        var response = service.stage("goai-bounded-demo", TASK_ID, RUN_ID, MANIFEST_DIGEST);

        assertThat(response.success()).isFalse();
        assertThat(response.errorCode()).isEqualTo("ASSET_OWNERSHIP_CONFLICT");
        assertThat(response.artifactDigest()).isNull();
        verify(assetsService, org.mockito.Mockito.never())
                .deleteBoundedMarkerVersion(anyString(), anyString(), anyString());
    }

    @Test
    void removesMarkerWhenStepRecordingFails() {
        UUID deploymentId = UUID.randomUUID();
        String path = "__goai_e2e/" + RUN_ID + "/marker.json";
        when(deploymentService.create(any(CreateDeploymentRequest.class)))
                .thenReturn(deployment(deploymentId, AppDeployment.STATUS_RUNNING, null));
        when(assetsService.uploadBoundedMarker(anyString(), any(byte[].class), anyString(), anyString()))
                .thenReturn(new BoundedMarkerUploadResult(
                        path,
                        10,
                        "etag-1",
                        "version-1"));
        when(assetsService.deleteBoundedMarkerVersion(
                path, "version-1", "etag-1")).thenReturn(true);
        doThrow(new IllegalStateException("metadata unavailable"))
                .when(deploymentService)
                .recordStep(eq(deploymentId), any(RecordDeploymentStepRequest.class));

        var response = service.stage("goai-bounded-demo", TASK_ID, RUN_ID, MANIFEST_DIGEST);

        assertThat(response.success()).isFalse();
        assertThat(response.errorCode()).isEqualTo("STEP_RECORD_FAILED");
        assertThat(response.ownershipVersionId()).isEqualTo("version-1");
        verify(assetsService).deleteBoundedMarkerVersion(path, "version-1", "etag-1");
        verify(deploymentService).complete(
                eq(deploymentId),
                eq(new CompleteDeploymentRequest(AppDeployment.STATUS_FAILED, null, "STEP_RECORD_FAILED")));
    }

    @Test
    void reportsCompensationFailureWithOwnershipEvidence() {
        UUID deploymentId = UUID.randomUUID();
        String path = "__goai_e2e/" + RUN_ID + "/marker.json";
        when(deploymentService.create(any(CreateDeploymentRequest.class)))
                .thenReturn(deployment(deploymentId, AppDeployment.STATUS_RUNNING, null));
        when(assetsService.uploadBoundedMarker(anyString(), any(byte[].class), anyString(), anyString()))
                .thenReturn(new BoundedMarkerUploadResult(
                        path, 10, "etag-1", "version-1"));
        doThrow(new IllegalStateException("metadata unavailable"))
                .when(deploymentService)
                .recordStep(eq(deploymentId), any(RecordDeploymentStepRequest.class));
        when(assetsService.deleteBoundedMarkerVersion(
                path, "version-1", "etag-1")).thenReturn(false);

        var response = service.stage("goai-bounded-demo", TASK_ID, RUN_ID, MANIFEST_DIGEST);

        assertThat(response.success()).isFalse();
        assertThat(response.errorCode()).isEqualTo("ASSET_COMPENSATION_FAILED");
        assertThat(response.artifactDigest()).matches("sha256:[a-f0-9]{64}");
        assertThat(response.ownershipEtag()).isEqualTo("etag-1");
        assertThat(response.ownershipVersionId()).isEqualTo("version-1");
        verify(deploymentService).complete(
                eq(deploymentId),
                eq(new CompleteDeploymentRequest(
                        AppDeployment.STATUS_FAILED, null, "ASSET_COMPENSATION_FAILED")));
    }

    @Test
    void duplicateRunReturnsExistingDeploymentWithoutTouchingAssets() {
        UUID deploymentId = UUID.randomUUID();
        String path = "__goai_e2e/" + RUN_ID + "/marker.json";
        String artifactDigest =
                "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        when(deploymentService.create(any(CreateDeploymentRequest.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"));
        when(deploymentService.findBoundedByRunId(RUN_ID)).thenReturn(Optional.of(new DeploymentResponse(
                deploymentId,
                "goai_sandbox",
                "goai-bounded-demo",
                AppDeployment.STATUS_SUCCEEDED,
                null,
                Map.of(
                        "artifactDigest", artifactDigest,
                        "manifestDigest", MANIFEST_DIGEST,
                        "markerPath", path,
                        "profile", BoundedAssetDeploymentService.PROFILE,
                        "taskId", TASK_ID,
                        "transport", BoundedAssetDeploymentService.TRANSPORT
                ),
                null,
                "builder-agent",
                RUN_ID,
                Instant.now(),
                Instant.now(),
                Instant.now()
        )));

        var response = service.stage("goai-bounded-demo", TASK_ID, RUN_ID, MANIFEST_DIGEST);

        assertThat(response.success()).isFalse();
        assertThat(response.deploymentId()).isEqualTo(deploymentId);
        assertThat(response.errorCode()).isEqualTo("RUN_ID_ALREADY_USED");
        assertThat(response.artifactDigest()).isEqualTo(artifactDigest);
        ArgumentCaptor<CreateDeploymentRequest> createCaptor =
                ArgumentCaptor.forClass(CreateDeploymentRequest.class);
        verify(deploymentService).create(createCaptor.capture());
        assertThat(createCaptor.getValue().manifestSummary().get("artifactDigest"))
                .isNotEqualTo(artifactDigest);
        verifyNoInteractions(assetsService);
    }

    private DeploymentResponse deployment(UUID id, String status, String publicUrl) {
        Instant now = Instant.now();
        return new DeploymentResponse(
                id,
                "goai_sandbox",
                "goai-bounded-demo",
                status,
                publicUrl,
                Map.of(),
                null,
                "builder-agent",
                RUN_ID,
                now,
                now,
                AppDeployment.STATUS_RUNNING.equals(status) ? null : now
        );
    }
}
