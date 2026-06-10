package ai.nubase.functions.service;

import ai.nubase.auth.entity.User;
import ai.nubase.common.context.MultiTenancyContext;
import ai.nubase.functions.executor.EdgeFunctionExecutorRouter;
import ai.nubase.functions.executor.EdgeFunctionInvocationRequest;
import ai.nubase.functions.executor.EdgeFunctionInvocationResponse;
import ai.nubase.functions.util.EdgeFunctionNames;
import ai.nubase.metadata.edge.entity.EdgeFunction;
import ai.nubase.metadata.edge.entity.EdgeFunctionInvocation;
import ai.nubase.metadata.edge.entity.EdgeFunctionVersion;
import ai.nubase.metadata.edge.repository.EdgeFunctionInvocationRepository;
import ai.nubase.metadata.edge.repository.EdgeFunctionRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.UUID;

import static ai.nubase.functions.service.EdgeFunctionExceptions.EdgeFunctionException;

@Service
@RequiredArgsConstructor
public class EdgeFunctionInvocationService {

    private final EdgeFunctionRepository functionRepository;
    private final EdgeFunctionInvocationRepository invocationRepository;
    private final EdgeFunctionExecutorRouter executor;
    private final HeaderSanitizer headerSanitizer;
    private final EdgeFunctionRateLimiter rateLimiter;

    @Transactional("metadataTransactionManager")
    public EdgeFunctionInvocationResponse invoke(
            String functionSlug,
            String path,
            byte[] body,
            HttpServletRequest servletRequest
    ) {
        long start = System.nanoTime();
        String requestId = requestId(servletRequest);
        String projectRef = projectRef();
        String slug = EdgeFunctionNames.normalizeSlug(functionSlug);
        EdgeFunction function = null;
        EdgeFunctionVersion version = null;
        Integer status = null;
        String errorCode = null;
        String errorMessage = null;

        try {
            rateLimiter.check(projectRef, slug);
            function = functionRepository.findByProjectRefAndSlug(projectRef, slug)
                    .orElseThrow(() -> new EdgeFunctionException(HttpStatus.NOT_FOUND, "FUNCTION_NOT_FOUND", "Function not found"));
            if (!Boolean.TRUE.equals(function.getEnabled())) {
                throw new EdgeFunctionException(HttpStatus.NOT_FOUND, "FUNCTION_NOT_FOUND", "Function not found");
            }
            if (Boolean.TRUE.equals(function.getVerifyJwt()) && !isAuthenticatedUser() && !MultiTenancyContext.isServiceRole()) {
                throw new EdgeFunctionException(HttpStatus.UNAUTHORIZED, "JWT_REQUIRED", "Function requires a valid user JWT");
            }
            version = function.getActiveVersion();
            if (version == null || !"deployed".equals(version.getStatus())) {
                throw new EdgeFunctionException(HttpStatus.SERVICE_UNAVAILABLE, "FUNCTION_NOT_DEPLOYED", "Function is not deployed");
            }

            EdgeFunctionInvocationResponse response = executor.invoke(new EdgeFunctionInvocationRequest(
                    requestId,
                    projectRef,
                    slug,
                    version.getProviderDeploymentId(),
                    servletRequest.getMethod(),
                    path,
                    servletRequest.getQueryString(),
                    headerSanitizer.forwardableHeaders(servletRequest),
                    body == null ? new byte[0] : body,
                    defaultEnv(function)
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
            invocationRepository.save(EdgeFunctionInvocation.builder()
                    .requestId(requestId)
                    .projectRef(projectRef)
                    .functionSlug(slug)
                    .functionVersion(version)
                    .method(servletRequest.getMethod())
                    .path(path == null ? "" : path)
                    .statusCode(status)
                    .durationMs(durationMs)
                    .executorProvider(executor.provider())
                    .errorCode(errorCode)
                    .errorMessage(truncate(errorMessage, 1000))
                    .callerType(callerType())
                    .callerRole(MultiTenancyContext.isServiceRole() ? "service_role" : (isAuthenticatedUser() ? "authenticated" : "anon"))
                    .callerUserId(currentUserId())
                    .build());
        }
    }

    private Map<String, String> defaultEnv(EdgeFunction function) {
        // Do not add service_role by default. Privileged env will be introduced behind
        // an explicit token/scoping design.
        return Map.of(
                "NUBASE_PROJECT_REF", function.getProjectRef(),
                "NUBASE_FUNCTION_NAME", function.getSlug()
        );
    }

    private boolean isAuthenticatedUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof User;
    }

    private String callerType() {
        if (MultiTenancyContext.isServiceRole()) return "service_role";
        if (isAuthenticatedUser()) return "auth_user";
        return "anon";
    }

    private UUID currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof User user && user.getId() != null) {
            return user.getId();
        }
        return null;
    }

    private String requestId(HttpServletRequest request) {
        String requestId = request.getHeader("x-request-id");
        return StringUtils.hasText(requestId) ? requestId : UUID.randomUUID().toString();
    }

    private String projectRef() {
        String projectRef = MultiTenancyContext.getAppCode();
        if (!StringUtils.hasText(projectRef)) {
            throw new EdgeFunctionException(HttpStatus.UNAUTHORIZED, "TENANT_CONTEXT_REQUIRED", "Project context is required");
        }
        return projectRef;
    }

    private String truncate(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max);
    }
}
