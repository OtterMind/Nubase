package ai.nubase.platform.mcp;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "nubase.platform-mcp")
public class PlatformMcpProperties {

    private boolean enabled = false;
    private String jwtSecret = "";
    private String publicBaseUrl = "http://localhost:9999";
    private List<String> allowedOrigins = new ArrayList<>();
}
