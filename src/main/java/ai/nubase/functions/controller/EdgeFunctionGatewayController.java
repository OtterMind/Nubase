package ai.nubase.functions.controller;

import ai.nubase.common.util.RequestUtil;
import ai.nubase.functions.executor.EdgeFunctionExecutorProperties;
import ai.nubase.functions.executor.EdgeFunctionInvocationResponse;
import ai.nubase.functions.service.EdgeFunctionExceptions.EdgeFunctionException;
import ai.nubase.functions.service.EdgeFunctionInvocationService;
import ai.nubase.functions.util.EdgeFunctionHeaders;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(value = "nubase.functions.enabled", havingValue = "true", matchIfMissing = true)
public class EdgeFunctionGatewayController {

    private final EdgeFunctionInvocationService invocationService;
    private final EdgeFunctionExecutorProperties properties;

    // EdgeFunctionException is rendered by EdgeFunctionExceptionHandler — no
    // error-shaping here, so the JSON contract has a single owner.
    @RequestMapping("/functions/v1/{functionSlug}/**")
    public ResponseEntity<byte[]> invoke(HttpServletRequest request) throws IOException {
        String functionSlug = extractFunctionSlug(request);
        String suffix = extractSuffix(request, functionSlug);
        byte[] body = readBody(request);
        EdgeFunctionInvocationResponse response = invocationService.invoke(functionSlug, suffix, body, request);
        return toResponseEntity(response);
    }

    private byte[] readBody(HttpServletRequest request) throws IOException {
        try {
            // Streams with an early size-limit abort instead of buffering an
            // arbitrarily large body before checking it.
            return RequestUtil.readRawRequestBody(request, properties.getMaxRequestBytes());
        } catch (IllegalArgumentException e) {
            throw new EdgeFunctionException(HttpStatus.PAYLOAD_TOO_LARGE, "REQUEST_TOO_LARGE", "Function request body is too large");
        }
    }

    private ResponseEntity<byte[]> toResponseEntity(EdgeFunctionInvocationResponse response) {
        HttpHeaders headers = new HttpHeaders();
        for (Map.Entry<String, List<String>> entry : response.headers().entrySet()) {
            String lower = entry.getKey().toLowerCase(Locale.ROOT);
            if (EdgeFunctionHeaders.RESPONSE_BLOCKED.contains(lower)) continue;
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
}
