package ai.nubase.deploy.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(value = "nubase.deploy.app-worker.cloudflare.enabled", havingValue = "true")
public class CloudflareAppWorkerDeployer implements AppWorkerDeployer {

    private static final MediaType JSON = MediaType.parse("application/json");
    private static final MediaType JS_MODULE = MediaType.parse("application/javascript+module");
    private static final List<String> DEFAULT_COMPATIBILITY_FLAGS = List.of("nodejs_compat");
    private static final Set<String> SERVER_PUBLIC_ASSET_EXTENSIONS = Set.of(
            ".css",
            ".json",
            ".wasm",
            ".svg",
            ".png",
            ".jpg",
            ".jpeg",
            ".gif",
            ".webp",
            ".avif",
            ".bmp",
            ".tif",
            ".tiff",
            ".ico",
            ".woff",
            ".woff2",
            ".ttf",
            ".otf",
            ".eot"
    );
    private static final Set<String> SERVER_PUBLIC_ASSET_EXCLUDED_EXTENSIONS = Set.of(
            ".js",
            ".mjs",
            ".map"
    );

    private final AppWorkerCloudflareProperties properties;
    private final ObjectMapper objectMapper;
    private OkHttpClient httpClient;

    @Override
    public AppWorkerDeploymentResult deploy(AppWorkerDeploymentRequest request) {
        AppWorkerDeploymentTarget target = request.deploymentTarget() == null
                ? AppWorkerDeploymentTarget.PREVIEW
                : request.deploymentTarget();
        String dispatchNamespace = validateConfig(target);
        String workerName = normalizeWorkerName(request.workerName());
        try {
            List<AppWorkerDeploymentRequest.AppWorkerFile> publicAssets = publicAssetFiles(request);
            AssetUpload upload = uploadAssets(publicAssets, workerName, dispatchNamespace);
            if (request.replaceSecretBindings()) {
                // Before the upload, so the returned version is the one that serves traffic:
                // changing a secret afterwards would create a newer version.
                deleteUndeclaredSecrets(request, workerName, dispatchNamespace);
            }
            String providerVersionId = uploadWorker(request, workerName, dispatchNamespace, upload.completionJwt());
            String previewUrl = "https://" + normalizeHost(request.previewHost());
            return new AppWorkerDeploymentResult(
                    "cloudflare",
                    target.value(),
                    dispatchNamespace,
                    workerName,
                    providerVersionId,
                    previewUrl,
                    "deployed",
                    upload.manifestHash(),
                    upload.assetFileCount(),
                    Instant.now()
            );
        } catch (Exception e) {
            log.warn("Cloudflare app worker deployment failed: appCode={}, workerName={}, error={}",
                    request.appCode(), workerName, e.toString());
            throw e instanceof AppWorkerDeploymentException
                    ? (AppWorkerDeploymentException) e
                    : new AppWorkerDeploymentException(e.getMessage(), e);
        }
    }

    private List<AppWorkerDeploymentRequest.AppWorkerFile> publicAssetFiles(AppWorkerDeploymentRequest request) {
        Map<String, AppWorkerDeploymentRequest.AppWorkerFile> byPath = new LinkedHashMap<>();
        List<AppWorkerDeploymentRequest.AppWorkerFile> assetFiles = request.assetFiles() == null
                ? List.of()
                : request.assetFiles();
        for (AppWorkerDeploymentRequest.AppWorkerFile file : assetFiles) {
            addPublicAsset(byPath, file.path(), file, request);
        }

        List<AppWorkerDeploymentRequest.AppWorkerFile> serverFiles = request.serverFiles() == null
                ? List.of()
                : request.serverFiles();
        for (AppWorkerDeploymentRequest.AppWorkerFile file : serverFiles) {
            String publicPath = serverPublicAssetPath(file.path());
            if (publicPath == null) continue;
            byte[] content = file.content() == null ? new byte[0] : file.content();
            String contentType = effectiveContentType(publicPath, file.contentType());
            addPublicAsset(byPath, publicPath, new AppWorkerDeploymentRequest.AppWorkerFile(publicPath, content, contentType), request);
        }
        return new ArrayList<>(byPath.values());
    }

