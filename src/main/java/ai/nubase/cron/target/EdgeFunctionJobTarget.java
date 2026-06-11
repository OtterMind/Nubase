package ai.nubase.cron.target;

import ai.nubase.functions.executor.EdgeFunctionInvocationResponse;
import ai.nubase.functions.service.EdgeFunctionExceptions.EdgeFunctionException;
import ai.nubase.functions.service.EdgeFunctionInvocationCommand;
import ai.nubase.functions.service.EdgeFunctionInvocationService;
import ai.nubase.metadata.cron.entity.ScheduledJob;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Invokes an edge function through the regular invocation service, so scheduled
 * calls get the same rate limits, secrets handling and invocation logging as
 * gateway traffic. callerRole=cron bypasses verify_jwt (platform-initiated,
 * like Supabase's pg_cron calling functions with the service key).
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(value = "nubase.cron.enabled", havingValue = "true", matchIfMissing = true)
public class EdgeFunctionJobTarget implements ScheduledJobTarget {

    // ObjectProvider because the functions module has its own kill switch
    // (nubase.functions.enabled) and may be absent from the context.
    private final ObjectProvider<EdgeFunctionInvocationService> invocationService;

    @Override
    public String type() {
        return ScheduledJob.TARGET_EDGE_FUNCTION;
    }

    @Override
    public RunOutcome execute(ScheduledJob job) {
        EdgeFunctionInvocationService service = invocationService.getIfAvailable();
        if (service == null) {
            return RunOutcome.failure(null, "FUNCTIONS_DISABLED: edge functions module is disabled (nubase.functions.enabled=false)");
        }
        byte[] body = job.getRequestBody() == null ? new byte[0] : job.getRequestBody().getBytes(StandardCharsets.UTF_8);
        Map<String, List<String>> headers = body.length == 0
                ? Map.of()
                : Map.of("content-type", List.of("application/json"));
        try {
            EdgeFunctionInvocationResponse response = service.invoke(job.getFunctionSlug(), new EdgeFunctionInvocationCommand(
                    "cron-" + UUID.randomUUID(),
                    StringUtils.hasText(job.getHttpMethod()) ? job.getHttpMethod().toUpperCase(Locale.ROOT) : "POST",
                    normalizePath(job.getRequestPath()),
                    null,
                    headers,
                    body,
                    EdgeFunctionInvocationCommand.ROLE_CRON,
                    null,
                    job.getTimeoutSeconds()
            ));
            String result = "HTTP " + response.statusCode();
            if (response.statusCode() >= 400) {
                return RunOutcome.failure(result, snippet(response.body()));
            }
            return RunOutcome.success(result);
        } catch (EdgeFunctionException e) {
            return RunOutcome.failure("HTTP " + e.status().value(), e.code() + ": " + e.getMessage());
        }
    }

    private String normalizePath(String path) {
        if (!StringUtils.hasText(path)) return "";
        return path.startsWith("/") ? path : "/" + path;
    }

    private String snippet(byte[] body) {
        if (body == null || body.length == 0) return null;
        String text = new String(body, 0, Math.min(body.length, 500), StandardCharsets.UTF_8);
        return text;
    }
}
