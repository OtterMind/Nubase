package ai.nubase.platform.mcp;

import ai.nubase.platform.mcp.PlatformProjectDtos.CreateRequest;
import ai.nubase.platform.mcp.PlatformProjectDtos.ProvisionRequest;
import ai.nubase.platform.mcp.PlatformProjectDtos.StatusRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/platform/mcp")
@ConditionalOnProperty(prefix = "nubase.platform-mcp", name = "enabled", havingValue = "true")
public class PlatformMcpController {

    private static final String JSON_RPC_VERSION = "2.0";
    private static final String SUPPORTED_PROTOCOL_VERSION = "2025-03-26";
    private static final String SAFE_PATH = "/platform/mcp";
    private static final Set<String> REQUEST_KEYS = Set.of("jsonrpc", "id", "method", "params");
    private static final Set<String> NOTIFICATION_KEYS = Set.of("jsonrpc", "method", "params");
    private static final Set<String> RESPONSE_RESULT_KEYS = Set.of("jsonrpc", "id", "result");
    private static final Set<String> RESPONSE_ERROR_KEYS = Set.of("jsonrpc", "id", "error");
    private static final Set<String> ERROR_KEYS = Set.of("code", "message", "data");
    private static final Set<String> INITIALIZE_PARAM_KEYS =
            Set.of("protocolVersion", "capabilities", "clientInfo");
    private static final Set<String> CLIENT_INFO_KEYS = Set.of("name", "version");
    private static final Set<String> TOOL_CALL_PARAM_KEYS = Set.of("name", "arguments");
    private static final Set<String> CANCELLED_PARAM_KEYS = Set.of("requestId", "reason");

    private final PlatformProjectAutomationFacade projectFacade;
    private final ObjectMapper objectMapper;

    @PostMapping({"", "/"})
    public ResponseEntity<JsonNode> invoke(
            @RequestBody JsonNode requestBody, HttpServletRequest servletRequest) {
        try {
            return requestBody != null && requestBody.isArray()
                    ? invokeBatch((ArrayNode) requestBody, servletRequest)
                    : invokeSingle(requestBody, servletRequest);
        } catch (PlatformMcpOperationException e) {
            return ok(toolError(responseId(requestBody), e.code()));
        } catch (Exception e) {
            log.error("Platform MCP request failed for {}: errorType={}",
                    SAFE_PATH, e.getClass().getSimpleName());
            return ok(error(responseId(requestBody), -32603, "Internal error"));
        }
    }

    private ResponseEntity<JsonNode> invokeSingle(
            JsonNode message, HttpServletRequest servletRequest) {
        MessageKind kind = classify(message);
        if (isNotificationCandidate(message) && kind != MessageKind.NOTIFICATION) {
            return badRequest(error(null, -32602, "Invalid notification"));
        }
        return switch (kind) {
            case REQUEST -> invokeRequest(message, servletRequest);
            case NOTIFICATION -> acceptedNotification(message)
                    ? accepted()
                    : badRequest(error(null, -32601, "Unsupported notification"));
            case RESPONSE -> accepted();
            case INVALID -> ok(error(responseId(message), -32600, "Invalid Request"));
        };
    }

    private ResponseEntity<JsonNode> invokeBatch(
            ArrayNode batch, HttpServletRequest servletRequest) {
        if (batch.isEmpty()) {
            return badRequest(error(null, -32600, "Invalid Request"));
        }
        ResponseEntity<JsonNode> preflightFailure = preflightBatch(batch);
        if (preflightFailure != null) {
            return preflightFailure;
        }

        ArrayNode responses = objectMapper.createArrayNode();
        for (JsonNode message : batch) {
            MessageKind kind = classify(message);
            if (kind == MessageKind.REQUEST) {
                ResponseEntity<JsonNode> response = invokeRequest(message, servletRequest);
                if (response.getBody() != null) {
                    responses.add(response.getBody());
                }
            } else if (kind == MessageKind.INVALID) {
                responses.add(error(responseId(message), -32600, "Invalid Request"));
            }
        }
        return responses.isEmpty() ? accepted() : ok(responses);
    }

