package ai.nubase.assets.controller;

import ai.nubase.assets.entity.AssetFile;
import ai.nubase.assets.service.AssetsService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.HandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssetsPublicControllerTest {

    @Test
    void headReturnsNotModifiedWhenEtagMatches() {
        AssetsService assetsService = mock(AssetsService.class);
        AssetsPublicController controller = new AssetsPublicController(assetsService);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)).thenReturn("/assets/v1/**");
        when(request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE)).thenReturn("/assets/v1/app.css");
        when(assetsService.getPublicFileOrThrow("app.css"))
                .thenReturn(AssetFile.builder().etag("asset-v1").sizeBytes(42).build());

        ResponseEntity<Void> response = controller.head("W/\"asset-v1\"", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(response.getHeaders().getETag()).isEqualTo("\"asset-v1\"");
        verify(assetsService).getPublicFileOrThrow("app.css");
    }
}
  
