package ai.nubase.functions.controller;

import ai.nubase.functions.executor.EdgeFunctionInvocationResponse;
import ai.nubase.functions.executor.EdgeFunctionExecutorProperties;
import ai.nubase.functions.service.EdgeFunctionExceptions.EdgeFunctionException;
import ai.nubase.functions.service.EdgeFunctionInvocationService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@RestController
@RequiredArgsConstructor
public class EdgeFunctionGatewayController {

    private static final Set<String> RESPONSE_BLOCKED_HEADERS = Set.of(
            "connection",
            "keep-alive",
            "transfer-encoding",
            "upgrade",
            "proxy-authenticate",
            "proxy-authorization"
    );

    private final EdgeFunctionInvocationService invocationService;
    private final EdgeFunctionExecutorProperties properties;

    @RequestMapping("/functions/v1/{functionSlug}/**")
    public ResponseEntity<byte[]> invoke(HttpServletRequest request) throws IOException {
        String functionSlug = extractFunctionSlug(request);
        String suffix = extractSuffix(request, functionSlug);
        byte[] body = StreamUtils.copyToByteArray(request.getInputStream());
        if (body.length > properties.getMaxRequestBytes()) {
            byte[] error = "{\"code\":\"REQUEST_TOO_LARGE\",\"message\":\"Function request body is too large\"}"
                    .getBytes(StandardCharsets.UTF_8);
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .body(error);
        }
        try {
            EdgeFunctionInvocationResponse response = invocationService.invoke(functionSlug, suffix, body, request);
            return toResponseEntity(response);
        } catch (EdgeFunctionException e) {
            byte[] error = ("{\"code\":\"" + e.code() + "\",\"message\":\"" + escape(e.getMessage()) + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            return ResponseEntity.status(e.status())
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .body(error);
        }
    }

    private ResponseEntity<byte[]> toResponseEntity(EdgeFunctionInvocationResponse response) {
        HttpHeaders headers = new HttpHeaders();
        for (Map.Entry<String, List<String>> entry : response.headers().entrySet()) {
            String lower = entry.getKey().toLowerCase(Locale.ROOT);
            if (RESPONSE_BLOCKED_HEADERS.contains(lower)) continue;
            headers.put(entry.getKey(), entry.getValue());
        }
        HttpStatus status = HttpStatus.resolve(response.statusCode());
        return new ResponseEntity<>(response.body(), headers, status == null ? HttpStatus.BAD_GATEWAY : status);
    }

    private String extractFunctionSlug(HttpServletRequest request) {
        String path = request.getRequestURI();
        String prefix = "/functions/v1/";
        String rest = path.startsWith(prefix) ? path.substring(prefix.length()) : path;
        int slash = rest.indexOf('/');
        return slash >= 0 ? rest.substring(0, slash) : rest;
    }

    private String extractSuffix(HttpServletRequest request, String functionSlug) {
        Object best = request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        String path = best == null ? request.getRequestURI() : best.toString();
        String prefix = "/functions/v1/" + functionSlug;
        if (!path.startsWith(prefix)) return "";
        String suffix = path.substring(prefix.length());
        return suffix.startsWith("/") ? suffix : "";
    }

    private String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
