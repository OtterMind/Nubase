package ai.nubase.deploy.service;

import ai.nubase.common.context.MultiTenancyContext;
import ai.nubase.deploy.dto.AppDeploymentDtos.CompleteDeploymentRequest;
import ai.nubase.deploy.dto.AppDeploymentDtos.CreateDeploymentRequest;
import ai.nubase.deploy.dto.AppDeploymentDtos.DeploymentDetailResponse;
import ai.nubase.deploy.dto.AppDeploymentDtos.DeploymentResponse;
import ai.nubase.deploy.dto.AppDeploymentDtos.DeploymentStepResponse;
import ai.nubase.deploy.dto.AppDeploymentDtos.RecordDeploymentStepRequest;
import ai.nubase.metadata.entity.AppDeployment;
import ai.nubase.metadata.entity.AppDeploymentStep;
import ai.nubase.metadata.repository.AppDeploymentRepository;
import ai.nubase.metadata.repository.AppDeploymentStepRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AppDeploymentService {

    private final AppDeploymentRepository deploymentRepository;
    private final AppDeploymentStepRepository stepRepository;
    private final ObjectMapper objectMapper;

    @Transactional("metadataTransactionManager")
    public DeploymentResponse create(CreateDeploymentRequest request) {
        return createForProjectRef(projectRef(), request);
    }

    @Transactional("metadataTransactionManager")
    public DeploymentResponse createForProjectRef(String projectRef, CreateDeploymentRequest request) {
        AppDeployment deployment = AppDeployment.builder()
                .projectRef(required(projectRef, "projectRef"))
                .appName(StringUtils.hasText(request.appName()) ? request.appName().trim() : "app")
                .status(AppDeployment.STATUS_RUNNING)
                .manifestSummary(writeJson(request.manifestSummary()))
                .agentId(blankToNull(request.agentId()))
                .runId(blankToNull(request.runId()))
                .build();
        return toResponse(deploymentRepository.save(deployment));
    }

    @Transactional("metadataTransactionManager")
    public DeploymentStepResponse recordStep(UUID deploymentId, RecordDeploymentStepRequest request) {
        return recordStepForProjectRef(projectRef(), deploymentId, request);
    }

    @Transactional("metadataTransactionManager")
    public DeploymentStepResponse recordStepForProjectRef(
            String projectRef,
            UUID deploymentId,
            RecordDeploymentStepRequest request
    ) {
        AppDeployment deployment = findDeploymentForProjectRef(projectRef, deploymentId);
        AppDeploymentStep step = AppDeploymentStep.builder()
                .deployment(deployment)
                .stepOrder(request.stepOrder())
                .stepName(required(request.stepName(), "stepName"))
                .targetName(blankToNull(request.targetName()))
                .status(normalizeStepStatus(request.status()))
                .result(writeJson(request.result()))
                .errorMessage(blankToNull(request.errorMessage()))
                .startedAt(Instant.now())
                .completedAt(Instant.now())
                .build();
        return toStepResponse(stepRepository.save(step));
    }

    @Transactional("metadataTransactionManager")
    public DeploymentResponse complete(UUID deploymentId, CompleteDeploymentRequest request) {
        return completeForProjectRef(projectRef(), deploymentId, request);
    }

    @Transactional("metadataTransactionManager")
    public DeploymentResponse completeForProjectRef(
            String projectRef,
            UUID deploymentId,
            CompleteDeploymentRequest request
    ) {
        AppDeployment deployment = findDeploymentForProjectRef(projectRef, deploymentId);
        deployment.setStatus(normalizeDeploymentStatus(request.status()));
        deployment.setPublicUrl(blankToNull(request.publicUrl()));
        deployment.setErrorMessage(blankToNull(request.errorMessage()));
        deployment.setCompletedAt(Instant.now());
        return toResponse(deploymentRepository.save(deployment));
    }

    @Transactional(transactionManager = "metadataTransactionManager", readOnly = true)
    public List<DeploymentResponse> list(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return deploymentRepository.findByProjectRefOrderByCreatedAtDesc(projectRef(), PageRequest.of(0, safeLimit))
                .stream().map(this::toResponse).toList();
    }

    @Transactional(transactionManager = "metadataTransactionManager", readOnly = true)
    public DeploymentDetailResponse get(UUID deploymentId) {
        AppDeployment deployment = findDeployment(deploymentId);
        return new DeploymentDetailResponse(
                toResponse(deployment),
                stepRepository.findByDeploymentIdOrderByStepOrderAsc(deployment.getId()).stream()
                        .map(this::toStepResponse)
                        .toList()
        );
    }

    @Transactional(transactionManager = "metadataTransactionManager", readOnly = true)
    public List<DeploymentStepResponse> logs(UUID deploymentId) {
        AppDeployment deployment = findDeployment(deploymentId);
        return stepRepository.findByDeploymentIdOrderByStepOrderAsc(deployment.getId()).stream()
                .map(this::toStepResponse)
                .toList();
    }

    private AppDeployment findDeployment(UUID id) {
        return findDeploymentForProjectRef(projectRef(), id);
    }

    private AppDeployment findDeploymentForProjectRef(String projectRef, UUID id) {
        return deploymentRepository.findByProjectRefAndId(required(projectRef, "projectRef"), id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Deployment not found"));
    }

    private DeploymentResponse toResponse(AppDeployment deployment) {
        return DeploymentResponse.from(deployment, readJson(deployment.getManifestSummary()));
    }

    private DeploymentStepResponse toStepResponse(AppDeploymentStep step) {
        return DeploymentStepResponse.from(step, readJson(step.getResult()));
    }

    private String projectRef() {
        String projectRef = MultiTenancyContext.getAppCode();
        if (!StringUtils.hasText(projectRef)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Project context is required");
        }
        return projectRef;
    }

    private String writeJson(Map<String, Object> value) {
        if (value == null || value.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid JSON payload", e);
        }
    }

    private Object readJson(String value) {
        if (!StringUtils.hasText(value)) return null;
        try {
            return objectMapper.readValue(value, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return value;
        }
    }

    private String normalizeDeploymentStatus(String status) {
        if (AppDeployment.STATUS_SUCCEEDED.equals(status) || AppDeployment.STATUS_FAILED.equals(status)) {
            return status;
        }
        return AppDeployment.STATUS_FAILED;
    }

    private String normalizeStepStatus(String status) {
        if (AppDeploymentStep.STATUS_SUCCEEDED.equals(status) || AppDeploymentStep.STATUS_FAILED.equals(status)) {
            return status;
        }
        return AppDeploymentStep.STATUS_FAILED;
    }

    private static String required(String value, String name) {
        if (!StringUtils.hasText(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + " is required");
        }
        return value.trim();
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
