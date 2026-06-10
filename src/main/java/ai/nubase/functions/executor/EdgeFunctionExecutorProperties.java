package ai.nubase.functions.executor;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "nubase.functions.executor")
public class EdgeFunctionExecutorProperties {

    private String provider = "local";
    private int timeoutMs = 30000;
    private long maxRequestBytes = 10 * 1024 * 1024;
    private long maxResponseBytes = 10 * 1024 * 1024;
    private int perProjectRpm = 600;
    private int perFunctionRpm = 120;
    private int invocationLogRetentionDays = 30;
    private long invocationLogRetentionScanMs = 3_600_000;
    private Local local = new Local();
    private Cloudflare cloudflare = new Cloudflare();

    @Data
    public static class Local {
        private String baseUrl = "http://localhost:8787";
    }

    @Data
    public static class Cloudflare {
        private String apiBaseUrl = "https://api.cloudflare.com/client/v4";
        private String accountId = "";
        private String apiToken = "";
        private String dispatchNamespace = "";
        private String dispatcherUrl = "";
        private String dispatcherSecret = "";
    }
}