    private void addPublicAsset(
            Map<String, AppWorkerDeploymentRequest.AppWorkerFile> byPath,
            String rawPath,
            AppWorkerDeploymentRequest.AppWorkerFile file,
            AppWorkerDeploymentRequest request
    ) {
        String path = normalizeAssetPath(rawPath);
        byte[] content = file.content() == null ? new byte[0] : file.content();
        String contentType = effectiveContentType(path, file.contentType());
        AppWorkerDeploymentRequest.AppWorkerFile candidate = new AppWorkerDeploymentRequest.AppWorkerFile(path, content, contentType);
        AppWorkerDeploymentRequest.AppWorkerFile existing = byPath.get(path);
        if (existing == null) {
            byPath.put(path, candidate);
            return;
        }
        byte[] existingContent = existing.content() == null ? new byte[0] : existing.content();
        if (Objects.equals(existing.contentType(), contentType) && Arrays.equals(existingContent, content)) {
            return;
        }
        throw new AppWorkerDeploymentException("Conflicting public asset path during app worker deploy: path="
                + path + " appCode=" + firstText(request.appCode(), "") + " version=" + firstText(request.version(), ""));
    }

    private String serverPublicAssetPath(String rawPath) {
        String path = normalizeModulePath(rawPath);
        if (!path.startsWith("server/assets/")) return null;
        String extension = extension(path);
        if (SERVER_PUBLIC_ASSET_EXCLUDED_EXTENSIONS.contains(extension)) return null;
        if (!SERVER_PUBLIC_ASSET_EXTENSIONS.contains(extension)) return null;
        return "assets/" + path.substring("server/assets/".length());
    }

