package ai.nubase.common.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyLogMaskTest {

    @Test
    void mask_longKeyShowsPrefixAndSuffix() {
        assertThat(ApiKeyLogMask.mask("nbk_demo_abcdefghijklmnopqrstuvwxyz"))
                .isEqualTo("nbk_...wxyz");
    }

    @Test
    void mask_shortOrNullReturnsPlaceholder() {
        assertThat(ApiKeyLogMask.mask(null)).isEqualTo("***");
        assertThat(ApiKeyLogMask.mask("short")).isEqualTo("***");
    }
}
