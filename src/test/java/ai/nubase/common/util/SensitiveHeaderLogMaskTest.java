package ai.nubase.common.util;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveHeaderLogMaskTest {

    @Test
    void keepsOnlyAllowlistedHeaderNamesAndNeverRetainsValues() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("apikey", "api-key-sensitive-sentinel");
        request.addHeader("Authorization", "authorization-sensitive-sentinel");
        request.addHeader("Cookie", "cookie-sensitive-sentinel");
        request.addHeader("X-Custom-Token", "custom-token-sensitive-sentinel");
        request.addHeader("X-Unreviewed", "unknown-header-sensitive-sentinel");
        request.addHeader("X-Request-Id", "diagnostic-value-sensitive-sentinel");
        request.addHeader("User-Agent", "user-agent-sensitive-sentinel");
        request.addHeader("Content-Type", "application/json");

        var masked = SensitiveHeaderLogMask.collectMasked(request);

        assertThat(masked)
                .containsEntry("X-Request-Id", "[present]")
                .containsEntry("User-Agent", "[present]")
                .doesNotContainKeys(
                        "apikey",
                        "Authorization",
                        "Cookie",
                        "X-Custom-Token",
                        "X-Unreviewed",
                        "Content-Type");
        assertThat(masked.values()).containsOnly("[present]");
    }

    @Test
    void returnsEmptyMapForMissingRequest() {
        assertThat(SensitiveHeaderLogMask.collectMasked(null)).isEmpty();
    }
}
