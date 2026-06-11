package ai.nubase.functions.util;

import java.util.Set;

/**
 * Single source of truth for headers that must not cross the gateway/executor
 * boundary. Referenced by the gateway controller, HeaderSanitizer and the HTTP
 * executors so the lists cannot drift apart.
 */
public final class EdgeFunctionHeaders {

    /** Hop-by-hop headers plus Nubase credentials, stripped from forwarded requests. */
    public static final Set<String> REQUEST_BLOCKED = Set.of(
            "host",
            "connection",
            "keep-alive",
            "transfer-encoding",
            "upgrade",
            "proxy-authenticate",
            "proxy-authorization",
            "apikey"
    );

    /** Hop-by-hop headers stripped from function responses before they leave the gateway. */
    public static final Set<String> RESPONSE_BLOCKED = Set.of(
            "connection",
            "keep-alive",
            "transfer-encoding",
            "upgrade",
            "proxy-authenticate",
            "proxy-authorization"
    );

    private EdgeFunctionHeaders() {
    }
}
