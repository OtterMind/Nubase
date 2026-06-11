package ai.nubase.functions.service;

import ai.nubase.common.context.MultiTenancyContext;
import ai.nubase.functions.dto.EdgeFunctionDtos.DeployFunctionRequest;
import ai.nubase.functions.dto.EdgeFunctionDtos.SetFunctionSecretsRequest;
import ai.nubase.functions.executor.EdgeFunctionDeploymentRequest;
import ai.nubase.functions.executor.EdgeFunctionDeploymentResponse;
import ai.nubase.functions.executor.EdgeFunctionExecutorRouter;
import ai.nubase.metadata.edge.entity.EdgeFunction;
import ai.nubase.metadata.edge.entity.EdgeFunctionVersion;
import ai.nubase.metadata.edge.repository.EdgeFunctionInvocationRepository;
import ai.nubase.metadata.edge.repository.EdgeFunctionRepository;
import ai.nubase.metadata.edge.repository.EdgeFunctionSecretRepository;
import ai.nubase.metadata.edge.repository.EdgeFunctionVersionRepository;
import ai.nubase.postgrest.multidb.EncryptionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static ai.nubase.functions.service.EdgeFunctionExceptions.EdgeFunctionException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EdgeFunctionAdminServiceTest {

    @Mock
    private EdgeFunctionRepository functionRepository;
    @Mock
    private EdgeFunctionVersionRepository versionRepository;
    @Mock
    private EdgeFunctionSecretRepository secretRepository;
    @Mock
    private EdgeFunctionInvocationRepository invocationRepository;
    @Mock
    private EdgeFunctionExecutorRouter executor;
    @Mock
    private EdgeFunctionDeploymentRecorder deploymentRecorder;
    @Mock
    private EncryptionService encryptionService;
    @Mock
    private EdgeFunctionSecretEnv secretEnv;

    private EdgeFunctionAdminService service;

    @BeforeEach
    void setUp() {
        service = new EdgeFunctionAdminService(
                functionRepository, versionRepository, secretRepository, invocationRepository,
                executor, deploymentRecorder, encryptionService, secretEnv);
        MultiTenancyContext.setContext(MultiTenancyContext.ContextData.builder().appCode("app1").build());
        lenient().when(secretRepository.findByFunctionOrderByNameAsc(any())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        MultiTenancyContext.clear();
    }

    @Test
    void setSecretsSyncsToActiveDeployment() throws Exception {
        EdgeFunction fn = function(deployedVersion());
        when(functionRepository.findByProjectRefAndSlug("app1", "hello")).thenReturn(Optional.of(fn));
        when(secretRepository.findByFunctionAndName(eq(fn), anyString())).thenReturn(Optional.empty());
        when(encryptionService.encrypt(anyString())).thenReturn("encrypted");
        when(secretEnv.decryptedEnv(fn)).thenReturn(Map.of("API_KEY", "v1"));

        service.setSecrets("hello", new SetFunctionSecretsRequest(Map.of("API_KEY", "v1")));

        verify(executor).syncSecrets("app1", "hello", "deployment-1", Map.of("API_KEY", "v1"));
    }

    @Test
    void setSecretsSkipsSyncWhenFunctionNotDeployed() throws Exception {
        EdgeFunction fn = function(null);
        when(functionRepository.findByProjectRefAndSlug("app1", "hello")).thenReturn(Optional.of(fn));
        when(secretRepository.findByFunctionAndName(eq(fn), anyString())).thenReturn(Optional.empty());
        when(encryptionService.encrypt(anyString())).thenReturn("encrypted");

        service.setSecrets("hello", new SetFunctionSecretsRequest(Map.of("API_KEY", "v1")));

        verify(executor, never()).syncSecrets(anyString(), anyString(), anyString(), any());
    }

    @Test
    void setSecretsSurfacesSyncFailure() throws Exception {
        EdgeFunction fn = function(deployedVersion());
        when(functionRepository.findByProjectRefAndSlug("app1", "hello")).thenReturn(Optional.of(fn));
        when(secretRepository.findByFunctionAndName(eq(fn), anyString())).thenReturn(Optional.empty());
        when(encryptionService.encrypt(anyString())).thenReturn("encrypted");
        when(secretEnv.decryptedEnv(fn)).thenReturn(Map.of("API_KEY", "v1"));
        doThrow(new IllegalStateException("cloudflare down"))
                .when(executor).syncSecrets(anyString(), anyString(), anyString(), any());

        assertThatThrownBy(() -> service.setSecrets("hello", new SetFunctionSecretsRequest(Map.of("API_KEY", "v1"))))
                .isInstanceOf(EdgeFunctionException.class)
                .satisfies(e -> assertThat(((EdgeFunctionException) e).code()).isEqualTo("SECRET_SYNC_FAILED"));
    }

    @Test
    void deployPassesEntrypointAndDecryptedSecretsToExecutor() {
        EdgeFunction fn = function(null);
        fn.setEntrypoint("main.js");
        when(functionRepository.findByProjectRefAndSlug("app1", "hello")).thenReturn(Optional.of(fn));
        when(secretEnv.decryptedEnv(fn)).thenReturn(Map.of("API_KEY", "v1"));
        when(executor.deploy(any())).thenReturn(EdgeFunctionDeploymentResponse.failed("local", "boom"));
        when(deploymentRecorder.record(eq(fn.getId()), any(), any())).thenReturn(deployedVersion());

        service.deploy("hello", new DeployFunctionRequest("hash", null, null, "bundle"));

        ArgumentCaptor<EdgeFunctionDeploymentRequest> captor = ArgumentCaptor.forClass(EdgeFunctionDeploymentRequest.class);
        verify(executor).deploy(captor.capture());
        assertThat(captor.getValue().entrypoint()).isEqualTo("main.js");
        assertThat(captor.getValue().env()).containsEntry("API_KEY", "v1");
        verify(deploymentRecorder).record(eq(fn.getId()), any(), any());
    }

    private EdgeFunction function(EdgeFunctionVersion activeVersion) {
        return EdgeFunction.builder()
                .id(UUID.randomUUID())
                .projectRef("app1")
                .slug("hello")
                .name("hello")
                .enabled(true)
                .activeVersion(activeVersion)
                .build();
    }

    private EdgeFunctionVersion deployedVersion() {
        return EdgeFunctionVersion.builder()
                .status("deployed")
                .providerDeploymentId("deployment-1")
                .build();
    }
}
