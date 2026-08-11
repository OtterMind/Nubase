package ai.nubase.platform.mcp;

import java.util.Set;

public final class PlatformMcpScopes {

    public static final String PROJECT_CREATE = "project:create";
    public static final String PROJECT_PROVISION = "project:provision";
    public static final String PROJECT_READ = "project:status";
    public static final Set<String> ALL = Set.of(PROJECT_CREATE, PROJECT_PROVISION, PROJECT_READ);

    private PlatformMcpScopes() {
    }
}
