package ai.nubase.postgrest.auth;

import ai.nubase.common.context.MultiTenancyContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Applies the transaction-local PostgreSQL role and request GUCs that PostgREST
 * functions expect. Shared by HTTP /rest/v1 and background cron RPC execution.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PostgrestRequestContext {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public void apply(String role, HttpServletRequest request, Claims claims) {
        setDatabaseRole(role);
        setRequestContext(request, claims);
    }

    public void applySynthetic(String role, String path, String method) {
        setDatabaseRole(role);
        try {
            setGucVariable("request.jwt.claims", "{}");
            setGucVariable("request.headers", "{}");
            setGucVariable("request.cookies", "{}");
            setGucVariable("request.path", path == null ? "" : path);
            setGucVariable("request.method", method == null ? "POST" : method);
        } catch (Exception e) {
            log.warn("Failed to set synthetic PostgREST request context: {}", e.getMessage());
        }
    }

    public void resetDatabaseRole() {
        try {
            jdbcTemplate.execute("RESET ROLE");
            log.debug("Reset database role");
        } catch (Exception e) {
            log.warn("Failed to reset database role: {}", e.getMessage());
        }
    }

    private void setDatabaseRole(String role) {
        // The tenant connection runs as the table OWNER (db_user), which bypasses RLS.
        // Fail closed if the requested role cannot be established.
        if (role == null || !role.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
            throw new IllegalStateException("Refusing to set an invalid database role: " + role);
        }
        try {
            jdbcTemplate.execute(String.format("SET LOCAL ROLE %s", quote(role)));
            log.debug("Set database role to: {}", role);
        } catch (Exception e) {
            log.error("Failed to set database role {} — aborting request to avoid an RLS bypass: {}",
                    role, e.getMessage());
            throw new IllegalStateException("Could not establish the database role for this request", e);
        }
    }

    private void setRequestContext(HttpServletRequest request, Claims claims) {
        try {
            if (claims != null) {
                setGucVariable("request.jwt.claims", objectMapper.writeValueAsString(claims));
            }

            Map<String, String> headers = new HashMap<>();
            java.util.Enumeration<String> headerNames = request.getHeaderNames();
            while (headerNames.hasMoreElements()) {
                String headerName = headerNames.nextElement();
                headers.put(headerName.toLowerCase(), request.getHeader(headerName));
            }
            setGucVariable("request.headers", objectMapper.writeValueAsString(headers));

            Map<String, String> cookies = new HashMap<>();
            if (request.getCookies() != null) {
                for (jakarta.servlet.http.Cookie cookie : request.getCookies()) {
                    cookies.put(cookie.getName(), cookie.getValue());
                }
            }
            setGucVariable("request.cookies", objectMapper.writeValueAsString(cookies));
            setGucVariable("request.path", request.getRequestURI());
            setGucVariable("request.method", request.getMethod());
        } catch (Exception e) {
            log.warn("Failed to set request context: {}", e.getMessage());
        }
    }

    private void setGucVariable(String name, String value) {
        try {
            String escapedValue = value.replace("'", "''");
            jdbcTemplate.execute(String.format("SELECT set_config('%s', '%s', true)", name, escapedValue));
        } catch (Exception e) {
            log.warn("Failed to set GUC variable {}: {}", name, e.getMessage());
        }
    }

    private String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
