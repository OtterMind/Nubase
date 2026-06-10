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
    void deploymentIdIsDeterministicAndSafe() {
        EdgeFunctionExecutorProperties props = props("http://127.0.0.1:1");
        props.getCloudflare().setApiBaseUrl("http://127.0.0.1:9");
        var executor = new CloudflareEdgeFunctionExecutor(props, new ObjectMapper());

        var res = executor.deploy(deployRequest("Project_Ref_With_Long_Name_1234567890", "hello-world"));

        assertThat(res.status()).isEqualTo("failed");
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
            assertThat(request.getPath()).isEqualTo("/client/v4/accounts/acct/workers/dispatch/namespaces/ns/scripts/nubase-app1-hello");
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

    private EdgeFunctionExecutorProperties props(String dispatcherUrl) {
        EdgeFunctionExecutorProperties props = new EdgeFunctionExecutorProperties();
        props.getCloudflare().setAccountId("acct");
        props.getCloudflare().setApiToken("token");
        props.getCloudflare().setDispatchNamespace("ns");
        props.getCloudflare().setDispatcherUrl(dispatcherUrl);
        props.getCloudflare().setDispatcherSecret("secret");
        return props;
    }

    private EdgeFunctionDeploymentRequest deployRequest(String projectRef, String slug) {
        String payload = "{\"files\":[{\"path\":\"index.js\",\"content\":\""
                + Base64.getEncoder().encodeToString("export default { async fetch() { return new Response('ok') } };".getBytes(StandardCharsets.UTF_8))
                + "\"}]}";
        return new EdgeFunctionDeploymentRequest(
                projectRef,
                slug,
                "hash",
                null,
                "source_bundle",
                Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8)),
                Map.of("CUSTOM_SECRET", "value")
        );
    }
}
