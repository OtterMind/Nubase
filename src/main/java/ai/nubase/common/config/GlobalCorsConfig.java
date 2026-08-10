package ai.nubase.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;
import java.util.List;

@Configuration
public class GlobalCorsConfig {
    private static final List<String> ALLOWED_METHODS = List.of(
            "GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD");
    private static final List<String> EXPOSED_HEADERS = List.of(
            "Content-Range",
            "Content-Location",
            "Preference-Applied",
            "Location",
            "ETag",
            "X-Nubase-Request-Id");

    private final List<String> allowedOriginPatterns;
    private final boolean allowCredentials;

    GlobalCorsConfig(
            @Value("${nubase.cors.allowed-origin-patterns:*}") String allowedOriginPatterns,
            @Value("${nubase.cors.allow-credentials:false}") boolean allowCredentials) {
        this.allowedOriginPatterns = Arrays.stream(allowedOriginPatterns.split(","))
                .map(String::trim)
                .filter(pattern -> !pattern.isEmpty())
                .toList();
        this.allowCredentials = allowCredentials;

        if (allowCredentials && this.allowedOriginPatterns.contains("*")) {
            throw new IllegalArgumentException(
                    "Wildcard CORS origins cannot be combined with credentialed requests");
        }
    }

    @Bean
    public CorsFilter corsFilter() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", corsConfiguration());
        return new CorsFilter(source);
    }

    CorsConfiguration corsConfiguration() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(allowedOriginPatterns);
        config.setAllowCredentials(allowCredentials);
        config.setAllowedMethods(ALLOWED_METHODS);
        // API clients use provider-specific and PostgREST headers. Origin policy is the trust boundary.
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(EXPOSED_HEADERS);
        config.setMaxAge(3600L);
        return config;
    }
}
