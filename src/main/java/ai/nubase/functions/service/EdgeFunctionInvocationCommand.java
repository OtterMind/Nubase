package ai.nubase.functions.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Transport-neutral description of one function invocation. The HTTP gateway
 * builds it from the servlet request; the cron scheduler builds it directly,
 * so the invocation service has no servlet dependency.
 *
 * @param callerRole one of service_role | authenticated | anon | cron — only
 *                   "anon" is rejected by verify_jwt functions
 */
public record EdgeFunctionInvocationCommand(
        String requestId,
        String method,
        String path,
        String queryString,
        Map<String, List<String>> headers,
        byte[] body,
        String callerRole,
        UUID callerUserId,
        Integer timeoutSeconds
) {

    public static final String ROLE_SERVICE = "service_role";
    public static final String ROLE_AUTHENTICATED = "authenticated";
    public static final String ROLE_ANON = "anon";
    public static final String ROLE_CRON = "cron";

    public EdgeFunctionInvocationCommand(
            String requestId,
            String method,
            String path,
            String queryString,
            Map<String, List<String>> headers,
            byte[] body,
            String callerRole,
            UUID callerUserId
    ) {
        this(requestId, method, path, queryString, headers, body, callerRole, callerUserId, null);
    }

    public boolean hasRecognizedRole() {
        return ROLE_SERVICE.equals(callerRole)
                || ROLE_AUTHENTICATED.equals(callerRole)
                || ROLE_ANON.equals(callerRole)
                || ROLE_CRON.equals(callerRole);
    }
}
