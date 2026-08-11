package ai.nubase.platform.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class PlatformMcpControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private PlatformProjectAutomationFacade facade;
    private PlatformMcpController controller;

    @BeforeEach
    void setUp() {
        facade = mock(PlatformProjectAutomationFacade.class);
        controller = new PlatformMcpController(facade, objectMapper);
    }

    @Test
    void buildGrantListsExactlyTheThreePlatformProjectTools() throws Exception {
        JsonNode body = objectMapper.readTree("""
                {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
                """);

        JsonNode response = controller.invoke(body, request(PlatformMcpScopes.ALL)).getBody();

        assertThat(response).isNotNull();
        assertThat(response.at("/result/tools")).hasSize(3);
        assertThat(response.at("/result/tools/0/name").asText())
                .isEqualTo("platformProjectCreate");
        assertThat(response.at("/result/tools/1/name").asText())
                .isEqualTo("platformProjectProvision");
        assertThat(response.at("/result/tools/2/name").asText())
                .isEqualTo("platformProjectStatus");
        assertThat(response.toString())
                .doesNotContain("executeSql")
                .doesNotContain("database")
                .doesNotContain("serviceRole");
    }

    @Test
    void readGrantListsOnlyStatusAndCannotInvokeCreate() throws Exception {
        MockHttpServletRequest request = request(Set.of(PlatformMcpScopes.PROJECT_READ));
        JsonNode list = controller.invoke(objectMapper.readTree("""
                {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
                """), request).getBody();
        assertThat(list.at("/result/tools")).hasSize(1);
        assertThat(list.at("/result/tools/0/name").asText())
                .isEqualTo("platformProjectStatus");

        var denied = controller.invoke(objectMapper.readTree("""
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
                  "name":"platformProjectCreate","arguments":{}
                }}
                """), request);
        assertThat(denied.getStatusCode().value()).isEqualTo(403);
        assertThat(denied.getBody().at("/error/message").asText()).isEqualTo("Forbidden");
    }

    @Test
    void rejectsUnknownAndSensitiveToolArgumentsBeforeDtoBinding() throws Exception {
        JsonNode body = objectMapper.readTree("""
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{
                  "name":"platformProjectCreate","arguments":{
                    "idempotencyKey":"idem-key-1",
                    "ref":"goai_notes",
                    "name":"Notes",
                    "taskId":"task-1",
                    "runId":"run-1",
                    "specDigest":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    "sql":"select 1",
                    "token":"secret-sentinel",
                    "serviceRoleToken":"secret-sentinel"
                  }
                }}
                """);

        JsonNode response = controller.invoke(body, request(PlatformMcpScopes.ALL)).getBody();

        assertThat(response.at("/error/code").asInt()).isEqualTo(-32602);
        assertThat(response.at("/error/message").asText()).isEqualTo("Invalid params");
        assertThat(response.toString()).doesNotContain("secret-sentinel");
        verifyNoInteractions(facade);
    }

    @Test
    void rejectsMissingOrNonTextualArgumentsBeforeJacksonCoercion() throws Exception {
        JsonNode numericTrace = objectMapper.readTree("""
                {"jsonrpc":"2.0","id":7,"method":"tools/call","params":{
                  "name":"platformProjectStatus","arguments":{
                    "ref":"goai_notes",
                    "taskId":123,
                    "runId":"run-1",
                    "specDigest":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                  }
                }}
                """);
        JsonNode missingTrace = objectMapper.readTree("""
                {"jsonrpc":"2.0","id":8,"method":"tools/call","params":{
                  "name":"platformProjectStatus","arguments":{
                    "ref":"goai_notes",
                    "taskId":"task-1",
                    "runId":"run-1"
                  }
                }}
                """);

        JsonNode numericResponse = controller.invoke(
                numericTrace, request(PlatformMcpScopes.ALL)).getBody();
        JsonNode missingResponse = controller.invoke(
                missingTrace, request(PlatformMcpScopes.ALL)).getBody();

        assertThat(numericResponse.at("/error/code").asInt()).isEqualTo(-32602);
        assertThat(missingResponse.at("/error/code").asInt()).isEqualTo(-32602);
        verifyNoInteractions(facade);
    }

    @Test
    void initializeAdvertisesOnlyTheCurrentProtocolVersion() throws Exception {
        JsonNode response = controller.invoke(objectMapper.readTree("""
                {"jsonrpc":"2.0","id":4,"method":"initialize","params":{
                  "protocolVersion":"2024-11-05",
                  "capabilities":{},
                  "clientInfo":{"name":"test-client","version":"1.0.0"}
                }}
                """), request(PlatformMcpScopes.ALL)).getBody();

        assertThat(response.at("/result/protocolVersion").asText())
                .isEqualTo("2025-03-26");
        assertThat(response.toString()).doesNotContain("2024-11-05");
    }

    @Test
    void initializeNegotiatesTheLatestSupportedVersionForANewerClient() throws Exception {
        JsonNode response = controller.invoke(objectMapper.readTree("""
                {"jsonrpc":"2.0","id":5,"method":"initialize","params":{
                  "protocolVersion":"2025-11-25",
                  "capabilities":{},
                  "clientInfo":{"name":"test-client","version":"1.0.0"}
                }}
                """), request(PlatformMcpScopes.ALL)).getBody();

        assertThat(response.at("/result/protocolVersion").asText())
                .isEqualTo("2025-03-26");
    }

    @Test
    void initializeRejectsAMissingProtocolVersion() throws Exception {
        JsonNode response = controller.invoke(objectMapper.readTree("""
                {"jsonrpc":"2.0","id":6,"method":"initialize","params":{}}
                """), request(PlatformMcpScopes.ALL)).getBody();

        assertThat(response.at("/error/code").asInt()).isEqualTo(-32602);
        assertThat(response.at("/error/message").asText()).isEqualTo("Invalid params");
    }

    @Test
    void initializeRejectsMalformedCapabilitiesAndClientInfo() throws Exception {
        for (String params : List.of(
                """
                {"protocolVersion":"2025-03-26","clientInfo":{"name":"client","version":"1"}}
                """,
                """
                {"protocolVersion":"2025-03-26","capabilities":[],"clientInfo":{"name":"client","version":"1"}}
                """,
                """
                {"protocolVersion":"2025-03-26","capabilities":{}}
                """,
                """
                {"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":7,"version":"1"}}
                """,
                """
                {"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"client"}}
                """)) {
            JsonNode response = controller.invoke(objectMapper.readTree("""
                    {"jsonrpc":"2.0","id":"init","method":"initialize","params":%s}
                    """.formatted(params)), request(PlatformMcpScopes.ALL)).getBody();

            assertThat(response.at("/error/code").asInt()).isEqualTo(-32602);
            assertThat(response.at("/error/message").asText()).isEqualTo("Invalid params");
        }
        verifyNoInteractions(facade);
    }

    @Test
    void rejectsNullBooleanCompositeAndFractionalRequestIds() throws Exception {
        for (String id : List.of("null", "true", "{}", "[]", "1.5")) {
            var response = controller.invoke(objectMapper.readTree("""
                    {"jsonrpc":"2.0","id":%s,"method":"ping","params":{}}
                    """.formatted(id)), request(PlatformMcpScopes.ALL));

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody().at("/id").isNull()).isTrue();
            assertThat(response.getBody().at("/error/code").asInt()).isEqualTo(-32600);
        }
        verifyNoInteractions(facade);
    }

    @Test
    void mixedBatchRespondsOnlyToRequestsAndInvalidMessages() throws Exception {
        var response = controller.invoke(objectMapper.readTree("""
                [
                  {"jsonrpc":"2.0","id":"list-1","method":"tools/list","params":{}},
                  {"jsonrpc":"2.0","method":"notifications/initialized","params":{}},
                  {"jsonrpc":"2.0","method":"notifications/cancelled","params":{
                    "requestId":"work-1","reason":"client cancelled"
                  }},
                  {"jsonrpc":"2.0","id":2,"method":"ping","params":{}},
                  42
                ]
                """), request(PlatformMcpScopes.ALL));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).hasSize(3);
        assertThat(response.getBody().get(0).get("id").asText()).isEqualTo("list-1");
        assertThat(response.getBody().get(1).get("id").asInt()).isEqualTo(2);
        assertThat(response.getBody().get(2).get("id").isNull()).isTrue();
        assertThat(response.getBody().get(2).at("/error/code").asInt()).isEqualTo(-32600);
        verifyNoInteractions(facade);
    }

    @Test
    void allRecognizedNotificationsReturnAcceptedWithoutABody() throws Exception {
        var response = controller.invoke(objectMapper.readTree("""
                [
                  {"jsonrpc":"2.0","method":"notifications/initialized"},
                  {"jsonrpc":"2.0","method":"notifications/cancelled","params":{
                    "requestId":17
                  }}
                ]
                """), request(PlatformMcpScopes.ALL));

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody()).isNull();
        verifyNoInteractions(facade);
    }

    @Test
    void responseOnlyPostsReturnAcceptedWithoutABody() throws Exception {
        var single = controller.invoke(objectMapper.readTree("""
                {"jsonrpc":"2.0","id":"server-request","result":{}}
                """), request(PlatformMcpScopes.ALL));
        var batch = controller.invoke(objectMapper.readTree("""
                [
                  {"jsonrpc":"2.0","id":"one","result":{}},
                  {"jsonrpc":"2.0","id":"two","error":{"code":-1,"message":"failed"}}
                ]
                """), request(PlatformMcpScopes.ALL));

        assertThat(single.getStatusCode().value()).isEqualTo(202);
        assertThat(single.getBody()).isNull();
        assertThat(batch.getStatusCode().value()).isEqualTo(202);
        assertThat(batch.getBody()).isNull();
        verifyNoInteractions(facade);
    }

    @Test
    void emptyBatchIsRejected() throws Exception {
        var response = controller.invoke(
                objectMapper.readTree("[]"), request(PlatformMcpScopes.ALL));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().at("/error/code").asInt()).isEqualTo(-32600);
        verifyNoInteractions(facade);
    }

    @Test
    void unsupportedNotificationIsRejectedWithoutEchoingPayload() throws Exception {
        String sentinel = "unsupported-notification-secret-sentinel";
        var response = controller.invoke(objectMapper.readTree("""
                {"jsonrpc":"2.0","method":"notifications/unsupported","params":{
                  "token":"%s"
                }}
                """.formatted(sentinel)), request(PlatformMcpScopes.ALL));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().at("/error/code").asInt()).isEqualTo(-32601);
        assertThat(response.getBody().toString()).doesNotContain(sentinel);
        verifyNoInteractions(facade);
    }

    @Test
    void unsupportedNotificationFailsBatchBeforeAnyToolSideEffect() throws Exception {
        String sentinel = "unsupported-batch-secret-sentinel";
        var response = controller.invoke(objectMapper.readTree("""
                [
                  %s,
                  {"jsonrpc":"2.0","method":"notifications/unsupported","params":{
                    "token":"%s"
                  }}
                ]
                """.formatted(validCreateRequest(), sentinel)), request(PlatformMcpScopes.ALL));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().at("/error/code").asInt()).isEqualTo(-32601);
        assertThat(response.getBody().toString()).doesNotContain(sentinel);
        verifyNoInteractions(facade);
    }

    @Test
    void initializeFailsBatchBeforeAnyToolSideEffect() throws Exception {
        var response = controller.invoke(objectMapper.readTree("""
                [
                  %s,
                  {"jsonrpc":"2.0","id":"init","method":"initialize","params":{
                    "protocolVersion":"2025-03-26",
                    "capabilities":{},
                    "clientInfo":{"name":"test-client","version":"1.0.0"}
                  }}
                ]
                """.formatted(validCreateRequest())), request(PlatformMcpScopes.ALL));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().at("/error/code").asInt()).isEqualTo(-32600);
        verifyNoInteractions(facade);
    }

    @Test
    void mixedDirectionBatchFailsBeforeAnyToolSideEffect() throws Exception {
        var response = controller.invoke(objectMapper.readTree("""
                [
                  %s,
                  {"jsonrpc":"2.0","id":"server-request","result":{}}
                ]
                """.formatted(validCreateRequest())), request(PlatformMcpScopes.ALL));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().at("/error/code").asInt()).isEqualTo(-32600);
        verifyNoInteractions(facade);
    }

    private static String validCreateRequest() {
        return """
                {"jsonrpc":"2.0","id":"create","method":"tools/call","params":{
                  "name":"platformProjectCreate","arguments":{
                    "idempotencyKey":"idem-key-1",
                    "ref":"goai_notes",
                    "name":"Notes",
                    "taskId":"task-1",
                    "runId":"run-1",
                    "specDigest":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                  }
                }}
                """;
    }

    private MockHttpServletRequest request(Set<String> scopes) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/platform/mcp");
        request.setAttribute(
                PlatformMcpAutomationAuthFilter.PRINCIPAL_ATTRIBUTE,
                new AutomationPrincipal(
                        "agentteams.local",
                        UUID.fromString("11111111-1111-1111-1111-111111111111"),
                        "jti",
                        1,
                        scopes,
                        "goai_",
                        2,
                        null));
        return request;
    }
}
