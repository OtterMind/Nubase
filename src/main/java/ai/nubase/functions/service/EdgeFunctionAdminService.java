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
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static ai.nubase.functions.service.EdgeFunctionExceptions.EdgeFunctionException;

@Service
@RequiredArgsConstructor
public class EdgeFunctionAdminService {

    private final EdgeFunctionRepository functionRepository;
    private final EdgeFunctionVersionRepository versionRepository;
    private final EdgeFunctionSecretRepository secretRepository;
    private final EdgeFunctionInvocationRepository invocationRepository;
    private final EdgeFunctionExecutorRouter executor;
    private final EncryptionService encryptionService;

    @Transactional(transactionManager = "metadataTransactionManager", readOnly = true)
    public List<EdgeFunction> listFunctions() {
        return functionRepository.findByProjectRefOrderByCreatedAtDesc(projectRef());
    }

    @Transactional(transactionManager = "metadataTransactionManager", readOnly = true)
    public EdgeFunction getFunction(String slug) {
        return findFunction(slug);
    }

    @Transactional("metadataTransactionManager")
    public EdgeFunction createFunction(CreateFunctionRequest request, UUID platformUserId) {
        String projectRef = projectRef();
        String slug = EdgeFunctionNames.normalizeSlug(
                StringUtils.hasText(request.slug()) ? request.slug() : request.name()
        );
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
                .createdByPlatformUserId(platformUserId)
                .updatedByPlatformUserId(platformUserId)
                .build();
        return functionRepository.save(fn);
    }

    @Transactional("metadataTransactionManager")
    public EdgeFunction updateFunction(String slug, UpdateFunctionRequest request, UUID platformUserId) {
        EdgeFunction fn = findFunction(slug);
        if (StringUtils.hasText(request.name())) fn.setName(request.name().trim());
        if (request.description() != null) fn.setDescription(request.description());
        if (request.verifyJwt() != null) fn.setVerifyJwt(request.verifyJwt());
        if (request.enabled() != null) fn.setEnabled(request.enabled());
        if (request.privileged() != null) fn.setPrivileged(request.privileged());
        if (request.importMap() != null) fn.setImportMap(request.importMap());
        if (StringUtils.hasText(request.entrypoint())) fn.setEntrypoint(request.entrypoint().trim());
        fn.setUpdatedByPlatformUserId(platformUserId);
        return functionRepository.save(fn);
    }

    @Transactional("metadataTransactionManager")
    public EdgeFunctionVersion deploy(String slug, DeployFunctionRequest request, UUID platformUserId) {
        EdgeFunction fn = findFunction(slug);
        int nextVersion = versionRepository.findFirstByFunctionOrderByVersionNoDesc(fn)
                .map(version -> version.getVersionNo() + 1)
                .orElse(1);
        EdgeFunctionDeploymentResponse deployment = executor.deploy(new EdgeFunctionDeploymentRequest(
                fn.getProjectRef(),
                fn.getSlug(),
                request.sourceHash(),
                request.artifactUri(),
                StringUtils.hasText(request.artifactType()) ? request.artifactType() : "source_bundle",
                request.sourceBundleBase64(),
                functionEnv(fn)
        ));
        EdgeFunctionVersion version = EdgeFunctionVersion.builder()
                .function(fn)
                .versionNo(nextVersion)
                .sourceHash(request.sourceHash())
                .artifactUri(request.artifactUri())
                .artifactType(StringUtils.hasText(request.artifactType()) ? request.artifactType() : "source_bundle")
                .provider(deployment.provider())
                .providerDeploymentId(deployment.providerDeploymentId())
                .status(deployment.status())
                .errorMessage(deployment.errorMessage())
                .deployedByPlatformUserId(platformUserId)
                .activatedAt("deployed".equals(deployment.status()) ? Instant.now() : null)
                .build();
        EdgeFunctionVersion saved = versionRepository.save(version);
        if ("deployed".equals(saved.getStatus())) {
            fn.setActiveVersion(saved);
            functionRepository.save(fn);
        }
        return saved;
    }

    @Transactional("metadataTransactionManager")
    public void deleteFunction(String slug) {
        EdgeFunction fn = findFunction(slug);
        EdgeFunctionVersion active = fn.getActiveVersion();
        if (active != null) {
            executor.delete(fn.getProjectRef(), fn.getSlug(), active.getProviderDeploymentId());
        }
        functionRepository.delete(fn);
    }

    @Transactional("metadataTransactionManager")
    public List<EdgeFunctionSecret> setSecrets(String slug, SetFunctionSecretsRequest request, UUID platformUserId) {
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
                            .createdByPlatformUserId(platformUserId)
                            .build());
            secret.setUpdatedByPlatformUserId(platformUserId);
            try {
                secret.setEncryptedValue(encryptionService.encrypt(value));
            } catch (Exception e) {
                throw new EdgeFunctionException(HttpStatus.INTERNAL_SERVER_ERROR, "SECRET_ENCRYPTION_FAILED", "Failed to encrypt function secret");
            }
            secretRepository.save(secret);
        }
        return secretRepository.findByFunctionOrderByNameAsc(fn);
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
        return functionRepository.findByProjectRefAndSlug(projectRef(), EdgeFunctionNames.normalizeSlug(slug))
                .orElseThrow(() -> new EdgeFunctionException(HttpStatus.NOT_FOUND, "FUNCTION_NOT_FOUND", "Function not found"));
    }

    private Map<String, String> functionEnv(EdgeFunction fn) {
        Map<String, String> env = new LinkedHashMap<>();
        for (EdgeFunctionSecret secret : secretRepository.findByFunctionOrderByNameAsc(fn)) {
            try {
                env.put(secret.getName(), encryptionService.decrypt(secret.getEncryptedValue()));
            } catch (Exception e) {
                throw new EdgeFunctionException(HttpStatus.INTERNAL_SERVER_ERROR, "SECRET_DECRYPTION_FAILED", "Failed to decrypt function secret");
            }
        }
        return env;
    }

    private String projectRef() {
        String projectRef = MultiTenancyContext.getAppCode();
        if (!StringUtils.hasText(projectRef)) {
            throw new EdgeFunctionException(HttpStatus.UNAUTHORIZED, "TENANT_CONTEXT_REQUIRED", "Project context is required");
        }
        return projectRef;
    }
}
