package ai.nubase.functions.service;

import ai.nubase.common.context.MultiTenancyContext;
import ai.nubase.functions.executor.EdgeFunctionExecutorRouter;
import ai.nubase.functions.executor.EdgeFunctionInvocationRequest;
import ai.nubase.functions.executor.EdgeFunctionInvocationResponse;
import ai.nubase.functions.util.EdgeFunctionNames;
import ai.nubase.metadata.edge.entity.EdgeFunction;
import ai.nubase.metadata.edge.entity.EdgeFunctionInvocation;
import ai.nubase.metadata.edge.entity.EdgeFunctionVersion;
import ai.nubase.metadata.edge.repository.EdgeFunctionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

import static ai.nubase.functions.service.EdgeFunctionExceptions.EdgeFunctionException;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(value = "nubase.functions.enabled", havingValue = "true", matchIfMissing = true)
public class EdgeFunctionInvocationService {

    private final EdgeFunctionRepository functionRepository;
    private final EdgeFunctionInvocationLogWriter logWriter;
    private final EdgeFunctionExecutorRouter executor;
    private final EdgeFunctionRateLimiter rateLimiter;
    private final EdgeFunctionSecretEnv secretEnv;

    // Deliberately NOT @Transactional: executor.invoke() is a remote HTTP call that can
    // run up to the executor timeout, and holding a metadata-pool connection across it
    // would let a handful of slow functions starve the whole control plane. The lookups
    // run in the repositories' own short transactions and the invocation log is written
    // by EdgeFunctionInvocationLogWriter in a separate transaction so it also survives
    // the EdgeFunctionException rethrow below.
    public EdgeFunctionInvocationResponse invoke(String functionSlug, EdgeFunctionInvocationCommand command) {
        long start = System.nanoTime();
        String projectRef = projectRef();
        String slug = normalizeSlug(functionSlug);
        EdgeFunctionVersion version = null;
        Integer status = null;
        String errorCode = null;
        String errorMessage = null;

        try {
            if (!command.hasRecognizedRole()) {
                throw new EdgeFunctionException(HttpStatus.UNAUTHORIZED, "JWT_REQUIRED", "Function requires a valid user JWT");
            }
            rateLimiter.check(projectRef, slug);
            EdgeFunction function = functionRepository.findByProjectRefAndSlug(projectRef, slug)
                    .orElseThrow(() -> new EdgeFunctionException(HttpStatus.NOT_FOUND, "FUNCTION_NOT_FOUND", "Function not found"));
            if (!Boolean.TRUE.equals(function.getEnabled())) {
                throw new EdgeFunctionException(HttpStatus.NOT_FOUND, "FUNCTION_NOT_FOUND", "Function not found");
            }
            if (Boolean.TRUE.equals(function.getVerifyJwt())
                    && EdgeFunctionInvocationCommand.ROLE_ANON.equals(command.callerRole())) {
                throw new EdgeFunctionException(HttpStatus.UNAUTHORIZED, "JWT_REQUIRED", "Function requires a valid user JWT");
            }
            version = function.getActiveVersion();
            if (version == null || !"deployed".equals(version.getStatus())) {
                throw new EdgeFunctionException(HttpStatus.SERVICE_UNAVAILABLE, "FUNCTION_NOT_DEPLOYED", "Function is not deployed");
            }

            EdgeFunctionInvocationResponse response = executor.invoke(new EdgeFunctionInvocationRequest(
                    command.requestId(),
                    projectRef,
                    slug,
                    version.getProviderDeploymentId(),
                    command.method(),
                    command.path(),
                    command.queryString(),
                    command.headers(),
                    command.body() == null ? new byte[0] : command.body(),
                    invocationEnv(function),
                    command.timeoutSeconds()
            ));
            status = response.statusCode();
            errorCode = response.errorCode();
            errorMessage = response.errorMessage();
            return response;
        } catch (EdgeFunctionException e) {
            status = e.status().value();
            errorCode = e.code();
            errorMessage = e.getMessage();
            throw e;
        } finally {
            int durationMs = (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - start) / 1_000_000);
            try {
                logWriter.write(EdgeFunctionInvocation.builder()
                        .requestId(command.requestId())
                        .projectRef(projectRef)
                        .functionSlug(slug)
                        .functionVersion(version)
                        .method(command.method())
                        .path(command.path() == null ? "" : command.path())
                        .statusCode(status)
                        .durationMs(durationMs)
                        .executorProvider(executor.provider())
                        .errorCode(errorCode)
                        .errorMessage(truncate(errorMessage, 1000))
                        .callerRole(command.callerRole())
                        .callerUserId(command.callerUserId())
                        .build());
            } catch (Exception e) {
                log.warn("Failed to record edge function invocation log: requestId={}, error={}",
                        command.requestId(), e.toString());
            }
        }
    }

    private Map<String, String> invocationEnv(EdgeFunction function) {
        Map<String, String> env = new LinkedHashMap<>();
        if (executor.injectsEnvAtInvoke()) {
            // Executors that bind secrets at deploy time (Cloudflare) never need them
            // here; only invoke-time executors (local) pay for the decryption.
            env.putAll(secretEnv.decryptedEnv(function));
        }
        // Built-ins last so a secret cannot shadow them. Do not add service_role by
        // default: privileged env will be introduced behind an explicit scoping design.
        env.put("NUBASE_PROJECT_REF", function.getProjectRef());
        env.put("NUBASE_FUNCTION_NAME", function.getSlug());
        return env;
    }

    private String projectRef() {
        String projectRef = MultiTenancyContext.getAppCode();
        if (!StringUtils.hasText(projectRef)) {
            throw new EdgeFunctionException(HttpStatus.UNAUTHORIZED, "TENANT_CONTEXT_REQUIRED", "Project context is required");
        }
        return projectRef;
    }

    private String normalizeSlug(String value) {
        try {
            return EdgeFunctionNames.normalizeSlug(value);
        } catch (IllegalArgumentException e) {
            throw new EdgeFunctionException(HttpStatus.BAD_REQUEST, "INVALID_FUNCTION_SLUG", e.getMessage());
        }
    }

    private String truncate(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max);
    }
}
