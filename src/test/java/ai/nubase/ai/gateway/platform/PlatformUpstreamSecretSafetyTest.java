package ai.nubase.ai.gateway.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformUpstreamSecretSafetyTest {

    @Test
    void decryptedCredentialIsExcludedFromJsonAndToString() throws Exception {
        PlatformUpstream upstream = PlatformUpstream.builder()
                .id(1L)
                .name("upstream-a")
                .authToken("token-sentinel")
                .build();

        String json = new ObjectMapper().writeValueAsString(upstream);

        assertThat(json).doesNotContain("authToken", "token-sentinel");
        assertThat(upstream.toString()).doesNotContain("token-sentinel");
    }
}
