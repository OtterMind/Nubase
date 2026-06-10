package ai.nubase.functions.dto;

import ai.nubase.metadata.edge.entity.EdgeFunction;
import ai.nubase.metadata.edge.entity.EdgeFunctionInvocation;
import ai.nubase.metadata.edge.entity.EdgeFunctionSecret;
import ai.nubase.metadata.edge.entity.EdgeFunctionVersion;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class EdgeFunctionDtos {

    public static final String SLUG_PATTERN = "^[a-zA-Z0-9_-]{1,128}$";
    public static final String SECRET_NAME_PATTERN = "^[A-Z_][A-Z0-9_]{0,127}$";

    private EdgeFunctionDtos() {
    }

    public record CreateFunctionRequest(
            @NotBlank @Size(max = 128) String name,
            @Size(max = 128) @Pattern(regexp = SLUG_PATTERN) String slug,
            String description,
            Boolean verifyJwt,
            Boolean enabled,
            Boolean privileged,
            String importMap,
            String entrypoint
    ) {
    }

    public record UpdateFunctionRequest(
            @Size(max = 128) String name,
            String description,
            Boolean verifyJwt,
            Boolean enabled,
            Boolean privileged,
            String importMap,
            String entrypoint
    ) {
    }

    public record DeployFunctionRequest(
            @NotBlank String sourceHash,
            String artifactUri,
            String artifactType,
            String sourceBundleBase64
    ) {
    }

    public record SetFunctionSecretsRequest(
            Map<@Pattern(regexp = SECRET_NAME_PATTERN) String, @NotBlank String> secrets
    ) {
    }

    public record EdgeFunctionResponse(
            UUID id,
            String projectRef,
            String name,
            String slug,
            String description,
            boolean verifyJwt,
            boolean enabled,
            boolean privileged,
            String importMap,
            String entrypoint,
            EdgeFunctionVersionResponse activeVersion,
            UUID createdByPlatformUserId,
            UUID updatedByPlatformUserId,
            Instant createdAt,
            Instant updatedAt
    ) {
        public static EdgeFunctionResponse from(EdgeFunction fn) {
            return new EdgeFunctionResponse(
                    fn.getId(),
                    fn.getProjectRef(),
                    fn.getName(),
                    fn.getSlug(),
                    fn.getDescription(),
                    Boolean.TRUE.equals(fn.getVerifyJwt()),
                    Boolean.TRUE.equals(fn.getEnabled()),
                    Boolean.TRUE.equals(fn.getPrivileged()),
                    fn.getImportMap(),
                    fn.getEntrypoint(),
                    fn.getActiveVersion() == null ? null : EdgeFunctionVersionResponse.from(fn.getActiveVersion()),
                    fn.getCreatedByPlatformUserId(),
                    fn.getUpdatedByPlatformUserId(),
                    fn.getCreatedAt(),
                    fn.getUpdatedAt()
            );
        }
    }

    public record EdgeFunctionVersionResponse(
            UUID id,
            int versionNo,
            String sourceHash,
            String artifactUri,
            String artifactType,
            String provider,
            String providerDeploymentId,
            String status,
            String errorMessage,
            UUID deployedByPlatformUserId,
            Instant createdAt,
            Instant activatedAt
    ) {
        public static EdgeFunctionVersionResponse from(EdgeFunctionVersion version) {
            return new EdgeFunctionVersionResponse(
                    version.getId(),
                    version.getVersionNo() == null ? 0 : version.getVersionNo(),
                    version.getSourceHash(),
                    version.getArtifactUri(),
                    version.getArtifactType(),
                    version.getProvider(),
                    version.getProviderDeploymentId(),
                    version.getStatus(),
                    version.getErrorMessage(),
                    version.getDeployedByPlatformUserId(),
                    version.getCreatedAt(),
                    version.getActivatedAt()
            );
        }
    }

    public record FunctionSecretResponse(
            String name,
            UUID createdByPlatformUserId,
            UUID updatedByPlatformUserId,
            Instant createdAt,
            Instant updatedAt
    ) {
        public static FunctionSecretResponse from(EdgeFunctionSecret secret) {
            return new FunctionSecretResponse(
                    secret.getName(),
                    secret.getCreatedByPlatformUserId(),
                    secret.getUpdatedByPlatformUserId(),
                    secret.getCreatedAt(),
                    secret.getUpdatedAt()
            );
        }
    }

    public record InvocationLogResponse(
            UUID id,
            String requestId,
            String projectRef,
            String functionSlug,
            UUID functionVersionId,
            String method,
            String path,
            Integer statusCode,
            Integer durationMs,
            String executorProvider,
            String errorCode,
            String errorMessage,
            String callerType,
            String callerRole,
            UUID callerUserId,
            UUID callerPlatformUserId,
            Instant createdAt
    ) {
        public static InvocationLogResponse from(EdgeFunctionInvocation invocation) {
            return new InvocationLogResponse(
                    invocation.getId(),
                    invocation.getRequestId(),
                    invocation.getProjectRef(),
                    invocation.getFunctionSlug(),
                    invocation.getFunctionVersion() == null ? null : invocation.getFunctionVersion().getId(),
                    invocation.getMethod(),
                    invocation.getPath(),
                    invocation.getStatusCode(),
                    invocation.getDurationMs(),
                    invocation.getExecutorProvider(),
                    invocation.getErrorCode(),
                    invocation.getErrorMessage(),
                    invocation.getCallerType(),
                    invocation.getCallerRole(),
                    invocation.getCallerUserId(),
                    invocation.getCallerPlatformUserId(),
                    invocation.getCreatedAt()
            );
        }
    }

    public record ListResponse<T>(List<T> data) {
    }
}
