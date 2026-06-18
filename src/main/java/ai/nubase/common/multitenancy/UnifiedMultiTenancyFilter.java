package ai.nubase.common.multitenancy;

import ai.nubase.common.config.oauth.OAuthProperties;
import ai.nubase.auth.dto.oauth.OAuthStateData;
import ai.nubase.auth.entity.User;
import ai.nubase.auth.repository.UserRepository;
import ai.nubase.auth.service.JwtSecretService;
import ai.nubase.auth.service.OAuthStateService;
import ai.nubase.common.context.MultiTenancyContext;
import ai.nubase.common.util.SensitiveHeaderLogMask;
import ai.nubase.common.enums.DatabaseInitStatus;
import ai.nubase.common.enums.Role;
import ai.nubase.postgrest.multidb.DatabaseConfig;
import ai.nubase.postgrest.multidb.DatabaseConfigRepository;
import ai.nubase.postgrest.multidb.RoutingDataSource;
import cn.hutool.core.codec.Base64;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Unified multi-tenancy filter (database-level isolation).
 * <p>
 * This filter combines the functionality of the original JwtAuthenticationFilter, but uses
 * database-level multi-tenancy isolation.
 * <p>
 * Workflow:
 * 1. Extract the apikey (JWT) from the request.
 * 2. Parse the ref (app_code) from the JWT.
 * 3. Look up the unified tenant configuration by app_code (which contains database_key and
 *    schema_name).
 * 4. Look up the database configuration by database_key.
 * 5. Set DatabaseContext (used to dynamically route to the correct database).
 * 6. Set TenantContext (kept for backward compatibility; some auth module code may still rely
 *    on it).
 * 7. Validate the apikey signature.
 * 8. Optionally validate the user Bearer token.
 * <p>
 *
 * @author nubase
 * @since 2025-01-02
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UnifiedMultiTenancyFilter extends OncePerRequestFilter {
    private static final Logger MCP_LOG = LoggerFactory.getLogger("McpLogger");
    private final DatabaseConfigRepository databaseConfigRepository;
    private final RoutingDataSource routingDataSource;
    private final JwtSecretService jwtSecretService;
    private final OAuthStateService oauthStateService;
    private final UserRepository userRepository;

    private static final List<String> NON_FILTERED_PATHS = List.of(
            // Bundled Studio UI (static assets + client routes) — no apikey, no tenant context.
            "/studio",
            "/swagger-ui/",
            "/v3/api-docs",
            "/favicon.ico",
            "/actuator/",
            "/health",
            "/auth/v1/health",
            // AI gateway DATA PLANE: tenant is resolved by GatewayApiKeyAuthFilter from the
            // nbk_<appCode>_<secret> key, so the Supabase-apikey tenant filter must skip these.
            // NOTE: control plane lives under /ai-gateway/** (NOT matched by "/ai/") and DOES
            // go through this filter so the project's service_role apikey resolves the tenant.
            "/v1/",
            "/ai/",
            "/openai",
            // /mem/v1/** must go THROUGH this filter — it relies on MultiTenancyContext
            // for routing JDBC to the tenant DB and on the authenticateUser step for
            // user-id binding. Do NOT add it to this list.
//            "/mcp",
            "/auth/v1/admin/init/",       // Admin initialization endpoints, authenticated by a dedicated filter
            "/auth/v1/admin/projects",    // Cross-tenant project list, authenticated by AdminInitAuthFilter
            "/auth/v1/admin/platform/",   // Platform user management, authenticated by AdminInitAuthFilter
            "/auth/v1/platform/",         // Platform-level developer accounts (Studio login), separate JWT auth
            "/storage/v1/health"    // Storage health check, no authentication required
    );

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestPath = request.getRequestURI();
        if (!requestPath.contains("/auth/v1/health")) {
            log.info("Processing request (unified multitenancy): {} {}", request.getMethod(), requestPath);
        }

        // Root path redirects to the bundled Studio UI; no apikey, exact match only
        // (cannot be a startsWith entry — "/" would match everything).
        if ("/".equals(requestPath)) {
            filterChain.doFilter(request, response);
            return;
        }

        // Skip paths that should not be filtered
        for (String path : NON_FILTERED_PATHS) {
            if (requestPath.startsWith(path)) {
                filterChain.doFilter(request, response);
                return;
            }
        }
        ContentCachingRequestWrapper cachingRequest = new ContentCachingRequestWrapper(request);
        ContentCachingResponseWrapper cachingResponse = new ContentCachingResponseWrapper(response);
        try {
            // 1. Build the tenant context (extract app_code from the apikey)
            buildUnifiedContext(request);
            // 2. Handle user authentication (Bearer token)
            authenticateUser(request);
            // 3. Pass the request through
            log.debug("Unified multitenancy context setup successful for: {}", requestPath);
            filterChain.doFilter(cachingRequest, cachingResponse);
        } catch (Exception e) {
            log.error("Unified multitenancy context setup failed for {}: {}",
                    requestPath, e.getMessage(), e);
            if (requestPath.startsWith("/mcp")) {
                MCP_LOG.error("MCP Request processing failed: method={}, uri={}, error={}",
                        request.getMethod(), requestPath, e.getMessage(), e);
            }
            handleException(response, e.getMessage());
        } finally {
            if (requestPath.startsWith("/mcp")) {
                // MCP requests use a dedicated logger
                logRequest(cachingRequest);
                logResponse(cachingResponse, 100);
            }
            cachingResponse.copyBodyToResponse();
            // 4. Clear the ThreadLocal to prevent thread pollution
            MultiTenancyContext.clear();
        }
    }

    /**
     * Builds the unified multi-tenancy context.
     * <p>
     * Steps:
     * 1. Extract the apikey, or extract app_code from a second-level domain.
     * 2. Resolve app_code (from the ref claim or the second-level domain).
     * 3. Load tenant configuration (app_code -> database_key + schema_name).
     * 4. Load database configuration (database_key -> jdbc_url + credentials).
     * 5. Ensure the data source is created and registered with RoutingDataSource.
     * 6. Parse OAuth configuration.
     * 7. Set MultiTenancyContext (the unified context).
     * 8. Validate the apikey signature (except for OAuth callback).
     */
    private void buildUnifiedContext(HttpServletRequest request) {
        String requestPath = request.getRequestURI();
        String apikey = null;
        String appCode = null;
        String role = Role.AUTHENTICATED.getValue(); // Default role for OAuth callback
        boolean skipApikeyValidation = false;

        // Special handling: OAuth authorize request
        if ("/auth/v1/authorize".equals(requestPath)) {
            appCode = request.getParameter("app_code");
            if (StringUtils.isBlank(appCode)) {
                appCode = extractAppCodeFromDomain(request);
            }
            if (StringUtils.isNotBlank(appCode)) {
                DatabaseConfig databaseConfig = databaseConfigRepository.findByAppCode(appCode);
                if (databaseConfig != null) {
                    apikey = databaseConfig.getAuthenticatedToken();
                    log.debug("OAuth authorize: Using authenticated token for app_code={}", appCode);
                }
            }
        }

        // Special handling: OAuth callback request — derive appcode from the second-level domain and skip apikey validation
        if ("/auth/v1/callback".equals(requestPath)) {
            // Prefer to retrieve the apikey from state
            String state = request.getParameter("state");
            if (StringUtils.isNotBlank(state)) {
                OAuthStateData stateData = oauthStateService.getState(state);
                if (stateData != null) {
                    apikey = stateData.getApikey();
                    log.debug("OAuth callback: Retrieved apikey from state");
                }
            }

            // If state does not contain an apikey (likely expired), extract appcode from the second-level domain or Referer
            if (StringUtils.isBlank(apikey)) {
                appCode = resolveAppCodeFromDomainOrReferer(request);
                if (StringUtils.isNotBlank(appCode)) {
                    log.info("OAuth callback: Resolved app_code from domain/referer: {}", appCode);
                    skipApikeyValidation = true; // Skip apikey signature validation
                } else {
                    throw new IllegalArgumentException("Unable to extract app_code from domain for OAuth callback");
                }
            }
        }

        // Regular handling: obtain apikey from header or parameter
        if (StringUtils.isBlank(apikey) && StringUtils.isBlank(appCode)) {
            apikey = request.getHeader("Apikey");
            if (StringUtils.isBlank(apikey)) {
                apikey = request.getParameter("apikey");
            }
            if (StringUtils.isBlank(apikey)) {
                // Public / default Storage download paths, the Assets data plane and SAML SSO
                // endpoints (the IdP POSTs to the ACS without an apikey, like the OAuth
                // callback): resolve the appCode from the subdomain to allow apikey-free access.
                if (isPublicStoragePath(requestPath) || isPublicAssetsPath(requestPath)
                        || isSubdomainAuthPath(requestPath)) {
                    appCode = resolveAppCodeFromDomainOrReferer(request);
                    if (StringUtils.isNotBlank(appCode)) {
                        log.info("Subdomain-auth path: Resolved app_code from domain/referer: {}", appCode);
                        skipApikeyValidation = true;
                    } else {
                        throw new IllegalArgumentException("Apikey header is missing");
                    }
                } else {
                    throw new IllegalArgumentException("Apikey header is missing");
                }
            }
        }

        // 1. Parse the JWT to obtain app_code (ref claim) and role
        if (StringUtils.isNotBlank(apikey)) {
            JSONObject jsonObject = extractAppCodeAndRole(apikey);
            appCode = jsonObject.getStr("ref");
            role = jsonObject.getStr("role");

            if (StringUtils.isBlank(appCode) || StringUtils.isBlank(role)) {
                throw new IllegalArgumentException("Token missing 'ref' or 'role' claim");
            }
        }

        if (StringUtils.isBlank(appCode)) {
            throw new IllegalArgumentException("Unable to determine app_code");
        }

        // 2. Load tenant configuration (app_code -> database_key + schema_name)
        DatabaseConfig databaseConfig = databaseConfigRepository.findByAppCode(appCode);
        if (databaseConfig == null || !databaseConfig.isAvailable()) {
            throw new IllegalArgumentException("Invalid app_code or tenant not enabled: " + appCode);
        }

        // 3. Validate the apikey signature (only when required)
        if (!skipApikeyValidation && StringUtils.isNotBlank(apikey)) {
            String jwtSecret = databaseConfig.getJwtSecret();
            try {
                SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
                Jwts.parser().setSigningKey(key).build().parseClaimsJws(apikey);
                log.debug("Apikey signature validated successfully");
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid or expired Apikey: " + e.getMessage());
            }
        } else if (skipApikeyValidation) {
            log.info("Skipping apikey validation (subdomain auth), app_code={}", appCode);
            // Use the authenticated token as the default apikey (for OAuth callback and public subdomain access)
            apikey = databaseConfig.getAuthenticatedToken();
        }

        String databaseKey = databaseConfig.getDbKey();
        String schemaName = databaseConfig.getSchemaName();

        log.info("Tenant routing: app_code={} -> database_key={}, schema={}", appCode, databaseKey, schemaName);

        // 4. Ensure the data source has been created and registered
        if (DatabaseInitStatus.INITIALIZED.name().equals(databaseConfig.getInitStatus())) {
            if (!routingDataSource.hasDataSource(databaseKey)) {
                log.info("Creating and registering DataSource for database: {}", databaseKey);
                routingDataSource.initializeDataSource(databaseConfig);
            }
            // Record the access time for lifecycle management to avoid idle eviction
            routingDataSource.recordAccess(databaseKey);
        }

        // 5. Check whether the request uses the service_role key
        boolean isServiceRole = Role.fromString(role).isServiceRole();

        // 6. Parse the OAuth configuration (if any)
        OAuthProperties oauthProperties = null;
        if (StringUtils.isNotBlank(databaseConfig.getOauthConfig())) {
            try {
                oauthProperties = JSONUtil.parseObj(databaseConfig.getOauthConfig()).toBean(OAuthProperties.class);
            } catch (Exception e) {
                log.warn("Failed to parse OAuth config for app_code: {}", appCode, e);
            }
        }

        // 6b. Parse the per-tenant auth settings override (if any)
        ai.nubase.common.config.TenantAuthConfig tenantAuthConfig = null;
        if (StringUtils.isNotBlank(databaseConfig.getAuthConfigJson())) {
            try {
                tenantAuthConfig = JSONUtil.parseObj(databaseConfig.getAuthConfigJson())
                        .toBean(ai.nubase.common.config.TenantAuthConfig.class);
            } catch (Exception e) {
                log.warn("Failed to parse auth_config for app_code: {}", appCode, e);
            }
        }

        // 7. Set the unified MultiTenancyContext
        MultiTenancyContext.ContextData contextData = MultiTenancyContext.ContextData.builder()
                // Common fields
                .appCode(appCode).schemaName(schemaName).jwtSecret(databaseConfig.getJwtSecret()).jwtSecretKey(Keys.hmacShaKeyFor(databaseConfig.getJwtSecret().getBytes(StandardCharsets.UTF_8)))
                // Database-level isolation fields
                .databaseKey(databaseKey).databaseConfig(databaseConfig)
                // Auth-related fields
                .apikey(apikey).serviceRole(isServiceRole).oauthProperties(oauthProperties)
                .tenantAuthConfig(tenantAuthConfig).build();

        MultiTenancyContext.setContext(contextData);

        log.debug("MultiTenancyContext set: app_code={}, database_key={}, schema={}, service_role={}", appCode, databaseKey, schemaName, isServiceRole);
    }

    /**
     * Determines whether the request path is a publicly accessible Storage path.
     * Includes: public object downloads, public object info, public image rendering and
     * the default download path.
     */
    private static final List<String> PUBLIC_STORAGE_PREFIXES = List.of(
            "/storage/v1/object/public/",
            "/storage/v1/object/info/public/",
            "/storage/v1/render/image/public/"
    );

    /**
     * SAML SSO endpoints that resolve the tenant from the request subdomain rather than an
     * apikey: the ACS (IdP POST), SP metadata, and SP-initiated SSO start.
     */
    private boolean isSubdomainAuthPath(String requestPath) {
        return requestPath.startsWith("/auth/v1/sso");
    }

    /**
     * Assets data plane (/assets/v1/**): public static asset delivery, no apikey needed.
     * The control plane (/assets/admin/v1/**) does NOT match this prefix and keeps
     * requiring the project's service_role apikey.
     */
    private boolean isPublicAssetsPath(String requestPath) {
        return requestPath.startsWith("/assets/v1/");
    }

    private boolean isPublicStoragePath(String requestPath) {
        for (String prefix : PUBLIC_STORAGE_PREFIXES) {
            if (requestPath.startsWith(prefix)) {
                return true;
            }
        }
        // Default download path: GET /storage/v1/object/{bucket}/** (excluding special prefixes such as public/authenticated/sign/upload)
        return requestPath.startsWith("/storage/v1/object/") && !requestPath.startsWith("/storage/v1/object/public/")
                && !requestPath.startsWith("/storage/v1/object/authenticated/")
                && !requestPath.startsWith("/storage/v1/object/sign/")
                && !requestPath.startsWith("/storage/v1/object/upload/")
                && !requestPath.startsWith("/storage/v1/object/info/")
                && !"/storage/v1/object/move".equals(requestPath)
                && !"/storage/v1/object/copy".equals(requestPath)
                && !requestPath.startsWith("/storage/v1/object/list/")
                && !"/storage/v1/object/list".equals(requestPath);
    }

    /**
     * Extracts app_code from the request's domain (second-level domain).
     * For example: app20260108111430zrjprnycyi.nubase.co -> app20260108111430zrjprnycyi
     */
    private String extractAppCodeFromDomain(HttpServletRequest request) {
        String serverName = request.getServerName();
        int firstDot = serverName.indexOf('.');
        if (firstDot > 0) {
            String appCode = serverName.substring(0, firstDot);
            log.debug("Extracted app_code from domain: {} -> {}", serverName, appCode);
            return appCode;
        }
        return null;
    }

    /**
     * Resolves a valid appCode from the request's own Host/subdomain only.
     *
     * <p>Deliberately does NOT fall back to the {@code Referer} header: Referer is attacker-controllable,
     * so resolving the tenant from it would let a malicious page select which tenant's context these
     * apikey-free endpoints (OAuth callback, public storage, SSO) run against.
     */
    private String resolveAppCodeFromDomainOrReferer(HttpServletRequest request) {
        String appCode = extractAppCodeFromDomain(request);
        if (StringUtils.isNotBlank(appCode)) {
            DatabaseConfig config = databaseConfigRepository.findByAppCode(appCode);
            if (config != null) {
                return appCode;
            }
            log.debug("Domain appCode '{}' not found in database", appCode);
        }
        return null;
    }

    /**
     * Handles user authentication (Bearer token).
     * An optional second authentication layer.
     */
    private void authenticateUser(HttpServletRequest request) {
        String token = extractToken(request);
        if (token == null) return;

        try {
            // Validate the token with the JWT secret in TenantContext
            Claims claims = jwtSecretService.validateToken(token);
            String userId = claims.getSubject();

            if (userId != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                // Load the full User object from the database.
                // Note: because of database-level isolation, the query routes to the correct
                // database (via MultiTenancyContext).
                User user = userRepository.findById(UUID.fromString(userId)).orElse(null);

                if (user != null) {
                    String role = claims.get("role", String.class);
                    SimpleGrantedAuthority authority = new SimpleGrantedAuthority("ROLE_" + (role != null ? role.toUpperCase() : "USER"));

                    // Use the User object as the principal (rather than a userId String)
                    UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(user, null, Collections.singletonList(authority));
                    auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(auth);

                    log.debug("User authenticated: userId={}, email={}, role={}", user.getId(), user.getEmail(), role);
                } else {
                    log.warn("User not found in database: userId={}", userId);
                }
            }
        } catch (Exception e) {
            log.warn("User authentication failed: {}", e.getMessage());
            // User authentication failure usually does not block the request (it may target a public endpoint)
        }
    }

    /**
     * Extracts app_code (ref claim) and role from the JWT.
     */
    private JSONObject extractAppCodeAndRole(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) {
                throw new IllegalArgumentException("Invalid JWT format");
            }

            String payload = Base64.decodeStr(parts[1]);
            return JSONUtil.parseObj(payload);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse Apikey payload: " + e.getMessage());
        }
    }

    /**
     * Extracts the Bearer token from the Authorization header.
     */
    private String extractToken(HttpServletRequest request) {
        String bearer = request.getHeader("Authorization");
        if (bearer != null && bearer.startsWith("Bearer ")) {
            return bearer.substring(7);
        }
        return null;
    }

    /**
     * Handles an exception and returns a 401 response.
     */
    private void handleException(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(JSONUtil.toJsonStr(new JSONObject().set("error", message)));
    }

    private void logRequest(ContentCachingRequestWrapper request) {
        byte[] content = request.getContentAsByteArray();
        String body = content.length > 0 ? new String(content, StandardCharsets.UTF_8) : null;

        MCP_LOG.info("MCP Request: method={}, uri={}, headers={}, body={}",
                request.getMethod(), request.getRequestURI(), SensitiveHeaderLogMask.collectMasked(request), body);

    }

    private void logResponse(ContentCachingResponseWrapper response, long duration) {
        byte[] content = response.getContentAsByteArray();
        String body = content.length > 0 ? new String(content, StandardCharsets.UTF_8) : null;

        MCP_LOG.info("MCP Response: status={}, body={}, duration={}ms", response.getStatus(), body, duration);
    }
}
