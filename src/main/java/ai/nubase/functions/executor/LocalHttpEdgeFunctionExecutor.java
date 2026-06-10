package ai.nubase.functions.executor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class LocalHttpEdgeFunctionExecutor implements EdgeFunctionExecutor {

    private final EdgeFunctionExecutorProperties properties;
    private volatile OkHttpClient client;

    @Override
    public String provider() {
        return "local";
    }

    @Override
    public EdgeFunctionDeploymentResponse deploy(EdgeFunctionDeploymentRequest request) {
        String deploymentId = request.projectRef() + "/" + request.functionSlug();
        return EdgeFunctionDeploymentResponse.deployed(provider(), deploymentId);
    }

    @Override
    public void delete(String projectRef, String functionSlug, String providerDeploymentId) {
        log.debug("Local edge function delete is a no-op: projectRef={}, slug={}, deploymentId={}",
                projectRef, functionSlug, providerDeploymentId);
    }

    @Override
    public EdgeFunctionInvocationResponse invoke(EdgeFunctionInvocationRequest request) {
        String url = buildUrl(request);
        RequestBody body = buildRequestBody(request);
        Request.Builder builder = new Request.Builder().url(url).method(request.method(), body);

        request.headers().forEach((name, values) -> {
            if (!shouldForwardHeader(name)) return;
            for (String value : values) {
                builder.addHeader(name, value);
            }
        });
        builder.header("x-nubase-request-id", request.requestId());
        builder.header("x-nubase-project-ref", request.projectRef());
        builder.header("x-nubase-function-slug", request.functionSlug());
        request.env().forEach((key, value) -> builder.header("x-nubase-env-" + key.toLowerCase(Locale.ROOT), value));

        try (Response response = httpClient().newCall(builder.build()).execute()) {
            byte[] responseBytes = readBody(response.body());
            return new EdgeFunctionInvocationResponse(
                    response.code(),
                    toHeaderMap(response.headers()),
                    responseBytes,
                    null,
                    null
            );
        } catch (IOException e) {
            log.warn("Local edge function invocation failed: projectRef={}, slug={}, error={}",
                    request.projectRef(), request.functionSlug(), e.toString());
            return EdgeFunctionInvocationResponse.error(502, "EXECUTOR_ERROR", e.getMessage());
        }
    }

    private OkHttpClient httpClient() {
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

    private String buildUrl(EdgeFunctionInvocationRequest request) {
        String base = properties.getLocal().getBaseUrl();
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(base)
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

    private RequestBody buildRequestBody(EdgeFunctionInvocationRequest request) {
        String method = request.method().toUpperCase(Locale.ROOT);
        if (method.equals("GET") || method.equals("HEAD")) return null;
        byte[] bytes = request.body() == null ? new byte[0] : request.body();
        String contentType = firstHeader(request.headers(), "content-type");
        MediaType mediaType = contentType == null ? null : MediaType.parse(contentType);
        return RequestBody.create(bytes, mediaType);
    }

    private String firstHeader(Map<String, List<String>> headers, String expected) {
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(expected) && !entry.getValue().isEmpty()) {
                return entry.getValue().get(0);
            }
        }
        return null;
    }

    private boolean shouldForwardHeader(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return !lower.equals("host")
                && !lower.equals("connection")
                && !lower.equals("keep-alive")
                && !lower.equals("transfer-encoding")
                && !lower.equals("upgrade")
                && !lower.equals("proxy-authenticate")
                && !lower.equals("proxy-authorization")
                && !lower.equals("apikey");
    }

    private byte[] readBody(ResponseBody body) throws IOException {
        if (body == null) return new byte[0];
        byte[] bytes = body.bytes();
        if (bytes.length > properties.getMaxResponseBytes()) {
            throw new IOException("Function response exceeds max-response-bytes");
        }
        return bytes;
    }

    private Map<String, List<String>> toHeaderMap(Headers headers) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        for (String name : headers.names()) {
            map.put(name, headers.values(name));
        }
        return map;
    }
}
