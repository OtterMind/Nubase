package ai.nubase.functions.executor;

import java.util.Map;

public record EdgeFunctionDeploymentRequest(
        String projectRef,
        String functionSlug,
        String sourceHash,
        String artifactUri,
        String artifactType,
        String sourceBundleBase64,
        Map<String, String> env
) {
}
