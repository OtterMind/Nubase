package ai.nubase.functions.executor;

import lombok.extern.slf4j.Slf4j;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Locale;

@Slf4j
@Component
@ConditionalOnProperty(value = "nubase.functions.enabled", havingValue = "true", matchIfMissing = true)
public class LocalHttpEdgeFunctionExecutor extends AbstractHttpEdgeFunctionExecutor {

    public LocalHttpEdgeFunctionExecutor(EdgeFunctionExecutorProperties properties) {
        super(properties);
    }

    @Override
    public String provider() {
        return "local";
    }

    @Override
    public EdgeFunctionDeploymentResponse deploy(EdgeFunctionDeploymentRequest request) {
        String deploymentId = request.projectRef() + "/" + request.functionSlug();
        return EdgeFunctionDeploymentResponse.deployed(provider(), deploymentId);
    }

    // The local runtime holds no deploy-time state, so secrets must be supplied on
    // every invocation (forwarded as x-nubase-env-* headers below).
    @Override
    public boolean injectsEnvAtInvoke() {
        return true;
    }

    @Override
    public void delete(String projectRef, String functionSlug, String providerDeploymentId) {
        log.debug("Local edge function delete is a no-op: projectRef={}, slug={}, deploymentId={}",
                projectRef, functionSlug, providerDeploymentId);
    }

    @Override
    public EdgeFunctionInvocationResponse invoke(EdgeFunctionInvocationRequest request) {
        String url = buildUrl(properties.getLocal().getBaseUrl(), request);
        RequestBody body = buildRequestBody(request, request.body());
        Request.Builder builder = new Request.Builder().url(url).method(request.method(), body);

        copyForwardableHeaders(request, builder);
        builder.header("x-nubase-request-id", request.requestId());
        builder.header("x-nubase-project-ref", request.projectRef());
        builder.header("x-nubase-function-slug", request.functionSlug());
        request.env().forEach((key, value) -> builder.header("x-nubase-env-" + key.toLowerCase(Locale.ROOT), value));

        try (Response response = httpClient(request.timeoutSeconds()).newCall(builder.build()).execute()) {
            byte[] responseBytes = readBody(response.body());
            return new EdgeFunctionInvocationResponse(
                    response.code(),
                    toHeaderMap(response.headers()),
                    responseBytes,
                    null,
                    null
            );
        } catch (IOException | RuntimeException e) {
            log.warn("Local edge function invocation failed: projectRef={}, slug={}, error={}",
                    request.projectRef(), request.functionSlug(), e.toString());
            return EdgeFunctionInvocationResponse.error(502, "EXECUTOR_ERROR", e.getMessage());
        }
    }
}
