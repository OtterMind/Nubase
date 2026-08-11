package ai.nubase.platform.mcp;

import ai.nubase.common.multitenancy.PlatformAdminPaths;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Slf4j
@Component
@Order(2)
@ConditionalOnProperty(prefix = "nubase.platform-mcp", name = "enabled", havingValue = "true")
public class PlatformMcpAutomationAuthFilter extends OncePerRequestFilter {

    public static final String PRINCIPAL_ATTRIBUTE =
            PlatformMcpAutomationAuthFilter.class.getName() + ".principal";
    private static final String SAFE_PATH = "/platform/mcp";
    private static final int MAX_ORIGIN_LENGTH = 2048;

    private final AutomationJwtVerifier jwtVerifier;
    private final Set<NormalizedOrigin> allowedOrigins;

    public PlatformMcpAutomationAuthFilter(
            AutomationJwtVerifier jwtVerifier, PlatformMcpProperties properties) {
        this.jwtVerifier = jwtVerifier;
        this.allowedOrigins = normalizeConfiguredOrigins(properties.getAllowedOrigins());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !PlatformAdminPaths.isPlatformMcpPath(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (!originAllowed(request)) {
            SecurityContextHolder.clearContext();
            log.warn("Platform MCP origin rejected for {}", SAFE_PATH);
            writeForbidden(response);
            return;
        }
        log.info("Processing platform MCP request: {} {}", request.getMethod(), SAFE_PATH);
        AutomationPrincipal principal;
        try {
            String token = bearerToken(request.getHeader("Authorization"));
            principal = jwtVerifier.verify(token);
        } catch (Exception e) {
            SecurityContextHolder.clearContext();
            log.warn("Platform MCP authentication failed for {}: errorType={}",
                    SAFE_PATH, e.getClass().getSimpleName());
            writeUnauthorized(response);
            return;
        }

        request.setAttribute(PRINCIPAL_ATTRIBUTE, principal);
        var authorities = principal.scopes().stream()
                .map(SimpleGrantedAuthority::new)
                .toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, authorities));
        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static String bearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new PlatformMcpAuthenticationException();
        }
        String token = authorization.substring(7).trim();
        if (token.isEmpty()) {
            throw new PlatformMcpAuthenticationException();
        }
        return token;
    }

    private boolean originAllowed(HttpServletRequest request) {
        List<String> origins = Collections.list(request.getHeaders("Origin"));
        if (origins.isEmpty()) {
            return true;
        }
        if (origins.size() != 1) {
            return false;
        }
        try {
            return allowedOrigins.contains(normalizeOrigin(origins.get(0)));
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static Set<NormalizedOrigin> normalizeConfiguredOrigins(List<String> configured) {
        Set<NormalizedOrigin> normalized = new HashSet<>();
        if (configured == null) {
            return Set.of();
        }
        try {
            for (String value : configured) {
                if (value != null && !value.isBlank()) {
                    normalized.add(normalizeOrigin(value));
                }
            }
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException(
                    "nubase.platform-mcp.allowed-origins contains an invalid origin");
        }
        return Set.copyOf(normalized);
    }

    private static NormalizedOrigin normalizeOrigin(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_ORIGIN_LENGTH) {
            throw new IllegalArgumentException("Invalid Origin");
        }
        try {
            URI uri = URI.create(value.strip());
            String scheme = uri.getScheme() == null
                    ? ""
                    : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = canonicalHost(uri.getHost());
            String path = uri.getRawPath();
            int port = uri.getPort();
            if (!("http".equals(scheme) || "https".equals(scheme))
                    || host.isBlank()
                    || uri.isOpaque()
                    || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || !(path == null || path.isEmpty() || "/".equals(path))
                    || port == 0
                    || port > 65535
                    || (uri.getRawAuthority() != null && uri.getRawAuthority().endsWith(":"))) {
                throw new IllegalArgumentException("Invalid Origin");
            }
            if (port == -1) {
                port = "https".equals(scheme) ? 443 : 80;
            }
            return new NormalizedOrigin(scheme, host, port);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid Origin");
        }
    }

    private static String canonicalHost(String value) {
        String host = value == null ? "" : value.toLowerCase(Locale.ROOT);
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        return host;
    }

    private static void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(
                "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32001,"
                        + "\"message\":\"Unauthorized\"}}");
    }

    private static void writeForbidden(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(
                "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32003,"
                        + "\"message\":\"Forbidden\"}}");
    }

    private record NormalizedOrigin(String scheme, String host, int port) {
    }
}
