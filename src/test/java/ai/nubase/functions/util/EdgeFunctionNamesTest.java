package ai.nubase.functions.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EdgeFunctionNamesTest {

    @Test
    void validatesSlugs() {
        assertThat(EdgeFunctionNames.isValidSlug("hello")).isTrue();
        assertThat(EdgeFunctionNames.isValidSlug("hello_world")).isTrue();
        assertThat(EdgeFunctionNames.isValidSlug("hello-world")).isTrue();
        assertThat(EdgeFunctionNames.isValidSlug("v1")).isTrue();

        assertThat(EdgeFunctionNames.isValidSlug("")).isFalse();
        assertThat(EdgeFunctionNames.isValidSlug("/x")).isFalse();
        assertThat(EdgeFunctionNames.isValidSlug("../x")).isFalse();
        assertThat(EdgeFunctionNames.isValidSlug("x y")).isFalse();
    }

    @Test
    void normalizesNamesToSlugs() {
        assertThat(EdgeFunctionNames.normalizeSlug("Hello World")).isEqualTo("hello-world");
        assertThat(EdgeFunctionNames.normalizeSlug(" hello_world ")).isEqualTo("hello_world");
        assertThatThrownBy(() -> EdgeFunctionNames.normalizeSlug("///"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validatesSecretNames() {
        assertThat(EdgeFunctionNames.isValidSecretName("API_KEY")).isTrue();
        assertThat(EdgeFunctionNames.isValidSecretName("NUBASE_CUSTOM_TOKEN")).isTrue();

        assertThat(EdgeFunctionNames.isValidSecretName("api_key")).isFalse();
        assertThat(EdgeFunctionNames.isValidSecretName("1_KEY")).isFalse();
        assertThat(EdgeFunctionNames.isValidSecretName("A-B")).isFalse();
    }
}
