package ai.nubase.deploy.service;

import ai.nubase.common.context.MultiTenancyContext;
import ai.nubase.deploy.dto.AppDeploymentDtos.AppWorkerDeployMetadata;
import ai.nubase.deploy.dto.AppDeploymentDtos.CompleteDeploymentRequest;
import ai.nubase.deploy.dto.AppDeploymentDtos.CreateDeploymentRequest;
import ai.nubase.deploy.dto.AppDeploymentDtos.DeploymentResponse;
import ai.nubase.deploy.dto.AppDeploymentDtos.RecordDeploymentStepRequest;
import ai.nubase.metadata.entity.AppDeployment;
import ai.nubase.metadata.entity.AppDeploymentStep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppWorkerDeployServiceTest {

    private AppDeploymentService deploymentService;
    private AppWorkerDeployer deployer;
    private AppWorkerDeployService service;

    @BeforeEach
    void setUp() {
        MultiTenancyContext.setContext(MultiTenancyContext.ContextData.builder()
                .appCode("appabc")
                .serviceRole(true)
                .build());
        deploymentService = mock(AppDeploymentService.class);
        deployer = mock(AppWorkerDeployer.class);
        service = new AppWorkerDeployService(deploymentService, deployer);
    }

    @AfterEach
    void tearDown() {
        MultiTenancyContext.clear();
    }

    @Test
    void deploysAppWorkerAndRecordsDeploymentSteps() {
        UUID deploymentId = UUID.randomUUID();
        when(deploymentService.create(any(CreateDeploymentRequest.class))).thenReturn(new DeploymentResponse(
                deploymentId,
                "appabc",
                "appabc",
                AppDeployment.STATUS_RUNNING,
                null,
                Map.of(),
                null,
                null,
                "v1",
                Instant.now(),
                Instant.now(),
                null
        ));
        when(deployer.deploy(any(AppWorkerDeploymentRequest.class))).thenReturn(new AppWorkerDeploymentResult(
                "cloudflare",
                "appabc",
                "https://appabc.ottermind.app",
                "deployed",
                "asset-hash",
                1,
                Instant.parse("2026-06-17T00:00:00Z")
        ));

        var response = service.deploy(metadata(), List.of(serverFile()), List.of(assetFile()));

        assertThat(response.status()).isEqualTo("deployed");
        assertThat(response.previewUrl()).isEqualTo("https://appabc.ottermind.app");
        assertThat(response.assetManifestHash()).isEqualTo("asset-hash");

        ArgumentCaptor<AppWorkerDeploymentRequest> request = ArgumentCaptor.forClass(AppWorkerDeploymentRequest.class);
        verify(deployer).deploy(request.capture());
        assertThat(request.getValue().appCode()).isEqualTo("appabc");
        assertThat(request.getValue().mainModule()).isEqualTo("server/index.js");
        assertThat(request.getValue().plainTextBindings()).containsEntry("VITE_NUBASE_URL", "https://appabc.nubase.example");
        assertThat(request.getValue().secretTextBindings()).containsEntry("NUBASE_SERVICE_ROLE_KEY", "server-secret");
        assertThat(request.getValue().serverFiles()).hasSize(1);
        assertThat(request.getValue().assetFiles()).hasSize(1);

        ArgumentCaptor<RecordDeploymentStepRequest> steps = ArgumentCaptor.forClass(RecordDeploymentStepRequest.class);
        verify(deploymentService, org.mockito.Mockito.times(3)).recordStep(eq(deploymentId), steps.capture());
        assertThat(steps.getAllValues()).extracting(RecordDeploymentStepRequest::stepName)
                .containsExactly("server_bundle_received", "assets_received", "cloudflare_app_worker_deploy");
        verify(deploymentService).complete(eq(deploymentId), any(CompleteDeploymentRequest.class));
    }

    @Test
    void recordsFailedDeploymentWhenCloudflareDeployFails() {
        UUID deploymentId = UUID.randomUUID();
        when(deploymentService.create(any(CreateDeploymentRequest.class))).thenReturn(new DeploymentResponse(
                deploymentId,
                "appabc",
                "appabc",
                AppDeployment.STATUS_RUNNING,
                null,
                Map.of(),
                null,
                null,
                "v1",
                Instant.now(),
                Instant.now(),
                null
        ));
        when(deployer.deploy(any(AppWorkerDeploymentRequest.class)))
                .thenThrow(new AppWorkerDeploymentException("Cloudflare 500"));

        var response = service.deploy(metadata(), List.of(serverFile()), List.of(assetFile()));

        assertThat(response.status()).isEqualTo("failed");
        assertThat(response.previewUrl()).isNull();
        assertThat(response.errorMessage()).isEqualTo("Cloudflare 500");

        ArgumentCaptor<RecordDeploymentStepRequest> steps = ArgumentCaptor.forClass(RecordDeploymentStepRequest.class);
        verify(deploymentService, org.mockito.Mockito.times(3)).recordStep(eq(deploymentId), steps.capture());
        assertThat(steps.getAllValues().get(2).stepName()).isEqualTo("cloudflare_app_worker_deploy");
        assertThat(steps.getAllValues().get(2).status()).isEqualTo(AppDeploymentStep.STATUS_FAILED);
        verify(deploymentService).complete(eq(deploymentId), any(CompleteDeploymentRequest.class));
    }

    private AppWorkerDeployMetadata metadata() {
        return new AppWorkerDeployMetadata(
                "appabc",
                "v1",
                "appabc",
                "server/index.js",
                "server/index.js",
                "dist/client",
                "appabc.ottermind.app",
                "2026-06-17",
                List.of("nodejs_compat"),
                Map.of("NUBASE_URL", "https://appabc.nubase.example"),
                Map.of("VITE_NUBASE_URL", "https://appabc.nubase.example"),
                Map.of("NUBASE_SERVICE_ROLE_KEY", "server-secret")
        );
    }

    private MockMultipartFile serverFile() {
        return new MockMultipartFile(
                "serverFile",
                "server/index.js",
                "application/javascript+module",
                "export default {}".getBytes(StandardCharsets.UTF_8)
        );
    }

    private MockMultipartFile assetFile() {
        return new MockMultipartFile(
                "assetFile",
                "index.html",
                "text/html",
                "<html></html>".getBytes(StandardCharsets.UTF_8)
        );
    }
}
