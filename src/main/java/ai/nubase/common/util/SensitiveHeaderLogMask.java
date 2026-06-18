package ai.nubase.common.util;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * MCP/HTTP 请求日志用 header 脱敏：保留 header 名便于排障，敏感值仅留首尾片段。
 */
public final class SensitiveHeaderLogMask {

    private static final Set<String> SENSITIVE_NAMES = Set.of(
            "apikey",
            "authorization",
            "cookie",
            "proxy-authorization",
            "x-api-key",
            "x-auth-token"
    );

    private SensitiveHeaderLogMask() {}

    /**
     * 从请求收集 header 并脱敏，专用于日志输出，不影响实际鉴权链路。
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
            headers.put(name, maskValue(name, request.getHeader(name)));
        }
        return headers;
    }

    static String maskValue(String headerName, String value) {
        if (!isSensitive(headerName)) {
            return value;
        }
        if (value == null || value.isBlank()) {
            return value;
        }
        if (value.length() <= 8) {
            return "***";
        }
        return value.substring(0, 4) + "..." + value.substring(value.length() - 4);
    }

    private static boolean isSensitive(String headerName) {
        return headerName != null && SENSITIVE_NAMES.contains(headerName.toLowerCase(Locale.ROOT));
    }
}
