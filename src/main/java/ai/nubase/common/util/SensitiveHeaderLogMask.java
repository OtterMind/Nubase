package ai.nubase.common.util;

import ai.nubase.ai.gateway.util.SensitiveHeaderSanitizer;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sanitizes HTTP headers for MCP runtime logs without retaining caller-controlled values.
 */
public final class SensitiveHeaderLogMask {

    private SensitiveHeaderLogMask() {}

    /**
     * Keeps only the existing diagnostic allowlist and replaces every retained value with a
     * presence marker. Unknown headers fail closed and are omitted.
     */
    public static Map<String, String> collectMasked(HttpServletRequest request) {
        if (request == null) {
            return Collections.emptyMap();
        }
        Map<String, String> headers = new LinkedHashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        if (names == null) {
            return headers;
        }
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            headers.put(name, "");
        }
        return SensitiveHeaderSanitizer.sanitizeForPersistence(headers);
    }
}
