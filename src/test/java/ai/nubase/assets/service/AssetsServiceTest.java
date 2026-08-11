package ai.nubase.assets.service;

import ai.nubase.assets.dto.UpdateAssetSettingsRequest;
import ai.nubase.assets.entity.AssetFile;
import ai.nubase.assets.entity.AssetSettings;
import ai.nubase.assets.repository.AssetFileRepository;
import ai.nubase.assets.repository.AssetSettingsRepository;
import ai.nubase.assets.service.AssetsExceptions.AssetsException;
import ai.nubase.assets.service.AssetsService.BoundedMarkerReconcileStatus;
import ai.nubase.common.config.AuthConfig;
import ai.nubase.common.context.MultiTenancyContext;
import ai.nubase.platform.storage.R2ClientProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AssetsServiceTest {

    private static final String MARKER_PATH = "__goai_e2e/run-bounded-demo-001/marker.json";

    private AssetFileRepository assetFileRepository;
    private AssetSettingsRepository assetSettingsRepository;
    private R2ClientProvider r2;
    private S3Client s3;
    private AssetsService service;

    @BeforeEach
    void setUp() {
        assetFileRepository = mock(AssetFileRepository.class);
        assetSettingsRepository = mock(AssetSettingsRepository.class);
        r2 = mock(R2ClientProvider.class);
        s3 = mock(S3Client.class);
        service = new AssetsService(
                assetFileRepository,
                assetSettingsRepository,
                r2,
                new AuthConfig()
        );
        ReflectionTestUtils.setField(service, "platformMaxFileSize", 1024L);
        ReflectionTestUtils.setField(service, "assetsBucket", "");
        ReflectionTestUtils.setField(service, "publicBaseUrl", "");
        ReflectionTestUtils.setField(service, "boundedPrivateStorageEnabled", true);
        when(r2.bucket()).thenReturn("storage-bucket");
        when(r2.publicUrl()).thenReturn(null);
        clearInvocations(r2);
        MultiTenancyContext.setContext(MultiTenancyContext.ContextData.builder()
                .appCode("demo")
                .build());
    }

    @AfterEach
    void tearDown() {
        MultiTenancyContext.clear();
    }

    @Test
    void genericUploadAndDeleteRejectReservedMarkerNamespace() {
        byte[] marker = "{}".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service.upload("/" + MARKER_PATH, marker,
                "application/json", "no-store", true))
                .isInstanceOfSatisfying(AssetsException.class,
                        exception -> assertThat(exception.code()).isEqualTo("INVALID_REQUEST"));
        assertThatThrownBy(() -> service.delete(MARKER_PATH))
                .isInstanceOfSatisfying(AssetsException.class,
                        exception -> assertThat(exception.code()).isEqualTo("INVALID_REQUEST"));

        verifyNoInteractions(assetFileRepository, assetSettingsRepository, s3);
    }

    @Test
    void boundedUploadRejectsExistingMarkerWithoutReplacingObject() {
        when(assetSettingsRepository.findById(AssetSettings.SINGLETON_ID)).thenReturn(Optional.empty());
        when(assetFileRepository.findByPathForUpdate(MARKER_PATH))
                .thenReturn(Optional.of(AssetFile.builder().path(MARKER_PATH).etag("etag-existing").build()));

        assertThatThrownBy(() -> service.uploadBoundedMarker(
                MARKER_PATH,
                "{}".getBytes(StandardCharsets.UTF_8),
                "application/json",
                "no-store"
        )).isInstanceOfSatisfying(AssetsException.class,
                exception -> assertThat(exception.code()).isEqualTo("ASSET_EXISTS"));

        verify(assetFileRepository, never()).saveAndFlush(any(AssetFile.class));
        verifyNoInteractions(s3);
    }

    @Test
    void boundedUploadRequiresExplicitPrivateNonPublicStorageBeforeDatabaseAccess() {
        byte[] marker = "{}".getBytes(StandardCharsets.UTF_8);

        ReflectionTestUtils.setField(service, "boundedPrivateStorageEnabled", false);
        assertPrivateStorageRequired(marker);

        ReflectionTestUtils.setField(service, "boundedPrivateStorageEnabled", true);
        ReflectionTestUtils.setField(service, "assetsBucket", "public-assets");
        assertPrivateStorageRequired(marker);

        ReflectionTestUtils.setField(service, "assetsBucket", "");
        ReflectionTestUtils.setField(service, "publicBaseUrl", "https://assets.example");
        assertPrivateStorageRequired(marker);

        ReflectionTestUtils.setField(service, "publicBaseUrl", "");
        when(r2.publicUrl()).thenReturn("https://storage.example");
        clearInvocations(r2);
        assertPrivateStorageRequired(marker);

        verifyNoInteractions(assetFileRepository, assetSettingsRepository, s3);
    }

    @Test
    void boundedUploadClaimsUniqueMetadataBeforeWritingObject() {
        byte[] marker = "{\"status\":\"staged\"}".getBytes(StandardCharsets.UTF_8);
        when(assetSettingsRepository.findById(AssetSettings.SINGLETON_ID)).thenReturn(Optional.empty());
        when(assetFileRepository.findByPathForUpdate(MARKER_PATH)).thenReturn(Optional.empty());
        when(assetFileRepository.saveAndFlush(any(AssetFile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(assetFileRepository.save(any(AssetFile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        enableVersioning();
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder()
                        .eTag("etag-1")
                        .versionId("version-1")
                        .build());

        var uploaded = service.uploadBoundedMarker(
                MARKER_PATH,
                marker,
                "application/json",
                "no-store"
        );

        assertThat(uploaded.path()).isEqualTo(MARKER_PATH);
        assertThat(uploaded.etag()).isEqualTo("etag-1");
        assertThat(uploaded.ownershipVersionId()).isEqualTo("version-1");
        ArgumentCaptor<AssetFile> fileCaptor = ArgumentCaptor.forClass(AssetFile.class);
        ArgumentCaptor<PutObjectRequest> putCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        InOrder order = inOrder(assetFileRepository, s3);
        order.verify(assetFileRepository).findByPathForUpdate(MARKER_PATH);
        order.verify(assetFileRepository).saveAndFlush(fileCaptor.capture());
        order.verify(s3).getBucketVersioning(any(GetBucketVersioningRequest.class));
        order.verify(s3).putObject(putCaptor.capture(), any(RequestBody.class));
        order.verify(assetFileRepository).save(any(AssetFile.class));
        String artifactDigest = digest(marker);
        assertThat(fileCaptor.getValue().getMetadata())
                .containsEntry("bounded-artifact-digest", artifactDigest);
        assertThat(putCaptor.getValue().ifNoneMatch()).isEqualTo("*");
        assertThat(putCaptor.getValue().metadata())
                .containsEntry("bounded-artifact-digest", artifactDigest);
        assertThat(fileCaptor.getValue().getMetadata())
                .containsEntry("bounded-version-id", "version-1");
    }

    @Test
    void boundedUploadFailsBeforePutWhenBucketVersioningIsNotEnabled() {
        when(assetSettingsRepository.findById(AssetSettings.SINGLETON_ID)).thenReturn(Optional.empty());
        when(assetFileRepository.findByPathForUpdate(MARKER_PATH)).thenReturn(Optional.empty());
        when(assetFileRepository.saveAndFlush(any(AssetFile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(r2.s3()).thenReturn(s3);
        when(s3.getBucketVersioning(any(GetBucketVersioningRequest.class)))
                .thenReturn(GetBucketVersioningResponse.builder()
                        .status(BucketVersioningStatus.SUSPENDED)
                        .build());

        assertThatThrownBy(() -> service.uploadBoundedMarker(
                MARKER_PATH,
                "{}".getBytes(StandardCharsets.UTF_8),
                "application/json",
                "no-store"
        )).isInstanceOfSatisfying(AssetsException.class,
                exception -> assertThat(exception.code()).isEqualTo("ASSET_VERSIONING_REQUIRED"));

        verify(s3, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        verify(assetFileRepository, never()).save(any(AssetFile.class));
    }

    @Test
    void boundedUploadNeverClaimsSuccessWithoutProviderVersionId() {
        when(assetSettingsRepository.findById(AssetSettings.SINGLETON_ID)).thenReturn(Optional.empty());
        when(assetFileRepository.findByPathForUpdate(MARKER_PATH)).thenReturn(Optional.empty());
        when(assetFileRepository.saveAndFlush(any(AssetFile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        enableVersioning();
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().eTag("etag-1").build());

        assertThatThrownBy(() -> service.uploadBoundedMarker(
                MARKER_PATH,
                "{}".getBytes(StandardCharsets.UTF_8),
                "application/json",
                "no-store"
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("Bounded marker version ID is unavailable");

        verify(assetFileRepository, never()).save(any(AssetFile.class));
    }

    @Test
    void boundedUploadMapsConcurrentUniqueConflictWithoutWritingObject() {
        when(assetSettingsRepository.findById(AssetSettings.SINGLETON_ID)).thenReturn(Optional.empty());
        when(assetFileRepository.findByPathForUpdate(MARKER_PATH)).thenReturn(Optional.empty());
        when(assetFileRepository.saveAndFlush(any(AssetFile.class)))
                .thenThrow(new DataIntegrityViolationException("database detail"));

        assertThatThrownBy(() -> service.uploadBoundedMarker(
                MARKER_PATH,
                "{}".getBytes(StandardCharsets.UTF_8),
                "application/json",
                "no-store"
        )).isInstanceOfSatisfying(AssetsException.class, exception -> {
            assertThat(exception.code()).isEqualTo("ASSET_EXISTS");
            assertThat(exception.getMessage()).doesNotContain("database detail");
        });

        verifyNoInteractions(s3);
    }

    @Test
    void versionDeleteRemovesOnlyTheRecordedMarkerVersion() {
        String artifactDigest = digest("owned".getBytes(StandardCharsets.UTF_8));
        AssetFile marker = AssetFile.builder()
                .path(MARKER_PATH)
                .etag("etag-1")
                .metadata(Map.of(
                        "bounded-artifact-digest", artifactDigest,
                        "bounded-version-id", "version-1"))
                .build();
        when(assetFileRepository.findByPathForUpdate(MARKER_PATH)).thenReturn(Optional.of(marker));
        when(r2.s3()).thenReturn(s3);
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder()
                        .eTag("etag-1")
                        .versionId("version-1")
                        .metadata(Map.of("bounded-artifact-digest", artifactDigest))
                        .build())
                .thenThrow(NoSuchKeyException.builder().statusCode(404).build());

        boolean deleted = service.deleteBoundedMarkerVersion(
                MARKER_PATH, "version-1", "etag-1");

        assertThat(deleted).isTrue();
        ArgumentCaptor<DeleteObjectRequest> request = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        InOrder order = inOrder(assetFileRepository, s3);
        order.verify(assetFileRepository).findByPathForUpdate(MARKER_PATH);
        order.verify(s3).headObject(any(HeadObjectRequest.class));
        order.verify(s3).deleteObject(request.capture());
        order.verify(s3).headObject(any(HeadObjectRequest.class));
        order.verify(assetFileRepository).delete(marker);
        assertThat(request.getValue().bucket()).isEqualTo("storage-bucket");
        assertThat(request.getValue().key()).isEqualTo("demo/__assets__/" + MARKER_PATH);
        assertThat(request.getValue().versionId()).isEqualTo("version-1");
        assertThat(request.getValue().ifMatch()).isNull();
    }

    @Test
    void versionDeleteDoesNotDeleteWhenReplacementIsLatestBeforeDelete() {
        String ownedDigest = digest("owned".getBytes(StandardCharsets.UTF_8));
        String replacementDigest = digest("replacement".getBytes(StandardCharsets.UTF_8));
        AssetFile marker = AssetFile.builder()
                .path(MARKER_PATH)
                .etag("etag-owned")
                .metadata(Map.of(
                        "bounded-artifact-digest", ownedDigest,
                        "bounded-version-id", "version-owned"))
                .build();
        when(assetFileRepository.findByPathForUpdate(MARKER_PATH)).thenReturn(Optional.of(marker));
        when(r2.s3()).thenReturn(s3);
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
                .eTag("etag-replacement")
                .versionId("version-replacement")
                .metadata(Map.of("bounded-artifact-digest", replacementDigest))
                .build());

        boolean deleted = service.deleteBoundedMarkerVersion(
                MARKER_PATH, "version-owned", "etag-owned");

        assertThat(deleted).isFalse();
        verify(s3, never()).deleteObject(any(DeleteObjectRequest.class));
        verify(assetFileRepository, never()).delete(any(AssetFile.class));
    }

    @Test
    void versionDeleteKeepsMetadataWhenReplacementAppearsAfterExactDelete() {
        String ownedDigest = digest("owned".getBytes(StandardCharsets.UTF_8));
        String replacementDigest = digest("replacement".getBytes(StandardCharsets.UTF_8));
        AssetFile marker = AssetFile.builder()
                .path(MARKER_PATH)
                .etag("etag-owned")
                .metadata(Map.of(
                        "bounded-artifact-digest", ownedDigest,
                        "bounded-version-id", "version-owned"))
                .build();
        when(assetFileRepository.findByPathForUpdate(MARKER_PATH)).thenReturn(Optional.of(marker));
        when(r2.s3()).thenReturn(s3);
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder()
                        .eTag("etag-owned")
                        .versionId("version-owned")
                        .metadata(Map.of("bounded-artifact-digest", ownedDigest))
                        .build())
                .thenReturn(HeadObjectResponse.builder()
                        .eTag("etag-replacement")
                        .versionId("version-replacement")
                        .metadata(Map.of("bounded-artifact-digest", replacementDigest))
                        .build());

        boolean deleted = service.deleteBoundedMarkerVersion(
                MARKER_PATH, "version-owned", "etag-owned");

        assertThat(deleted).isFalse();
        ArgumentCaptor<DeleteObjectRequest> deleteCaptor =
                ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3).deleteObject(deleteCaptor.capture());
        assertThat(deleteCaptor.getValue().versionId()).isEqualTo("version-owned");
        verify(assetFileRepository, never()).delete(any(AssetFile.class));
    }

    @Test
    void versionDeleteKeepsMetadataWhenPostDeleteHeadFails() {
        String artifactDigest = digest("owned".getBytes(StandardCharsets.UTF_8));
        AssetFile marker = AssetFile.builder()
                .path(MARKER_PATH)
                .etag("etag-owned")
                .metadata(Map.of(
                        "bounded-artifact-digest", artifactDigest,
                        "bounded-version-id", "version-owned"))
                .build();
        when(assetFileRepository.findByPathForUpdate(MARKER_PATH)).thenReturn(Optional.of(marker));
        when(r2.s3()).thenReturn(s3);
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder()
                        .eTag("etag-owned")
                        .versionId("version-owned")
                        .metadata(Map.of("bounded-artifact-digest", artifactDigest))
                        .build())
                .thenThrow(new IllegalStateException("head unavailable"));

        boolean deleted = service.deleteBoundedMarkerVersion(
                MARKER_PATH, "version-owned", "etag-owned");

        assertThat(deleted).isFalse();
        verify(s3).deleteObject(any(DeleteObjectRequest.class));
        verify(assetFileRepository, never()).delete(any(AssetFile.class));
    }

    @Test
    void reconcilesOwnedOrphanAfterPutSucceededButClientTimedOut() {
        byte[] marker = "{\"ownershipNonce\":\"attempt-1\"}".getBytes(StandardCharsets.UTF_8);
        String artifactDigest = digest(marker);
        when(assetFileRepository.findByPathForUpdate(MARKER_PATH)).thenReturn(Optional.empty());
        when(r2.s3()).thenReturn(s3);
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder()
                        .eTag("etag-owned")
                        .versionId("version-owned")
                        .metadata(Map.of("bounded-artifact-digest", artifactDigest))
                        .build())
                .thenThrow(NoSuchKeyException.builder().statusCode(404).build());

        var result = service.reconcileBoundedMarker(MARKER_PATH, artifactDigest);

        assertThat(result.status()).isEqualTo(BoundedMarkerReconcileStatus.COMPENSATED);
        assertThat(result.ownershipEtag()).isEqualTo("etag-owned");
        assertThat(result.ownershipVersionId()).isEqualTo("version-owned");
        ArgumentCaptor<HeadObjectRequest> headCaptor = ArgumentCaptor.forClass(HeadObjectRequest.class);
        ArgumentCaptor<DeleteObjectRequest> deleteCaptor =
                ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3, times(2)).headObject(headCaptor.capture());
        verify(s3).deleteObject(deleteCaptor.capture());
        assertThat(headCaptor.getValue().key()).isEqualTo("demo/__assets__/" + MARKER_PATH);
        assertThat(deleteCaptor.getValue().key()).isEqualTo("demo/__assets__/" + MARKER_PATH);
        assertThat(deleteCaptor.getValue().versionId()).isEqualTo("version-owned");
        assertThat(deleteCaptor.getValue().ifMatch()).isNull();
        verify(assetFileRepository, never()).delete(any(AssetFile.class));
    }

    @Test
    void reconcilesOwnedObjectAndMetadataAfterDatabaseSaveFailure() {
        byte[] marker = "{\"ownershipNonce\":\"attempt-2\"}".getBytes(StandardCharsets.UTF_8);
        String artifactDigest = digest(marker);
        AssetFile recoveredMetadata = AssetFile.builder()
                .path(MARKER_PATH)
                .etag("etag-owned")
                .metadata(Map.of(
                        "bounded-artifact-digest", artifactDigest,
                        "bounded-version-id", "version-owned"))
                .build();
        when(assetSettingsRepository.findById(AssetSettings.SINGLETON_ID)).thenReturn(Optional.empty());
        when(assetFileRepository.findByPathForUpdate(MARKER_PATH))
                .thenReturn(Optional.empty(), Optional.of(recoveredMetadata));
        when(assetFileRepository.saveAndFlush(any(AssetFile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(assetFileRepository.save(any(AssetFile.class)))
                .thenThrow(new IllegalStateException("database save failed"));
        enableVersioning();
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder()
                        .eTag("etag-owned")
                        .versionId("version-owned")
                        .build());
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder()
                        .eTag("etag-owned")
                        .versionId("version-owned")
                        .metadata(Map.of("bounded-artifact-digest", artifactDigest))
                        .build())
                .thenThrow(NoSuchKeyException.builder().statusCode(404).build());

        assertThatThrownBy(() -> service.uploadBoundedMarker(
                MARKER_PATH, marker, "application/json", "no-store"))
                .isInstanceOf(IllegalStateException.class);
        var result = service.reconcileBoundedMarker(MARKER_PATH, artifactDigest);

        assertThat(result.status()).isEqualTo(BoundedMarkerReconcileStatus.COMPENSATED);
        ArgumentCaptor<DeleteObjectRequest> deleteCaptor =
                ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3).deleteObject(deleteCaptor.capture());
        assertThat(deleteCaptor.getValue().key()).isEqualTo("demo/__assets__/" + MARKER_PATH);
        assertThat(deleteCaptor.getValue().versionId()).isEqualTo("version-owned");
        verify(assetFileRepository).delete(recoveredMetadata);
    }

    @Test
    void reconciliationDoesNotDeleteReplacementThatIsLatestBeforeDelete() {
        String attemptedDigest = digest("attempted".getBytes(StandardCharsets.UTF_8));
        String existingDigest = digest("existing".getBytes(StandardCharsets.UTF_8));
        when(assetFileRepository.findByPathForUpdate(MARKER_PATH)).thenReturn(Optional.empty());
        when(r2.s3()).thenReturn(s3);
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
                .eTag("etag-existing")
                .versionId("version-replacement")
                .metadata(Map.of("bounded-artifact-digest", existingDigest))
                .build());

        var result = service.reconcileBoundedMarker(MARKER_PATH, attemptedDigest);

        assertThat(result.status()).isEqualTo(BoundedMarkerReconcileStatus.OWNERSHIP_MISMATCH);
        assertThat(result.ownershipEtag()).isNull();
        assertThat(result.ownershipVersionId()).isNull();
        verify(s3, never()).deleteObject(any(DeleteObjectRequest.class));
        verify(assetFileRepository, never()).delete(any(AssetFile.class));
    }

    @Test
    void reconciliationReportsUnknownWhenObjectStateCannotBeRead() {
        String artifactDigest = digest("attempted".getBytes(StandardCharsets.UTF_8));
        when(assetFileRepository.findByPathForUpdate(MARKER_PATH)).thenReturn(Optional.empty());
        when(r2.s3()).thenReturn(s3);
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenThrow(new IllegalStateException("storage unavailable"));

        var result = service.reconcileBoundedMarker(MARKER_PATH, artifactDigest);

        assertThat(result.status()).isEqualTo(BoundedMarkerReconcileStatus.UNKNOWN);
        assertThat(result.ownershipEtag()).isNull();
        verify(s3, never()).deleteObject(any(DeleteObjectRequest.class));
        verify(assetFileRepository, never()).delete(any(AssetFile.class));
    }

    @Test
    void firstHeadNotFoundStaysUnknownAndLaterReconcileDeletesOwnedVersion() {
        String artifactDigest = digest("attempted".getBytes(StandardCharsets.UTF_8));
        AssetFile pendingMetadata = AssetFile.builder()
                .path(MARKER_PATH)
                .metadata(Map.of("bounded-artifact-digest", artifactDigest))
                .build();
        when(assetFileRepository.findByPathForUpdate(MARKER_PATH))
                .thenReturn(Optional.of(pendingMetadata));
        when(r2.s3()).thenReturn(s3);
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().statusCode(404).build())
                .thenReturn(HeadObjectResponse.builder()
                        .eTag("etag-owned")
                        .versionId("version-owned")
                        .metadata(Map.of("bounded-artifact-digest", artifactDigest))
                        .build())
                .thenThrow(NoSuchKeyException.builder().statusCode(404).build());

        var pending = service.reconcileBoundedMarker(MARKER_PATH, artifactDigest);

        assertThat(pending.status()).isEqualTo(BoundedMarkerReconcileStatus.UNKNOWN);
        verify(s3, never()).deleteObject(any(DeleteObjectRequest.class));
        verify(assetFileRepository, never()).delete(any(AssetFile.class));

        var reconciled = service.reconcileBoundedMarker(MARKER_PATH, artifactDigest);

        assertThat(reconciled.status()).isEqualTo(BoundedMarkerReconcileStatus.COMPENSATED);
        assertThat(reconciled.ownershipVersionId()).isEqualTo("version-owned");
        ArgumentCaptor<DeleteObjectRequest> deleteCaptor =
                ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3).deleteObject(deleteCaptor.capture());
        assertThat(deleteCaptor.getValue().versionId()).isEqualTo("version-owned");
        verify(assetFileRepository).delete(pendingMetadata);
    }

    @Test
    void reconciliationKeepsMetadataWhenReplacementAppearsAfterExactDelete() {
        String artifactDigest = digest("owned".getBytes(StandardCharsets.UTF_8));
        String replacementDigest = digest("replacement".getBytes(StandardCharsets.UTF_8));
        AssetFile pendingMetadata = AssetFile.builder()
                .path(MARKER_PATH)
                .etag("etag-owned")
                .metadata(Map.of(
                        "bounded-artifact-digest", artifactDigest,
                        "bounded-version-id", "version-owned"))
                .build();
        when(assetFileRepository.findByPathForUpdate(MARKER_PATH))
                .thenReturn(Optional.of(pendingMetadata));
        when(r2.s3()).thenReturn(s3);
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder()
                        .eTag("etag-owned")
                        .versionId("version-owned")
                        .metadata(Map.of("bounded-artifact-digest", artifactDigest))
                        .build())
                .thenReturn(HeadObjectResponse.builder()
                        .eTag("etag-replacement")
                        .versionId("version-replacement")
                        .metadata(Map.of("bounded-artifact-digest", replacementDigest))
                        .build());

        var result = service.reconcileBoundedMarker(MARKER_PATH, artifactDigest);

        assertThat(result.status()).isEqualTo(BoundedMarkerReconcileStatus.COMPENSATION_FAILED);
        assertThat(result.ownershipVersionId()).isEqualTo("version-owned");
        ArgumentCaptor<DeleteObjectRequest> deleteCaptor =
                ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3).deleteObject(deleteCaptor.capture());
        assertThat(deleteCaptor.getValue().versionId()).isEqualTo("version-owned");
        verify(assetFileRepository, never()).delete(any(AssetFile.class));
    }

    @Test
    void versionDeleteDoesNothingWhenMetadataOwnershipDoesNotMatch() {
        AssetFile marker = AssetFile.builder()
                .path(MARKER_PATH)
                .etag("etag-current")
                .metadata(Map.of("bounded-version-id", "version-current"))
                .build();
        when(assetFileRepository.findByPathForUpdate(MARKER_PATH)).thenReturn(Optional.of(marker));

        boolean wrongEtag = service.deleteBoundedMarkerVersion(
                MARKER_PATH, "version-current", "etag-old");
        boolean wrongVersion = service.deleteBoundedMarkerVersion(
                MARKER_PATH, "version-old", "etag-current");

        assertThat(wrongEtag).isFalse();
        assertThat(wrongVersion).isFalse();
        verify(assetFileRepository, never()).delete(any(AssetFile.class));
        verifyNoInteractions(s3);
    }

    @Test
    void versionDeleteDoesNothingWhenMetadataOrOwnershipEvidenceIsMissing() {
        AssetFile markerWithoutEvidence = AssetFile.builder().path(MARKER_PATH).build();
        when(assetFileRepository.findByPathForUpdate(MARKER_PATH))
                .thenReturn(Optional.empty(), Optional.of(markerWithoutEvidence));

        boolean missingMetadata = service.deleteBoundedMarkerVersion(
                MARKER_PATH, "version-1", "etag-1");
        boolean missingMetadataEvidence = service.deleteBoundedMarkerVersion(
                MARKER_PATH, "version-1", "etag-1");
        boolean missingExpectedVersion = service.deleteBoundedMarkerVersion(
                MARKER_PATH, " ", "etag-1");
        boolean missingExpectedEtag = service.deleteBoundedMarkerVersion(
                MARKER_PATH, "version-1", " ");

        assertThat(missingMetadata).isFalse();
        assertThat(missingMetadataEvidence).isFalse();
        assertThat(missingExpectedVersion).isFalse();
        assertThat(missingExpectedEtag).isFalse();

        verify(assetFileRepository, times(2)).findByPathForUpdate(MARKER_PATH);
        verify(assetFileRepository, never()).delete(any(AssetFile.class));
        verifyNoInteractions(s3);
    }

    @Test
    void reservedPrefixListReturnsEmptyWithoutReadingMetadata() {
        assertThat(service.list("/__goai_e2e/", null, null, null)).isEmpty();

        verifyNoInteractions(assetFileRepository, assetSettingsRepository, r2, s3);
    }

    @Test
    void genericListFiltersReservedMarkersAndKeepsRegularAssets() {
        AssetFile regular = AssetFile.builder()
                .path("img/logo.png")
                .contentType("image/png")
                .sizeBytes(10)
                .etag("etag-logo")
                .build();
        AssetFile reserved = AssetFile.builder()
                .path(MARKER_PATH)
                .contentType("application/json")
                .sizeBytes(20)
                .etag("etag-marker")
                .metadata(Map.of("bounded-version-id", "version-secret"))
                .build();
        when(assetSettingsRepository.findById(AssetSettings.SINGLETON_ID)).thenReturn(Optional.empty());
        when(assetFileRepository.findAllByOrderByPathAsc()).thenReturn(java.util.List.of(reserved, regular));

        var result = service.list(null, null, null, null);

        assertThat(result).singleElement().satisfies(file -> {
            assertThat(file.path()).isEqualTo("img/logo.png");
            assertThat(file.etag()).isEqualTo("etag-logo");
            assertThat(file.publicUrl())
                    .isEqualTo("http://demo.localhost:9999/assets/v1/img/logo.png");
        });
        assertThat(result.toString()).doesNotContain("__goai_e2e", "version-secret");
    }

    @Test
    void serviceRoleListKeepsReservedMarkersForVerifier() {
        AssetFile reserved = AssetFile.builder()
                .path(MARKER_PATH)
                .contentType("application/json")
                .sizeBytes(20)
                .etag("etag-marker")
                .build();
        MultiTenancyContext.setContext(MultiTenancyContext.ContextData.builder()
                .appCode("demo")
                .serviceRole(true)
                .build());
        when(assetSettingsRepository.findById(AssetSettings.SINGLETON_ID)).thenReturn(Optional.empty());
        when(assetFileRepository.findByPathPrefix("__goai_e2e"))
                .thenReturn(java.util.List.of(reserved));

        var result = service.list("/__goai_e2e/", null, null, null);

        assertThat(result).singleElement().satisfies(file -> {
            assertThat(file.path()).isEqualTo(MARKER_PATH);
            assertThat(file.etag()).isEqualTo("etag-marker");
            assertThat(file.publicUrl()).isNull();
        });
    }

    @Test
    void genericGetPublicGetAndOpenStreamHideReservedMarkersWithoutIo() {
        AssetFile reserved = AssetFile.builder().path(MARKER_PATH).build();

        assertThatThrownBy(() -> service.getFileOrThrow(MARKER_PATH))
                .isInstanceOfSatisfying(AssetsException.class,
                        exception -> assertThat(exception.code()).isEqualTo("ASSET_NOT_FOUND"));
        assertThatThrownBy(() -> service.getPublicFileOrThrow(MARKER_PATH))
                .isInstanceOfSatisfying(AssetsException.class,
                        exception -> assertThat(exception.code()).isEqualTo("ASSET_NOT_FOUND"));
        assertThatThrownBy(() -> service.openStream(reserved))
                .isInstanceOfSatisfying(AssetsException.class,
                        exception -> assertThat(exception.code()).isEqualTo("ASSET_NOT_FOUND"));

        verifyNoInteractions(assetFileRepository, assetSettingsRepository, r2, s3);
    }

    @Test
    void genericGetStillReturnsRegularAsset() {
        AssetFile regular = AssetFile.builder().path("img/logo.png").build();
        when(assetFileRepository.findByPath("img/logo.png")).thenReturn(Optional.of(regular));

        assertThat(service.getFileOrThrow("img/logo.png")).isSameAs(regular);
        assertThat(service.getPublicFileOrThrow("img/logo.png")).isSameAs(regular);
    }

    @Test
    void spaFallbackCannotExposeReservedMarker() {
        AssetSettings settings = AssetSettings.builder()
                .id(AssetSettings.SINGLETON_ID)
                .defaultCacheControl("no-cache")
                .spaFallbackPath(MARKER_PATH)
                .build();
        when(assetFileRepository.findByPath("missing.html")).thenReturn(Optional.empty());
        when(assetSettingsRepository.findById(AssetSettings.SINGLETON_ID))
                .thenReturn(Optional.of(settings));

        assertThatThrownBy(() -> service.getPublicFileOrThrow("missing.html"))
                .isInstanceOfSatisfying(AssetsException.class,
                        exception -> assertThat(exception.code()).isEqualTo("ASSET_NOT_FOUND"));

        verify(assetFileRepository, never()).findByPath(MARKER_PATH);
        verifyNoInteractions(r2, s3);
    }

    @Test
    void settingsRejectReservedMarkerAsSpaFallback() {
        AssetSettings settings = AssetSettings.builder()
                .id(AssetSettings.SINGLETON_ID)
                .defaultCacheControl("no-cache")
                .build();
        UpdateAssetSettingsRequest request = new UpdateAssetSettingsRequest();
        request.setSpaFallbackPath(MARKER_PATH);
        when(assetSettingsRepository.findById(AssetSettings.SINGLETON_ID))
                .thenReturn(Optional.of(settings));

        assertThatThrownBy(() -> service.updateSettings(request))
                .isInstanceOfSatisfying(AssetsException.class,
                        exception -> assertThat(exception.code()).isEqualTo("INVALID_REQUEST"));

        verify(assetSettingsRepository, never()).save(any(AssetSettings.class));
        verifyNoInteractions(assetFileRepository, r2, s3);
    }

    @Test
    void dedicatedMarkerOperationsRejectPathsOutsideExactMarkerShape() {
        byte[] marker = "{}".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service.uploadBoundedMarker(
                "img/marker.json", marker, "application/json", "no-store"))
                .isInstanceOf(AssetsException.class);
        assertThatThrownBy(() -> service.uploadBoundedMarker(
                "__goai_e2e/run-Bounded-demo/marker.json", marker, "application/json", "no-store"))
                .isInstanceOf(AssetsException.class);
        assertThatThrownBy(() -> service.uploadBoundedMarker(
                "__goai_e2e/run-bounded.demo/marker.json", marker, "application/json", "no-store"))
                .isInstanceOf(AssetsException.class);
        assertThatThrownBy(() -> service.uploadBoundedMarker(
                "__goai_e2e/bounded-demo/marker.json", marker, "application/json", "no-store"))
                .isInstanceOf(AssetsException.class);
        assertThatThrownBy(() -> service.deleteBoundedMarkerVersion(
                "__goai_e2e/run-bounded-demo-001/other.json", "version-1", "etag-1"))
                .isInstanceOf(AssetsException.class);

        verifyNoInteractions(assetFileRepository, assetSettingsRepository, s3);
    }

    private String digest(byte[] value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void assertPrivateStorageRequired(byte[] marker) {
        assertThatThrownBy(() -> service.uploadBoundedMarker(
                MARKER_PATH, marker, "application/json", "no-store"))
                .isInstanceOfSatisfying(AssetsException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo("ASSET_BOUNDED_PRIVATE_STORAGE_REQUIRED"));
    }

    private void enableVersioning() {
        when(r2.s3()).thenReturn(s3);
        when(s3.getBucketVersioning(any(GetBucketVersioningRequest.class)))
                .thenReturn(GetBucketVersioningResponse.builder()
                        .status(BucketVersioningStatus.ENABLED)
                        .build());
    }
}
