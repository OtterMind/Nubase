package ai.nubase.deploy.dto;

import ai.nubase.metadata.entity.AppDeployment;
import ai.nubase.metadata.entity.AppDeploymentStep;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AppDeploymentDtos {

    private AppDeploymentDtos() {
    }

    public record CreateDeploymentRequest(
            String appName,
            Map<String, Object> manifestSummary,
            String agentId,
            String runId
    ) {
    }

    public record RecordDeploymentStepRequest(
            Integer stepOrder,
            String stepName,
            String targetName,
            String status,
            Map<String, Object> result,
            String errorMessage
    ) {
    }

    public record CompleteDeploymentRequest(
            String status,
            String publicUrl,
            String errorMessage
    ) {
    }

    public record DeploymentResponse(
            UUID id,
            String projectRef,
            String appName,
            String status,
            String publicUrl,
            Object manifestSummary,
            String errorMessage,
            String agentId,
            String runId,
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt
    ) {
        public static DeploymentResponse from(AppDeployment deployment, Object manifestSummary) {
            return new DeploymentResponse(
                    deployment.getId(),
                    deployment.getProjectRef(),
                    deployment.getAppName(),
                    deployment.getStatus(),
                    deployment.getPublicUrl(),
                    manifestSummary,
                    deployment.getErrorMessage(),
                    deployment.getAgentId(),
                    deployment.getRunId(),
                    deployment.getCreatedAt(),
                    deployment.getUpdatedAt(),
                    deployment.getCompletedAt()
            );
        }
    }

    public record DeploymentStepResponse(
            UUID id,
            int stepOrder,
            String stepName,
            String targetName,
            String status,
            Object result,
            String errorMessage,
            Instant startedAt,
            Instant completedAt
    ) {
        public static DeploymentStepResponse from(AppDeploymentStep step, Object result) {
            return new DeploymentStepResponse(
                    step.getId(),
                    step.getStepOrder() == null ? 0 : step.getStepOrder(),
                    step.getStepName(),
                    step.getTargetName(),
                    step.getStatus(),
                    result,
                    step.getErrorMessage(),
                    step.getStartedAt(),
                    step.getCompletedAt()
            );
        }
    }

    public record DeploymentDetailResponse(
            DeploymentResponse deployment,
            List<DeploymentStepResponse> steps
    ) {
    }

    public record RollbackActionResponse(
            String stepName,
            String targetName,
            String status,
            Object result,
            String errorMessage
    ) {
    }

    public record RollbackDeploymentResponse(
            UUID deploymentId,
            String status,
            boolean success,
            List<RollbackActionResponse> actions
    ) {
    }

    public record AppWorkerDeployMetadata(
            String appCode,
            String version,
            String workerName,
            String mainModule,
            String serverEntrypointPath,
            String clientDistPath,
            String previewHost,
            String compatibilityDate,
            List<String> compatibilityFlags,
            Map<String, String> envBindings,
            Map<String, String> plainTextBindings,
            Map<String, String> secretTextBindings
    ) {
    }

    public record AppWorkerDeployResponse(
            UUID deploymentId,
            String provider,
            String providerDeploymentId,
            String previewUrl,
            String status,
            String assetManifestHash,
            Integer assetFileCount,
            Instant deployedAt,
            String errorMessage
    ) {
    }

    /** One app worker the current project owns, derived from its deployment history. */
    public record AppWorkerSummary(
            String workerName,
            String projectRef,
            String version,
            String previewHost,
            String publicUrl,
            String lastDeploymentStatus,
            UUID lastDeploymentId,
            Instant deployedAt,
            Instant completedAt
    ) {
    }

    /** An app worker summary enriched with live provider state. */
    public record AppWorkerDetail(
            AppWorkerSummary worker,
            boolean existsOnProvider,
            Object provider
    ) {
    }

    public record AppWorkerDeleteResponse(
            String workerName,
            boolean deleted,
            UUID auditDeploymentId
    ) {
    }
}
