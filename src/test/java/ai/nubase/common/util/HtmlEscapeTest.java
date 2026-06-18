package ai.nubase.common.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HtmlEscapeTest {

    @Test
    void escapesScriptTags() {
        assertThat(HtmlEscape.escape("<script>alert(1)</script>"))
                .isEqualTo("&lt;script&gt;alert(1)&lt;/script&gt;");
    }

    @Test
    void nullReturnsEmptyString() {
        assertThat(HtmlEscape.escape(null)).isEmpty();
    }
}
