package ai.nubase.functions.executor.cloudflare;

import ai.nubase.functions.executor.EdgeFunctionDeploymentRequest;
import ai.nubase.functions.executor.EdgeFunctionExecutorProperties;
import ai.nubase.functions.executor.EdgeFunctionInvocationRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CloudflareEdgeFunctionExecutorTest {

    @Test
    void deploymentIdAvoidsProjectSlugSeparatorCollisions() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"success\":true}"));
            server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"success\":true}"));
            server.start();
            EdgeFunctionExecutorProperties props = props(server.url("/dispatch").toString());
            props.getCloudflare().setApiBaseUrl(server.url("/client/v4").toString().replaceAll("/$", ""));
            var executor = new CloudflareEdgeFunctionExecutor(props, new ObjectMapper());

            var first = executor.deploy(deployRequest("team", "a-report"));
            var second = executor.deploy(deployRequest("team-a", "report"));

            assertThat(first.providerDeploymentId()).isNotEqualTo(second.providerDeploymentId());
            assertThat(first.providerDeploymentId()).matches("nubase-[a-f0-9]{16}-[a-f0-9]{16}");
        }
    }

    @Test
    void deployUploadsScriptToDispatchNamespace() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"success\":true}"));
            server.start();
            EdgeFunctionExecutorProperties props = props(server.url("/dispatch").toString());
            props.getCloudflare().setApiBaseUrl(server.url("/client/v4").toString().replaceAll("/$", ""));
            var executor = new CloudflareEdgeFunctionExecutor(props, new ObjectMapper());

            var res = executor.deploy(deployRequest("app1", "hello"));

            assertThat(res.status()).isEqualTo("deployed");
            var request = server.takeRequest();
            assertThat(request.getMethod()).isEqualTo("PUT");
            assertThat(request.getPath()).matches("/client/v4/accounts/acct/workers/dispatch/namespaces/ns/scripts/nubase-[a-f0-9]{16}-[a-f0-9]{16}");
            assertThat(request.getHeader("Authorization")).isEqualTo("Bearer token");
            assertThat(request.getBody().readUtf8()).contains("metadata").contains("index.js");
        }
    }

    @Test
    void deployRetriesTransientCloudflareErrors() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(500).setBody("temporary"));
            server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"success\":true}"));
            server.start();
            EdgeFunctionExecutorProperties props = props(server.url("/dispatch").toString());
            props.getCloudflare().setApiBaseUrl(server.url("/client/v4").toString().replaceAll("/$", ""));
            var executor = new CloudflareEdgeFunctionExecutor(props, new ObjectMapper());

            var res = executor.deploy(deployRequest("app1", "hello"));

            assertThat(res.status()).isEqualTo("deployed");
            assertThat(server.getRequestCount()).isEqualTo(2);
        }
    }

    @Test
    void deployFailsWhenCloudflareResponseSuccessFalse() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"success\":false,\"errors\":[{\"message\":\"bad\"}]}"));
            server.start();
            EdgeFunctionExecutorProperties props = props(server.url("/dispatch").toString());
            props.getCloudflare().setApiBaseUrl(server.url("/client/v4").toString().replaceAll("/$", ""));
            var executor = new CloudflareEdgeFunctionExecutor(props, new ObjectMapper());

            var res = executor.deploy(deployRequest("app1", "hello"));

            assertThat(res.status()).isEqualTo("failed");
            assertThat(res.errorMessage()).contains("success\":false");
        }
    }

    @Test
    void invocationAddsSignedInternalHeaders() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(201).setBody("{\"ok\":true}").addHeader("content-type", "application/json"));
            server.start();
            EdgeFunctionExecutorProperties props = props(server.url("/dispatch").toString());
            var executor = new CloudflareEdgeFunctionExecutor(props, new ObjectMapper());

            var res = executor.invoke(new EdgeFunctionInvocationRequest(
                    "req-1",
                    "app1",
                    "hello",
                    "deployment-1",
                    "POST",
                    "/nested",
                    "x=1",
                    Map.of("Content-Type", List.of("application/json"), "apikey", List.of("secret")),
                    "{\"a\":1}".getBytes(),
                    Map.of()
            ));

            assertThat(res.statusCode()).isEqualTo(201);
            var request = server.takeRequest();
            assertThat(request.getPath()).isEqualTo("/dispatch/app1/hello/nested?x=1");
            assertThat(request.getHeader("x-nubase-signature")).isNotBlank();
            assertThat(request.getHeader("x-nubase-project-ref")).isEqualTo("app1");
            assertThat(request.getHeader("apikey")).isNull();
        }
    }

    @Test
    void signaturePayloadMatchesDispatcherContract() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));
            server.start();
            EdgeFunctionExecutorProperties props = props(server.url("/dispatch").toString());
            var executor = new CloudflareEdgeFunctionExecutor(props, new ObjectMapper());

            byte[] body = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
            executor.invoke(new EdgeFunctionInvocationRequest(
                    "req-1",
                    "app1",
                    "hello",
                    "nubase-app1-hello",
                    "POST",
                    "/a%20b/c",
                    "x=1",
                    Map.of("Content-Type", List.of("application/json")),
                    body,
                    Map.of()
            ));

            var recorded = server.takeRequest();
            String timestamp = recorded.getHeader("x-nubase-timestamp");
            // Payload contract shared with worker.js verifySignature and worker.test.js:
            // requestId, projectRef, functionSlug, deploymentId, METHOD, rawPathSuffix,
            // rawQuery, timestamp, sha256Hex(body)
            String bodyHash = java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(body));
            String payload = String.join("\n",
                    "req-1", "app1", "hello", "nubase-app1-hello", "POST", "/a%20b/c", "x=1", timestamp, bodyHash);
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec("secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = java.util.HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));

            assertThat(recorded.getHeader("x-nubase-signature")).isEqualTo(expected);
            assertThat(recorded.getHeader("x-nubase-deployment-id")).isEqualTo("nubase-app1-hello");
            // The signed raw suffix must be exactly what appears in the forwarded URL,
            // so the dispatcher can verify against its own pathname.
            assertThat(recorded.getPath()).isEqualTo("/dispatch/app1/hello/a%20b/c?x=1");
        }
    }

    private EdgeFunctionExecutorProperties props(String dispatcherUrl) {
        EdgeFunctionExecutorProperties props = new EdgeFunctionExecutorProperties();
        props.getCloudflare().setAccountId("acct");
        props.getCloudflare().setApiToken("token");
        props.getCloudflare().setDispatchNamespace("ns");
        props.getCloudflare().setDispatcherUrl(dispatcherUrl);
        props.getCloudflare().setDispatcherSecret("secret");
        return props;
    }

    @Test
    void deployFailsForUncompiledTypescriptEntrypoint() {
        EdgeFunctionExecutorProperties props = props("http://127.0.0.1:1");
        props.getCloudflare().setApiBaseUrl("http://127.0.0.1:9");
        var executor = new CloudflareEdgeFunctionExecutor(props, new ObjectMapper());

        var res = executor.deploy(deployRequest("app1", "hello", "index.ts",
                bundle("index.ts", "export default { async fetch(req: Request) { return new Response('ok') } };")));

        assertThat(res.status()).isEqualTo("failed");
        assertThat(res.errorMessage()).contains("TYPESCRIPT_REQUIRES_BUNDLE");
    }

    @Test
    void deployUsesCompiledSiblingForTypescriptEntrypoint() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"success\":true}"));
            server.start();
            EdgeFunctionExecutorProperties props = props(server.url("/dispatch").toString());
            props.getCloudflare().setApiBaseUrl(server.url("/client/v4").toString().replaceAll("/$", ""));
            var executor = new CloudflareEdgeFunctionExecutor(props, new ObjectMapper());

            var res = executor.deploy(deployRequest("app1", "hello", "index.ts",
                    bundle("index.js", "export default { async fetch() { return new Response('compiled') } };")));

            assertThat(res.status()).isEqualTo("deployed");
            assertThat(server.takeRequest().getBody().readUtf8()).contains("compiled");
        }
    }

    @Test
    void deployWrapsEsbuildStyleDefaultReExport() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"success\":true}"));
            server.start();
            EdgeFunctionExecutorProperties props = props(server.url("/dispatch").toString());
            props.getCloudflare().setApiBaseUrl(server.url("/client/v4").toString().replaceAll("/$", ""));
            var executor = new CloudflareEdgeFunctionExecutor(props, new ObjectMapper());

            var res = executor.deploy(deployRequest("app1", "hello", "index.js",
                    bundle("index.js", "var index_default = { async fetch() { return new Response('ok') } };\nexport { index_default as default };\n")));

            assertThat(res.status()).isEqualTo("deployed");
            String body = server.takeRequest().getBody().readUtf8();
            assertThat(body).contains("const __userDefault = index_default;");
            assertThat(body).doesNotContain("export { index_default as default }");
        }
    }

    @Test
    void invokeReturnsExecutorErrorForMissingCloudflareConfig() {
        EdgeFunctionExecutorProperties props = props("http://127.0.0.1:1");
        props.getCloudflare().setDispatcherSecret("");
        var executor = new CloudflareEdgeFunctionExecutor(props, new ObjectMapper());

        var res = executor.invoke(new EdgeFunctionInvocationRequest(
                "req-1", "app1", "hello", "deployment-1", "GET", "", null, Map.of(), new byte[0], Map.of()));

        assertThat(res.statusCode()).isEqualTo(502);
        assertThat(res.errorCode()).isEqualTo("CLOUDFLARE_EXECUTOR_ERROR");
    }

    @Test
    void deployHonorsCustomEntrypoint() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"success\":true}"));
            server.start();
            EdgeFunctionExecutorProperties props = props(server.url("/dispatch").toString());
            props.getCloudflare().setApiBaseUrl(server.url("/client/v4").toString().replaceAll("/$", ""));
            var executor = new CloudflareEdgeFunctionExecutor(props, new ObjectMapper());

            var res = executor.deploy(deployRequest("app1", "hello", "main.js",
                    bundle("main.js", "export default { async fetch() { return new Response('main') } };")));

            assertThat(res.status()).isEqualTo("deployed");
        }
    }

    @Test
    void deployFailsWhenEntrypointMissingFromBundle() {
        EdgeFunctionExecutorProperties props = props("http://127.0.0.1:1");
        props.getCloudflare().setApiBaseUrl("http://127.0.0.1:9");
        var executor = new CloudflareEdgeFunctionExecutor(props, new ObjectMapper());

        var res = executor.deploy(deployRequest("app1", "hello", "main.js",
                bundle("other.js", "export default {};")));

        assertThat(res.status()).isEqualTo("failed");
        assertThat(res.errorMessage()).contains("must contain entrypoint main.js");
    }

    @Test
    void syncSecretsPutsEachSecretToCloudflare() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"success\":true}"));
            server.start();
            EdgeFunctionExecutorProperties props = props(server.url("/dispatch").toString());
            props.getCloudflare().setApiBaseUrl(server.url("/client/v4").toString().replaceAll("/$", ""));
            var executor = new CloudflareEdgeFunctionExecutor(props, new ObjectMapper());

            executor.syncSecrets("app1", "hello", "nubase-app1-hello", Map.of("API_KEY", "v2"));

            var request = server.takeRequest();
            assertThat(request.getMethod()).isEqualTo("PUT");
            assertThat(request.getPath())
                    .isEqualTo("/client/v4/accounts/acct/workers/dispatch/namespaces/ns/scripts/nubase-app1-hello/secrets");
            assertThat(request.getBody().readUtf8()).contains("\"API_KEY\"").contains("\"secret_text\"");
        }
    }

    private EdgeFunctionDeploymentRequest deployRequest(String projectRef, String slug) {
        return deployRequest(projectRef, slug, "index.js",
                bundle("index.js", "export default { async fetch() { return new Response('ok') } };"));
    }

    private EdgeFunctionDeploymentRequest deployRequest(String projectRef, String slug, String entrypoint, String bundleBase64) {
        return new EdgeFunctionDeploymentRequest(
                projectRef,
                slug,
                entrypoint,
                bundleBase64,
                Map.of("CUSTOM_SECRET", "value")
        );
    }

    private String bundle(String path, String source) {
        String payload = "{\"files\":[{\"path\":\"" + path + "\",\"content\":\""
                + Base64.getEncoder().encodeToString(source.getBytes(StandardCharsets.UTF_8))
                + "\"}]}";
        return Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }
}
