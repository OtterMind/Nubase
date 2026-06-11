package ai.nubase.functions.executor.cloudflare;

import ai.nubase.functions.executor.AbstractHttpEdgeFunctionExecutor;
import ai.nubase.functions.executor.EdgeFunctionDeploymentRequest;
import ai.nubase.functions.executor.EdgeFunctionDeploymentResponse;
import ai.nubase.functions.executor.EdgeFunctionExecutorProperties;
import ai.nubase.functions.executor.EdgeFunctionInvocationRequest;
import ai.nubase.functions.executor.EdgeFunctionInvocationResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.time.Instant;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Component
@ConditionalOnProperty(value = "nubase.functions.enabled", havingValue = "true", matchIfMissing = true)
public class CloudflareEdgeFunctionExecutor extends AbstractHttpEdgeFunctionExecutor {

    // Bound as plain_text at deploy time; user secrets must not collide with them.
    private static final java.util.Set<String> RESERVED_ENV_NAMES =
            java.util.Set.of("NUBASE_PROJECT_REF", "NUBASE_FUNCTION_NAME");

    private final ObjectMapper objectMapper;

    public CloudflareEdgeFunctionExecutor(EdgeFunctionExecutorProperties properties, ObjectMapper objectMapper) {
        super(properties);
        this.objectMapper = objectMapper;
    }

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
        String url = buildUrl(properties.getCloudflare().getDispatcherUrl(), request);

        RequestBody requestBody = buildRequestBody(request, body);
        Request.Builder builder = new Request.Builder().url(url).method(request.method(), requestBody);
        copyForwardableHeaders(request, builder);
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

    @Override
    public void syncSecrets(String projectRef, String functionSlug, String providerDeploymentId, Map<String, String> env) {
        validateConfigForDeploy();
        if (!StringUtils.hasText(providerDeploymentId) || env == null || env.isEmpty()) return;
        String url = properties.getCloudflare().getApiBaseUrl()
                + "/accounts/" + properties.getCloudflare().getAccountId()
                + "/workers/dispatch/namespaces/" + properties.getCloudflare().getDispatchNamespace()
                + "/scripts/" + providerDeploymentId + "/secrets";
        for (Map.Entry<String, String> entry : env.entrySet()) {
            if (RESERVED_ENV_NAMES.contains(entry.getKey())) continue;
            try {
                String payload = objectMapper.writeValueAsString(Map.of(
                        "name", entry.getKey(),
                        "text", entry.getValue(),
                        "type", "secret_text"
                ));
                Request request = new Request.Builder()
                        .url(url)
                        .put(RequestBody.create(payload, MediaType.parse("application/json")))
                        .header("Authorization", "Bearer " + properties.getCloudflare().getApiToken())
                        .build();
                try (Response response = httpClient().newCall(request).execute()) {
                    assertCloudflareSuccess(response);
                }
            } catch (IOException e) {
                throw new IllegalStateException("Failed to apply secret " + entry.getKey() + " to Cloudflare deployment: " + e.getMessage(), e);
            }
        }
    }

    private void uploadWorkerScript(EdgeFunctionDeploymentRequest request, String deploymentId) throws Exception {
        SourceBundle bundle = decodeSourceBundle(request.sourceBundleBase64());
        String entrypoint = loadEntrypoint(bundle, request.entrypoint());
        String workerModule = buildWorkerModule(request, entrypoint);
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
                // Cloudflare rejects duplicate binding names, so a secret cannot shadow a built-in.
                if (StringUtils.hasText(name) && value != null && !RESERVED_ENV_NAMES.contains(name)) {
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

    // Resolves the function's configured entrypoint inside the bundle. TypeScript is
    // not compiled server-side (the upload API only accepts JavaScript modules), so a
    // .ts entrypoint is accepted only when the bundle carries its compiled .js sibling
    // (the CLI bundles TypeScript with esbuild by default).
    private String loadEntrypoint(SourceBundle bundle, String entrypoint) throws IOException {
        String wanted = StringUtils.hasText(entrypoint) ? entrypoint.trim() : "index.ts";
        String compiledSibling = wanted.toLowerCase(Locale.ROOT).endsWith(".ts")
                ? wanted.substring(0, wanted.length() - 3) + ".js"
                : null;
        SourceBundleFile exact = null;
        SourceBundleFile compiled = null;
        for (SourceBundleFile file : bundle.files()) {
            if (wanted.equals(file.path())) exact = file;
            if (compiledSibling != null && compiledSibling.equals(file.path())) compiled = file;
        }
        if (compiled != null) {
            return decodeFile(compiled);
        }
        if (exact == null) {
            throw new IOException("Source bundle must contain entrypoint " + wanted
                    + (compiledSibling == null ? "" : " or its compiled form " + compiledSibling));
        }
        if (wanted.toLowerCase(Locale.ROOT).endsWith(".ts")) {
            throw new IOException("TYPESCRIPT_REQUIRES_BUNDLE: TypeScript entrypoints must be compiled to JavaScript "
                    + "before deployment (the nubase_cli bundles them automatically; otherwise upload " + compiledSibling + ")");
        }
        return decodeFile(exact);
    }

    private String decodeFile(SourceBundleFile file) {
        return new String(Base64.getDecoder().decode(file.content()), StandardCharsets.UTF_8);
    }

    private String buildWorkerModule(EdgeFunctionDeploymentRequest request, String entrypoint) {
        return """
                const __envDefaults = {
                  NUBASE_PROJECT_REF: %s,
                  NUBASE_FUNCTION_NAME: %s
                };
                %s
                const __userDefault = globalThis.default;
                export default {
                  async fetch(request, env, ctx) {
                    const mergedEnv = Object.assign({}, __envDefaults, env || {});
                    if (__userDefault && typeof __userDefault.fetch === "function") {
                      return __userDefault.fetch(request, mergedEnv, ctx);
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

    // Signed payload lines (must match worker.js verifySignature): requestId, projectRef,
    // functionSlug, deploymentId, METHOD, rawPathSuffix, rawQuery, timestamp, sha256Hex(body).
    // deploymentId is included so the routing header cannot be swapped to another
    // tenant's worker without invalidating the signature.
    private String sign(EdgeFunctionInvocationRequest request, byte[] body, String timestamp) {
        try {
            String bodyHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
            String payload = request.requestId() + "\n"
                    + request.projectRef() + "\n"
                    + request.functionSlug() + "\n"
                    + request.providerDeploymentId() + "\n"
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

}
