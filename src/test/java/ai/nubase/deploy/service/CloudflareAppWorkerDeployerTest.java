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
                    {"success":true,"result":{"jwt":"upload-token","buckets":[["c83301425b2ad1d496473a5ff3d9ecca"]]}}
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
                    )),
                    List.of(new AppWorkerDeploymentRequest.AppWorkerFile(
                            "index.html",
                            "<html></html>".getBytes(StandardCharsets.UTF_8),
                            "text/html"
                    ))
            ));

            assertThat(result.status()).isEqualTo("deployed");
            assertThat(result.previewUrl()).isEqualTo("https://appabc.ottermind.app");
            assertThat(result.assetFileCount()).isEqualTo(1);

            var session = server.takeRequest();
            assertThat(session.getMethod()).isEqualTo("POST");
            assertThat(session.getPath()).isEqualTo("/client/v4/accounts/acct/workers/dispatch/namespaces/ns/scripts/appabc/assets-upload-session");
            assertThat(session.getBody().readUtf8()).contains("\"/index.html\"");

            var assetUpload = server.takeRequest();
            assertThat(assetUpload.getMethod()).isEqualTo("POST");
            assertThat(assetUpload.getPath()).isEqualTo("/client/v4/accounts/acct/workers/assets/upload?base64=true");
            assertThat(assetUpload.getHeader("Authorization")).isEqualTo("Bearer upload-token");
            assertThat(assetUpload.getBody().readUtf8()).contains("c83301425b2ad1d496473a5ff3d9ecca");

            var scriptUpload = server.takeRequest();
            assertThat(scriptUpload.getMethod()).isEqualTo("PUT");
            assertThat(scriptUpload.getPath()).isEqualTo("/client/v4/accounts/acct/workers/dispatch/namespaces/ns/scripts/appabc");
            String body = scriptUpload.getBody().readUtf8();
            assertThat(body).contains("metadata")
                    .contains("\"main_module\":\"server/index.js\"")
                    .contains("\"assets\":{\"jwt\":\"completion-token\"}")
                    .contains("\"type\":\"assets\"")
                    .contains("\"NUBASE_SERVICE_ROLE_KEY\"");
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

    private EdgeFunctionExecutorProperties props(MockWebServer server) {
        EdgeFunctionExecutorProperties props = new EdgeFunctionExecutorProperties();
        props.getCloudflare().setAccountId("acct");
        props.getCloudflare().setApiToken("token");
        props.getCloudflare().setDispatchNamespace("ns");
        props.getCloudflare().setApiBaseUrl(server.url("/client/v4").toString().replaceAll("/$", ""));
        return props;
    }
}
