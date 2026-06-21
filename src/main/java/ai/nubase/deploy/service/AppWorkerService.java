package ai.nubase.deploy.service;

import ai.nubase.common.context.MultiTenancyContext;
import ai.nubase.deploy.dto.AppDeploymentDtos.AppWorkerDeleteResponse;
import ai.nubase.deploy.dto.AppDeploymentDtos.AppWorkerDetail;
import ai.nubase.deploy.dto.AppDeploymentDtos.AppWorkerSummary;
import ai.nubase.deploy.dto.AppDeploymentDtos.CompleteDeploymentRequest;
import ai.nubase.deploy.dto.AppDeploymentDtos.CreateDeploymentRequest;
import ai.nubase.deploy.dto.AppDeploymentDtos.DeploymentResponse;
import ai.nubase.deploy.dto.AppDeploymentDtos.RecordDeploymentStepRequest;
import ai.nubase.metadata.entity.AppDeployment;
import ai.nubase.metadata.entity.AppDeploymentStep;
import ai.nubase.metadata.repository.AppDeploymentRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Control-plane management for deployed app workers. The Cloudflare dispatch namespace is shared
 * across all tenants, so every operation is scoped to workers the current project actually owns —
 * ownership is derived from this project's own {@code app_deployments} history, never from the
 * shared namespace listing.
 */
@Service
@RequiredArgsConstructor
public class AppWorkerService {

    private static final int SCAN_LIMIT = 200;
    private static final String TYPE_APP_WORKER = "app_worker";

    private final AppDeploymentRepository deploymentRepository;
    private final AppDeploymentService deploymentService;
    private final AppWorkerDeployer deployer;
    private final ObjectMapper objectMapper;

    @Transactional(transactionManager = "metadataTransactionManager", readOnly = true)
    public List<AppWorkerSummary> list() {
        return ownedWorkers().values().stream()
                .map(this::toSummary)
                .sorted(Comparator.comparing(AppWorkerSummary::workerName))
                .toList();
    }

    @Transactional(transactionManager = "metadataTransactionManager", readOnly = true)
    public AppWorkerDetail get(String workerName) {
        OwnedWorker owned = requireOwned(workerName);
        AppWorkerInfo info = deployer.get(owned.workerName());
        return new AppWorkerDetail(toSummary(owned), info.exists(), info.details());
    }

    @Transactional("metadataTransactionManager")
    public AppWorkerDeleteResponse delete(String workerName) {
        OwnedWorker owned = requireOwned(workerName);
        deployer.delete(owned.workerName());
        DeploymentResponse audit = recordDeletion(owned);
        return new AppWorkerDeleteResponse(owned.workerName(), true, audit.id());
    }

    private OwnedWorker requireOwned(String workerName) {
        if (!StringUtils.hasText(workerName)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "workerName is required");
        }
        OwnedWorker owned = ownedWorkers().get(canonical(workerName));
        if (owned == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "App worker not found for this project: " + workerName.trim());
        }
        return owned;
    }

    /** Latest app-worker deployment per worker name owned by the current project, keyed by canonical name. */
    private Map<String, OwnedWorker> ownedWorkers() {
        String projectRef = projectRef();
        List<AppDeployment> deployments = deploymentRepository
                .findByProjectRefOrderByCreatedAtDesc(projectRef, PageRequest.of(0, SCAN_LIMIT));
        Map<String, OwnedWorker> byName = new LinkedHashMap<>();
        for (AppDeployment deployment : deployments) {
            Map<String, Object> summary = readSummary(deployment.getManifestSummary());
            if (!TYPE_APP_WORKER.equals(summary.get("type"))) continue;
            String workerName = str(summary.get("workerName"));
            if (workerName == null) workerName = str(summary.get("appCode"));
            if (workerName == null) continue;
            // Deployments arrive newest-first; keep the most recent record per worker.
            byName.putIfAbsent(canonical(workerName), new OwnedWorker(canonical(workerName), deployment, summary));
        }
        return byName;
    }

    private DeploymentResponse recordDeletion(OwnedWorker owned) {
        DeploymentResponse record = deploymentService.create(new CreateDeploymentRequest(
                str(owned.summary().get("appCode")),
                Map.of("type", "app_worker_delete", "workerName", owned.workerName()),
                null,
                null
        ));
        deploymentService.recordStep(record.id(), new RecordDeploymentStepRequest(
                1,
                "cloudflare_app_worker_delete",
                owned.workerName(),
                AppDeploymentStep.STATUS_SUCCEEDED,
                Map.of("workerName", owned.workerName()),
                null
        ));
        return deploymentService.complete(record.id(), new CompleteDeploymentRequest(
                AppDeployment.STATUS_SUCCEEDED,
                null,
                null
        ));
    }

    private AppWorkerSummary toSummary(OwnedWorker owned) {
        AppDeployment deployment = owned.deployment();
        Map<String, Object> summary = owned.summary();
        return new AppWorkerSummary(
                owned.workerName(),
                deployment.getProjectRef(),
                str(summary.get("version")),
                str(summary.get("previewHost")),
                deployment.getPublicUrl(),
                deployment.getStatus(),
                deployment.getId(),
                deployment.getCreatedAt(),
                deployment.getCompletedAt()
        );
    }

    private Map<String, Object> readSummary(String json) {
        if (!StringUtils.hasText(json)) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String projectRef() {
        String projectRef = MultiTenancyContext.getAppCode();
        if (!StringUtils.hasText(projectRef)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Project context is required");
        }
        return projectRef;
    }

    private static String canonical(String workerName) {
        return workerName.trim().toLowerCase(Locale.ROOT);
    }

    private static String str(Object value) {
        return value instanceof String text && StringUtils.hasText(text) ? text : null;
    }

    private record OwnedWorker(String workerName, AppDeployment deployment, Map<String, Object> summary) {
    }
}
