package ai.nubase.functions.service;

import ai.nubase.functions.util.EdgeFunctionHeaders;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
@ConditionalOnProperty(value = "nubase.functions.enabled", havingValue = "true", matchIfMissing = true)
public class HeaderSanitizer {

    public Map<String, List<String>> forwardableHeaders(HttpServletRequest request) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            String lower = name.toLowerCase(Locale.ROOT);
            if (EdgeFunctionHeaders.REQUEST_BLOCKED.contains(lower)) continue;
            List<String> values = new ArrayList<>();
            Enumeration<String> rawValues = request.getHeaders(name);
            while (rawValues.hasMoreElements()) {
                values.add(rawValues.nextElement());
            }
            headers.put(name, values);
        }
        return headers;
    }
}
