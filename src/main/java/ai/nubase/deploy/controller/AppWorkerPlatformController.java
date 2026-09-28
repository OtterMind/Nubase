package ai.nubase.deploy.controller;

import ai.nubase.deploy.dto.AppDeploymentDtos.AppWorkerDeleteResponse;
import ai.nubase.deploy.dto.AppDeploymentDtos.AppWorkerDeployMetadata;
import ai.nubase.deploy.dto.AppDeploymentDtos.AppWorkerDeployResponse;
import ai.nubase.deploy.dto.AppDeploymentDtos.AppWorkerDetail;
import ai.nubase.deploy.dto.AppDeploymentDtos.AppWorkerSummary;
import ai.nubase.deploy.service.AppWorkerDeployService;
import ai.nubase.deploy.service.AppWorkerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/deployments/platform/v1/app-workers")
@RequiredArgsConstructor
public class AppWorkerPlatformController {

    private static final String PROJECT_REF_HEADER = "x-nubase-project-ref";

    private final AppWorkerDeployService appWorkerDeployService;
    private final AppWorkerService appWorkerService;
    private final ObjectMapper objectMapper;

    @GetMapping
    public ResponseEntity<List<AppWorkerSummary>> listAppWorkers(
            @RequestHeader(value = PROJECT_REF_HEADER, required = false) String projectRef
    ) {
        return ResponseEntity.ok(appWorkerService.list(requiredProjectRef(projectRef)));
    }

    @GetMapping("/{workerName}")
    public ResponseEntity<AppWorkerDetail> getAppWorker(
            @RequestHeader(value = PROJECT_REF_HEADER, required = false) String projectRef,
            @PathVariable String workerName
    ) {
        return ResponseEntity.ok(appWorkerService.get(requiredProjectRef(projectRef), workerName));
    }

    @DeleteMapping("/{workerName}")
    public ResponseEntity<AppWorkerDeleteResponse> deleteAppWorker(
            @RequestHeader(value = PROJECT_REF_HEADER, required = false) String projectRef,
            @PathVariable String workerName
    ) {
        return ResponseEntity.ok(appWorkerService.delete(requiredProjectRef(projectRef), workerName));
    }

    @PostMapping(value = "/deploy", consumes = "multipart/form-data")
    public ResponseEntity<AppWorkerDeployResponse> deployAppWorker(
            @RequestHeader(value = PROJECT_REF_HEADER, required = false) String projectRef,
            @RequestPart("metadata") String metadataJson,
            @RequestPart("serverFile") List<MultipartFile> serverFiles,
            @RequestPart(value = "assetFile", required = false) List<MultipartFile> assetFiles
    ) {
        try {
            AppWorkerDeployMetadata metadata = objectMapper.readValue(metadataJson, AppWorkerDeployMetadata.class);
            return ResponseEntity.ok(appWorkerDeployService.deployForProjectRef(
                    requiredProjectRef(projectRef), metadata, serverFiles, assetFiles));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid app worker deployment payload", e);
        }
    }

    private String requiredProjectRef(String projectRef) {
        if (!StringUtils.hasText(projectRef)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, PROJECT_REF_HEADER + " is required");
        }
        return projectRef.trim();
    }
}
