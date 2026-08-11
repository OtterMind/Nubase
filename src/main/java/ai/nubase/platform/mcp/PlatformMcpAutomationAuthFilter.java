package ai.nubase.platform.mcp;

import ai.nubase.common.multitenancy.PlatformAdminPaths;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
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

@Slf4j
@Component
@Order(2)
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "nubase.platform-mcp", name = "enabled", havingValue = "true")
public class PlatformMcpAutomationAuthFilter extends OncePerRequestFilter {

    public static final String PRINCIPAL_ATTRIBUTE =
            PlatformMcpAutomationAuthFilter.class.getName() + ".principal";
    private static final String SAFE_PATH = "/platform/mcp";

    private final AutomationJwtVerifier jwtVerifier;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !PlatformAdminPaths.isPlatformMcpPath(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
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

    private static void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(
                "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32001,"
                        + "\"message\":\"Unauthorized\"}}");
    }
}
