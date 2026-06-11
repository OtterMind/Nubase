package ai.nubase.functions.executor;

import ai.nubase.functions.util.EdgeFunctionHeaders;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shared HTTP plumbing for executors that forward invocations over HTTP
 * (request building, header filtering, size-limited response reading, and the
 * lazily built OkHttp client).
 */
public abstract class AbstractHttpEdgeFunctionExecutor implements EdgeFunctionExecutor {

    protected final EdgeFunctionExecutorProperties properties;
    private volatile OkHttpClient client;

    protected AbstractHttpEdgeFunctionExecutor(EdgeFunctionExecutorProperties properties) {
        this.properties = properties;
    }

    protected OkHttpClient httpClient() {
        OkHttpClient existing = client;
        if (existing != null) return existing;
        synchronized (this) {
            if (client == null) {
                Duration timeout = Duration.ofMillis(Math.max(1, properties.getTimeoutMs()));
                client = new OkHttpClient.Builder()
                        .connectTimeout(timeout)
                        .readTimeout(timeout)
                        .writeTimeout(timeout)
                        .callTimeout(timeout.plusMillis(500))
                        .build();
            }
            return client;
        }
    }

    protected OkHttpClient httpClient(Integer timeoutSeconds) {
        if (timeoutSeconds == null) return httpClient();
        Duration timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        return httpClient().newBuilder()
                .connectTimeout(timeout)
                .readTimeout(timeout)
                .writeTimeout(timeout)
                .callTimeout(timeout.plusMillis(500))
                .build();
    }

    /** Builds {@code <baseUrl>/<projectRef>/<slug><rawSuffix>?<rawQuery>} without re-encoding. */
    protected String buildUrl(String baseUrl, EdgeFunctionInvocationRequest request) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .pathSegment(request.projectRef(), request.functionSlug());
        if (StringUtils.hasText(request.path())) {
            String path = request.path().startsWith("/") ? request.path().substring(1) : request.path();
            if (!path.isBlank()) builder.path("/").path(path);
        }
        if (StringUtils.hasText(request.queryString())) {
            builder.query(request.queryString());
        }
        return builder.build(true).toUriString();
    }

    protected RequestBody buildRequestBody(EdgeFunctionInvocationRequest request, byte[] body) {
        String method = request.method().toUpperCase(Locale.ROOT);
        if (method.equals("GET") || method.equals("HEAD")) return null;
        String contentType = firstHeader(request.headers(), "content-type");
        MediaType mediaType = contentType == null ? null : MediaType.parse(contentType);
        return RequestBody.create(body == null ? new byte[0] : body, mediaType);
    }

    protected void copyForwardableHeaders(EdgeFunctionInvocationRequest request, Request.Builder builder) {
        request.headers().forEach((name, values) -> {
            if (!shouldForwardHeader(name)) return;
            for (String value : values) {
                builder.addHeader(name, value);
            }
        });
    }

    protected String firstHeader(Map<String, List<String>> headers, String expected) {
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(expected) && !entry.getValue().isEmpty()) {
                return entry.getValue().get(0);
            }
        }
        return null;
    }

    protected boolean shouldForwardHeader(String name) {
        return !EdgeFunctionHeaders.REQUEST_BLOCKED.contains(name.toLowerCase(Locale.ROOT));
    }

    protected byte[] readBody(ResponseBody body) throws IOException {
        if (body == null) return new byte[0];
        long max = Math.max(0, properties.getMaxResponseBytes());
        long contentLength = body.contentLength();
        if (contentLength > max) {
            throw new IOException("Function response exceeds max-response-bytes");
        }
        try (InputStream in = body.byteStream();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > max) {
                    throw new IOException("Function response exceeds max-response-bytes");
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    protected Map<String, List<String>> toHeaderMap(Headers headers) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        for (String name : headers.names()) {
            map.put(name, headers.values(name));
        }
        return map;
    }
}
