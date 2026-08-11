package ai.nubase.platform.mcp;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "nubase.platform-mcp")
public class PlatformMcpProperties {

    private boolean enabled = false;
    private String jwtSecret = "";
    private String publicBaseUrl = "http://localhost:9999";
}
