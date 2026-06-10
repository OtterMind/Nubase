package ai.nubase.common.config;

import ai.nubase.ai.gateway.filter.GatewayApiKeyAuthFilter;
import ai.nubase.common.multitenancy.UnifiedMultiTenancyFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final UnifiedMultiTenancyFilter unifiedMultiTenancyFilter;
    private final GatewayApiKeyAuthFilter gatewayApiKeyAuthFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                // CORS is already handled in UnifiedMultiTenancyFilter; no extra config needed here
                .cors(Customizer.withDefaults())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Bundled Studio UI (static export served under /studio) + root redirect.
                        .requestMatchers("/", "/studio/**").permitAll()
                        // Public endpoints
                        .requestMatchers(
                                "/auth/v1/signup",
                                "/auth/v1/token",
                                "/auth/v1/recover",
                                "/auth/v1/verify",
                                "/auth/v1/otp",
                                "/auth/v1/resend",
                                "/auth/v1/settings",
                                "/auth/v1/authorize",
                                "/auth/v1/callback",
                                "/auth/v1/sso/**",
                                "/auth/v1/health"
                        ).permitAll()
                        // Admin endpoints (access control enforced by the @RequireServiceRole AOP)
                        .requestMatchers("/auth/v1/admin/**", "/auth/v1/invite").permitAll()
                        // Platform developer account endpoints (Studio login); authenticated by PlatformAuthService itself
                        .requestMatchers("/auth/v1/platform/**").permitAll()
                        // PostgREST proxy endpoints (apikey handled in JwtAuthenticationFilter)
                        .requestMatchers("/rest/v1/**").permitAll()
                        // Storage endpoints (apikey handled in JwtAuthenticationFilter)
                        .requestMatchers("/storage/v1/**").permitAll()
                        // MCP service endpoints
                        .requestMatchers("/mcp/**").permitAll()
                        // AI Gateway DATA PLANE (authenticated by GatewayApiKeyAuthFilter via nbk_ keys)
                        .requestMatchers("/v1/**").permitAll()
                        .requestMatchers("/ai/**").permitAll()
                        .requestMatchers("/openai/**").permitAll()
                        // AI Gateway CONTROL PLANE (tenant resolved by UnifiedMultiTenancyFilter; @RequireServiceRole enforces role)
                        .requestMatchers("/ai-gateway/**").permitAll()
                        // Edge Functions (tenant resolved by UnifiedMultiTenancyFilter; admin endpoints use @RequireServiceRole)
                        .requestMatchers("/functions/v1/**", "/functions/admin/v1/**").permitAll()
                        // Memory service endpoints — authentication flow:
                        //   1. UnifiedMultiTenancyFilter validates the apikey and sets MultiTenancyContext
                        //   2. authenticateUser validates the Bearer token and writes to SecurityContextHolder
                        //   3. MemoryService.resolveScope enforces owner = JWT sub (non-service_role)
                        //   4. /mem/v1/reset additionally goes through the @RequireServiceRole AOP
                        .requestMatchers("/mem/v1/**").permitAll()
                        // Protected endpoints
                        .requestMatchers(
                                "/auth/v1/user",
                                "/auth/v1/logout"
                        ).authenticated()
                        .anyRequest().authenticated()
                )
                // Data-plane gateway-key auth must run BEFORE the Supabase-apikey tenant filter.
                .addFilterBefore(unifiedMultiTenancyFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(gatewayApiKeyAuthFilter, UnifiedMultiTenancyFilter.class);

        return http.build();
    }
}
