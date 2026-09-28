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
import org.springframework.util.unit.DataSize;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
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
        service = new AppWorkerDeployService(deploymentService, deployer, defaultProperties());
    }

    @AfterEach
    void tearDown() {
        MultiTenancyContext.clear();
    }

    @Test
    void deploysAppWorkerAndRecordsDeploymentSteps() {
        MultiTenancyContext.setContext(MultiTenancyContext.ContextData.builder()
                .appCode("unrelated-tenant").build());
        UUID deploymentId = UUID.randomUUID();
        when(deploymentService.createForProjectRef(eq("appabc"), any(CreateDeploymentRequest.class))).thenReturn(new DeploymentResponse(
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
                "preview",
                "preview-ns",
                "appabc",
                "cf-version-1",
                "https://preview-appabc.ottermind.app",
                "deployed",
                "asset-hash",
                1,
                Instant.parse("2026-06-17T00:00:00Z")
        ));

        var response = service.deployForProjectRef("appabc", metadata(), List.of(serverFile()), List.of(assetFile()));

        assertThat(response.status()).isEqualTo("deployed");
        assertThat(response.deploymentTarget()).isEqualTo("preview");
        assertThat(response.dispatchNamespace()).isEqualTo("preview-ns");
        assertThat(response.previewUrl()).isEqualTo("https://preview-appabc.ottermind.app");
        assertThat(response.assetManifestHash()).isEqualTo("asset-hash");
        assertThat(response.providerDeploymentId()).isEqualTo("appabc");
        assertThat(response.providerVersionId()).isEqualTo("cf-version-1");

        ArgumentCaptor<AppWorkerDeploymentRequest> request = ArgumentCaptor.forClass(AppWorkerDeploymentRequest.class);
        verify(deployer).deploy(request.capture());
        assertThat(request.getValue().appCode()).isEqualTo("appabc");
        assertThat(request.getValue().deploymentTarget()).isEqualTo(AppWorkerDeploymentTarget.PREVIEW);
        assertThat(request.getValue().mainModule()).isEqualTo("server/index.js");
        assertThat(request.getValue().plainTextBindings()).containsEntry("NUBASE_RUNTIME_MODE", "same-origin-proxy");
        assertThat(request.getValue().plainTextBindings()).containsEntry("NUBASE_PROJECT_REF", "appabc");
        assertThat(request.getValue().plainTextBindings()).containsEntry("NUBASE_UPSTREAM_URL", "https://rebel-earl-transport-floyd.trycloudflare.com");
        assertThat(request.getValue().plainTextBindings()).containsEntry("VITE_NUBASE_PUBLISHABLE_KEY", "public-authenticated-token");
        assertThat(request.getValue().secretTextBindings()).containsEntry("NUBASE_SERVICE_ROLE_KEY", "server-secret");
        assertThat(request.getValue().serverFiles()).hasSize(1);
        assertThat(request.getValue().assetFiles()).hasSize(1);
        assertThat(request.getValue().serverFiles().get(0).contentType()).isEqualTo("application/javascript+module");
        assertThat(request.getValue().assetFiles().get(0).contentType()).isEqualTo("text/html");

        ArgumentCaptor<RecordDeploymentStepRequest> steps = ArgumentCaptor.forClass(RecordDeploymentStepRequest.class);
        verify(deploymentService, org.mockito.Mockito.times(3)).recordStepForProjectRef(eq("appabc"), eq(deploymentId), steps.capture());
        assertThat(steps.getAllValues()).extracting(RecordDeploymentStepRequest::stepName)
                .containsExactly("server_bundle_received", "assets_received", "cloudflare_app_worker_deploy");
        ArgumentCaptor<CreateDeploymentRequest> createRequest = ArgumentCaptor.forClass(CreateDeploymentRequest.class);
        verify(deploymentService).createForProjectRef(eq("appabc"), createRequest.capture());
        Map<String, Object> manifestSummary = createRequest.getValue().manifestSummary();
        assertThat(manifestSummary).containsEntry("deploymentTarget", "preview");
        assertThat(manifestSummary).containsEntry("runtimeMode", "same-origin-proxy");
        assertThat(manifestSummary).containsEntry("upstreamEndpoint", "https://rebel-earl-transport-floyd.trycloudflare.com");
        assertThat(manifestSummary).containsEntry("proxyEnabled", true);
        verify(deploymentService).completeForProjectRef(eq("appabc"), eq(deploymentId), any(CompleteDeploymentRequest.class));
    }

    @Test
    void deploysAppWorkerOnlyWithoutTenantContextOrNubaseBindings() {
        MultiTenancyContext.clear();
        UUID deploymentId = UUID.randomUUID();
        when(deploymentService.createForProjectRef(eq("appfrontend"), any(CreateDeploymentRequest.class))).thenReturn(new DeploymentResponse(
                deploymentId,
                "appfrontend",
                "appfrontend",
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
                "preview",
                "preview-ns",
                "appfrontend",
                "cf-version-frontend",
                "https://preview-appfrontend.ottermind.app",
                "deployed",
                "asset-hash",
                1,
                Instant.parse("2026-06-17T00:00:00Z")
        ));

        var response = service.deployForProjectRef("appfrontend", frontendOnlyMetadata(), List.of(serverFile()), List.of(assetFile()));

        assertThat(response.status()).isEqualTo("deployed");
        ArgumentCaptor<AppWorkerDeploymentRequest> request = ArgumentCaptor.forClass(AppWorkerDeploymentRequest.class);
        verify(deployer).deploy(request.capture());
        assertThat(request.getValue().appCode()).isEqualTo("appfrontend");
        assertThat(request.getValue().plainTextBindings()).doesNotContainKeys(
                "NUBASE_RUNTIME_MODE",
                "NUBASE_PROJECT_REF",
                "NUBASE_UPSTREAM_URL",
                "NUBASE_PUBLISHABLE_KEY",
                "VITE_NUBASE_PUBLISHABLE_KEY"
        );
        assertThat(request.getValue().secretTextBindings()).isEmpty();

        ArgumentCaptor<CreateDeploymentRequest> createRequest = ArgumentCaptor.forClass(CreateDeploymentRequest.class);
        verify(deploymentService).createForProjectRef(eq("appfrontend"), createRequest.capture());
        assertThat(createRequest.getValue().appName()).isEqualTo("appfrontend");
        assertThat(createRequest.getValue().manifestSummary()).containsEntry("proxyEnabled", false);
        assertThat(createRequest.getValue().manifestSummary()).doesNotContainKeys("runtimeMode", "upstreamEndpoint");
    }

    @Test
    void recordsFailedDeploymentWhenCloudflareDeployFails() {
        UUID deploymentId = UUID.randomUUID();
        when(deploymentService.createForProjectRef(eq("appabc"), any(CreateDeploymentRequest.class))).thenReturn(new DeploymentResponse(
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

        var response = service.deployForProjectRef("appabc", metadata(), List.of(serverFile()), List.of(assetFile()));

        assertThat(response.status()).isEqualTo("failed");
        assertThat(response.previewUrl()).isNull();
        assertThat(response.errorMessage()).isEqualTo("Cloudflare 500");

        ArgumentCaptor<RecordDeploymentStepRequest> steps = ArgumentCaptor.forClass(RecordDeploymentStepRequest.class);
        verify(deploymentService, org.mockito.Mockito.times(3)).recordStepForProjectRef(eq("appabc"), eq(deploymentId), steps.capture());
        assertThat(steps.getAllValues().get(2).stepName()).isEqualTo("cloudflare_app_worker_deploy");
        assertThat(steps.getAllValues().get(2).status()).isEqualTo(AppDeploymentStep.STATUS_FAILED);
        verify(deploymentService).completeForProjectRef(eq("appabc"), eq(deploymentId), any(CompleteDeploymentRequest.class));
    }

    @Test
    void rejectsWorkerNameNotNamespacedUnderAppCode() {
        var metadata = new AppWorkerDeployMetadata(
                "appabc",
                "v1",
                "someone-elses-worker",
                "server/index.js",
                "server/index.js",
                "dist/client",
                null,
                "preview",
                null,
                null,
                null,
                null,
                null
        );

        assertThatThrownBy(() -> service.deployForProjectRef("appabc", metadata, List.of(serverFile()), List.of()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("workerName must equal the project appCode");

        verify(deployer, org.mockito.Mockito.never()).deploy(any());
        verify(deploymentService, org.mockito.Mockito.never()).createForProjectRef(any(), any());
    }

    @Test
    void rejectsServerFileThatExceedsAppWorkerFileLimit() {
        AppWorkerDeployProperties properties = defaultProperties();
        properties.setMaxFileSize(DataSize.ofBytes(4));
        properties.setMaxRequestSize(DataSize.ofBytes(128));
        service = new AppWorkerDeployService(deploymentService, deployer, properties);

        ResponseStatusException exception = catchThrowableOfType(
                () -> service.deployForProjectRef("appabc", metadata(), List.of(serverFile()), List.of(assetFile())),
                ResponseStatusException.class
        );
        assertThat(exception.getStatusCode().value()).isEqualTo(413);
        assertThat(exception.getReason())
                .contains("part=serverFile")
                .contains("file=server/index.js")
                .contains("limit=4")
                .doesNotContain("export default");

        verify(deployer, org.mockito.Mockito.never()).deploy(any());
        verify(deploymentService, org.mockito.Mockito.never()).createForProjectRef(any(), any());
    }

    @Test
    void rejectsCombinedServerAndAssetFilesThatExceedAppWorkerRequestLimit() {
        AppWorkerDeployProperties properties = defaultProperties();
        properties.setMaxFileSize(DataSize.ofBytes(128));
        properties.setMaxRequestSize(DataSize.ofBytes(16));
        service = new AppWorkerDeployService(deploymentService, deployer, properties);

        ResponseStatusException exception = catchThrowableOfType(
                () -> service.deployForProjectRef("appabc", metadata(), List.of(serverFile()), List.of(assetFile())),
                ResponseStatusException.class
        );
        assertThat(exception.getStatusCode().value()).isEqualTo(413);
        assertThat(exception.getReason())
                .contains("maximum request size")
                .contains("limit=16")
                .doesNotContain("<html>");

        verify(deployer, org.mockito.Mockito.never()).deploy(any());
        verify(deploymentService, org.mockito.Mockito.never()).createForProjectRef(any(), any());
    }

    @Test
    void allowsWorkerNameNamespacedUnderAppCode() {
        UUID deploymentId = UUID.randomUUID();
        when(deploymentService.createForProjectRef(eq("appabc"), any(CreateDeploymentRequest.class))).thenReturn(new DeploymentResponse(
                deploymentId, "appabc", "appabc", AppDeployment.STATUS_RUNNING, null, Map.of(),
                null, null, "v1", Instant.now(), Instant.now(), null
        ));
        when(deployer.deploy(any(AppWorkerDeploymentRequest.class))).thenReturn(new AppWorkerDeploymentResult(
                "cloudflare",
                "preview",
                "preview-ns", "appabc-preview", "cf-version-preview", "https://preview-appabc-preview.ottermind.app", "deployed",
                "asset-hash", 0, Instant.parse("2026-06-17T00:00:00Z")
        ));
        var metadata = new AppWorkerDeployMetadata(
                "appabc", "v1", "appabc-preview", "server/index.js", "server/index.js",
                "dist/client", null, "preview", null, null, null, null, null
        );

        var response = service.deployForProjectRef("appabc", metadata, List.of(serverFile()), List.of());

        assertThat(response.status()).isEqualTo("deployed");
        ArgumentCaptor<AppWorkerDeploymentRequest> request = ArgumentCaptor.forClass(AppWorkerDeploymentRequest.class);
        verify(deployer).deploy(request.capture());
        assertThat(request.getValue().workerName()).isEqualTo("appabc-preview");
    }

    @Test
    void deploysProductionTargetWithSameWorkerName() {
        UUID deploymentId = UUID.randomUUID();
        when(deploymentService.createForProjectRef(eq("appabc"), any(CreateDeploymentRequest.class))).thenReturn(new DeploymentResponse(
                deploymentId, "appabc", "appabc", AppDeployment.STATUS_RUNNING, null, Map.of(),
                null, null, "v1", Instant.now(), Instant.now(), null
        ));
        when(deployer.deploy(any(AppWorkerDeploymentRequest.class))).thenReturn(new AppWorkerDeploymentResult(
                "cloudflare",
                "production",
                "production-ns", "appabc", "cf-version-live", "https://appabc.ottermind.app", "deployed",
                "asset-hash", 0, Instant.parse("2026-06-17T00:00:00Z")
        ));
        var metadata = new AppWorkerDeployMetadata(
                "appabc", "v1", "appabc", "server/index.js", "server/index.js",
                "dist/client", "appabc.ottermind.app", "production", null, null, null, null, null
        );

        var response = service.deployForProjectRef("appabc", metadata, List.of(serverFile()), List.of());

        assertThat(response.status()).isEqualTo("deployed");
        assertThat(response.deploymentTarget()).isEqualTo("production");
        assertThat(response.dispatchNamespace()).isEqualTo("production-ns");
        assertThat(response.providerDeploymentId()).isEqualTo("appabc");
        ArgumentCaptor<AppWorkerDeploymentRequest> request = ArgumentCaptor.forClass(AppWorkerDeploymentRequest.class);
        verify(deployer).deploy(request.capture());
        assertThat(request.getValue().deploymentTarget()).isEqualTo(AppWorkerDeploymentTarget.PRODUCTION);
        assertThat(request.getValue().workerName()).isEqualTo("appabc");
        assertThat(request.getValue().previewHost()).isEqualTo("appabc.ottermind.app");
    }

    @Test
    void rejectsProjectRefMismatchWithoutCreatingDeployment() {
        var exception = catchThrowableOfType(() -> service.deployForProjectRef(
                "another-app", metadata(), List.of(serverFile()), List.of()), ResponseStatusException.class);
        assertThat(exception.getStatusCode().value()).isEqualTo(403);
        assertThat(exception.getReason()).isEqualTo("metadata.appCode must match x-nubase-project-ref");
        org.mockito.Mockito.verifyNoInteractions(deploymentService, deployer);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.NullAndEmptySource
    @org.junit.jupiter.params.provider.ValueSource(strings = "   ")
    void rejectsMissingProjectRef(String projectRef) {
        var exception = catchThrowableOfType(() -> service.deployForProjectRef(
                projectRef, metadata(), List.of(serverFile()), List.of()), ResponseStatusException.class);
        assertThat(exception.getStatusCode().value()).isEqualTo(400);
        org.mockito.Mockito.verifyNoInteractions(deploymentService, deployer);
    }

    private AppWorkerDeployMetadata metadata() {
        return new AppWorkerDeployMetadata(
                "appabc",
                "v1",
                "appabc",
                "server/index.js",
                "server/index.js",
                "dist/client",
                "preview-appabc.ottermind.app",
                "preview",
                "2026-06-17",
                List.of("nodejs_compat"),
                Map.of("NUBASE_RUNTIME_MODE", "same-origin-proxy"),
                Map.of(
                        "NUBASE_PROJECT_REF", "appabc",
                        "NUBASE_UPSTREAM_URL", "https://rebel-earl-transport-floyd.trycloudflare.com",
                        "NUBASE_PUBLISHABLE_KEY", "public-authenticated-token",
                        "VITE_NUBASE_PUBLISHABLE_KEY", "public-authenticated-token"
                ),
                Map.of("NUBASE_SERVICE_ROLE_KEY", "server-secret")
        );
    }

    private AppWorkerDeployMetadata frontendOnlyMetadata() {
        return new AppWorkerDeployMetadata(
                "appfrontend",
                "v1",
                "appfrontend",
                "server/index.js",
                "server/index.js",
                "dist/client",
                "preview-appfrontend.ottermind.app",
                "preview",
                "2026-06-17",
                List.of("nodejs_compat"),
                null,
                Map.of(),
                Map.of()
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

    private AppWorkerDeployProperties defaultProperties() {
        AppWorkerDeployProperties properties = new AppWorkerDeployProperties();
        properties.setMaxFileSize(DataSize.ofMegabytes(64));
        properties.setMaxRequestSize(DataSize.ofMegabytes(128));
        return properties;
    }
}
