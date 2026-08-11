package ai.nubase.platform.mcp;

public class PlatformMcpOperationException extends RuntimeException {

    private final String code;

    public PlatformMcpOperationException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
