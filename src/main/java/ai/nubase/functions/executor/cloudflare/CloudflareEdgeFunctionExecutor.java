package ai.nubase.functions.executor.cloudflare;

import ai.nubase.functions.executor.EdgeFunctionDeploymentRequest;
import ai.nubase.functions.executor.EdgeFunctionDeploymentResponse;
import ai.nubase.functions.executor.EdgeFunctionExecutor;
import ai.nubase.functions.executor.EdgeFunctionExecutorProperties;
import ai.nubase.functions.executor.EdgeFunctionInvocationRequest;
import ai.nubase.functions.executor.EdgeFunctionInvocationResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class CloudflareEdgeFunctionExecutor implements EdgeFunctionExecutor {

    private final EdgeFunctionExecutorProperties properties;
    private final ObjectMapper objectMapper;
    private volatile OkHttpClient client;

    @Override
    public String provider() {
        return "cloudflare";
    }

    @Override
    public EdgeFunctionDeploymentResponse deploy(EdgeFunctionDeploymentRequest request) {
        validateConfigForDeploy();
        String deploymentId = workerName(request.projectRef(), request.functionSlug());
        try {
            uploadWorkerScript(request, deploymentId);
            return EdgeFunctionDeploymentResponse.deployed(provider(), deploymentId);
        } catch (Exception e) {
            log.warn("Cloudflare edge function deployment failed: projectRef={}, slug={}, error={}",
                    request.projectRef(), request.functionSlug(), e.toString());
            return EdgeFunctionDeploymentResponse.failed(provider(), e.getMessage());
        }
    }

    @Override
    public void delete(String projectRef, String functionSlug, String providerDeploymentId) {
        validateConfigForDeploy();
        if (!StringUtils.hasText(providerDeploymentId)) return;
        String url = properties.getCloudflare().getApiBaseUrl()
                + "/accounts/" + properties.getCloudflare().getAccountId()
                + "/workers/dispatch/namespaces/" + properties.getCloudflare().getDispatchNamespace()
                + "/scripts/" + providerDeploymentId;
        Request request = new Request.Builder()
                .url(url)
                .delete()
                .header("Authorization", "Bearer " + properties.getCloudflare().getApiToken())
                .build();
        try (Response response = httpClient().newCall(request).execute()) {
            if (!response.isSuccessful() && response.code() != 404) {
                log.warn("Cloudflare edge function delete failed: projectRef={}, slug={}, deploymentId={}, status={}",
                        projectRef, functionSlug, providerDeploymentId, response.code());
            }
        } catch (IOException e) {
            log.warn("Cloudflare edge function delete failed: projectRef={}, slug={}, deploymentId={}, error={}",
                    projectRef, functionSlug, providerDeploymentId, e.toString());
        }
    }

    @Override
    public EdgeFunctionInvocationResponse invoke(EdgeFunctionInvocationRequest request) {
        validateConfigForInvoke();
        byte[] body = request.body() == null ? new byte[0] : request.body();
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String signature = sign(request, body, timestamp);
        String url = buildUrl(request);

        RequestBody requestBody = buildRequestBody(request, body);
        Request.Builder builder = new Request.Builder().url(url).method(request.method(), requestBody);
        request.headers().forEach((name, values) -> {
            if (!shouldForwardHeader(name)) return;
            values.forEach(value -> builder.addHeader(name, value));
        });
        builder.header("x-nubase-request-id", request.requestId());
        builder.header("x-nubase-project-ref", request.projectRef());
        builder.header("x-nubase-function-slug", request.functionSlug());
        builder.header("x-nubase-deployment-id", request.providerDeploymentId());
        builder.header("x-nubase-timestamp", timestamp);
        builder.header("x-nubase-signature", signature);

        try (Response response = httpClient().newCall(builder.build()).execute()) {
            return new EdgeFunctionInvocationResponse(
                    response.code(),
                    toHeaderMap(response.headers()),
                    readBody(response.body()),
                    null,
                    null
            );
        } catch (IOException e) {
            log.warn("Cloudflare edge function invocation failed: projectRef={}, slug={}, error={}",
                    request.projectRef(), request.functionSlug(), e.toString());
            return EdgeFunctionInvocationResponse.error(502, "CLOUDFLARE_EXECUTOR_ERROR", e.getMessage());
        }
    }

    private void validateConfigForDeploy() {
        EdgeFunctionExecutorProperties.Cloudflare cf = properties.getCloudflare();
        if (!StringUtils.hasText(cf.getAccountId())
                || !StringUtils.hasText(cf.getDispatchNamespace())
                || !StringUtils.hasText(cf.getApiToken())) {
            throw new IllegalStateException("Cloudflare account-id, dispatch-namespace and api-token are required");
        }
    }

    private void uploadWorkerScript(EdgeFunctionDeploymentRequest request, String deploymentId) throws Exception {
        SourceBundle bundle = decodeSourceBundle(request.sourceBundleBase64());
        String entrypoint = loadEntrypoint(bundle);
        String workerModule = buildWorkerModule(request, transpileEntrypoint(entrypoint));
        String metadata = objectMapper.writeValueAsString(Map.of(
                "main_module", "index.js",
                "compatibility_date", "2026-06-01",
                "bindings", cloudflareBindings(request)
        ));
        RequestBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("metadata", null, RequestBody.create(metadata, MediaType.parse("application/json")))
                .addFormDataPart("index.js", "index.js", RequestBody.create(workerModule, MediaType.parse("application/javascript+module")))
                .build();
        String url = properties.getCloudflare().getApiBaseUrl()
                + "/accounts/" + properties.getCloudflare().getAccountId()
                + "/workers/dispatch/namespaces/" + properties.getCloudflare().getDispatchNamespace()
                + "/scripts/" + deploymentId;
        Request httpRequest = new Request.Builder()
                .url(url)
                .put(body)
                .header("Authorization", "Bearer " + properties.getCloudflare().getApiToken())
                .build();
        executeCloudflareUpload(httpRequest);
    }

    private void executeCloudflareUpload(Request request) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try (Response response = httpClient().newCall(request).execute()) {
                if (response.code() == 429 || response.code() >= 500) {
                    String body = response.body() == null ? "" : response.body().string();
                    last = new IOException("Cloudflare upload failed (" + response.code() + "): " + body);
                    sleepBeforeRetry(attempt);
                    continue;
                }
                assertCloudflareSuccess(response);
                return;
            }
        }
        throw last != null ? last : new IOException("Cloudflare upload failed");
    }

    private void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(100L * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private List<Map<String, String>> cloudflareBindings(EdgeFunctionDeploymentRequest request) {
        List<Map<String, String>> bindings = new ArrayList<>();
        bindings.add(Map.of("type", "plain_text", "name", "NUBASE_PROJECT_REF", "text", request.projectRef()));
        bindings.add(Map.of("type", "plain_text", "name", "NUBASE_FUNCTION_NAME", "text", request.functionSlug()));
        if (request.env() != null) {
            request.env().forEach((name, value) -> {
                if (StringUtils.hasText(name) && value != null) {
                    bindings.add(Map.of("type", "secret_text", "name", name, "text", value));
                }
            });
        }
        return bindings;
    }

    private void assertCloudflareSuccess(Response response) throws IOException {
        String body = response.body() == null ? "" : response.body().string();
        if (!response.isSuccessful()) {
            throw new IOException("Cloudflare upload failed (" + response.code() + "): " + body);
        }
        if (StringUtils.hasText(body)) {
            Map<?, ?> parsed = objectMapper.readValue(body, Map.class);
            Object success = parsed.get("success");
            if (Boolean.FALSE.equals(success)) {
                throw new IOException("Cloudflare upload failed: " + body);
            }
        }
    }

    private SourceBundle decodeSourceBundle(String sourceBundleBase64) throws IOException {
        if (!StringUtils.hasText(sourceBundleBase64)) {
            throw new IOException("sourceBundleBase64 is required for Cloudflare deployments");
        }
        String json = new String(Base64.getDecoder().decode(sourceBundleBase64), StandardCharsets.UTF_8);
        SourceBundle bundle = objectMapper.readValue(json, SourceBundle.class);
        if (bundle.files() == null || bundle.files().isEmpty()) {
            throw new IOException("Source bundle has no files");
        }
        return bundle;
    }

    private String loadEntrypoint(SourceBundle bundle) throws IOException {
        for (SourceBundleFile file : bundle.files()) {
            if ("index.ts".equals(file.path()) || "index.js".equals(file.path())) {
                return new String(Base64.getDecoder().decode(file.content()), StandardCharsets.UTF_8);
            }
        }
        throw new IOException("Source bundle must contain index.ts or index.js");
    }

    private String buildWorkerModule(EdgeFunctionDeploymentRequest request, String entrypoint) {
        return """
                const __module = {};
                const __exports = {};
                const __envDefaults = {
                  NUBASE_PROJECT_REF: %s,
                  NUBASE_FUNCTION_NAME: %s
                };
                %s
                const userDefault = typeof __exports.default !== "undefined" ? __exports.default : (typeof exports !== "undefined" ? exports.default : undefined);
                export default {
                  async fetch(request, env, ctx) {
                    const mergedEnv = Object.assign({}, __envDefaults, env || {});
                    if (globalThis.default && typeof globalThis.default.fetch === "function") {
                      return globalThis.default.fetch(request, mergedEnv, ctx);
                    }
                    if (userDefault && typeof userDefault.fetch === "function") {
                      return userDefault.fetch(request, mergedEnv, ctx);
                    }
                    return new Response("Nubase function must export default.fetch", { status: 500 });
                  }
                };
                """.formatted(
                jsonString(request.projectRef()),
                jsonString(request.functionSlug()),
                stripEsmDefault(entrypoint)
        );
    }

    private String stripEsmDefault(String source) {
        return source
                .replaceFirst("export\\s+default\\s+", "globalThis.default = ")
                .replaceFirst("export\\s+\\{\\s*default\\s*\\}\\s*;", "");
    }

    private String transpileEntrypoint(String source) {
        // This is intentionally narrow. The Cloudflare upload API accepts JavaScript modules;
        // Wrangler normally compiles TypeScript, but Nubase uploads directly. Support the
        // scaffold's common annotations without pretending to be a full TS compiler.
        return source
                .replace(": Request", "")
                .replace(": Record<string, string>", "")
                .replace(": Record<string,string>", "");
    }

    private String jsonString(String value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void validateConfigForInvoke() {
        EdgeFunctionExecutorProperties.Cloudflare cf = properties.getCloudflare();
        if (!StringUtils.hasText(cf.getDispatcherUrl()) || !StringUtils.hasText(cf.getDispatcherSecret())) {
            throw new IllegalStateException("Cloudflare dispatcher-url and dispatcher-secret are required");
        }
    }

    private String buildUrl(EdgeFunctionInvocationRequest request) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromHttpUrl(properties.getCloudflare().getDispatcherUrl())
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

    private RequestBody buildRequestBody(EdgeFunctionInvocationRequest request, byte[] body) {
        String method = request.method().toUpperCase(Locale.ROOT);
        if (method.equals("GET") || method.equals("HEAD")) return null;
        String contentType = firstHeader(request.headers(), "content-type");
        MediaType mediaType = contentType == null ? null : MediaType.parse(contentType);
        return RequestBody.create(body, mediaType);
    }

    private String sign(EdgeFunctionInvocationRequest request, byte[] body, String timestamp) {
        try {
            String bodyHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
            String payload = request.requestId() + "\n"
                    + request.projectRef() + "\n"
                    + request.functionSlug() + "\n"
                    + request.method().toUpperCase(Locale.ROOT) + "\n"
                    + (request.path() == null ? "" : request.path()) + "\n"
                    + (request.queryString() == null ? "" : request.queryString()) + "\n"
                    + timestamp + "\n"
                    + bodyHash;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.getCloudflare().getDispatcherSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign Cloudflare invocation", e);
        }
    }

    private String workerName(String projectRef, String slug) {
        String raw = "nubase-" + projectRef + "-" + slug;
        String safe = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]+", "-").replaceAll("-+", "-");
        if (safe.length() <= 63) return safe;
        String hash = HexFormat.of().formatHex(sha256(safe)).substring(0, 12);
        return safe.substring(0, 50).replaceAll("-+$", "") + "-" + hash;
    }

    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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
}
