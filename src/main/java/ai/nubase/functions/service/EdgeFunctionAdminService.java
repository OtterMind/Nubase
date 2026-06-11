package ai.nubase.functions.service;

import ai.nubase.common.context.MultiTenancyContext;
import ai.nubase.functions.dto.EdgeFunctionDtos.CreateFunctionRequest;
import ai.nubase.functions.dto.EdgeFunctionDtos.DeployFunctionRequest;
import ai.nubase.functions.dto.EdgeFunctionDtos.SetFunctionSecretsRequest;
import ai.nubase.functions.dto.EdgeFunctionDtos.UpdateFunctionRequest;
import ai.nubase.functions.executor.EdgeFunctionDeploymentRequest;
import ai.nubase.functions.executor.EdgeFunctionDeploymentResponse;
import ai.nubase.functions.executor.EdgeFunctionExecutorRouter;
import ai.nubase.functions.util.EdgeFunctionNames;
import ai.nubase.metadata.edge.entity.EdgeFunction;
import ai.nubase.metadata.edge.entity.EdgeFunctionInvocation;
import ai.nubase.metadata.edge.entity.EdgeFunctionSecret;
import ai.nubase.metadata.edge.entity.EdgeFunctionVersion;
import ai.nubase.metadata.edge.repository.EdgeFunctionInvocationRepository;
import ai.nubase.metadata.edge.repository.EdgeFunctionRepository;
import ai.nubase.metadata.edge.repository.EdgeFunctionSecretRepository;
import ai.nubase.metadata.edge.repository.EdgeFunctionVersionRepository;
import ai.nubase.postgrest.multidb.EncryptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

import static ai.nubase.functions.service.EdgeFunctionExceptions.EdgeFunctionException;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(value = "nubase.functions.enabled", havingValue = "true", matchIfMissing = true)
public class EdgeFunctionAdminService {

    private final EdgeFunctionRepository functionRepository;
    private final EdgeFunctionVersionRepository versionRepository;
    private final EdgeFunctionSecretRepository secretRepository;
    private final EdgeFunctionInvocationRepository invocationRepository;
    private final EdgeFunctionExecutorRouter executor;
    private final EdgeFunctionDeploymentRecorder deploymentRecorder;
    private final EncryptionService encryptionService;
    private final EdgeFunctionSecretEnv secretEnv;

    @Transactional(transactionManager = "metadataTransactionManager", readOnly = true)
    public List<EdgeFunction> listFunctions() {
        return functionRepository.findByProjectRefOrderByCreatedAtDesc(projectRef());
    }

    @Transactional(transactionManager = "metadataTransactionManager", readOnly = true)
    public EdgeFunction getFunction(String slug) {
        return findFunction(slug);
    }

    @Transactional("metadataTransactionManager")
    public EdgeFunction createFunction(CreateFunctionRequest request) {
        rejectPrivileged(request.privileged());
        String projectRef = projectRef();
        String slug = normalizeSlug(StringUtils.hasText(request.slug()) ? request.slug() : request.name());
        if (functionRepository.existsByProjectRefAndSlug(projectRef, slug)) {
            throw new EdgeFunctionException(HttpStatus.CONFLICT, "FUNCTION_EXISTS", "Function already exists");
        }
        EdgeFunction fn = EdgeFunction.builder()
                .projectRef(projectRef)
                .name(request.name().trim())
                .slug(slug)
                .description(request.description())
                .verifyJwt(request.verifyJwt())
                .enabled(request.enabled())
                .privileged(request.privileged())
                .importMap(request.importMap())
                .entrypoint(StringUtils.hasText(request.entrypoint()) ? request.entrypoint().trim() : "index.ts")
                .build();
        return functionRepository.save(fn);
    }

    @Transactional("metadataTransactionManager")
    public EdgeFunction updateFunction(String slug, UpdateFunctionRequest request) {
        rejectPrivileged(request.privileged());
        EdgeFunction fn = findFunction(slug);
        if (StringUtils.hasText(request.name())) fn.setName(request.name().trim());
        if (request.description() != null) fn.setDescription(request.description());
        if (request.verifyJwt() != null) fn.setVerifyJwt(request.verifyJwt());
        if (request.enabled() != null) fn.setEnabled(request.enabled());
        if (request.privileged() != null) fn.setPrivileged(Boolean.FALSE);
        if (request.importMap() != null) fn.setImportMap(request.importMap());
        if (StringUtils.hasText(request.entrypoint())) fn.setEntrypoint(request.entrypoint().trim());
        return functionRepository.save(fn);
    }

    public EdgeFunctionVersion deploy(String slug, DeployFunctionRequest request) {
        EdgeFunction fn = findFunction(slug);
        EdgeFunctionDeploymentResponse deployment = executor.deploy(new EdgeFunctionDeploymentRequest(
                fn.getProjectRef(),
                fn.getSlug(),
                fn.getEntrypoint(),
                request.sourceBundleBase64(),
                secretEnv.decryptedEnv(fn)
        ));
        return deploymentRecorder.record(fn.getId(), request, deployment);
    }

