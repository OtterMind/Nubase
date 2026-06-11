package ai.nubase.functions.service;

import ai.nubase.functions.dto.EdgeFunctionDtos.DeployFunctionRequest;
import ai.nubase.functions.executor.EdgeFunctionDeploymentResponse;
import ai.nubase.metadata.edge.entity.EdgeFunction;
import ai.nubase.metadata.edge.entity.EdgeFunctionVersion;
import ai.nubase.metadata.edge.repository.EdgeFunctionRepository;
import ai.nubase.metadata.edge.repository.EdgeFunctionVersionRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.UUID;

import static ai.nubase.functions.service.EdgeFunctionExceptions.EdgeFunctionException;

@Component
@ConditionalOnProperty(value = "nubase.functions.enabled", havingValue = "true", matchIfMissing = true)
public class EdgeFunctionDeploymentRecorder {

    private final EdgeFunctionRepository functionRepository;
    private final EdgeFunctionVersionRepository versionRepository;
    private final PlatformTransactionManager transactionManager;

    public EdgeFunctionDeploymentRecorder(EdgeFunctionRepository functionRepository,
                                          EdgeFunctionVersionRepository versionRepository,
                                          @Qualifier("metadataTransactionManager") PlatformTransactionManager transactionManager) {
        this.functionRepository = functionRepository;
        this.versionRepository = versionRepository;
        this.transactionManager = transactionManager;
    }

    public EdgeFunctionVersion record(UUID functionId,
                                      DeployFunctionRequest request,
                                      EdgeFunctionDeploymentResponse deployment) {
        DataIntegrityViolationException lastConflict = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return transactionTemplate().execute(status -> recordOnce(functionId, request, deployment));
            } catch (DataIntegrityViolationException e) {
                lastConflict = e;
            }
        }
        throw lastConflict;
    }

    private TransactionTemplate transactionTemplate() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    private EdgeFunctionVersion recordOnce(UUID functionId,
                                           DeployFunctionRequest request,
                                           EdgeFunctionDeploymentResponse deployment) {
        EdgeFunction fn = functionRepository.findById(functionId)
                .orElseThrow(() -> new EdgeFunctionException(HttpStatus.NOT_FOUND, "FUNCTION_NOT_FOUND", "Function not found"));
        int nextVersion = versionRepository.findFirstByFunctionOrderByVersionNoDesc(fn)
                .map(version -> version.getVersionNo() + 1)
                .orElse(1);
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
                .activatedAt("deployed".equals(deployment.status()) ? Instant.now() : null)
                .build();
        EdgeFunctionVersion saved = versionRepository.saveAndFlush(version);
        if ("deployed".equals(saved.getStatus())) {
            fn.setActiveVersion(saved);
            functionRepository.save(fn);
        }
        return saved;
    }
}
