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
    void rollbackDeletesAssetsAndCronAndSkipsUnsafeSteps() {
        UUID id = UUID.randomUUID();
        AppDeployment deployment = deployment(id);
        AppDeploymentStep sql = step(deployment, 1, "sql_execute", "001_init", AppDeploymentStep.STATUS_SUCCEEDED);
        AppDeploymentStep asset = step(deployment, 2, "assets_upload", "index.html", AppDeploymentStep.STATUS_SUCCEEDED);
        AppDeploymentStep cron = step(deployment, 3, "cron_create", "nightly", AppDeploymentStep.STATUS_SUCCEEDED);
        when(deploymentRepository.findByProjectRefAndId("app1", id)).thenReturn(Optional.of(deployment));
        when(deploymentRepository.findById(id)).thenReturn(Optional.of(deployment));
        when(deploymentRepository.getReferenceById(id)).thenReturn(deployment);
        when(stepRepository.findByDeploymentIdOrderByStepOrderAsc(id)).thenReturn(List.of(sql, asset, cron));
        when(stepRepository.findFirstByDeploymentIdOrderByStepOrderDesc(id)).thenReturn(Optional.of(cron));
        when(stepRepository.save(any(AppDeploymentStep.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(deploymentRepository.save(any(AppDeployment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.rollback(id);

        assertThat(result.success()).isTrue();
        assertThat(result.status()).isEqualTo(AppDeployment.STATUS_ROLLED_BACK);
        assertThat(result.actions()).extracting("status")
                .containsExactly(AppDeploymentStep.STATUS_SUCCEEDED, AppDeploymentStep.STATUS_SUCCEEDED, AppDeploymentStep.STATUS_SKIPPED);
        verify(cronService).deleteJob("nightly");
        verify(assetsService).delete("index.html");
        verify(stepRepository, times(3)).save(any(AppDeploymentStep.class));
        assertThat(deployment.getStatus()).isEqualTo(AppDeployment.STATUS_ROLLED_BACK);
    }

    @Test
    void failedOriginalStepsAreSkipped() {
        UUID id = UUID.randomUUID();
        AppDeployment deployment = deployment(id);
        AppDeploymentStep asset = step(deployment, 1, "assets_upload", "index.html", AppDeploymentStep.STATUS_FAILED);
        when(deploymentRepository.findByProjectRefAndId("app1", id)).thenReturn(Optional.of(deployment));
        when(deploymentRepository.findById(id)).thenReturn(Optional.of(deployment));
        when(deploymentRepository.getReferenceById(id)).thenReturn(deployment);
        when(stepRepository.findByDeploymentIdOrderByStepOrderAsc(id)).thenReturn(List.of(asset));
        when(stepRepository.findFirstByDeploymentIdOrderByStepOrderDesc(id)).thenReturn(Optional.of(asset));
        when(stepRepository.save(any(AppDeploymentStep.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.rollback(id);

        assertThat(result.success()).isTrue();
        assertThat(result.actions()).singleElement().satisfies(action -> {
            assertThat(action.status()).isEqualTo(AppDeploymentStep.STATUS_SKIPPED);
            assertThat(action.errorMessage()).isNull();
        });
        verifyNoInteractions(assetsService);
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
