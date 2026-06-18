package ai.nubase.common.util;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveHeaderLogMaskTest {

    @Test
    void collectMasked_redactsApikeyAndAuthorization() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("apikey", "eyJhbGciOiJIUzI1NiJ9.service_role_payload");
        request.addHeader("Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.user_jwt");
        request.addHeader("Content-Type", "application/json");

        var masked = SensitiveHeaderLogMask.collectMasked(request);

        assertThat(masked.get("apikey")).isEqualTo("eyJh...load");
        assertThat(masked.get("Authorization")).isEqualTo("Bear..._jwt");
        assertThat(masked.get("Content-Type")).isEqualTo("application/json");
    }

    @Test
    void maskValue_shortSensitiveHeaderReturnsPlaceholder() {
        assertThat(SensitiveHeaderLogMask.maskValue("apikey", "short")).isEqualTo("***");
        assertThat(SensitiveHeaderLogMask.maskValue("Content-Type", "application/json"))
                .isEqualTo("application/json");
    }
}