    private ResponseEntity<JsonNode> preflightBatch(ArrayNode batch) {
        boolean hasRequestOrNotification = false;
        boolean hasResponse = false;
        for (JsonNode message : batch) {
            if (isMethod(message, "initialize")) {
                return badRequest(error(null, -32600, "Initialize must not be batched"));
            }
            MessageKind kind = classify(message);
            if (kind == MessageKind.REQUEST || kind == MessageKind.NOTIFICATION) {
                hasRequestOrNotification = true;
            } else if (kind == MessageKind.RESPONSE) {
                hasResponse = true;
            }
            if (hasRequestOrNotification && hasResponse) {
                return badRequest(error(null, -32600, "Invalid Request"));
            }
            if (isNotificationCandidate(message)) {
                if (kind != MessageKind.NOTIFICATION) {
                    return badRequest(error(null, -32602, "Invalid notification"));
                }
                if (!acceptedNotification(message)) {
                    return badRequest(error(null, -32601, "Unsupported notification"));
                }
            }
        }
        return null;
    }

    private ResponseEntity<JsonNode> invokeRequest(
            JsonNode message, HttpServletRequest servletRequest) {
        JsonNode id = message.get("id");
        AutomationPrincipal principal = principal(servletRequest);
        String method = text(message, "method");
        if ("initialize".equals(method)) {
            if (!validInitializeParams(message.get("params"))) {
                return ok(error(id, -32602, "Invalid params"));
            }
            return ok(success(id, initializeResult(SUPPORTED_PROTOCOL_VERSION)));
        }
        if ("ping".equals(method)) {
            return ok(success(id, objectMapper.createObjectNode()));
        }
        if ("tools/list".equals(method)) {
            return ok(success(id, toolsList(principal)));
        }
        if ("tools/call".equals(method)) {
            return callTool(id, message.get("params"), principal);
        }
        return ok(error(id, -32601, "Method not found"));
    }

    private ResponseEntity<JsonNode> callTool(
            JsonNode id, JsonNode params, AutomationPrincipal principal) {
        if (params == null || !params.isObject()) {
            return ok(error(id, -32602, "Invalid params"));
        }
        String toolName = text(params, "name");
        String requiredScope = requiredScope(toolName);
        if (requiredScope == null) {
            return ok(error(id, -32602, "Unknown tool"));
        }
        if (!principal.hasScope(requiredScope)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(error(id, -32003, "Forbidden"));
        }
        JsonNode arguments = params.get("arguments");
        if (!hasOnlyKeys(params, TOOL_CALL_PARAM_KEYS)
                || arguments == null
                || !arguments.isObject()
                || !hasValidArgumentShape(toolName, arguments)) {
            return ok(error(id, -32602, "Invalid params"));
        }

        try {
            Object result = switch (toolName) {
                case PlatformProjectAutomationFacade.CREATE_ACTION -> projectFacade.create(
                        principal, objectMapper.treeToValue(arguments, CreateRequest.class));
                case PlatformProjectAutomationFacade.PROVISION_ACTION -> projectFacade.provision(
                        principal, objectMapper.treeToValue(arguments, ProvisionRequest.class));
                case "platformProjectStatus" -> projectFacade.status(
                        principal, objectMapper.treeToValue(arguments, StatusRequest.class));
                default -> throw new PlatformMcpOperationException("UNKNOWN_TOOL");
            };
            return ok(success(id, toolResult(result, false)));
        } catch (PlatformMcpOperationException e) {
            return ok(success(id, toolResult(Map.of("error", e.code()), true)));
        } catch (Exception e) {
            log.error("Platform MCP tool failed for {}: errorType={}",
                    SAFE_PATH, e.getClass().getSimpleName());
            return ok(success(id, toolResult(Map.of("error", "INTERNAL_ERROR"), true)));
        }
    }