    private AssetUpload uploadAssets(
            List<AppWorkerDeploymentRequest.AppWorkerFile> assetFiles,
            String workerName,
            String dispatchNamespace
    ) throws IOException {
        List<AppWorkerDeploymentRequest.AppWorkerFile> files = assetFiles == null ? List.of() : assetFiles;
        Map<String, Map<String, Object>> manifest = new LinkedHashMap<>();
        Map<String, AppWorkerDeploymentRequest.AppWorkerFile> byHash = new LinkedHashMap<>();
        for (AppWorkerDeploymentRequest.AppWorkerFile file : files) {
            String path = normalizeAssetPath(file.path());
            byte[] content = file.content() == null ? new byte[0] : file.content();
            String contentType = effectiveContentType(path, file.contentType());
            String hash = assetHash(workerName, path, content, contentType);
            manifest.put(path, Map.of("hash", hash, "size", content.length));
            byHash.put(hash, new AppWorkerDeploymentRequest.AppWorkerFile(path, content, contentType));
        }
        String manifestHash = sha256Hex(objectMapper.writeValueAsBytes(manifest));
        String uploadSessionUrl = apiBase()
                + "/accounts/" + properties.getAccountId()
                + "/workers/dispatch/namespaces/" + dispatchNamespace
                + "/scripts/" + workerName + "/assets-upload-session";
        Map<String, Object> sessionPayload = Map.of("manifest", manifest);
        Map<String, Object> session = executeJson(new Request.Builder()
                .url(uploadSessionUrl)
                .post(RequestBody.create(objectMapper.writeValueAsBytes(sessionPayload), JSON))
                .header("Authorization", "Bearer " + properties.getApiToken())
                .build());

        String uploadJwt = requiredString(session, "jwt");
        List<List<String>> buckets = buckets(session.get("buckets"));
        if (buckets.isEmpty()) {
            return new AssetUpload(uploadJwt, manifestHash, files.size());
        }

        String completionJwt = null;
        for (List<String> bucket : buckets) {
            MultipartBody.Builder uploadBody = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM);
            for (String hash : bucket) {
                AppWorkerDeploymentRequest.AppWorkerFile file = byHash.get(hash);
                if (file == null) {
                    throw new AppWorkerDeploymentException("Cloudflare requested unknown asset hash: " + hash);
                }
                uploadBody.addFormDataPart(hash, null, RequestBody.create(
                        Base64.getEncoder().encodeToString(file.content()).getBytes(StandardCharsets.UTF_8),
                        mediaTypeFor(file.contentType())
                ));
            }
            Map<String, Object> uploadResponse = executeJson(new Request.Builder()
                    .url(apiBase() + "/accounts/" + properties.getAccountId() + "/workers/assets/upload?base64=true")
                    .post(uploadBody.build())
                    .header("Authorization", "Bearer " + uploadJwt)
                    .build());
            String maybeCompletion = stringValue(uploadResponse.get("jwt"));
            if (StringUtils.hasText(maybeCompletion)) {
                completionJwt = maybeCompletion;
            }
        }
        if (!StringUtils.hasText(completionJwt)) {
            throw new AppWorkerDeploymentException("Cloudflare asset upload completed without a completion token");
        }
        return new AssetUpload(completionJwt, manifestHash, files.size());
    }

    @Override
    public AppWorkerInfo get(String workerName) {
        String dispatchNamespace = validateConfig(AppWorkerDeploymentTarget.PREVIEW);
        String name = normalizeWorkerName(workerName);
        try {
            Map<String, Object> result = executeJson(new Request.Builder()
                    .url(scriptUrl(name, dispatchNamespace))
                    .get()
                    .header("Authorization", "Bearer " + properties.getApiToken())
                    .build(), true);
            if (result == null) {
                return new AppWorkerInfo(name, false, Map.of());
            }
            return new AppWorkerInfo(name, true, result);
        } catch (IOException e) {
            throw new AppWorkerDeploymentException("Failed to read app worker " + name + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void delete(String workerName) {
        String dispatchNamespace = validateConfig(AppWorkerDeploymentTarget.PREVIEW);
        String name = normalizeWorkerName(workerName);
        Request request = new Request.Builder()
                .url(scriptUrl(name, dispatchNamespace) + "?force=true")
                .delete()
                .header("Authorization", "Bearer " + properties.getApiToken())
                .build();
        try (Response ignored = executeCloudflare(request, true)) {
            // A null/closed response (404) means the script was already gone — idempotent success.
        } catch (IOException e) {
            throw new AppWorkerDeploymentException("Failed to delete app worker " + name + ": " + e.getMessage(), e);
        }
    }

    private String scriptUrl(String name, String dispatchNamespace) {
        return apiBase()
                + "/accounts/" + properties.getAccountId()
                + "/workers/dispatch/namespaces/" + dispatchNamespace
                + "/scripts/" + name;
    }

    private String uploadWorker(
            AppWorkerDeploymentRequest request,
            String workerName,
            String dispatchNamespace,
            String assetCompletionJwt
    ) throws IOException {
        Map<String, AppWorkerDeploymentRequest.AppWorkerFile> serverFiles = new LinkedHashMap<>();
        for (AppWorkerDeploymentRequest.AppWorkerFile file : request.serverFiles() == null ? List.<AppWorkerDeploymentRequest.AppWorkerFile>of() : request.serverFiles()) {
            String path = normalizeModulePath(file.path());
            if (!isWorkerModulePath(path)) continue;
            serverFiles.put(path, file);
        }
        String mainModule = normalizeModulePath(firstText(request.mainModule(), request.serverEntrypointPath(), "server/index.js"));
        if (!isWorkerModulePath(mainModule)) {
            throw new AppWorkerDeploymentException("main module must be a JavaScript module: " + mainModule);
        }
        if (!serverFiles.containsKey(mainModule)) {
            throw new AppWorkerDeploymentException("server files must include main module " + mainModule);
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("main_module", mainModule);
        metadata.put("compatibility_date", compatibilityDate(request.compatibilityDate()));
        metadata.put("compatibility_flags", compatibilityFlags(request.compatibilityFlags()));
        metadata.put("bindings", cloudflareBindings(request));
        metadata.put("assets", Map.of("jwt", assetCompletionJwt));

        MultipartBody.Builder body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("metadata", null,
                        RequestBody.create(objectMapper.writeValueAsBytes(metadata), JSON));
        for (Map.Entry<String, AppWorkerDeploymentRequest.AppWorkerFile> entry : serverFiles.entrySet()) {
            body.addFormDataPart(entry.getKey(), entry.getKey(),
                    RequestBody.create(entry.getValue().content() == null ? new byte[0] : entry.getValue().content(), JS_MODULE));
        }

        Request upload = new Request.Builder()
                .url(apiBase()
                        + "/accounts/" + properties.getAccountId()
                        + "/workers/dispatch/namespaces/" + dispatchNamespace
                        + "/scripts/" + workerName)
                .put(body.build())
                .header("Authorization", "Bearer " + properties.getApiToken())
                .build();
        try (Response response = executeCloudflare(upload)) {
            return providerVersionId(readEnvelope(response));
        }
    }

    /**
     * Cloudflare keeps secret_text bindings across script uploads even when the upload omits them,
     * so a secret the caller removed would otherwise stay readable by the worker.
     */
    private void deleteUndeclaredSecrets(
            AppWorkerDeploymentRequest request,
            String workerName,
            String dispatchNamespace
    ) throws IOException {
        Set<String> declared = new HashSet<>();
        if (request.secretTextBindings() != null) {
            for (String name : request.secretTextBindings().keySet()) {
                if (StringUtils.hasText(name)) declared.add(name.trim());
            }
        }
        String secretsUrl = scriptUrl(workerName, dispatchNamespace) + "/secrets";
        Map<String, Object> envelope;
        try (Response response = executeCloudflare(new Request.Builder()
                .url(secretsUrl)
                .get()
                .header("Authorization", "Bearer " + properties.getApiToken())
                .build(), true)) {
            if (response == null) return; // First deploy: the script does not exist yet.
            envelope = readEnvelope(response);
        }
        if (!(envelope.get("result") instanceof List<?> secrets)) {
            throw new AppWorkerDeploymentException("Cloudflare secret list response missing result");
        }
        for (Object secret : secrets) {
            String name = secret instanceof Map<?, ?> map ? stringValue(map.get("name")) : null;
            if (!StringUtils.hasText(name) || declared.contains(name)) continue;
            try (Response ignored = executeCloudflare(new Request.Builder()
                    .url(secretsUrl + "/" + URLEncoder.encode(name, StandardCharsets.UTF_8))
                    .delete()
                    .header("Authorization", "Bearer " + properties.getApiToken())
                    .build(), true)) {
                log.info("Deleted undeclared app worker secret: workerName={}, secret={}", workerName, name);
            }
        }
    }

    private List<Map<String, String>> cloudflareBindings(AppWorkerDeploymentRequest request) {
        List<Map<String, String>> bindings = new ArrayList<>();
        bindings.add(Map.of("type", "assets", "name", "ASSETS"));
        addPlainBinding(bindings, "NUBASE_PROJECT_REF", request.appCode());
        addPlainBinding(bindings, "NUBASE_APP_VERSION", request.version());
        Map<String, String> plain = new LinkedHashMap<>();
        if (request.plainTextBindings() != null) plain.putAll(request.plainTextBindings());
        for (Map.Entry<String, String> entry : plain.entrySet()) {
            addPlainBinding(bindings, entry.getKey(), entry.getValue());
        }
        Map<String, String> secrets = new LinkedHashMap<>();
        if (request.secretTextBindings() != null) secrets.putAll(request.secretTextBindings());
        for (Map.Entry<String, String> entry : secrets.entrySet()) {
            if (reservedBinding(entry.getKey())) continue;
            if (StringUtils.hasText(entry.getKey()) && entry.getValue() != null) {
                bindings.add(Map.of("type", "secret_text", "name", entry.getKey().trim(), "text", entry.getValue()));
            }
        }
        return bindings;
    }

    private void addPlainBinding(List<Map<String, String>> bindings, String name, String value) {
        if (reservedBinding(name) && bindings.stream().anyMatch(binding -> name.equals(binding.get("name")))) return;
        if (StringUtils.hasText(name) && value != null) {
            bindings.add(Map.of("type", "plain_text", "name", name.trim(), "text", value));
        }
    }

    private boolean reservedBinding(String name) {
        return "ASSETS".equals(name) || "NUBASE_PROJECT_REF".equals(name) || "NUBASE_APP_VERSION".equals(name);
    }

    private MediaType mediaTypeFor(String contentType) {
        if (StringUtils.hasText(contentType)) {
            MediaType parsed = MediaType.parse(contentType.trim());
            if (parsed != null) return parsed;
        }
        return MediaType.parse("application/octet-stream");
    }

    private String effectiveContentType(String path, String contentType) {
        if (StringUtils.hasText(contentType)) {
            String value = contentType.trim();
            if (MediaType.parse(value) != null && !"application/octet-stream".equalsIgnoreCase(value)) {
                return value;
            }
        }
        return contentTypeFromPath(path);
    }

    private String contentTypeFromPath(String path) {
        String value = path.toLowerCase(Locale.ROOT);
        if (value.endsWith(".html")) return "text/html; charset=utf-8";
        if (value.endsWith(".css")) return "text/css; charset=utf-8";
        if (value.endsWith(".js") || value.endsWith(".mjs")) return "text/javascript; charset=utf-8";
        if (value.endsWith(".json") || value.endsWith(".map")) return "application/json; charset=utf-8";
        if (value.endsWith(".svg")) return "image/svg+xml";
        if (value.endsWith(".png")) return "image/png";
        if (value.endsWith(".jpg") || value.endsWith(".jpeg")) return "image/jpeg";
        if (value.endsWith(".webp")) return "image/webp";
        if (value.endsWith(".ico")) return "image/x-icon";
        if (value.endsWith(".wasm")) return "application/wasm";
        if (value.endsWith(".woff")) return "font/woff";
        if (value.endsWith(".woff2")) return "font/woff2";
        if (value.endsWith(".txt")) return "text/plain; charset=utf-8";
        return "application/octet-stream";
    }

    private Map<String, Object> executeJson(Request request) throws IOException {
        return executeJson(request, false);
    }

    private Map<String, Object> executeJson(Request request, boolean allowNotFound) throws IOException {
        try (Response response = executeCloudflare(request, allowNotFound)) {
            if (response == null) return null;
            String body = response.body() == null ? "" : response.body().string();
            if (!StringUtils.hasText(body)) return Map.of();
            Map<String, Object> envelope = objectMapper.readValue(body, new TypeReference<>() {});
            Object result = envelope.get("result");
            if (result instanceof Map<?, ?> map) {
                Map<String, Object> out = new LinkedHashMap<>();
                map.forEach((key, value) -> out.put(String.valueOf(key), value));
                return out;
            }
            return envelope;
        }
    }

    private Response executeCloudflare(Request request) throws IOException {
        return executeCloudflare(request, false);
    }

    /**
     * Execute a Cloudflare request with retry on 429/5xx. When {@code allowNotFound} is true a
     * 404 returns {@code null} (caller treats it as absent) instead of throwing.
     */
    private Response executeCloudflare(Request request, boolean allowNotFound) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            Response response = httpClient().newCall(request).execute();
            if (allowNotFound && response.code() == 404) {
                response.close();
                return null;
            }
            if (response.code() == 429 || response.code() >= 500) {
                String body = response.body() == null ? "" : response.body().string();
                response.close();
                last = new IOException("Cloudflare request failed (" + response.code() + "): " + body);
                sleepBeforeRetry(attempt);
                continue;
            }
            String body = response.peekBody(1024 * 1024).string();
            if (!response.isSuccessful()) {
                response.close();
                throw new IOException("Cloudflare request failed (" + response.code() + "): " + body);
            }
            if (StringUtils.hasText(body)) {
                Map<?, ?> parsed = objectMapper.readValue(body, Map.class);
                if (Boolean.FALSE.equals(parsed.get("success"))) {
                    response.close();
                    throw new IOException("Cloudflare request failed: " + body);
                }
            }
            return response;
        }
        throw last != null ? last : new IOException("Cloudflare request failed");
    }

    private OkHttpClient httpClient() {
        if (httpClient == null) {
            httpClient = new OkHttpClient.Builder()
                    .connectTimeout(java.time.Duration.ofMillis(Math.max(1, properties.getTimeoutMs())))
                    .readTimeout(java.time.Duration.ofMillis(Math.max(1, properties.getTimeoutMs())))
                    .writeTimeout(java.time.Duration.ofMillis(Math.max(1, properties.getTimeoutMs())))
                    .callTimeout(java.time.Duration.ofMillis(Math.max(1, properties.getTimeoutMs() + 500L)))
                    .build();
        }
        return httpClient;
    }

    private String validateConfig(AppWorkerDeploymentTarget target) {
        String dispatchNamespace = properties.dispatchNamespace(target);
        if (!StringUtils.hasText(properties.getAccountId())
                || !StringUtils.hasText(dispatchNamespace)
                || !StringUtils.hasText(properties.getApiToken())) {
            throw new AppWorkerDeploymentException(
                    "Cloudflare app-worker account-id, "
                            + target.value()
                            + " dispatch-namespace and api-token are required");
        }
        return dispatchNamespace.trim();
    }

    private String apiBase() {
        return properties.getApiBaseUrl().replaceAll("/+$", "");
    }

    private void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(100L * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String normalizeWorkerName(String workerName) {
        String value = firstText(workerName, "").toLowerCase(Locale.ROOT);
        if (!value.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new AppWorkerDeploymentException("Invalid workerName");
        }
        return value;
    }

    private String normalizeHost(String host) {
        String value = firstText(host, "").toLowerCase(Locale.ROOT);
        if (!value.matches("[a-z0-9][a-z0-9.-]{1,251}[a-z0-9]")) {
            throw new AppWorkerDeploymentException("Invalid previewHost");
        }
        return value;
    }

    private String normalizeAssetPath(String path) {
        String value = firstText(path, "").replace("\\", "/");
        if (!value.startsWith("/")) value = "/" + value;
        if (value.contains("..") || value.contains("//") || value.endsWith("/")) {
            throw new AppWorkerDeploymentException("Invalid asset path: " + path);
        }
        return value;
    }

    private String normalizeModulePath(String path) {
        String value = firstText(path, "").replace("\\", "/").replaceFirst("^/+", "");
        if (!StringUtils.hasText(value) || value.contains("..") || value.contains("//") || value.endsWith("/")) {
            throw new AppWorkerDeploymentException("Invalid module path: " + path);
        }
        return value;
    }

    private boolean isWorkerModulePath(String path) {
        String value = path.toLowerCase(Locale.ROOT);
        return value.endsWith(".js") || value.endsWith(".mjs");
    }

    private String extension(String path) {
        String value = path.toLowerCase(Locale.ROOT);
        int slash = value.lastIndexOf('/');
        int dot = value.lastIndexOf('.');
        if (dot <= slash || dot + 1 >= value.length()) return "";
        return value.substring(dot);
    }

    private String compatibilityDate(String raw) {
        if (StringUtils.hasText(raw)) return raw.trim();
        return LocalDate.now().toString();
    }

    private List<String> compatibilityFlags(List<String> flags) {
        if (flags == null || flags.isEmpty()) return DEFAULT_COMPATIBILITY_FLAGS;
        return flags.stream().filter(StringUtils::hasText).map(String::trim).distinct().toList();
    }

    private String requiredString(Map<String, Object> map, String key) {
        String value = stringValue(map.get(key));
        if (!StringUtils.hasText(value)) {
            throw new AppWorkerDeploymentException("Cloudflare response missing " + key);
        }
        return value;
    }

    private Map<String, Object> readEnvelope(Response response) throws IOException {
        String body = response.body() == null ? "" : response.body().string();
        if (!StringUtils.hasText(body)) return Map.of();
        return objectMapper.readValue(body, new TypeReference<>() {});
    }

    private String providerVersionId(Map<String, Object> envelope) {
        Map<String, Object> result = resultObject(envelope);
        String id = firstText(
                stringValue(result.get("version_id")),
                stringValue(result.get("versionId")),
                stringValue(result.get("etag")),
                stringValue(resultObject(result, "script").get("etag"))
        );
        if (!StringUtils.hasText(id)) {
            throw new AppWorkerDeploymentException("Cloudflare script upload response missing worker content reference");
        }
        return id;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resultObject(Map<String, Object> envelope) {
        Object result = envelope.get("result");
        if (result instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((key, value) -> out.put(String.valueOf(key), value));
            return out;
        }
        return Map.of();
    }

    private Map<String, Object> resultObject(Map<String, Object> envelope, String key) {
        Object result = envelope.get(key);
        if (result instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((entryKey, value) -> out.put(String.valueOf(entryKey), value));
            return out;
        }
        return Map.of();
    }

    private String stringValue(Object value) {
        return value instanceof String text ? text : null;
    }

    @SuppressWarnings("unchecked")
    private List<List<String>> buckets(Object raw) {
        if (!(raw instanceof List<?> outer)) return List.of();
        List<List<String>> out = new ArrayList<>();
        for (Object bucket : outer) {
            if (!(bucket instanceof List<?> inner)) continue;
            out.add(inner.stream().map(Objects::toString).toList());
        }
        return out;
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) return value.trim();
        }
        return "";
    }

    private String assetHash(String workerName, String path, byte[] content, String contentType) {
        String extension = "";
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        if (dot > slash && dot + 1 < path.length()) {
            extension = path.substring(dot + 1);
        }
        return sha256Hex(workerName + "\n"
                + path + "\n"
                + extension + "\n"
                + contentType + "\n"
                + Base64.getEncoder().encodeToString(content)).substring(0, 32);
    }

    private String sha256Hex(String content) {
        return sha256Hex(content.getBytes(StandardCharsets.UTF_8));
    }

    private String sha256Hex(byte[] content) {
        return digestHex("SHA-256", content);
    }

    private String digestHex(String algorithm, byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(content));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private record AssetUpload(String completionJwt, String manifestHash, int assetFileCount) {
    }
}
