package ai.nubase.deploy.controller;

import ai.nubase.auth.annotation.RequireServiceRole;
import ai.nubase.deploy.dto.AppDeploymentDtos.CompleteDeploymentRequest;
import ai.nubase.deploy.dto.AppDeploymentDtos.CreateDeploymentRequest;
import ai.nubase.deploy.dto.AppDeploymentDtos.DeploymentDetailResponse;
import ai.nubase.deploy.dto.AppDeploymentDtos.DeploymentResponse;
import ai.nubase.deploy.dto.AppDeploymentDtos.DeploymentStepResponse;
import ai.nubase.deploy.dto.AppDeploymentDtos.RecordDeploymentStepRequest;
import ai.nubase.deploy.dto.AppDeploymentDtos.RollbackDeploymentResponse;
import ai.nubase.deploy.service.AppDeploymentRollbackService;
import ai.nubase.deploy.service.AppDeploymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/deployments/admin/v1")
@RequireServiceRole
@RequiredArgsConstructor
public class AppDeploymentAdminController {

    private final AppDeploymentService deploymentService;
    private final AppDeploymentRollbackService rollbackService;

    @PostMapping("/deployments")
    public ResponseEntity<DeploymentResponse> create(@RequestBody CreateDeploymentRequest request) {
        return ResponseEntity.ok(deploymentService.create(request));
    }

    @GetMapping("/deployments")
    public ResponseEntity<List<DeploymentResponse>> list(@RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(deploymentService.list(limit));
    }

    @GetMapping("/deployments/{id}")
    public ResponseEntity<DeploymentDetailResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok(deploymentService.get(id));
    }

    @GetMapping("/deployments/{id}/logs")
    public ResponseEntity<List<DeploymentStepResponse>> logs(@PathVariable UUID id) {
        return ResponseEntity.ok(deploymentService.logs(id));
    }

    @PostMapping("/deployments/{id}/steps")
    public ResponseEntity<DeploymentStepResponse> recordStep(
            @PathVariable UUID id,
            @RequestBody RecordDeploymentStepRequest request
    ) {
        return ResponseEntity.ok(deploymentService.recordStep(id, request));
    }

    @PostMapping("/deployments/{id}/complete")
    public ResponseEntity<DeploymentResponse> complete(
            @PathVariable UUID id,
            @RequestBody CompleteDeploymentRequest request
    ) {
        return ResponseEntity.ok(deploymentService.complete(id, request));
    }

    @PostMapping("/deployments/{id}/rollback")
    public ResponseEntity<RollbackDeploymentResponse> rollback(@PathVariable UUID id) {
        return ResponseEntity.ok(rollbackService.rollback(id));
    }
}
