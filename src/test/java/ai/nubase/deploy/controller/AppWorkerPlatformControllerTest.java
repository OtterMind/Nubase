package ai.nubase.deploy.controller;

import ai.nubase.deploy.service.AppWorkerDeployService;
import ai.nubase.deploy.service.AppWorkerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AppWorkerPlatformControllerTest {

    private final AppWorkerDeployService deployService = mock(AppWorkerDeployService.class);
    private final AppWorkerService appWorkerService = mock(AppWorkerService.class);
    private final AppWorkerPlatformController controller = new AppWorkerPlatformController(
            deployService,
            appWorkerService,
            new ObjectMapper()
    );

    @Test
    void listRequiresExplicitProjectRefHeader() {
        assertThatThrownBy(() -> controller.listAppWorkers(null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("x-nubase-project-ref is required");
    }

    @Test
    void deployPassesExplicitProjectRefFromMultipartRequest() throws Exception {
        MockMvcBuilders.standaloneSetup(controller).build().perform(
                multipart("/deployments/platform/v1/app-workers/deploy")
                        .file(new MockMultipartFile("metadata", "", "text/plain",
                                "{\"appCode\":\"appabc\",\"deploymentTarget\":\"preview\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        .file(new MockMultipartFile("serverFile", "index.js", "text/javascript", new byte[]{1}))
                        .header("x-nubase-project-ref", " appabc "))
                .andExpect(status().isOk());
        verify(deployService).deployForProjectRef(eq("appabc"), any(), any(), any());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void deployRequiresExplicitProjectRef(String projectRef) {
        assertThatThrownBy(() -> controller.deployAppWorker(projectRef, "{}", java.util.List.of(), java.util.List.of()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("x-nubase-project-ref is required");
        verifyNoInteractions(deployService);
    }
}
