package ai.nubase.ai.gateway.platform;

import ai.nubase.common.enums.ApiProvider;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Platform-level unified upstream configuration (metadata DB: public.ai_gateway_platform_upstreams).
 *
 * <p>Mirrors the shape of the per-tenant {@code ai_gateway.upstream_configs} so the gateway's
 * routing/forwarding logic is identical regardless of whether a request is served by a project's
 * own custom upstream or by this platform fallback. This POJO is loaded via JdbcTemplate (not JPA);
 * {@code authToken} is decrypted on load and MUST NOT be serialized back to tenants.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlatformUpstream {

    private Long id;
    private String name;
    @Builder.Default
    private ApiProvider provider = ApiProvider.CLAUDE;
    private String baseUrl;
    /** Decrypted upstream API key (x-api-key). Never expose through admin read APIs. */
    @JsonIgnore
    @ToString.Exclude
    private String authToken;
    private String channelCode;
    @Builder.Default
    private List<String> supportedModels = new ArrayList<>();
    @Builder.Default
    private String chatCompletionsPath = "/v1/chat/completions";
    @Builder.Default
    private Boolean isDefault = false;
    @Builder.Default
    private Boolean isActive = true;
    @Builder.Default
    private Integer timeoutMs = 60000;
    @Builder.Default
    private Integer maxRetries = 2;
    @Builder.Default
    private Integer priority = 100;
    private Integer maxInputTokens;
    private String description;
    private Instant createdAt;
    private Instant updatedAt;
}
