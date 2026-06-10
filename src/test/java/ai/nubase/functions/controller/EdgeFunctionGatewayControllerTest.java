package ai.nubase.functions.controller;

import ai.nubase.functions.executor.EdgeFunctionExecutorProperties;
import ai.nubase.functions.executor.EdgeFunctionInvocationResponse;
import ai.nubase.functions.service.EdgeFunctionInvocationService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EdgeFunctionGatewayControllerTest {

    @Test
    void rejectsOversizedRequestBeforeInvokingFunction() throws Exception {
        EdgeFunctionInvocationService invocationService = mock(EdgeFunctionInvocationService.class);
        EdgeFunctionExecutorProperties properties = new EdgeFunctionExecutorProperties();
        properties.setMaxRequestBytes(3);
        EdgeFunctionGatewayController controller = new EdgeFunctionGatewayController(invocationService, properties);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/functions/v1/hello");
        request.setContent("abcd".getBytes(StandardCharsets.UTF_8));

        ResponseEntity<byte[]> response = controller.invoke(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(new String(response.getBody(), StandardCharsets.UTF_8)).contains("REQUEST_TOO_LARGE");
        verifyNoInteractions(invocationService);
    }

    @Test
    void forwardsSlugSuffixAndBodyToInvocationService() throws Exception {
        EdgeFunctionInvocationService invocationService = mock(EdgeFunctionInvocationService.class);
        EdgeFunctionExecutorProperties properties = new EdgeFunctionExecutorProperties();
        EdgeFunctionGatewayController controller = new EdgeFunctionGatewayController(invocationService, properties);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/functions/v1/hello/nested");
        request.setAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE, "/functions/v1/hello/nested");
        request.setContent("payload".getBytes(StandardCharsets.UTF_8));
        when(invocationService.invoke(eq("hello"), eq("/nested"), any(byte[].class), eq(request)))
                .thenReturn(new EdgeFunctionInvocationResponse(
                        201,
                        Map.of("content-type", List.of("text/plain"), "transfer-encoding", List.of("chunked")),
                        "ok".getBytes(StandardCharsets.UTF_8),
                        null,
                        null
                ));

        ResponseEntity<byte[]> response = controller.invoke(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().get("content-type")).containsExactly("text/plain");
        assertThat(response.getHeaders()).doesNotContainKey("transfer-encoding");
        assertThat(response.getBody()).isEqualTo("ok".getBytes(StandardCharsets.UTF_8));
    }
}
