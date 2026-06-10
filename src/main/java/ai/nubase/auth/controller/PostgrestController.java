package ai.nubase.auth.controller;

import ai.nubase.common.context.MultiTenancyContext;
import ai.nubase.common.enums.Role;
import ai.nubase.postgrest.api.ApiRequest;
import ai.nubase.postgrest.api.ApiRequestParser;
import ai.nubase.postgrest.api.Preferences;
import ai.nubase.postgrest.auth.AuthResult;
import ai.nubase.postgrest.query.QueryExecutor;
import ai.nubase.postgrest.query.QueryPlan;
import ai.nubase.postgrest.query.QueryPlanner;
import ai.nubase.postgrest.query.QueryResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * PostgREST direct invocation controller.
 * <p>
 * Keeps the same interface and path (/rest/v1/**) as PostgRESTProxyController,
 * but internally calls local Java code directly (QueryPlanner, QueryExecutor)
 * instead of forwarding via HTTP.
 * <p>
 * Workflow:
 * 1. Front end sends a request to /rest/v1/** with an apikey (JWT Token).
 * 2. Retrieve schema and JWT secret from TenantContext.
 * 3. Validate the user's Authorization header (if present).
 * 4. Parse the request with ApiRequestParser.
 * 5. Plan the query with QueryPlanner.
 * 6. Set the PostgreSQL role and request context.
 * 7. Execute the query with QueryExecutor.
 * 8. Return the query result.
 *
 * @author nubase
 * @since 2025-01-02
 */
@RestController
@RequestMapping("/rest/v1")
@RequiredArgsConstructor
@Slf4j
public class PostgrestController {

    private static final String APIKEY_HEADER = "apikey";

    private final ApiRequestParser requestParser;
    private final QueryPlanner queryPlanner;
    private final QueryExecutor queryExecutor;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    // ==================== HTTP method entry points ====================

    @GetMapping("/**")
    @Transactional
    public ResponseEntity<?> handleGet(
            @RequestHeader(value = APIKEY_HEADER, required = false) String apikey,
            HttpServletRequest request,
            HttpServletResponse response) {
        return handleRequest(request, response);
    }

    @PostMapping("/**")
    @Transactional
    public ResponseEntity<?> handlePost(
            @RequestHeader(value = APIKEY_HEADER, required = false) String apikey,
            HttpServletRequest request,
            HttpServletResponse response) {
        return handleRequest(request, response);
    }

    @PutMapping("/**")
    @Transactional
    public ResponseEntity<?> handlePut(
            @RequestHeader(value = APIKEY_HEADER, required = false) String apikey,
            HttpServletRequest request,
            HttpServletResponse response) {
        return handleRequest(request, response);
    }

    @PatchMapping("/**")
    @Transactional
    public ResponseEntity<?> handlePatch(
            @RequestHeader(value = APIKEY_HEADER, required = false) String apikey,
            HttpServletRequest request,
            HttpServletResponse response) {
        return handleRequest(request, response);
    }

    @DeleteMapping("/**")
    @Transactional
    public ResponseEntity<?> handleDelete(
            @RequestHeader(value = APIKEY_HEADER, required = false) String apikey,
            HttpServletRequest request,
            HttpServletResponse response) {
        return handleRequest(request, response);
    }

    @RequestMapping(value = "/**", method = RequestMethod.OPTIONS)
    public ResponseEntity<?> handleOptions(HttpServletResponse response) {
        response.setHeader("Access-Control-Allow-Origin", "*");
        response.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, DELETE, OPTIONS");
        response.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization, Prefer, Range, apikey");
        response.setHeader("Access-Control-Expose-Headers", "Content-Range, Content-Location");
        return ResponseEntity.ok().build();
    }

    // ==================== Core processing logic ====================

    /**
     * Handle all PostgREST requests.
     * Note: the transaction is managed by the entry method that calls this one
     * (handleGet/handlePost/etc.).
     */
    public ResponseEntity<?> handleRequest(HttpServletRequest request, HttpServletResponse response) {
        log.info("=== Received direct invocation request === Method: {}, URI: {}, QueryString: {}",
                request.getMethod(), request.getRequestURI(), request.getQueryString());

        try {
            // 1. Retrieve tenant info from TenantContext (already set by JwtAuthenticationFilter)
            MultiTenancyContext.ContextData contextInfo = MultiTenancyContext.getContext();
            if (contextInfo == null) {
                log.error("TenantContext not set, request rejected");
                return errorResponse(HttpStatus.UNAUTHORIZED, "Missing tenant context");
            }

            String schema = resolveSchema(request, contextInfo);
            String jwtSecret = contextInfo.getJwtSecret();

            // 2. Check whether this is an RPC request
            String uri = request.getRequestURI();
            if (uri.contains("/rpc/")) {
                return handleRpcRequest(request, response, schema, jwtSecret);
            }

            // 3. Extract the table name (from the URI path)
            String table = extractTableName(request);
            if (table == null || table.isEmpty()) {
                return errorResponse(HttpStatus.BAD_REQUEST, "Table name is required");
            }

            // 4. Determine role and authentication info
            AuthResult authResult = authenticateRequest(request, jwtSecret);

            // 5. Set PostgreSQL role and request context
            setDatabaseRole(authResult.getRole());
            setRequestContext(request, authResult.getClaims());

            // 6. Parse the request
            ApiRequest apiRequest = requestParser.parse(request, schema, table);

            log.info("Handling request: method={}, schema={}, table={}, role={}",
                    request.getMethod(), schema, table, authResult.getRole());

            // 7. Plan the query
            QueryPlan plan = queryPlanner.plan(apiRequest);

            // 8. Execute the query
            QueryResult result = queryExecutor.execute(
                    plan,
                    apiRequest.getBody(),
                    apiRequest.getPreferences()
            );

            // 9. Build the response
            return buildResponse(result, apiRequest, response);

        } catch (Exception e) {
            log.error("Failed to handle PostgREST direct invocation request: method={}, uri={}",
                    request.getMethod(), request.getRequestURI(), e);
            // 2. Get the most specific underlying error description
            return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, NestedExceptionUtils.getMostSpecificCause(e).getMessage());
        } finally {
            // Reset role
            resetDatabaseRole();
        }
    }

    /**
     * Handle RPC (stored procedure / function) requests.
     * PostgREST format: /rest/v1/rpc/function_name
     */
    private ResponseEntity<?> handleRpcRequest(HttpServletRequest request, HttpServletResponse response,
                                               String schema, String jwtSecret) {
        try {
            // 1. Extract the function name
            String functionName = extractFunctionName(request);
            if (functionName == null || functionName.isEmpty()) {
                return errorResponse(HttpStatus.BAD_REQUEST, "Function name is required");
            }

            // 2. Validate the HTTP method
            // PostgREST rules:
            // - GET/HEAD: can only call IMMUTABLE or STABLE functions
            // - POST: can call any function (including VOLATILE)
            String method = request.getMethod();
            if (!"GET".equals(method) && !"HEAD".equals(method) && !"POST".equals(method)) {
                return errorResponse(HttpStatus.METHOD_NOT_ALLOWED,
                        "RPC only supports GET, HEAD, and POST methods");
            }

            // 3. Determine role and authentication info
            AuthResult authResult = authenticateRequest(request, jwtSecret);

            // 4. Set PostgreSQL role and request context
            setDatabaseRole(authResult.getRole());
            setRequestContext(request, authResult.getClaims());

            // 5. Parse the RPC request
            ApiRequest apiRequest = requestParser.parseRpc(request, schema, functionName);

            log.info("Handling RPC request: method={}, schema={}, function={}, role={}",
                    method, schema, functionName, authResult.getRole());

            // 6. Plan the query
            QueryPlan plan = queryPlanner.plan(apiRequest);

            // 7. Execute the query
            QueryResult result = queryExecutor.execute(
                    plan,
                    apiRequest.getBody(),
                    apiRequest.getPreferences()
            );

            // 8. Build the response
            return buildResponse(result, apiRequest, response);

        } catch (Exception e) {
            log.error("Failed to handle RPC request: method={}, uri={}",
                    request.getMethod(), request.getRequestURI(), e);
            return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "RPC execution failed: " + e.getMessage());
        }
    }

    /**
     * Extract the function name from the URI.
     * Example: /rest/v1/rpc/my_function -> my_function
     */
    private String extractFunctionName(HttpServletRequest request) {
        String uri = request.getRequestURI();

        // Find the position of /rpc/
        int rpcIndex = uri.indexOf("/rpc/");
        if (rpcIndex < 0) {
            return null;
        }

        String path = uri.substring(rpcIndex + 5); // skip "/rpc/"

        // Strip the query string
        int queryIndex = path.indexOf('?');
        if (queryIndex > 0) {
            path = path.substring(0, queryIndex);
        }

        // Strip trailing slashes
        path = path.replaceAll("/+$", "");

        return path;
    }

    // ==================== Helper methods ====================

    /**
     * Resolve the target schema.
     * GET/HEAD use the Accept-Profile header for overrides; write operations use Content-Profile.
     * Only SQL identifier syntax validation is performed to prevent injection; real access
     * authorization is enforced by PostgreSQL roles + GRANTs
     * (PostgrestController issues SET LOCAL ROLE to switch to the caller's role before executing).
     */
    private static final java.util.regex.Pattern SCHEMA_NAME_PATTERN =
            java.util.regex.Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,62}$");

    private String resolveSchema(HttpServletRequest request, MultiTenancyContext.ContextData ctx) {
        String defaultSchema = ctx.getSchemaName();
        String method = request.getMethod();
        String headerName = "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)
                ? "Accept-Profile"
                : "Content-Profile";
        String requested = request.getHeader(headerName);
        if (requested == null || requested.isBlank()) {
            return defaultSchema;
        }
        requested = requested.trim();
        if (!SCHEMA_NAME_PATTERN.matcher(requested).matches()) {
            log.warn("Rejected {} header value '{}' (invalid identifier); falling back to {}",
                    headerName, requested, defaultSchema);
            return defaultSchema;
        }
        log.debug("Schema overridden via {} header: {}", headerName, requested);
        return requested;
    }

    /**
     * Extract the table name from the URI.
     * Examples:
     *   /rest/v1/users -> users
     *   /rest/v1/posts -> posts
     */
    private String extractTableName(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String path = uri.replace("/rest/v1/", "").replace("/rest/v1", "");

        // Strip the query string
        int queryIndex = path.indexOf('?');
        if (queryIndex > 0) {
            path = path.substring(0, queryIndex);
        }

        // Strip leading and trailing slashes
        path = path.replaceAll("^/+|/+$", "");

        // If the path contains a slash, take the first part as the table name
        int slashIndex = path.indexOf('/');
        if (slashIndex > 0) {
            path = path.substring(0, slashIndex);
        }

        return path.strip();
    }

    /**
     * Authenticate the request.
     * Prefer the JWT in the Authorization header; otherwise use the default anon role.
     */
    private AuthResult authenticateRequest(HttpServletRequest request, String jwtSecret) {
        String authHeader = request.getHeader("Authorization");
        String role = Role.ANON.getValue(); // default role
        Claims claims = null;

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            try {
                // Validate and parse the token with the tenant's JWT secret
                SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
                claims = Jwts.parser()
                        .setSigningKey(key)
                        .build()
                        .parseClaimsJws(token)
                        .getBody();

                // Extract the role from the JWT
                Object roleValue = claims.get("role");
                if (roleValue != null) {
                    role = roleValue.toString();
                    log.info("Resolved role from Authorization JWT: {}", role);
                } else {
                    // If the JWT has no role but has sub (user ID), use the authenticated role
                    if (claims.getSubject() != null) {
                        role = Role.AUTHENTICATED.getValue();
                        log.info("JWT has no role; falling back to authenticated role");
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to parse Authorization JWT; using default role: {}", e.getMessage());
            }
        }

        // Check whether the caller is service_role
        if (MultiTenancyContext.isServiceRole()) {
            role = Role.SERVICE_ROLE.getValue();
            log.info("Using service_role");
        }

        return new AuthResult(role, true, claims, null);
    }

    /**
     * Set the PostgreSQL database role.
     */
    private void setDatabaseRole(String role) {
        // The tenant connection runs as the table OWNER (db_user), which bypasses RLS. RLS is only
        // enforced once we switch to a non-owner role (authenticated/anon/service_role). If that switch
        // fails we MUST NOT run the query as the owner — fail closed rather than leaking every row.
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

    /**
     * Reset the PostgreSQL database role.
     */
    private void resetDatabaseRole() {
        try {
            jdbcTemplate.execute("RESET ROLE");
            log.debug("Reset database role");
        } catch (Exception e) {
            log.warn("Failed to reset database role: {}", e.getMessage());
        }
    }

    /**
     * Set the request context as PostgreSQL session variables.
     * Matches PostgREST's behavior: https://postgrest.org/en/stable/references/transactions.html
     * <p>
     * Sets the following GUC variables (all are transaction-scoped):
     * - request.jwt.claims - JWT token claims as JSON
     * - request.headers - HTTP headers as JSON
     * - request.cookies - Cookies as JSON
     * - request.path - Request path (e.g., "/users")
     * - request.method - HTTP method (e.g., "GET")
     */
    private void setRequestContext(HttpServletRequest request, Claims claims) {
        try {
            // 1. Set JWT claims
            if (claims != null) {
                String claimsJson = objectMapper.writeValueAsString(claims);
                setGucVariable("request.jwt.claims", claimsJson);
                log.debug("Set JWT claims: {}", claimsJson);
            }

            // 2. Set HTTP headers as JSON
            Map<String, String> headers = new HashMap<>();
            java.util.Enumeration<String> headerNames = request.getHeaderNames();
            while (headerNames.hasMoreElements()) {
                String headerName = headerNames.nextElement();
                // Lowercase header names to match PostgREST
                headers.put(headerName.toLowerCase(), request.getHeader(headerName));
            }
            String headersJson = objectMapper.writeValueAsString(headers);
            setGucVariable("request.headers", headersJson);
            log.debug("Set request headers");

            // 3. Set cookies as JSON
            Map<String, String> cookies = new HashMap<>();
            if (request.getCookies() != null) {
                for (jakarta.servlet.http.Cookie cookie : request.getCookies()) {
                    cookies.put(cookie.getName(), cookie.getValue());
                }
            }
            String cookiesJson = objectMapper.writeValueAsString(cookies);
            setGucVariable("request.cookies", cookiesJson);
            log.debug("Set request cookies");

            // 4. Set the request path
            String path = request.getRequestURI();
            setGucVariable("request.path", path);
            log.debug("Set request path: {}", path);

            // 5. Set the HTTP method
            String method = request.getMethod();
            setGucVariable("request.method", method);
            log.debug("Set request method: {}", method);

        } catch (Exception e) {
            log.warn("Failed to set request context: {}", e.getMessage());
        }
    }

    /**
     * Set a PostgreSQL GUC (Grand Unified Configuration) variable.
     * Uses set_config() with is_local=true so the variable is transaction-scoped.
     */
    private void setGucVariable(String name, String value) {
        try {
            // Escape single quotes for SQL
            String escapedValue = value.replace("'", "''");

            // set_config(setting_name, new_value, is_local)
            // is_local=true makes it a SET LOCAL (transaction-scoped, auto-reset after the transaction)
            String sql = String.format("SELECT set_config('%s', '%s', true)", name, escapedValue);
            jdbcTemplate.execute(sql);
        } catch (Exception e) {
            log.warn("Failed to set GUC variable {}: {}", name, e.getMessage());
        }
    }

    /**
     * Build the response.
     * Supports PostgREST's Accept header formats.
     */
    private ResponseEntity<?> buildResponse(
            QueryResult result,
            ApiRequest apiRequest,
            HttpServletResponse response
    ) throws IOException {

        // Set CORS headers
        response.setHeader("Access-Control-Allow-Origin", "*");

        // Set the Content-Range header for pagination
        if (apiRequest.getRange() != null) {
            long start = apiRequest.getRange().getStart() != null ?
                    apiRequest.getRange().getStart() : 0;
            long end = start + (result.getData() != null ? result.getData().size() : 0) - 1;

            String contentRange = String.format("%s %d-%d/%s",
                    apiRequest.getRange().getUnit(),
                    start,
                    Math.max(start, end),
                    result.getTotalCount() != null ? result.getTotalCount() : "*"
            );

            response.setHeader("Content-Range", contentRange);
        }

        // Handle preferences
        if (apiRequest.getPreferences() != null) {
            Preferences.ReturnPreference returnPref = apiRequest.getPreferences().getReturnPreference();

            if (returnPref == Preferences.ReturnPreference.MINIMAL) {
                response.setHeader("Preference-Applied", "return=minimal");
                return ResponseEntity.status(
                        "POST".equals(apiRequest.getMethod()) ? HttpStatus.CREATED : HttpStatus.NO_CONTENT
                ).build();
            } else if (returnPref == Preferences.ReturnPreference.HEADERS_ONLY) {
                response.setHeader("Preference-Applied", "return=headers-only");
                return ResponseEntity.noContent().build();
            }
        }

        // Inspect the Accept header to decide the response format
        String acceptHeader = apiRequest.getHeaders().get("accept");
        if (acceptHeader == null) {
            acceptHeader = "application/json"; // default format
        }

        // Handle the different Accept header formats
        Object responseData;
        MediaType contentType;

        if (acceptHeader.contains("application/vnd.pgrst.object+json")) {
            // Return a single object instead of an array
            if (result.getData() != null && !result.getData().isEmpty()) {
                responseData = result.getData().get(0);
            } else {
                responseData = null;
            }
            contentType = MediaType.parseMediaType("application/vnd.pgrst.object+json");
            log.debug("Accept header: application/vnd.pgrst.object+json, returning single object");
        }
//        else if (acceptHeader.contains("application/vnd.pgrst.plan+json")) {
//            // Return the query plan (when available)
//            // TODO: implement query plan return
//            responseData = Map.of(
//                    "plan", "Query plan not yet implemented",
//                    "query", result.toString()
//            );
//            contentType = MediaType.parseMediaType("application/vnd.pgrst.plan+json");
//            log.debug("Accept header: application/vnd.pgrst.plan+json, returning query plan");
//        }
        else {
            // Default: return an array
            responseData = result.getData();
            contentType = MediaType.APPLICATION_JSON;
        }

//        // If no data is available and a single object was requested, return 406 Not Acceptable
//        if (acceptHeader.contains("application/vnd.pgrst.object+json") &&
//                (result.getData() == null || result.getData().isEmpty())) {
//            return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE)
//                    .contentType(MediaType.APPLICATION_JSON)
//                    .body("{\"error\":\"No rows returned, cannot return single object\"}");
//        }

        // Serialize the response data as JSON
        String json = objectMapper.writeValueAsString(responseData);

        return ResponseEntity.ok()
                .contentType(contentType)
                .body(json);
    }

    /**
     * Quote a PostgreSQL identifier (table or column name) to handle special characters.
     * Follows the PostgreSQL standard: https://www.postgresql.org/docs/current/sql-syntax-lexical.html
     * The implementation matches PostgREST's escapeIdent function.
     */
    private String quote(String identifier) {
        if (identifier == null) {
            throw new IllegalArgumentException("Identifier cannot be null");
        }
        // Strip null characters (safety measure, matches PostgREST)
        String trimmed = trimNullChars(identifier);
        // Escape internal double quotes (by doubling them)
        String escaped = trimmed.replace("\"", "\"\"");
        // Wrap with double quotes
        return "\"" + escaped + "\"";
    }

    /**
     * Strip null characters from a string.
     * PostgreSQL does not allow null bytes in identifiers.
     * Matches PostgREST's trimNullChars function.
     */
    private String trimNullChars(String str) {
        int nullIndex = str.indexOf('\0');
        return nullIndex >= 0 ? str.substring(0, nullIndex) : str;
    }

    /**
     * Build an error response.
     */
    private ResponseEntity<String> errorResponse(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"" + message + "\"}");
    }

    /**
     * Error response record.
     */
    public record ErrorResponse(String message) {
    }
}
