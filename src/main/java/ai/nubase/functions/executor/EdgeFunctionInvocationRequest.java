package ai.nubase.functions.executor;

import java.util.List;
import java.util.Map;

public record EdgeFunctionInvocationRequest(
        String requestId,
        String projectRef,
        String functionSlug,
        String providerDeploymentId,
        String method,
        String path,
        String queryString,
        Map<String, List<String>> headers,
        byte[] body,
        Map<String, String> env
) {
}
