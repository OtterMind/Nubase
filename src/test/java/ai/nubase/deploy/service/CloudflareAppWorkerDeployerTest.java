package ai.nubase.deploy.service;

import ai.nubase.functions.executor.EdgeFunctionExecutorProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CloudflareAppWorkerDeployerTest {

    @Test
    void deployUploadsAssetsThenWorkerScriptToDispatchNamespace() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(200).setBody("""
                    {"success":true,"result":{"jwt":"upload-token","buckets":[["9f42c7c835bb97dcd6982ccc9d14933e","13f1e82bebff3b396749dd8f9d73e8b6"]]}}
                    """));
            server.enqueue(new MockResponse().setResponseCode(201).setBody("""
                    {"success":true,"result":{"jwt":"completion-token"}}
                    """));
            server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"success\":true}"));
            server.start();
            EdgeFunctionExecutorProperties props = props(server);
            var deployer = new CloudflareAppWorkerDeployer(props, new ObjectMapper());

            var result = deployer.deploy(new AppWorkerDeploymentRequest(
                    "appabc",
                    "v1",
                    "appabc",
                    "server/index.js",
                    "appabc.ottermind.app",
                    "appabc.ottermind.app",
                    "2026-06-17",
                    List.of("nodejs_compat"),
                    Map.of("NUBASE_URL", "https://appabc.nubase.local"),
                    Map.of("NUBASE_SERVICE_ROLE_KEY", "secret"),
                    List.of(new AppWorkerDeploymentRequest.AppWorkerFile(
                            "server/index.js",
                            "export default { async fetch(){ return new Response('ok') } }".getBytes(StandardCharsets.UTF_8),
                            "application/javascript+module"
                    ), new AppWorkerDeploymentRequest.AppWorkerFile(
                            "server/assets/chunk.js",
                            "export const chunk = true".getBytes(StandardCharsets.UTF_8),
                            "application/javascript+module"
                    ), new AppWorkerDeploymentRequest.AppWorkerFile(
                            "server/.vite/manifest.json",
                            "{\"src\":\"server/index.js\"}".getBytes(StandardCharsets.UTF_8),
                            "application/json"
                    ), new AppWorkerDeploymentRequest.AppWorkerFile(
                            "server/assets/styles.css",
                            "body {}".getBytes(StandardCharsets.UTF_8),
                            "text/css"
                    )),
                    List.of(new AppWorkerDeploymentRequest.AppWorkerFile(
                            "index.html",
                            "<html></html>".getBytes(StandardCharsets.UTF_8),
                            "text/html"
                    ), new AppWorkerDeploymentRequest.AppWorkerFile(
                            "assets/app.js",
                            "console.log('ok')".getBytes(StandardCharsets.UTF_8),
                            null
                    ))
            ));

            assertThat(result.status()).isEqualTo("deployed");
            assertThat(result.previewUrl()).isEqualTo("https://appabc.ottermind.app");
            assertThat(result.assetFileCount()).isEqualTo(2);

            var session = server.takeRequest();
            assertThat(session.getMethod()).isEqualTo("POST");
            assertThat(session.getPath()).isEqualTo("/client/v4/accounts/acct/workers/dispatch/namespaces/ns/scripts/appabc/assets-upload-session");
            assertThat(session.getBody().readUtf8()).contains("\"/index.html\"");

            var assetUpload = server.takeRequest();
            assertThat(assetUpload.getMethod()).isEqualTo("POST");
            assertThat(assetUpload.getPath()).isEqualTo("/client/v4/accounts/acct/workers/assets/upload?base64=true");
            assertThat(assetUpload.getHeader("Authorization")).isEqualTo("Bearer upload-token");
            assertThat(assetUpload.getHeader("Content-Type")).startsWith("multipart/form-data; boundary=");
            assertThat(assetUpload.getBody().readUtf8())
                    .contains("9f42c7c835bb97dcd6982ccc9d14933e")
                    .contains("13f1e82bebff3b396749dd8f9d73e8b6")
                    .contains("Content-Type: text/html")
                    .contains("Content-Type: text/javascript; charset=utf-8");

            var scriptUpload = server.takeRequest();
            assertThat(scriptUpload.getMethod()).isEqualTo("PUT");
            assertThat(scriptUpload.getPath()).isEqualTo("/client/v4/accounts/acct/workers/dispatch/namespaces/ns/scripts/appabc");
            String body = scriptUpload.getBody().readUtf8();
            assertThat(body).contains("metadata")
                    .contains("\"main_module\":\"server/index.js\"")
                    .contains("\"assets\":{\"jwt\":\"completion-token\"}")
                    .contains("\"type\":\"assets\"")
                    .contains("\"NUBASE_SERVICE_ROLE_KEY\"")
                    .contains("server/assets/chunk.js")
                    .doesNotContain("server/.vite/manifest.json")
                    .doesNotContain("server/assets/styles.css");
        }
    }

    @Test
    void deployReusesInitialJwtWhenNoAssetBucketsAreReturned() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(200).setBody("""
                    {"success":true,"result":{"jwt":"completion-token","buckets":[]}}
                    """));
            server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"success\":true}"));
            server.start();
            EdgeFunctionExecutorProperties props = props(server);
            var deployer = new CloudflareAppWorkerDeployer(props, new ObjectMapper());

            var result = deployer.deploy(new AppWorkerDeploymentRequest(
                    "appabc",
                    "v1",
                    "appabc",
                    "server/index.js",
                    "server/index.js",
                    "appabc.ottermind.app",
                    null,
                    null,
                    Map.of(),
                    Map.of(),
                    List.of(new AppWorkerDeploymentRequest.AppWorkerFile(
                            "server/index.js",
                            "export default { async fetch(){ return new Response('ok') } }".getBytes(StandardCharsets.UTF_8),
                            "application/javascript+module"
                    )),
                    List.of()
            ));

            assertThat(result.status()).isEqualTo("deployed");
            assertThat(server.getRequestCount()).isEqualTo(2);
            server.takeRequest();
            assertThat(server.takeRequest().getBody().readUtf8()).contains("\"assets\":{\"jwt\":\"completion-token\"}");
        }
    }

    @Test
    void getReturnsLiveScriptDetailsFromDispatchNamespace() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(200).setBody("""
                    {"success":true,"result":{"id":"appabc","created_on":"2026-06-17T00:00:00Z"}}
                    """));
            server.start();
            var deployer = new CloudflareAppWorkerDeployer(props(server), new ObjectMapper());

            AppWorkerInfo info = deployer.get("AppABC");

            assertThat(info.exists()).isTrue();
            assertThat(info.workerName()).isEqualTo("appabc");
            assertThat(info.details()).containsEntry("id", "appabc");

            var request = server.takeRequest();
            assertThat(request.getMethod()).isEqualTo("GET");
            assertThat(request.getPath())
                    .isEqualTo("/client/v4/accounts/acct/workers/dispatch/namespaces/ns/scripts/appabc");
            assertThat(request.getHeader("Authorization")).isEqualTo("Bearer token");
        }
    }

    @Test
    void getReportsMissingWorkerWhenProviderReturns404() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(404).setBody(
                    "{\"success\":false,\"errors\":[{\"code\":10007,\"message\":\"workers.api.error.script_not_found\"}]}"));
            server.start();
            var deployer = new CloudflareAppWorkerDeployer(props(server), new ObjectMapper());

            AppWorkerInfo info = deployer.get("appabc");

            assertThat(info.exists()).isFalse();
            assertThat(info.details()).isEmpty();
        }
    }

    @Test
    void deleteRemovesScriptFromDispatchNamespace() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"success\":true}"));
            server.start();
            var deployer = new CloudflareAppWorkerDeployer(props(server), new ObjectMapper());

            deployer.delete("appabc");

            var request = server.takeRequest();
            assertThat(request.getMethod()).isEqualTo("DELETE");
            assertThat(request.getPath())
                    .isEqualTo("/client/v4/accounts/acct/workers/dispatch/namespaces/ns/scripts/appabc?force=true");
        }
    }

    @Test
    void deleteIsIdempotentWhenWorkerAlreadyAbsent() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(404).setBody(
                    "{\"success\":false,\"errors\":[{\"code\":10007,\"message\":\"script_not_found\"}]}"));
            server.start();
            var deployer = new CloudflareAppWorkerDeployer(props(server), new ObjectMapper());

            deployer.delete("appabc");

            assertThat(server.getRequestCount()).isEqualTo(1);
        }
    }

    private EdgeFunctionExecutorProperties props(MockWebServer server) {
        EdgeFunctionExecutorProperties props = new EdgeFunctionExecutorProperties();
        props.getCloudflare().setAccountId("acct");
        props.getCloudflare().setApiToken("token");
        props.getCloudflare().setDispatchNamespace("ns");
        props.getCloudflare().setApiBaseUrl(server.url("/client/v4").toString().replaceAll("/$", ""));
        return props;
    }
}