    public void deleteFunction(String slug) {
        EdgeFunction fn = findFunction(slug);
        EdgeFunctionVersion active = fn.getActiveVersion();
        if (active != null) {
            executor.delete(fn.getProjectRef(), fn.getSlug(), active.getProviderDeploymentId());
        }
        functionRepository.delete(fn);
    }

    public List<EdgeFunctionSecret> setSecrets(String slug, SetFunctionSecretsRequest request) {
        EdgeFunction fn = findFunction(slug);
        if (request.secrets() == null || request.secrets().isEmpty()) {
            return secretRepository.findByFunctionOrderByNameAsc(fn);
        }
        for (var entry : request.secrets().entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue();
            if (!EdgeFunctionNames.isValidSecretName(name)) {
                throw new EdgeFunctionException(HttpStatus.BAD_REQUEST, "INVALID_SECRET_NAME", "Invalid secret name: " + name);
            }
            EdgeFunctionSecret secret = secretRepository.findByFunctionAndName(fn, name)
                    .orElseGet(() -> EdgeFunctionSecret.builder()
                            .function(fn)
                            .name(name)
                            .build());
            try {
                secret.setEncryptedValue(encryptionService.encrypt(value));
            } catch (Exception e) {
                throw new EdgeFunctionException(HttpStatus.INTERNAL_SERVER_ERROR, "SECRET_ENCRYPTION_FAILED", "Failed to encrypt function secret");
            }
            secretRepository.save(secret);
        }
        syncSecretsToActiveDeployment(fn);
        return secretRepository.findByFunctionOrderByNameAsc(fn);
    }

    // Deploy-time-bound executors (Cloudflare) would otherwise keep serving stale
    // secrets until the next manual deploy. This runs outside a metadata transaction
    // so a slow Cloudflare call cannot hold a metadata-pool connection.
    private void syncSecretsToActiveDeployment(EdgeFunction fn) {
        EdgeFunctionVersion active = fn.getActiveVersion();
        if (active == null || !"deployed".equals(active.getStatus())) {
            return;
        }
        try {
            executor.syncSecrets(fn.getProjectRef(), fn.getSlug(), active.getProviderDeploymentId(), secretEnv.decryptedEnv(fn));
        } catch (EdgeFunctionException e) {
            throw e;
        } catch (Exception e) {
            throw new EdgeFunctionException(HttpStatus.BAD_GATEWAY, "SECRET_SYNC_FAILED",
                    "Secrets could not be applied to the deployed function: " + e.getMessage());
        }
    }

    @Transactional(transactionManager = "metadataTransactionManager", readOnly = true)
    public List<EdgeFunctionSecret> listSecrets(String slug) {
        return secretRepository.findByFunctionOrderByNameAsc(findFunction(slug));
    }

    @Transactional(transactionManager = "metadataTransactionManager", readOnly = true)
    public List<EdgeFunctionVersion> listVersions(String slug) {
        return versionRepository.findByFunctionOrderByVersionNoDesc(findFunction(slug));
    }

    @Transactional(transactionManager = "metadataTransactionManager", readOnly = true)
    public List<EdgeFunctionInvocation> listInvocations(String functionSlug, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        if (StringUtils.hasText(functionSlug)) {
            return invocationRepository.findByProjectRefAndFunctionSlugOrderByCreatedAtDesc(
                    projectRef(),
                    EdgeFunctionNames.normalizeSlug(functionSlug),
                    PageRequest.of(0, safeLimit)
            );
        }
        return invocationRepository.findByProjectRefOrderByCreatedAtDesc(projectRef(), PageRequest.of(0, safeLimit));
    }

    private EdgeFunction findFunction(String slug) {
        return functionRepository.findByProjectRefAndSlug(projectRef(), normalizeSlug(slug))
                .orElseThrow(() -> new EdgeFunctionException(HttpStatus.NOT_FOUND, "FUNCTION_NOT_FOUND", "Function not found"));
    }

    private String normalizeSlug(String value) {
        try {
            return EdgeFunctionNames.normalizeSlug(value);
        } catch (IllegalArgumentException e) {
            throw new EdgeFunctionException(HttpStatus.BAD_REQUEST, "INVALID_FUNCTION_SLUG", e.getMessage());
        }
    }

    private void rejectPrivileged(Boolean privileged) {
        if (Boolean.TRUE.equals(privileged)) {
            throw new EdgeFunctionException(HttpStatus.BAD_REQUEST, "PRIVILEGED_FUNCTIONS_UNSUPPORTED",
                    "Privileged edge functions are not supported yet");
        }
    }

    private String projectRef() {
        String projectRef = MultiTenancyContext.getAppCode();
        if (!StringUtils.hasText(projectRef)) {
            throw new EdgeFunctionException(HttpStatus.UNAUTHORIZED, "TENANT_CONTEXT_REQUIRED", "Project context is required");
        }
        return projectRef;
    }
}