    private ObjectNode toolsList(AutomationPrincipal principal) {
        ArrayNode tools = objectMapper.createArrayNode();
        if (principal.hasScope(PlatformMcpScopes.PROJECT_CREATE)) {
            tools.add(tool(
                    PlatformProjectAutomationFacade.CREATE_ACTION,
                    "Create one actor-owned project configuration with durable idempotency.",
                    createSchema()));
        }
        if (principal.hasScope(PlatformMcpScopes.PROJECT_PROVISION)) {
            tools.add(tool(
                    PlatformProjectAutomationFacade.PROVISION_ACTION,
                    "Start or deduplicate provisioning for one actor-owned project.",
                    provisionSchema()));
        }
        if (principal.hasScope(PlatformMcpScopes.PROJECT_READ)) {
            tools.add(tool(
                    "platformProjectStatus",
                    "Read safe provisioning state and static control-plane checks for one actor-owned project.",
                    statusSchema()));
        }
        ObjectNode result = objectMapper.createObjectNode();
        result.set("tools", tools);
        return result;
    }

    private ObjectNode initializeResult(String protocolVersion) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("protocolVersion", protocolVersion);
        ObjectNode capabilities = result.putObject("capabilities");
        capabilities.putObject("tools").put("listChanged", false);
        ObjectNode serverInfo = result.putObject("serverInfo");
        serverInfo.put("name", "nubase-platform-mcp");
        serverInfo.put("version", "1.0.0");
        return result;
    }

    private ObjectNode toolResult(Object result, boolean error) {
        JsonNode structured = objectMapper.valueToTree(result);
        ObjectNode envelope = objectMapper.createObjectNode();
        ArrayNode content = envelope.putArray("content");
        ObjectNode textContent = content.addObject();
        textContent.put("type", "text");
        try {
            textContent.put("text", objectMapper.writeValueAsString(structured));
        } catch (Exception e) {
            textContent.put("text", "{\"error\":\"INTERNAL_ERROR\"}");
            error = true;
        }
        envelope.set("structuredContent", structured);
        envelope.put("isError", error);
        return envelope;
    }

    private ObjectNode tool(String name, String description, ObjectNode inputSchema) {
        ObjectNode tool = objectMapper.createObjectNode();
        tool.put("name", name);
        tool.put("description", description);
        tool.set("inputSchema", inputSchema);
        return tool;
    }

    private ObjectNode createSchema() {
        ObjectNode schema = baseMutationSchema();
        ObjectNode properties = (ObjectNode) schema.get("properties");
        properties.set("name", stringProperty("Project display name"));
        properties.set("description", stringProperty("Optional project description"));
        ((ArrayNode) schema.get("required")).add("name");
        return schema;
    }

    private ObjectNode provisionSchema() {
        return baseMutationSchema();
    }

    private ObjectNode statusSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        addTraceProperties(properties);
        ArrayNode required = schema.putArray("required");
        addRequiredTrace(required);
        schema.put("additionalProperties", false);
        return schema;
    }

    private ObjectNode baseMutationSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.set("idempotencyKey", stringProperty("Stable retry key"));
        addTraceProperties(properties);
        ArrayNode required = schema.putArray("required");
        required.add("idempotencyKey");
        addRequiredTrace(required);
        schema.put("additionalProperties", false);
        return schema;
    }

    private void addTraceProperties(ObjectNode properties) {
        properties.set("ref", stringProperty("Lowercase project reference"));
        properties.set("taskId", stringProperty("GOAI task identifier"));
        properties.set("runId", stringProperty("GOAI run identifier"));
        properties.set("specDigest", stringProperty("sha256:<hex> specification digest"));
        properties.set("approvalId", stringProperty("Optional approval binding"));
    }

    private static void addRequiredTrace(ArrayNode required) {
        for (String field : List.of("ref", "taskId", "runId", "specDigest")) {
            required.add(field);
        }
    }

    private ObjectNode stringProperty(String description) {
        ObjectNode property = objectMapper.createObjectNode();
        property.put("type", "string");
        property.put("description", description);
        return property;
    }

    private ObjectNode success(JsonNode id, JsonNode result) {
        ObjectNode response = baseResponse(id);
        response.set("result", result);
        return response;
    }

    private ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode response = baseResponse(id);
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message);
        return response;
    }

    private ObjectNode toolError(JsonNode id, String code) {
        return success(id, toolResult(Map.of("error", code), true));
    }

    private ObjectNode baseResponse(JsonNode id) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", JSON_RPC_VERSION);
        response.set("id", id == null ? objectMapper.nullNode() : id);
        return response;
    }

    private static String requiredScope(String toolName) {
        return switch (toolName) {
            case PlatformProjectAutomationFacade.CREATE_ACTION -> PlatformMcpScopes.PROJECT_CREATE;
            case PlatformProjectAutomationFacade.PROVISION_ACTION -> PlatformMcpScopes.PROJECT_PROVISION;
            case "platformProjectStatus" -> PlatformMcpScopes.PROJECT_READ;
            default -> null;
        };
    }

    private static boolean hasValidArgumentShape(String toolName, JsonNode arguments) {
        Set<String> allowed = switch (toolName) {
            case PlatformProjectAutomationFacade.CREATE_ACTION -> Set.of(
                    "idempotencyKey", "ref", "name", "description",
                    "taskId", "runId", "specDigest", "approvalId");
            case PlatformProjectAutomationFacade.PROVISION_ACTION -> Set.of(
                    "idempotencyKey", "ref", "taskId", "runId", "specDigest", "approvalId");
            case "platformProjectStatus" -> Set.of(
                    "ref", "taskId", "runId", "specDigest", "approvalId");
            default -> Set.of();
        };
        Set<String> required = switch (toolName) {
            case PlatformProjectAutomationFacade.CREATE_ACTION -> Set.of(
                    "idempotencyKey", "ref", "name", "taskId", "runId", "specDigest");
            case PlatformProjectAutomationFacade.PROVISION_ACTION -> Set.of(
                    "idempotencyKey", "ref", "taskId", "runId", "specDigest");
            case "platformProjectStatus" -> Set.of(
                    "ref", "taskId", "runId", "specDigest");
            default -> Set.of();
        };
        if (!required.stream().allMatch(arguments::has)) {
            return false;
        }
        var fields = arguments.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!allowed.contains(field) || !arguments.get(field).isTextual()) {
                return false;
            }
        }
        return true;
    }

    private static boolean validInitializeParams(JsonNode params) {
        if (params == null
                || !params.isObject()
                || !hasOnlyKeys(params, INITIALIZE_PARAM_KEYS)
                || !boundedText(params.get("protocolVersion"), 64)) {
            return false;
        }
        JsonNode capabilities = params.get("capabilities");
        JsonNode clientInfo = params.get("clientInfo");
        return capabilities != null
                && capabilities.isObject()
                && clientInfo != null
                && clientInfo.isObject()
                && hasOnlyKeys(clientInfo, CLIENT_INFO_KEYS)
                && boundedText(clientInfo.get("name"), 256)
                && boundedText(clientInfo.get("version"), 256);
    }

    private static boolean acceptedNotification(JsonNode message) {
        String method = text(message, "method");
        JsonNode params = message.get("params");
        if ("notifications/initialized".equals(method)) {
            return params == null || params.isObject();
        }
        if (!"notifications/cancelled".equals(method)
                || params == null
                || !params.isObject()
                || !hasOnlyKeys(params, CANCELLED_PARAM_KEYS)
                || !validRequestId(params.get("requestId"))) {
            return false;
        }
        JsonNode reason = params.get("reason");
        return reason == null || reason.isTextual();
    }

    private static MessageKind classify(JsonNode message) {
        if (message == null
                || !message.isObject()
                || !JSON_RPC_VERSION.equals(text(message, "jsonrpc"))) {
            return MessageKind.INVALID;
        }
        JsonNode method = message.get("method");
        if (method != null) {
            if (!method.isTextual() || method.textValue().isBlank() || !validParams(message)) {
                return MessageKind.INVALID;
            }
            if (message.has("id")) {
                return validRequestId(message.get("id")) && hasOnlyKeys(message, REQUEST_KEYS)
                        ? MessageKind.REQUEST
                        : MessageKind.INVALID;
            }
            return hasOnlyKeys(message, NOTIFICATION_KEYS)
                    ? MessageKind.NOTIFICATION
                    : MessageKind.INVALID;
        }
        return validResponse(message) ? MessageKind.RESPONSE : MessageKind.INVALID;
    }

    private static boolean validParams(JsonNode message) {
        return !message.has("params") || message.get("params").isObject();
    }

    private static boolean validResponse(JsonNode message) {
        if (!message.has("id") || !validResponseId(message.get("id"))) {
            return false;
        }
        boolean hasResult = message.has("result");
        boolean hasError = message.has("error");
        if (hasResult == hasError) {
            return false;
        }
        if (hasResult) {
            return hasOnlyKeys(message, RESPONSE_RESULT_KEYS);
        }
        JsonNode error = message.get("error");
        return hasOnlyKeys(message, RESPONSE_ERROR_KEYS)
                && error != null
                && error.isObject()
                && hasOnlyKeys(error, ERROR_KEYS)
                && error.has("code")
                && error.get("code").isIntegralNumber()
                && error.has("message")
                && error.get("message").isTextual();
    }

    private static boolean validRequestId(JsonNode id) {
        return id != null && (id.isTextual() || id.isIntegralNumber());
    }

    private static boolean validResponseId(JsonNode id) {
        return validRequestId(id);
    }

    private static JsonNode responseId(JsonNode message) {
        if (message != null && message.isObject()) {
            JsonNode id = message.get("id");
            if (validRequestId(id)) {
                return id;
            }
        }
        return null;
    }

    private static boolean boundedText(JsonNode value, int maxLength) {
        return value != null
                && value.isTextual()
                && !value.textValue().isBlank()
                && value.textValue().length() <= maxLength;
    }

    private static boolean hasOnlyKeys(JsonNode node, Set<String> allowed) {
        if (node == null || !node.isObject()) {
            return false;
        }
        var fields = node.fieldNames();
        while (fields.hasNext()) {
            if (!allowed.contains(fields.next())) {
                return false;
            }
        }
        return true;
    }

    private static boolean isMethod(JsonNode message, String method) {
        return message != null
                && message.isObject()
                && method.equals(text(message, "method"));
    }

    private static boolean isNotificationCandidate(JsonNode message) {
        return message != null
                && message.isObject()
                && !message.has("id")
                && message.has("method");
    }

    private static AutomationPrincipal principal(HttpServletRequest request) {
        Object principal = request.getAttribute(PlatformMcpAutomationAuthFilter.PRINCIPAL_ATTRIBUTE);
        if (principal instanceof AutomationPrincipal automationPrincipal) {
            return automationPrincipal;
        }
        throw new PlatformMcpOperationException("AUTHENTICATION_REQUIRED");
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static ResponseEntity<JsonNode> ok(JsonNode body) {
        return ResponseEntity.ok(body);
    }

    private static ResponseEntity<JsonNode> accepted() {
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    private static ResponseEntity<JsonNode> badRequest(JsonNode body) {
        return ResponseEntity.badRequest().body(body);
    }

    private enum MessageKind {
        REQUEST,
        NOTIFICATION,
        RESPONSE,
        INVALID
    }
}
