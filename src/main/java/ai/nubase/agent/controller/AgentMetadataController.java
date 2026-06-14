package ai.nubase.agent.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/agent/v1")
public class AgentMetadataController {

    @GetMapping("/instructions")
    public ResponseEntity<Map<String, Object>> instructions() {
        String instructions = """
                Use Nubase as the project backend.
                Use Memory for durable user, project, and agent context.
                First call memoryContext for relevant user, project, and task context.
                Use listTables and getTableStructure before writing SQL.
                Prefer REST APIs for application data through /rest/v1.
                Use Auth through /auth/v1 for users and sessions.
                Use Storage through /storage/v1 for buckets, objects, and signed URLs.
                Use Assets through /assets/v1 and /assets/admin/v1 to publish generated static frontends.
                Use Functions through /functions/v1 and /functions/admin/v1 to deploy backend logic.
                Use cron through /cron/admin/v1 to schedule recurring jobs.
                For generated apps, prefer deploy_app from the nubase_cli MCP bridge to orchestrate SQL, Functions, Assets, cron, and Memory.
                Use service_role only for server-side or admin actions.
                Write durable project decisions with memoryWrite.
                MCP tools are available at /mcp.
                AI Gateway is available through OpenAI-compatible /v1 endpoints and Anthropic-compatible /v1/messages.
                """;
        return ResponseEntity.ok(Map.of("instructions", instructions));
    }

    @GetMapping("/capabilities")
    public ResponseEntity<Map<String, Object>> capabilities() {
        return ResponseEntity.ok(Map.of(
                "name", "Nubase",
                "services", Map.of(
                        "memory", Map.of("enabled", true, "basePath", "/mem/v1"),
                        "database", Map.of("enabled", true, "basePath", "/rest/v1"),
                        "auth", Map.of("enabled", true, "basePath", "/auth/v1"),
                        "storage", Map.of("enabled", true, "basePath", "/storage/v1"),
                        "assets", Map.of(
                                "enabled", true,
                                "publicBasePath", "/assets/v1",
                                "adminBasePath", "/assets/admin/v1",
                                "purpose", "publish generated static frontends"
                        ),
                        "functions", Map.of(
                                "enabled", true,
                                "publicBasePath", "/functions/v1",
                                "adminBasePath", "/functions/admin/v1",
                                "purpose", "deploy edge/serverless backend logic"
                        ),
                        "cron", Map.of(
                                "enabled", true,
                                "adminBasePath", "/cron/admin/v1",
                                "purpose", "schedule edge functions or database functions"
                        ),
                        "mcp", Map.of("enabled", true, "endpoint", "/mcp"),
                        "aiGateway", Map.of(
                                "enabled", true,
                                "openAIBasePath", "/v1",
                                "anthropicMessagesPath", "/v1/messages"
                        )
                ),
                "deploy", Map.of(
                        "enabled", true,
                        "recommendedMcpTool", "deploy_app",
                        "cli", "nubase_cli app deploy <manifest.json>",
                        "supports", Set.of("sql", "functions", "assets", "cron", "memory")
                )
        ));
    }

    @GetMapping("/connect-config")
    public ResponseEntity<Map<String, Object>> connectConfig(String client) {
        String normalizedClient = normalizeClient(client);
        String baseUrl = "http://localhost:9999";
        String mcpEndpoint = baseUrl + "/mcp";
        Map<String, Object> mcpTemplate = Map.of(
                "mcpServers", Map.of(
                        "nubase", Map.of(
                                "url", mcpEndpoint,
                                "headers", Map.of("apikey", "YOUR_NUBASE_PROJECT_KEY")
                        )
                )
        );
        return ResponseEntity.ok(Map.of(
                "client", normalizedClient,
                "mcp", Map.of(
                        "endpoint", mcpEndpoint,
                        "headers", Map.of("apikey", "YOUR_NUBASE_PROJECT_KEY")
                ),
                "env", Map.of(
                        "NUBASE_URL", baseUrl,
                        "NUBASE_API_KEY", "YOUR_NUBASE_PROJECT_KEY",
                        "OPENAI_BASE_URL", baseUrl + "/v1",
                        "OPENAI_API_KEY", "YOUR_NUBASE_AI_GATEWAY_KEY",
                        "ANTHROPIC_BASE_URL", baseUrl,
                        "ANTHROPIC_AUTH_TOKEN", "YOUR_NUBASE_AI_GATEWAY_KEY"
                ),
                "aiGateway", Map.of(
                        "openAI", Map.of(
                                "baseUrl", baseUrl + "/v1",
                                "apiKey", "YOUR_NUBASE_AI_GATEWAY_KEY"
                        ),
                        "anthropic", Map.of(
                                "baseUrl", baseUrl,
                                "authToken", "YOUR_NUBASE_AI_GATEWAY_KEY"
                        )
                ),
                "templates", mcpTemplate
        ));
    }

    private String normalizeClient(String client) {
        if (client == null || client.isBlank()) {
            return "generic";
        }
        String normalized = client.trim().toLowerCase();
        Set<String> supported = Set.of("codex", "claude-code", "cursor", "idea", "generic");
        return supported.contains(normalized) ? normalized : "generic";
    }
}
