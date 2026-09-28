package ai.nubase.deploy.service;

import ai.nubase.deploy.dto.AppDeploymentDtos.AppWorkerDeployMetadata;
import ai.nubase.deploy.dto.AppDeploymentDtos.AppWorkerDeployResponse;
import ai.nubase.deploy.dto.AppDeploymentDtos.CompleteDeploymentRequest;
import ai.nubase.deploy.dto.AppDeploymentDtos.CreateDeploymentRequest;
import ai.nubase.deploy.dto.AppDeploymentDtos.RecordDeploymentStepRequest;
import ai.nubase.metadata.entity.AppDeployment;
import ai.nubase.metadata.entity.AppDeploymentStep;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AppWorkerDeployService {

    private final AppDeploymentService deploymentService;
    private final AppWorkerDeployer deployer;
    private final AppWorkerDeployProperties properties;

    public AppWorkerDeployResponse deployForProjectRef(
            String projectRef,
            AppWorkerDeployMetadata metadata,
            List<MultipartFile> serverFiles,
            List<MultipartFile> assetFiles
    ) {
        validate(projectRef, metadata, serverFiles, assetFiles);
        String appCode = projectRef.trim();
        String workerName = StringUtils.hasText(metadata.workerName()) ? metadata.workerName().trim() : appCode;
        AppWorkerDeploymentTarget deploymentTarget = deploymentTarget(metadata.deploymentTarget());
        String previewHost = StringUtils.hasText(metadata.previewHost())
                ? metadata.previewHost().trim()
                : defaultHost(workerName, deploymentTarget);

        var deployment = deploymentService.createForProjectRef(appCode, new CreateDeploymentRequest(
                appCode,
                manifestSummary(metadata, serverFiles, assetFiles, previewHost, deploymentTarget),
                null,
                metadata.version()
        ));
        try {
            List<AppWorkerDeploymentRequest.AppWorkerFile> serverBundle = files(serverFiles);
            deploymentService.recordStepForProjectRef(appCode, deployment.id(), new RecordDeploymentStepRequest(
                    1,
                    "server_bundle_received",
                    metadata.mainModule(),
                    AppDeploymentStep.STATUS_SUCCEEDED,
                    Map.of("fileCount", serverBundle.size()),
                    null
            ));

            List<AppWorkerDeploymentRequest.AppWorkerFile> assets = files(assetFiles);
            deploymentService.recordStepForProjectRef(appCode, deployment.id(), new RecordDeploymentStepRequest(
                    2,
                    "assets_received",
                    metadata.clientDistPath(),
                    AppDeploymentStep.STATUS_SUCCEEDED,
                    Map.of("fileCount", assets.size()),
                    null
            ));

            AppWorkerDeploymentResult result = deployer.deploy(new AppWorkerDeploymentRequest(
                    appCode,
                    metadata.version(),
                    deploymentTarget,
                    workerName,
                    metadata.mainModule(),
                    metadata.serverEntrypointPath(),
                    previewHost,
                    metadata.compatibilityDate(),
                    metadata.compatibilityFlags(),
                    plainBindings(metadata),
                    secretBindings(metadata),
                    serverBundle,
                    assets
            ));

            deploymentService.recordStepForProjectRef(appCode, deployment.id(), new RecordDeploymentStepRequest(
                    3,
                    "cloudflare_app_worker_deploy",
                    result.providerDeploymentId(),
                    AppDeploymentStep.STATUS_SUCCEEDED,
                    deploymentResult(result),
                    null
            ));
            deploymentService.completeForProjectRef(appCode, deployment.id(), new CompleteDeploymentRequest(
                    AppDeployment.STATUS_SUCCEEDED,
                    result.previewUrl(),
                    null
            ));
            return new AppWorkerDeployResponse(
                    deployment.id(),
                    result.provider(),
                    result.deploymentTarget(),
                    result.dispatchNamespace(),
                    result.providerDeploymentId(),
                    result.providerVersionId(),
                    result.previewUrl(),
                    result.status(),
                    result.assetManifestHash(),
                    result.assetFileCount(),
                    result.deployedAt(),
                    null
            );
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            deploymentService.recordStepForProjectRef(appCode, deployment.id(), new RecordDeploymentStepRequest(
                    99,
                    "cloudflare_app_worker_deploy",
                    workerName,
                    AppDeploymentStep.STATUS_FAILED,
                    Map.of(),
                    message
            ));
            deploymentService.completeForProjectRef(appCode, deployment.id(), new CompleteDeploymentRequest(
                    AppDeployment.STATUS_FAILED,
                    null,
                    message
            ));
            return new AppWorkerDeployResponse(
                    deployment.id(),
                    "cloudflare",
                    deploymentTarget.value(),
                    null,
                    workerName,
                    null,
                    null,
                    "failed",
                    null,
                    assetFiles == null ? 0 : assetFiles.size(),
                    Instant.now(),
                    message
            );
        }
    }

    private void validate(String projectRef, AppWorkerDeployMetadata metadata, List<MultipartFile> serverFiles, List<MultipartFile> assetFiles) {
        if (!StringUtils.hasText(projectRef)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "x-nubase-project-ref is required");
        }
        if (metadata == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "metadata is required");
        }
        if (!StringUtils.hasText(metadata.appCode())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "metadata.appCode is required");
        }
        if (!StringUtils.hasText(metadata.deploymentTarget())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "metadata.deploymentTarget is required");
        }
        if (!projectRef.trim().equals(metadata.appCode().trim())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "metadata.appCode must match x-nubase-project-ref");
        }
        requireWorkerNameOwnedByApp(metadata.appCode(), metadata.workerName());
        if (serverFiles == null || serverFiles.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "serverFile is required");
        }
        validateUploadSize(serverFiles, assetFiles);
    }

    /**
     * The Cloudflare dispatch namespace is shared across tenants, so a custom workerName is only
     * allowed when it is namespaced under the project's appCode. This prevents one project from
     * deploying over another project's worker. When omitted, workerName defaults to appCode.
     */
    private void requireWorkerNameOwnedByApp(String appCode, String workerName) {
        if (!StringUtils.hasText(workerName)) {
            return;
        }
        String app = appCode.trim().toLowerCase(Locale.ROOT);
        String worker = workerName.trim().toLowerCase(Locale.ROOT);
        if (!worker.equals(app) && !worker.startsWith(app + "-")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "workerName must equal the project appCode or start with \"" + app + "-\"");
        }
    }

    private void validateUploadSize(List<MultipartFile> serverFiles, List<MultipartFile> assetFiles) {
        long maxFileSize = properties.getMaxFileSize().toBytes();
        long maxRequestSize = properties.getMaxRequestSize().toBytes();
        long totalSize = 0L;
        totalSize += validatePartFiles("serverFile", serverFiles, maxFileSize);
        totalSize += validatePartFiles("assetFile", assetFiles, maxFileSize);
        if (totalSize > maxRequestSize) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "App worker upload exceeds maximum request size: size=" + totalSize
                            + " limit=" + maxRequestSize);
        }
    }

    private long validatePartFiles(String partName, List<MultipartFile> files, long maxFileSize) {
        long totalSize = 0L;
        for (MultipartFile file : files == null ? List.<MultipartFile>of() : files) {
            long fileSize = file.getSize();
            totalSize += fileSize;
            if (fileSize > maxFileSize) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                        "App worker upload exceeds maximum file size: part=" + partName
                                + " file=" + uploadFileName(file)
                                + " size=" + fileSize
                                + " limit=" + maxFileSize);
            }
        }
        return totalSize;
    }

    private String uploadFileName(MultipartFile file) {
        return StringUtils.hasText(file.getOriginalFilename()) ? file.getOriginalFilename() : file.getName();
    }

    private Map<String, Object> manifestSummary(
            AppWorkerDeployMetadata metadata,
            List<MultipartFile> serverFiles,
            List<MultipartFile> assetFiles,
            String previewHost,
            AppWorkerDeploymentTarget deploymentTarget
    ) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("type", "app_worker");
        summary.put("appCode", metadata.appCode());
        summary.put("version", metadata.version());
        summary.put("workerName", StringUtils.hasText(metadata.workerName()) ? metadata.workerName() : metadata.appCode());
        summary.put("mainModule", metadata.mainModule());
        summary.put("previewHost", previewHost);
        summary.put("deploymentTarget", deploymentTarget.value());
        summary.put("serverFiles", serverFiles == null ? 0 : serverFiles.size());
        summary.put("assetFiles", assetFiles == null ? 0 : assetFiles.size());
        Map<String, String> bindings = plainBindings(metadata);
        String runtimeMode = bindings.get("NUBASE_RUNTIME_MODE");
        putIfPresent(summary, "runtimeMode", runtimeMode);
        putIfPresent(summary, "upstreamEndpoint", bindings.get("NUBASE_UPSTREAM_URL"));
        summary.put("proxyEnabled", "same-origin-proxy".equals(runtimeMode));
        return summary;
    }

    private List<AppWorkerDeploymentRequest.AppWorkerFile> files(List<MultipartFile> files) throws IOException {
        List<AppWorkerDeploymentRequest.AppWorkerFile> out = new ArrayList<>();
        if (files == null) return out;
        for (MultipartFile file : files) {
            String path = StringUtils.hasText(file.getOriginalFilename()) ? file.getOriginalFilename() : file.getName();
            out.add(new AppWorkerDeploymentRequest.AppWorkerFile(
                    path,
                    file.getBytes(),
                    file.getContentType()
            ));
        }
        return out;
    }

    private Map<String, String> plainBindings(AppWorkerDeployMetadata metadata) {
        Map<String, String> bindings = new LinkedHashMap<>();
        if (metadata.envBindings() != null) bindings.putAll(metadata.envBindings());
        if (metadata.plainTextBindings() != null) bindings.putAll(metadata.plainTextBindings());
        return bindings;
    }

    private Map<String, String> secretBindings(AppWorkerDeployMetadata metadata) {
        Map<String, String> bindings = new LinkedHashMap<>();
        if (metadata.secretTextBindings() != null) bindings.putAll(metadata.secretTextBindings());
        return bindings;
    }

    private Map<String, Object> deploymentResult(AppWorkerDeploymentResult result) {
        Map<String, Object> out = new LinkedHashMap<>();
        putIfPresent(out, "provider", result.provider());
        putIfPresent(out, "deploymentTarget", result.deploymentTarget());
        putIfPresent(out, "dispatchNamespace", result.dispatchNamespace());
        putIfPresent(out, "providerDeploymentId", result.providerDeploymentId());
        putIfPresent(out, "providerVersionId", result.providerVersionId());
        putIfPresent(out, "previewUrl", result.previewUrl());
        putIfPresent(out, "assetManifestHash", result.assetManifestHash());
        out.put("assetFileCount", result.assetFileCount());
        putIfPresent(out, "status", result.status());
        return out;
    }

    private void putIfPresent(Map<String, Object> out, String key, Object value) {
        if (value != null) {
            out.put(key, value);
        }
    }

    private AppWorkerDeploymentTarget deploymentTarget(String raw) {
        try {
            return AppWorkerDeploymentTarget.from(raw);
        } catch (AppWorkerDeploymentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    private String defaultHost(String workerName, AppWorkerDeploymentTarget target) {
        return target == AppWorkerDeploymentTarget.PREVIEW
                ? "preview-" + workerName + ".ottermind.app"
                : workerName + ".ottermind.app";
    }
}
