package ai.nubase.common.multitenancy;

import ai.nubase.auth.service.PlatformAuthService;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Dedicated authentication filter for admin initialization endpoints.
 * <p>
 * Purpose:
 * - Provides a standalone authentication mechanism for /auth/v1/admin/init/* endpoints.
 * - These endpoints are called before tenant databases are initialized, so the regular
 *   multi-tenancy authentication cannot be used.
 * - Authenticates using the metadata database's service_role_key.
 * <p>
 * Security notes:
 * - METADATA_SERVICE_ROLE_KEY must be supplied via an environment variable in production.
 * - This key must be kept secret and only configured on the server side.
 * - Do not expose this key in client code or version control.
 *
 * @author nubase
 * @since 2025-01-03
 */
@Slf4j
@Component
@Order(1) // Executes before UnifiedMultiTenancyFilter
public class AdminInitAuthFilter extends OncePerRequestFilter {

    public static final String METADATA_ROOT_AUTHENTICATED_ATTRIBUTE =
            AdminInitAuthFilter.class.getName() + ".metadataRootAuthenticated";

    /** The default placeholder shipped in application.yml — must never be the live value in prod. */
    private static final String PLACEHOLDER_KEY = "replace-me-with-a-real-jwt-signed-by-master-key";

    @Value("${pgrst.multidb.metadata.service-role-key}")
    private String metadataServiceRoleKey;

    @Autowired
    private Environment environment;

    /**
     * Lazy injection avoids a startup cycle (PlatformAuthService depends on the JPA stack,
     * which is initialised after the filter chain).
     */
    @Autowired
    @Lazy
    private PlatformAuthService platformAuthService;

    /**
     * Refuse to boot outside the {@code dev} profile when the metadata service-role key is missing or
     * still the public placeholder. The key is accepted as a plain bearer for the cross-tenant admin
     * endpoints, so a known default value would grant any caller super-admin access.
     */
    @PostConstruct
    void validateConfiguredKey() {
        boolean dev = Arrays.asList(environment.getActiveProfiles()).contains("dev");
        if (dev) {
            return;
        }
        if (StringUtils.isBlank(metadataServiceRoleKey) || PLACEHOLDER_KEY.equals(metadataServiceRoleKey)) {
            throw new IllegalStateException(
                    "METADATA_SERVICE_ROLE_KEY is unset or still the placeholder. Set a strong, secret "
                    + "value before running outside the 'dev' profile — the default is publicly known and "
                    + "would grant cross-tenant admin access to anyone.");
        }
    }

    /** Constant-time comparison so the metadata key can't be recovered via timing. */
    private boolean keyMatches(String candidate) {
        if (candidate == null || StringUtils.isBlank(metadataServiceRoleKey)
                || PLACEHOLDER_KEY.equals(metadataServiceRoleKey)) {
            return false;
        }
        return MessageDigest.isEqual(
                metadataServiceRoleKey.getBytes(StandardCharsets.UTF_8),
                candidate.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestPath = request.getRequestURI();

        if (!PlatformAdminPaths.isPlatformAdminPath(requestPath)) {
            filterChain.doFilter(request, response);
            return;
        }

        log.info("Admin init authentication for: {}", requestPath);

        try {
            // 1. Extract the authentication token
            AuthToken authToken = extractAuthToken(request);

            if (authToken == null || StringUtils.isBlank(authToken.value())) {
                log.warn("Admin init request without authentication: {}", requestPath);
                sendUnauthorizedResponse(response, "Missing authentication token");
                return;
            }

            // 2. Validate the token — first try the platform JWT (if the path allows it),
            //    then fall back to a string equality check against the metadata service-role-key.
            //    Resolve the caller's identity AND super-admin scope here, so downstream
            //    controllers never have to re-load the user or special-case a null user id.
            boolean accepted = false;
            boolean isSuperAdmin = false;
            java.util.UUID resolvedUserId = null;
            if (PlatformAdminPaths.acceptsPlatformJwt(requestPath)) {
                try {
                    PlatformAuthService.PlatformPrincipal principal =
                            platformAuthService.resolvePrincipal(authToken.value());
                    resolvedUserId = principal.userId();
                    isSuperAdmin = principal.superAdmin();
                    accepted = true;
                    log.debug("Platform JWT accepted for {}, user_id={}, superAdmin={}",
                            requestPath, principal.userId(), isSuperAdmin);
                } catch (Exception jwtFailure) {
                    log.debug("Platform JWT validation failed, falling back to service-role-key match: {}", jwtFailure.getMessage());
                }
            }
            if (!accepted && authToken.allowsMetadataKey() && keyMatches(authToken.value())) {
                accepted = true;
                isSuperAdmin = true; // metadata service-role key → root / super-admin scope
                // The root key is not a human user; it acts as the reserved system user so that
                // downstream code always sees a concrete platformUserId (never null).
                resolvedUserId = PlatformAuthService.SYSTEM_USER_ID;
                request.setAttribute(METADATA_ROOT_AUTHENTICATED_ATTRIBUTE, Boolean.TRUE);
            }
            if (!accepted) {
                log.warn("Admin init request with invalid token: {}", requestPath);
                sendUnauthorizedResponse(response, "Invalid service role key");
                return;
            }
            if (PlatformAdminPaths.requiresSuperAdmin(requestPath) && !isSuperAdmin) {
                log.warn("Platform admin request without super-admin scope: {}", requestPath);
                sendUnauthorizedResponse(response, "Super admin privileges are required");
                return;
            }
            // Single source of truth for the caller identity + super-admin decision. Downstream
            // controllers can rely on platformUserId being non-null (SYSTEM_USER_ID for the root
            // key); a null platformUserId in business code now signals a bug, not the metadata path.
            request.setAttribute("platformUserId", resolvedUserId);
            request.setAttribute("platformIsSuperAdmin", isSuperAdmin);

            // 3. Authentication succeeded, continue processing
            log.info("Admin init authentication successful for: {}", requestPath);
            filterChain.doFilter(request, response);

        } catch (Exception e) {
            log.error("Admin init authentication failed for {}: {}", requestPath, e.getMessage(), e);
            sendUnauthorizedResponse(response, "Authentication failed: " + e.getMessage());
        }
    }

    /**
     * Extracts the authentication token from the request.
     * Supported sources:
     * 1. Authorization: Bearer <token>
     * 2. apikey header, for legacy bundled Studio platform JWT requests only.
     *
     * <p>The metadata service-role key is intentionally accepted only from Authorization. This keeps
     * high-privilege server credentials out of the tenant-style apikey channel while preserving
     * compatibility with existing Studio builds that send the platform user JWT as {@code apikey}.
     */
    private AuthToken extractAuthToken(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (StringUtils.isNotBlank(authHeader)) {
            if (authHeader.startsWith("Bearer ")) {
                return new AuthToken(authHeader.substring(7), true);
            }
            return new AuthToken(authHeader, true);
        }

        String apikey = request.getHeader("apikey");
        if (StringUtils.isNotBlank(apikey)) {
            return new AuthToken(apikey, false);
        }

        return null;
    }

    private record AuthToken(String value, boolean allowsMetadataKey) {
    }

    /**
     * Sends a 401 Unauthorized response.
     */
    private void sendUnauthorizedResponse(HttpServletResponse response, String message) throws IOException {
        log.info("Sending unauthorized response: {}", message);
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        String jsonResponse = String.format(
                "{\"error\":\"Unauthorized\",\"message\":\"%s\",\"status\":401}",
                message
        );

        response.getWriter().write(jsonResponse);
        response.getWriter().flush();
    }
}
