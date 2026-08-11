package ai.nubase.deploy.service;

import ai.nubase.assets.service.AssetsService;
import ai.nubase.common.context.MultiTenancyContext;
import ai.nubase.cron.service.ScheduledJobAdminService;
import ai.nubase.metadata.entity.AppDeployment;
import ai.nubase.metadata.entity.AppDeploymentStep;
import ai.nubase.metadata.repository.AppDeploymentRepository;
import ai.nubase.metadata.repository.AppDeploymentStepRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AppDeploymentRollbackServiceTest {

    private AppDeploymentRepository deploymentRepository;
    private AppDeploymentStepRepository stepRepository;
    private AssetsService assetsService;
    private ScheduledJobAdminService cronService;
    private AppDeploymentRollbackService service;

    @BeforeEach
    void setUp() {
        MultiTenancyContext.setContext(MultiTenancyContext.ContextData.builder()
                .appCode("app1")
                .serviceRole(true)
                .build());
        deploymentRepository = mock(AppDeploymentRepository.class);
        stepRepository = mock(AppDeploymentStepRepository.class);
        assetsService = mock(AssetsService.class);
        cronService = mock(ScheduledJobAdminService.class);
        service = new AppDeploymentRollbackService(
                deploymentRepository,
                stepRepository,
                assetsService,
                provider(cronService),
                new ObjectMapper()
        );
    }

    @AfterEach
    void tearDown() {
        MultiTenancyContext.clear();
    }

    @Test
    void rollbackReportsPartialCompensationWhenUnsafeStepsAreSkipped() {
        UUID id = UUID.randomUUID();
        AppDeployment deployment = deployment(id);
        AppDeploymentStep sql = step(deployment, 1, "sql_execute", "001_init", AppDeploymentStep.STATUS_SUCCEEDED);
        AppDeploymentStep asset = step(deployment, 2, "assets_upload", "index.html", AppDeploymentStep.STATUS_SUCCEEDED);
        AppDeploymentStep cron = step(deployment, 3, "cron_create", "nightly", AppDeploymentStep.STATUS_SUCCEEDED);
        when(deploymentRepository.findByProjectRefAndIdForUpdate("app1", id)).thenReturn(Optional.of(deployment));
        when(stepRepository.findByDeploymentIdOrderByStepOrderAsc(id)).thenReturn(List.of(sql, asset, cron));
        when(stepRepository.findFirstByDeploymentIdOrderByStepOrderDesc(id)).thenReturn(Optional.of(cron));
        when(stepRepository.save(any(AppDeploymentStep.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(deploymentRepository.save(any(AppDeployment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.rollback(id);

        assertThat(result.success()).isFalse();
        assertThat(result.status()).isEqualTo(AppDeployment.STATUS_PARTIALLY_ROLLED_BACK);
        assertThat(result.actions()).extracting("status")
                .containsExactly(
                        AppDeploymentStep.STATUS_SUCCEEDED,
                        AppDeploymentStep.STATUS_SKIPPED,
                        AppDeploymentStep.STATUS_SKIPPED);
        verify(cronService).deleteJob("nightly");
        verify(assetsService, never()).deleteBoundedMarkerVersion(any(), any(), any());
        verify(stepRepository, times(3)).save(any(AppDeploymentStep.class));
        assertThat(deployment.getStatus()).isEqualTo(AppDeployment.STATUS_PARTIALLY_ROLLED_BACK);
    }

    @Test
    void failedOriginalStepsAreSkipped() {
        UUID id = UUID.randomUUID();
        AppDeployment deployment = deployment(id);
        AppDeploymentStep asset = step(deployment, 1, "assets_upload", "index.html", AppDeploymentStep.STATUS_FAILED);
        when(deploymentRepository.findByProjectRefAndIdForUpdate("app1", id)).thenReturn(Optional.of(deployment));
        when(stepRepository.findByDeploymentIdOrderByStepOrderAsc(id)).thenReturn(List.of(asset));
        when(stepRepository.findFirstByDeploymentIdOrderByStepOrderDesc(id)).thenReturn(Optional.of(asset));
        when(stepRepository.save(any(AppDeploymentStep.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.rollback(id);

        assertThat(result.actions()).singleElement().satisfies(action -> {
            assertThat(action.status()).isEqualTo(AppDeploymentStep.STATUS_SKIPPED);
            assertThat(action.errorMessage()).isNull();
        });
        assertThat(result.success()).isFalse();
        assertThat(result.status()).isEqualTo(AppDeployment.STATUS_PARTIALLY_ROLLED_BACK);
        verifyNoInteractions(assetsService);
    }

    @Test
    void boundedDeploymentRejectsAssetOutsideRunNamespace() {
        UUID id = UUID.randomUUID();
        AppDeployment deployment = deployment(id);
        deployment.setRunId("run-bounded-demo-001");
        deployment.setManifestSummary("{\"profile\":\"bounded-asset-v1\",\"transport\":\"java-http-mcp\"}");
        AppDeploymentStep asset = step(
                deployment, 1, "assets_upload", "unrelated/index.html", AppDeploymentStep.STATUS_SUCCEEDED);
        when(deploymentRepository.findByProjectRefAndIdForUpdate("app1", id)).thenReturn(Optional.of(deployment));
        when(stepRepository.findByDeploymentIdOrderByStepOrderAsc(id)).thenReturn(List.of(asset));
        when(stepRepository.findFirstByDeploymentIdOrderByStepOrderDesc(id)).thenReturn(Optional.of(asset));
        when(stepRepository.save(any(AppDeploymentStep.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(deploymentRepository.save(any(AppDeployment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.rollback(id);

        assertThat(result.success()).isFalse();
        assertThat(result.status()).isEqualTo(AppDeployment.STATUS_PARTIALLY_ROLLED_BACK);
        assertThat(result.actions()).singleElement().satisfies(action ->
                assertThat(action.result().toString()).contains("bounded ownership proof"));
        verifyNoInteractions(assetsService);
    }

    @Test
    void boundedDeploymentDeletesExactRunMarker() {
        UUID id = UUID.randomUUID();
        AppDeployment deployment = deployment(id);
        deployment.setRunId("run-bounded-demo-001");
        deployment.setManifestSummary("{\"profile\":\"bounded-asset-v1\",\"transport\":\"java-http-mcp\"}");
        String markerPath = "__goai_e2e/run-bounded-demo-001/marker.json";
        AppDeploymentStep asset = step(
                deployment, 1, "assets_upload", markerPath, AppDeploymentStep.STATUS_SUCCEEDED);
        asset.setResult("{\"etag\":\"etag-1\",\"ownershipVersionId\":\"version-1\"}");
        when(deploymentRepository.findByProjectRefAndIdForUpdate("app1", id)).thenReturn(Optional.of(deployment));
        when(stepRepository.findByDeploymentIdOrderByStepOrderAsc(id)).thenReturn(List.of(asset));
        when(stepRepository.findFirstByDeploymentIdOrderByStepOrderDesc(id)).thenReturn(Optional.of(asset));
        when(stepRepository.save(any(AppDeploymentStep.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(deploymentRepository.save(any(AppDeployment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(assetsService.deleteBoundedMarkerVersion(
                markerPath, "version-1", "etag-1")).thenReturn(true);

        var result = service.rollback(id);

        assertThat(result.success()).isTrue();
        assertThat(result.status()).isEqualTo(AppDeployment.STATUS_ROLLED_BACK);
        assertThat(result.actions()).singleElement().satisfies(action ->
                assertThat(action.result().toString())
                        .contains("asset_version_deleted", "version-1"));
        verify(assetsService).deleteBoundedMarkerVersion(markerPath, "version-1", "etag-1");
    }

    @Test
    void boundedDeploymentDoesNotDeleteWhenAssetIdentityChanged() {
        UUID id = UUID.randomUUID();
        AppDeployment deployment = deployment(id);
        deployment.setRunId("run-bounded-demo-001");
        deployment.setManifestSummary("{\"profile\":\"bounded-asset-v1\",\"transport\":\"java-http-mcp\"}");
        String markerPath = "__goai_e2e/run-bounded-demo-001/marker.json";
        AppDeploymentStep asset = step(
                deployment, 1, "assets_upload", markerPath, AppDeploymentStep.STATUS_SUCCEEDED);
        asset.setResult("{\"etag\":\"etag-1\",\"ownershipVersionId\":\"version-1\"}");
        when(deploymentRepository.findByProjectRefAndIdForUpdate("app1", id)).thenReturn(Optional.of(deployment));
        when(stepRepository.findByDeploymentIdOrderByStepOrderAsc(id)).thenReturn(List.of(asset));
        when(stepRepository.findFirstByDeploymentIdOrderByStepOrderDesc(id)).thenReturn(Optional.of(asset));
        when(stepRepository.save(any(AppDeploymentStep.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(assetsService.deleteBoundedMarkerVersion(
                markerPath, "version-1", "etag-1")).thenReturn(false);

        var result = service.rollback(id);

        assertThat(result.success()).isFalse();
        assertThat(result.status()).isEqualTo(AppDeployment.STATUS_PARTIALLY_ROLLED_BACK);
        assertThat(result.actions()).singleElement().satisfies(action ->
                assertThat(action.result().toString()).contains("identity changed"));
    }

    @Test
    void boundedDeploymentFailsClosedWithoutOwnershipVersionId() {
        UUID id = UUID.randomUUID();
        AppDeployment deployment = deployment(id);
        deployment.setRunId("run-bounded-demo-001");
        deployment.setManifestSummary("{\"profile\":\"bounded-asset-v1\",\"transport\":\"java-http-mcp\"}");
        String markerPath = "__goai_e2e/run-bounded-demo-001/marker.json";
        AppDeploymentStep asset = step(
                deployment, 1, "assets_upload", markerPath, AppDeploymentStep.STATUS_SUCCEEDED);
        asset.setResult("{\"etag\":\"etag-1\"}");
        when(deploymentRepository.findByProjectRefAndIdForUpdate("app1", id)).thenReturn(Optional.of(deployment));
        when(stepRepository.findByDeploymentIdOrderByStepOrderAsc(id)).thenReturn(List.of(asset));
        when(stepRepository.findFirstByDeploymentIdOrderByStepOrderDesc(id)).thenReturn(Optional.of(asset));
        when(stepRepository.save(any(AppDeploymentStep.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.rollback(id);

        assertThat(result.success()).isFalse();
        assertThat(result.status()).isEqualTo(AppDeployment.STATUS_PARTIALLY_ROLLED_BACK);
        assertThat(result.actions()).singleElement().satisfies(action ->
                assertThat(action.result().toString()).contains("version ID was not recorded"));
        verify(assetsService, never()).deleteBoundedMarkerVersion(any(), any(), any());
    }

    @Test
    void deploymentWithoutJournalNeverClaimsRollbackSuccess() {
        UUID id = UUID.randomUUID();
        AppDeployment deployment = deployment(id);
        deployment.setRunId("run-bounded-demo-001");
        deployment.setManifestSummary("{\"profile\":\"bounded-asset-v1\",\"transport\":\"java-http-mcp\"}");
        when(deploymentRepository.findByProjectRefAndIdForUpdate("app1", id)).thenReturn(Optional.of(deployment));
        when(stepRepository.findByDeploymentIdOrderByStepOrderAsc(id)).thenReturn(List.of());

        var result = service.rollback(id);

        assertThat(result.success()).isFalse();
        assertThat(result.status()).isEqualTo(AppDeployment.STATUS_PARTIALLY_ROLLED_BACK);
        assertThat(result.actions()).isEmpty();
        verifyNoInteractions(assetsService, cronService);
    }

    @Test
    void repeatedSuccessfulRollbackIsIdempotent() {
        UUID id = UUID.randomUUID();
        AppDeployment deployment = deployment(id);
        deployment.setStatus(AppDeployment.STATUS_ROLLED_BACK);
        when(deploymentRepository.findByProjectRefAndIdForUpdate("app1", id)).thenReturn(Optional.of(deployment));

        var result = service.rollback(id);

        assertThat(result.success()).isTrue();
        assertThat(result.status()).isEqualTo(AppDeployment.STATUS_ROLLED_BACK);
        assertThat(result.actions()).isEmpty();
        verifyNoInteractions(stepRepository, assetsService, cronService);
    }

    private AppDeployment deployment(UUID id) {
        return AppDeployment.builder()
                .id(id)
                .projectRef("app1")
                .appName("notes")
                .status(AppDeployment.STATUS_FAILED)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    private AppDeploymentStep step(AppDeployment deployment, int order, String name, String target, String status) {
        return AppDeploymentStep.builder()
                .id(UUID.randomUUID())
                .deployment(deployment)
                .stepOrder(order)
                .stepName(name)
                .targetName(target)
                .status(status)
                .startedAt(Instant.now())
                .completedAt(Instant.now())
                .build();
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }

            @Override
            public T getObject() {
                return value;
            }
        };
    }
}
