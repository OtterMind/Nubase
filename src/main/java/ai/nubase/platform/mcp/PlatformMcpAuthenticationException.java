package ai.nubase.platform.mcp;

public class PlatformMcpAuthenticationException extends RuntimeException {

    public PlatformMcpAuthenticationException() {
        super("Platform MCP authentication failed");
    }

    public PlatformMcpAuthenticationException(Throwable cause) {
        super("Platform MCP authentication failed", cause);
    }
}
