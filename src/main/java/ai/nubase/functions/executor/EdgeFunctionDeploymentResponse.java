package ai.nubase.functions.executor;

public record EdgeFunctionDeploymentResponse(
        String provider,
        String providerDeploymentId,
        String status,
        String errorMessage
) {
    public static EdgeFunctionDeploymentResponse deployed(String provider, String providerDeploymentId) {
        return new EdgeFunctionDeploymentResponse(provider, providerDeploymentId, "deployed", null);
    }

    public static EdgeFunctionDeploymentResponse failed(String provider, String errorMessage) {
        return new EdgeFunctionDeploymentResponse(provider, null, "failed", errorMessage);
    }
}
