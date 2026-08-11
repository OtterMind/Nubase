package ai.nubase.common.multitenancy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformMcpPathIsolationTest {

    @Test
    void platformMcpSkipsTenantRoutingWithoutEnteringAdminInitAuthentication() {
        assertThat(PlatformAdminPaths.isPlatformMcpPath("/platform/mcp")).isTrue();
        assertThat(PlatformAdminPaths.isPlatformMcpPath("/platform/mcp/")).isTrue();
        assertThat(PlatformAdminPaths.isPlatformMcpPath(
                "/platform/mcp;token=must-not-be-logged")).isTrue();
        assertThat(PlatformAdminPaths.skipsTenantMultitenancy("/platform/mcp")).isTrue();
        assertThat(PlatformAdminPaths.isPlatformAdminPath("/platform/mcp")).isFalse();
    }

    @Test
    void similarlyNamedPathsAreNotMisclassified() {
        assertThat(PlatformAdminPaths.isPlatformMcpPath("/platform/mcpx")).isFalse();
        assertThat(PlatformAdminPaths.skipsTenantMultitenancy("/platform/mcpx")).isFalse();
    }
}
