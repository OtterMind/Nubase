package ai.nubase.assets.service;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class AssetsConfigurationTest {

    @Test
    void boundedPrivateStorageProofDefaultsToDisabled() throws Exception {
        ClassPathResource resource = new ClassPathResource("application.yml");
        String applicationYaml;
        try (InputStream input = resource.getInputStream()) {
            applicationYaml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(applicationYaml).contains(
                "bounded-private-storage-enabled: "
                        + "${NUBASE_ASSETS_BOUNDED_PRIVATE_STORAGE_ENABLED:false}");
    }
}
