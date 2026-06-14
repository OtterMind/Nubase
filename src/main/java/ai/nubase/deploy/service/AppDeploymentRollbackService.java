package ai.nubase.deploy.service;

import ai.nubase.assets.service.AssetsService;
import ai.nubase.common.context.MultiTenancyContext;
import ai.nubase.cron.service.ScheduledJobAdminService;
import ai.nubase.deploy.dto.AppDeploymentDtos.RollbackActionResponse;
import ai.nubase.deploy.dto.AppDeploymentDtos.RollbackDeploymentResponse;
import ai.nubase.metadata.entity.AppDeployment;
import ai.nubase.metadata.entity.AppDeploymentStep;
import ai.nubase.metadata.repository.AppDeploymentRepository;
import ai.nubase.metadata.repository.AppDeploymentStepRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AppDeploymentRollbackService {

    private static final String STEP_ASSETS_UPLOAD = "assets_upload";
    private static final String STEP_CRON_CREATE = "cron_create";
    private static final String STEP_FUNCTIONS_DEPLOY = "functions_deploy";
    private static final String STEP_SQL_EXECUTE = "sql_execute";
    private static final String STEP_MEMORY_WRITE = "memory_write";
    private static final String STEP_FUNCTIONS_SECRETS_SET = "functions_secrets_set";
    private static final String STEP_FUNCTIONS_INVOKE = "functions_invoke";

    private final AppDeploymentRepository deploymentRepository;
    private final AppDeploymentStepRepository stepRepository;
    private final AssetsService assetsService;
    private final ObjectProvider<ScheduledJobAdminService> scheduledJobAdminService;
    private final ObjectMapper objectMapper;

    public RollbackDeploymentResponse rollback(UUID deploymentId) {
        AppDeployment deployment = findDeployment(deploymentId);
        List<AppDeploymentStep> steps = new ArrayList<>(stepRepository.findByDeploymentIdOrderByStepOrderAsc(deployment.getId()));
        Collections.reverse(steps);

        List<RollbackActionResponse> actions = new ArrayList<>();
        int nextOrder = stepRepository.findFirstByDeploymentIdOrderByStepOrderDesc(deployment.getId())
                .map(AppDeploymentStep::getStepOrder)
                .orElse(0) + 1;
        for (AppDeploymentStep step : steps) {
            if (step.getStepName() != null && step.getStepName().startsWith("rollback:")) {
                continue;
            }
            RollbackActionResponse action = rollbackStep(step);
            actions.add(action);
            appendRollbackStep(deployment, nextOrder++, action);
        }

        boolean success = actions.stream().noneMatch(action -> AppDeploymentStep.STATUS_FAILED.equals(action.status()));
        String status = success ? AppDeployment.STATUS_ROLLED_BACK : AppDeployment.STATUS_ROLLBACK_FAILED;
        updateDeploymentStatus(deployment.getId(), status);
        return new RollbackDeploymentResponse(deployment.getId(), status, success, actions);
    }

    private RollbackActionResponse rollbackStep(AppDeploymentStep step) {
        if (!AppDeploymentStep.STATUS_SUCCEEDED.equals(step.getStatus())) {
            return skipped(step, "Only succeeded deployment steps are rollback candidates.");
        }
        if (!StringUtils.hasText(step.getStepName())) {
            return skipped(step, "No stepName recorded for this step.");
        }
        String target = step.getTargetName();
        if (!StringUtils.hasText(target)) {
            return skipped(step, "No targetName recorded for this step");
        }
        try {
            return switch (step.getStepName()) {
                case STEP_ASSETS_UPLOAD -> {
                    assetsService.delete(target);
                    yield succeeded(step, Map.of("operation", "asset_deleted", "path", target));
                }
                case STEP_CRON_CREATE -> {
                    ScheduledJobAdminService adminService = scheduledJobAdminService.getIfAvailable();
                    if (adminService == null) {
                        yield skipped(step, "Cron service is not enabled.");
                    }
                    adminService.deleteJob(target);
                    yield succeeded(step, Map.of("operation", "cron_deleted", "name", target));
                }
                case STEP_FUNCTIONS_DEPLOY -> skipped(step,
                        "Function rollback needs a recorded prior active version; this deployment only recorded the deploy target.");
                case STEP_SQL_EXECUTE -> skipped(step, "SQL migrations are not automatically reversible.");
                case STEP_MEMORY_WRITE -> skipped(step, "Memory writes are append-only and are not automatically removed.");
                case STEP_FUNCTIONS_SECRETS_SET -> skipped(step, "Secret updates are not reversible because prior secret values are never stored.");
                case STEP_FUNCTIONS_INVOKE -> skipped(step, "Function verification did not create deployable state.");
                default -> skipped(step, "Unsupported deployment step type: " + step.getStepName());
            };
        } catch (Exception e) {
            return failed(step, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private RollbackActionResponse succeeded(AppDeploymentStep step, Map<String, Object> result) {
        return new RollbackActionResponse("rollback:" + step.getStepName(), step.getTargetName(),
                AppDeploymentStep.STATUS_SUCCEEDED, result, null);
    }

    private RollbackActionResponse skipped(AppDeploymentStep step, String reason) {
        return new RollbackActionResponse("rollback:" + step.getStepName(), step.getTargetName(),
                AppDeploymentStep.STATUS_SKIPPED, Map.of("reason", reason), null);
    }

    private RollbackActionResponse failed(AppDeploymentStep step, String errorMessage) {
        return new RollbackActionResponse("rollback:" + step.getStepName(), step.getTargetName(),
                AppDeploymentStep.STATUS_FAILED, null, errorMessage);
    }

    @Transactional("metadataTransactionManager")
    protected void appendRollbackStep(AppDeployment deployment, int stepOrder, RollbackActionResponse action) {
        AppDeployment managedDeployment = deploymentRepository.getReferenceById(deployment.getId());
        AppDeploymentStep rollbackStep = AppDeploymentStep.builder()
                .deployment(managedDeployment)
                .stepOrder(stepOrder)
                .stepName(action.stepName())
                .targetName(action.targetName())
                .status(action.status())
                .result(writeJson(action.result()))
                .errorMessage(action.errorMessage())
                .startedAt(Instant.now())
                .completedAt(Instant.now())
                .build();
        stepRepository.save(rollbackStep);
    }

    @Transactional("metadataTransactionManager")
    protected void updateDeploymentStatus(UUID deploymentId, String status) {
        AppDeployment deployment = deploymentRepository.findById(deploymentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Deployment not found"));
        deployment.setStatus(status);
        deployment.setErrorMessage(AppDeployment.STATUS_ROLLBACK_FAILED.equals(status)
                ? "One or more rollback actions failed"
                : null);
        deployment.setCompletedAt(Instant.now());
        deploymentRepository.save(deployment);
    }

    private AppDeployment findDeployment(UUID id) {
        return deploymentRepository.findByProjectRefAndId(projectRef(), id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Deployment not found"));
    }

    private String projectRef() {
        String projectRef = MultiTenancyContext.getAppCode();
        if (!StringUtils.hasText(projectRef)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Project context is required");
        }
        return projectRef;
    }

    @SuppressWarnings("unchecked")
    private String writeJson(Object value) {
        if (value == null) return null;
        if (value instanceof Map<?, ?> map && map.isEmpty()) return null;
        try {
            Object serializable = value;
            if (value instanceof RollbackActionResponse action) {
                Map<String, Object> json = new LinkedHashMap<>();
                json.put("stepName", action.stepName());
                json.put("targetName", action.targetName());
                json.put("status", action.status());
                json.put("result", action.result());
                json.put("errorMessage", action.errorMessage());
                serializable = json;
            }
            return objectMapper.writeValueAsString(serializable);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid JSON payload", e);
        }
    }
}
