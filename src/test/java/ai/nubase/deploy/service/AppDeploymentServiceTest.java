package ai.nubase.deploy.service;

import ai.nubase.common.context.MultiTenancyContext;
import ai.nubase.deploy.dto.AppDeploymentDtos.CompleteDeploymentRequest;
import ai.nubase.deploy.dto.AppDeploymentDtos.CreateDeploymentRequest;
import ai.nubase.deploy.dto.AppDeploymentDtos.RecordDeploymentStepRequest;
import ai.nubase.metadata.entity.AppDeployment;
import ai.nubase.metadata.entity.AppDeploymentStep;
import ai.nubase.metadata.repository.AppDeploymentRepository;
import ai.nubase.metadata.repository.AppDeploymentStepRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AppDeploymentServiceTest {

    private AppDeploymentRepository deploymentRepository;
    private AppDeploymentStepRepository stepRepository;
    private AppDeploymentService service;

    @BeforeEach
    void setUp() {
        MultiTenancyContext.setContext(MultiTenancyContext.ContextData.builder()
                .appCode("app1")
                .serviceRole(true)
                .build());
        deploymentRepository = mock(AppDeploymentRepository.class);
        stepRepository = mock(AppDeploymentStepRepository.class);
        service = new AppDeploymentService(deploymentRepository, stepRepository, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        MultiTenancyContext.clear();
    }

    @Test
    void createsDeploymentForCurrentProject() {
        when(deploymentRepository.save(any(AppDeployment.class))).thenAnswer(invocation -> {
            AppDeployment deployment = invocation.getArgument(0);
            deployment.setId(UUID.randomUUID());
            deployment.setCreatedAt(Instant.now());
            deployment.setUpdatedAt(Instant.now());
            return deployment;
        });

        var response = service.create(new CreateDeploymentRequest(
                "notes",
                Map.of("hasAssets", true),
                "codex",
                "run-1"
        ));

        assertThat(response.projectRef()).isEqualTo("app1");
        assertThat(response.appName()).isEqualTo("notes");
        assertThat(response.status()).isEqualTo(AppDeployment.STATUS_RUNNING);
        assertThat(response.manifestSummary()).isEqualTo(Map.of("hasAssets", true));
    }

    @Test
    void recordsStepAndCompletesDeployment() {
        UUID id = UUID.randomUUID();
        AppDeployment deployment = AppDeployment.builder()
                .id(id)
                .projectRef("app1")
                .appName("notes")
                .status(AppDeployment.STATUS_RUNNING)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        when(deploymentRepository.findByProjectRefAndId("app1", id)).thenReturn(Optional.of(deployment));
        when(stepRepository.save(any(AppDeploymentStep.class))).thenAnswer(invocation -> {
            AppDeploymentStep step = invocation.getArgument(0);
            step.setId(UUID.randomUUID());
            step.setStartedAt(Instant.now());
            step.setCompletedAt(Instant.now());
            return step;
        });
        when(deploymentRepository.save(any(AppDeployment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var step = service.recordStep(id, new RecordDeploymentStepRequest(
                1,
                "assets_upload",
                "index.html",
                AppDeploymentStep.STATUS_SUCCEEDED,
                Map.of("publicUrl", "https://app.example"),
                null
        ));
        var completed = service.complete(id, new CompleteDeploymentRequest(
                AppDeployment.STATUS_SUCCEEDED,
                "https://app.example",
                null
        ));

        assertThat(step.stepName()).isEqualTo("assets_upload");
        assertThat(step.result()).isEqualTo(Map.of("publicUrl", "https://app.example"));
        assertThat(completed.status()).isEqualTo(AppDeployment.STATUS_SUCCEEDED);
        assertThat(completed.publicUrl()).isEqualTo("https://app.example");
        assertThat(completed.completedAt()).isNotNull();
    }

    @Test
    void listsAndReadsProjectDeployments() {
        UUID id = UUID.randomUUID();
        AppDeployment deployment = AppDeployment.builder()
                .id(id)
                .projectRef("app1")
                .appName("notes")
                .status(AppDeployment.STATUS_SUCCEEDED)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        when(deploymentRepository.findByProjectRefOrderByCreatedAtDesc(eq("app1"), any(Pageable.class)))
                .thenReturn(List.of(deployment));
        when(deploymentRepository.findByProjectRefAndId("app1", id)).thenReturn(Optional.of(deployment));
        when(stepRepository.findByDeploymentIdOrderByStepOrderAsc(id)).thenReturn(List.of());

        assertThat(service.list(10)).hasSize(1);
        assertThat(service.get(id).deployment().id()).isEqualTo(id);
    }
}
