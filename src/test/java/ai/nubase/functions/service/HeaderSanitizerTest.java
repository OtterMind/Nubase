package ai.nubase.functions.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class HeaderSanitizerTest {

    private final HeaderSanitizer sanitizer = new HeaderSanitizer();

    @Test
    void stripsHopByHopAndProjectKeyHeaders() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/functions/v1/hello");
        request.addHeader("Authorization", "Bearer user.jwt");
        request.addHeader("apikey", "project.key");
        request.addHeader("Connection", "keep-alive");
        request.addHeader("Content-Type", "application/json");

        var headers = sanitizer.forwardableHeaders(request);

        assertThat(headers).containsKeys("Authorization", "Content-Type");
        assertThat(headers.keySet()).noneMatch(name -> name.equalsIgnoreCase("apikey"));
        assertThat(headers.keySet()).noneMatch(name -> name.equalsIgnoreCase("Connection"));
    }
}
