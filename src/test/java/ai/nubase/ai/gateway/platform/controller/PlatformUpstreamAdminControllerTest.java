package ai.nubase.ai.gateway.platform.controller;

import ai.nubase.ai.gateway.platform.PlatformUpstream;
import ai.nubase.ai.gateway.platform.PlatformUpstreamRepository;
import ai.nubase.ai.gateway.platform.PlatformUpstreamService;
import ai.nubase.ai.gateway.platform.dto.PlatformUpstreamDtos.PlatformUpstreamRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ConcurrentModificationException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformUpstreamAdminControllerTest {

    @Test
    void repositoryEndpointPolicyFailureIsReturnedAsBadRequest() {
        PlatformUpstreamRepository repository = mock(PlatformUpstreamRepository.class);
        PlatformUpstreamService service = mock(PlatformUpstreamService.class);
        PlatformUpstreamAdminController controller =
                new PlatformUpstreamAdminController(repository, service);
        when(repository.existsById(7L)).thenReturn(true);
        when(repository.save(any(PlatformUpstream.class)))
                .thenThrow(new IllegalArgumentException(
                        "authToken is required when changing the upstream origin"));

        assertThatThrownBy(() -> controller.update(7L, request(null)))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(error.getReason())
                            .isEqualTo("authToken is required when changing the upstream origin");
                });
        verify(service, never()).refresh();
    }

    @Test
    void concurrentBlankTokenUpdateIsReturnedAsConflict() {
        PlatformUpstreamRepository repository = mock(PlatformUpstreamRepository.class);
        PlatformUpstreamService service = mock(PlatformUpstreamService.class);
        PlatformUpstreamAdminController controller =
                new PlatformUpstreamAdminController(repository, service);
        when(repository.existsById(7L)).thenReturn(true);
        when(repository.save(any(PlatformUpstream.class)))
                .thenThrow(new ConcurrentModificationException(
                        "Platform upstream changed concurrently; retry with an explicit authToken"));

        assertThatThrownBy(() -> controller.update(7L, request(null)))
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        verify(service, never()).refresh();
    }

    private static PlatformUpstreamRequest request(String authToken) {
        return new PlatformUpstreamRequest(
                "updated",
                "OPENAI",
                "https://other.example/v1",
                authToken,
                "openai",
                List.of("model-a"),
                "/v1/chat/completions",
                false,
                true,
                60000,
                2,
                100,
                null,
                null);
    }
}
