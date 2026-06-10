package ai.nubase.functions.executor;

public interface EdgeFunctionExecutor {

    String provider();

    EdgeFunctionDeploymentResponse deploy(EdgeFunctionDeploymentRequest request);

    void delete(String projectRef, String functionSlug, String providerDeploymentId);

    EdgeFunctionInvocationResponse invoke(EdgeFunctionInvocationRequest request);
}
