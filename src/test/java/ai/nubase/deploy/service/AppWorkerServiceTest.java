package ai.nubase.deploy.service;

import ai.nubase.common.context.MultiTenancyContext;
import ai.nubase.deploy.dto.AppDeploymentDtos.AppWorkerDeleteResponse;
import ai.nubase.deploy.dto.AppDeploymentDtos.AppWorkerDetail;
import ai.nubase.deploy.dto.AppDeploymentDtos.AppWorkerSummary;
import ai.nubase.deploy.dto.AppDeploymentDtos.CompleteDeploymentRequest;
import ai.nubase.deploy.dto.AppDeploymentDtos.CreateDeploymentRequest;
import ai.nubase.deploy.dto.AppDeploymentDtos.DeploymentResponse;
import ai.nubase.deploy.dto.AppDeploymentDtos.RecordDeploymentStepRequest;
import ai.nubase.metadata.entity.AppDeployment;
import ai.nubase.metadata.repository.AppDeploymentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppWorkerServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private AppDeploymentRepository deploymentRepository;
    private AppDeploymentService deploymentService;
    private AppWorkerDeployer deployer;
    private AppWorkerService service;

    @BeforeEach
    void setUp() {
        MultiTenancyContext.setContext(MultiTenancyContext.ContextData.builder()
                .appCode("appabc")
                .serviceRole(true)
                .build());
        deploymentRepository = mock(AppDeploymentRepository.class);
        deploymentService = mock(AppDeploymentService.class);
        deployer = mock(AppWorkerDeployer.class);
        service = new AppWorkerService(deploymentRepository, deploymentService, deployer, objectMapper);
    }

    @AfterEach
    void tearDown() {
        MultiTenancyContext.clear();
    }

    @Test
    void listReturnsLatestDeploymentPerOwnedWorkerSortedByName() {
        when(deploymentRepository.findByProjectRefOrderByCreatedAtDesc(eq("appabc"), any(Pageable.class)))
                .thenReturn(List.of(
                        appWorkerDeployment("appabc", "v2", AppDeployment.STATUS_SUCCEEDED),
                        appWorkerDeployment("widget", "v1", AppDeployment.STATUS_SUCCEEDED),
                        appWorkerDeployment("appabc", "v1", AppDeployment.STATUS_SUCCEEDED),
                        nonWorkerDeployment()
                ));

        List<AppWorkerSummary> workers = service.list();

        assertThat(workers).extracting(AppWorkerSummary::workerName)
                .containsExactly("appabc", "widget");
        // newest deployment wins (list is newest-first, putIfAbsent keeps the first seen)
        assertThat(workers.get(0).version()).isEqualTo("v2");
        assertThat(workers.get(0).previewHost()).isEqualTo("appabc.ottermind.app");
        assertThat(workers.get(0).publicUrl()).isEqualTo("https://appabc.ottermind.app");
    }

    @Test
    void getEnrichesOwnedWorkerWithLiveProviderState() {
        when(deploymentRepository.findByProjectRefOrderByCreatedAtDesc(eq("appabc"), any(Pageable.class)))
                .thenReturn(List.of(appWorkerDeployment("appabc", "v1", AppDeployment.STATUS_SUCCEEDED)));
        when(deployer.get("appabc")).thenReturn(new AppWorkerInfo("appabc", true, Map.of("id", "appabc")));

        AppWorkerDetail detail = service.get("APPABC");

        assertThat(detail.existsOnProvider()).isTrue();
        assertThat(detail.worker().workerName()).isEqualTo("appabc");
        assertThat(detail.provider()).isEqualTo(Map.of("id", "appabc"));
        verify(deployer).get("appabc");
    }

    @Test
    void getRejectsWorkerNotOwnedByProject() {
        when(deploymentRepository.findByProjectRefOrderByCreatedAtDesc(eq("appabc"), any(Pageable.class)))
                .thenReturn(List.of(appWorkerDeployment("appabc", "v1", AppDeployment.STATUS_SUCCEEDED)));

        assertThatThrownBy(() -> service.get("someone-elses-worker"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not found");
        verify(deployer, never()).get(any());
    }

    @Test
    void deleteUndeploysOwnedWorkerAndRecordsAudit() {
        UUID auditId = UUID.randomUUID();
        when(deploymentRepository.findByProjectRefOrderByCreatedAtDesc(eq("appabc"), any(Pageable.class)))
                .thenReturn(List.of(appWorkerDeployment("appabc", "v1", AppDeployment.STATUS_SUCCEEDED)));
        when(deploymentService.create(any(CreateDeploymentRequest.class))).thenReturn(auditResponse(auditId));
        when(deploymentService.complete(eq(auditId), any(CompleteDeploymentRequest.class))).thenReturn(auditResponse(auditId));

        AppWorkerDeleteResponse response = service.delete("appabc");

        assertThat(response.deleted()).isTrue();
        assertThat(response.workerName()).isEqualTo("appabc");
        assertThat(response.auditDeploymentId()).isEqualTo(auditId);
        verify(deployer).delete("appabc");
        verify(deploymentService).recordStep(eq(auditId), any(RecordDeploymentStepRequest.class));
        verify(deploymentService).complete(eq(auditId), any(CompleteDeploymentRequest.class));
    }

    @Test
    void deleteRejectsWorkerNotOwnedByProject() {
        when(deploymentRepository.findByProjectRefOrderByCreatedAtDesc(eq("appabc"), any(Pageable.class)))
                .thenReturn(List.of(appWorkerDeployment("appabc", "v1", AppDeployment.STATUS_SUCCEEDED)));

        assertThatThrownBy(() -> service.delete("not-mine"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not found");
        verify(deployer, never()).delete(any());
        verify(deploymentService, never()).create(any());
    }

    private AppDeployment appWorkerDeployment(String workerName, String version, String status) {
        return AppDeployment.builder()
                .id(UUID.randomUUID())
                .projectRef("appabc")
                .appName("appabc")
                .status(status)
                .publicUrl("https://" + workerName + ".ottermind.app")
                .manifestSummary(json(Map.of(
                        "type", "app_worker",
                        "appCode", "appabc",
                        "version", version,
                        "workerName", workerName,
                        "previewHost", workerName + ".ottermind.app"
                )))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .completedAt(Instant.now())
                .build();
    }

    private AppDeployment nonWorkerDeployment() {
        return AppDeployment.builder()
                .id(UUID.randomUUID())
                .projectRef("appabc")
                .appName("appabc")
                .status(AppDeployment.STATUS_SUCCEEDED)
                .manifestSummary(json(Map.of("functions", List.of("hello"))))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    private DeploymentResponse auditResponse(UUID id) {
        return new DeploymentResponse(
                id,
                "appabc",
                "appabc",
                AppDeployment.STATUS_RUNNING,
                null,
                Map.of(),
                null,
                null,
                null,
                Instant.now(),
                Instant.now(),
                null
        );
    }

    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
