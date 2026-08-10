package ai.nubase.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GlobalCorsConfigTest {

    @Test
    void wildcardOriginsRemainCompatibleWithoutCredentials() {
        CorsConfiguration config = new GlobalCorsConfig("*", false).corsConfiguration();

        assertThat(config.checkOrigin("https://client.example.com"))
                .isEqualTo("https://client.example.com");
        assertThat(config.getAllowCredentials()).isFalse();
    }

    @Test
    void explicitOriginCanUseCredentials() {
        CorsConfiguration config = new GlobalCorsConfig(
                "https://studio.example.com, https://*.apps.example.com", true)
                .corsConfiguration();

        assertThat(config.checkOrigin("https://studio.example.com"))
                .isEqualTo("https://studio.example.com");
        assertThat(config.checkOrigin("https://demo.apps.example.com"))
                .isEqualTo("https://demo.apps.example.com");
        assertThat(config.getAllowCredentials()).isTrue();
    }

    @Test
    void untrustedOriginIsRejectedByExplicitAllowlist() {
        CorsConfiguration config = new GlobalCorsConfig("https://studio.example.com", true)
                .corsConfiguration();

        assertThat(config.checkOrigin("https://untrusted.example.com")).isNull();
    }

    @Test
    void wildcardOriginsCannotUseCredentials() {
        assertThatThrownBy(() -> new GlobalCorsConfig("*", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Wildcard CORS origins");
    }

    @Test
    void exposesOnlyDocumentedResponseHeaders() {
        CorsConfiguration config = new GlobalCorsConfig("*", false).corsConfiguration();

        assertThat(config.getExposedHeaders())
                .containsExactly(
                        "Content-Range",
                        "Content-Location",
                        "Preference-Applied",
                        "Location",
                        "ETag",
                        "X-Nubase-Request-Id");
    }
}
