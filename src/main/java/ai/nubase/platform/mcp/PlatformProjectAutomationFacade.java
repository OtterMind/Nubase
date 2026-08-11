package ai.nubase.platform.mcp;

import ai.nubase.auth.dto.request.admin.InitDatabaseRequest;
import ai.nubase.auth.dto.response.admin.InitDatabaseResponse;
import ai.nubase.auth.service.DatabaseInitService;
import ai.nubase.auth.service.PlatformAuthService;
import ai.nubase.auth.service.ProjectOwnershipService;
import ai.nubase.auth.service.ProjectProvisioningService;
import ai.nubase.ai.gateway.platform.PlatformUpstreamRepository;
import ai.nubase.common.enums.DatabaseInitStatus;
import ai.nubase.metadata.entity.AutomationGrant;
import ai.nubase.metadata.entity.AutomationProjectOperation;
import ai.nubase.metadata.repository.AutomationGrantRepository;
import ai.nubase.metadata.repository.AutomationProjectOperationRepository;
import ai.nubase.platform.mcp.PlatformMcpProjectRepository.OwnedProjectState;
import ai.nubase.platform.mcp.PlatformProjectDtos.CreateRequest;
import ai.nubase.platform.mcp.PlatformProjectDtos.CreateResult;
import ai.nubase.platform.mcp.PlatformProjectDtos.ProvisionRequest;
import ai.nubase.platform.mcp.PlatformProjectDtos.ProvisionResult;
import ai.nubase.platform.mcp.PlatformProjectDtos.Readiness;
import ai.nubase.platform.mcp.PlatformProjectDtos.StatusRequest;
import ai.nubase.platform.mcp.PlatformProjectDtos.StatusResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "nubase.platform-mcp", name = "enabled", havingValue = "true")
public class PlatformProjectAutomationFacade {

    public static final String CREATE_ACTION = "platformProjectCreate";
    public static final String PROVISION_ACTION = "platformProjectProvision";
    public static final String STATIC_CONTROL_PLANE = "STATIC_CONTROL_PLANE";

    private static final String IDEMPOTENCY_PATTERN = "^[A-Za-z0-9._:-]{8,128}$";
    private static final String TRACE_PATTERN = "^[A-Za-z0-9._:-]{1,128}$";
    private static final String REF_PATTERN = "^[a-z][a-z0-9_]{0,39}$";
    private static final String DIGEST_PATTERN = "^sha256:[0-9a-f]{64}$";

    private final DatabaseInitService databaseInitService;
    private final ProjectOwnershipService projectOwnershipService;
    private final ProjectProvisioningService projectProvisioningService;
    private final PlatformUpstreamRepository platformUpstreamRepository;
    private final PlatformMcpProjectRepository projectRepository;
    private final AutomationGrantRepository grantRepository;
    private final AutomationProjectOperationRepository operationRepository;
    private final CanonicalRequestHasher requestHasher;
    private final PlatformMcpProperties properties;
    private final PlatformMcpReadinessChecker readinessChecker;
    private final ObjectMapper objectMapper;

    @Transactional("metadataTransactionManager")
    public CreateResult create(AutomationPrincipal principal, CreateRequest request) {
        validateCreateRequest(request);
        requireScope(principal, PlatformMcpScopes.PROJECT_CREATE);
        AutomationGrant grant = lockAndValidateGrant(
                principal, request.ref(), request.approvalId(), PlatformMcpScopes.PROJECT_CREATE);
        String requestHash = requestHasher.hash(request);
        OperationClaim<CreateResult> claim = claim(
                principal,
                CREATE_ACTION,
                request.idempotencyKey(),
                requestHash,
                request.ref(),
                request.taskId(),
                request.runId(),
                request.specDigest(),
                request.approvalId(),
                CreateResult.class);
        if (claim.replayed() != null) {
            return claim.replayed();
        }

        if (projectRepository.countOwnedProjects(principal.actor()) >= grant.getMaxProjects()) {
            throw new PlatformMcpOperationException("PROJECT_QUOTA_EXCEEDED");
        }

        InitDatabaseRequest initRequest = new InitDatabaseRequest();
        initRequest.setDbKey(request.ref());
        initRequest.setDbName(request.ref());
        initRequest.setAppCode(request.ref());
        initRequest.setAppName(request.name().trim());
        initRequest.setDescription(normalize(request.description()));

        InitDatabaseResponse raw = databaseInitService.createDatabaseConfigIfAbsent(initRequest);
        if (!raw.isSuccess()) {
            if (DatabaseInitService.PROJECT_REF_EXISTS.equals(raw.getErrorDetails())) {
                throw new PlatformMcpOperationException(DatabaseInitService.PROJECT_REF_EXISTS);
            }
            throw new PlatformMcpOperationException("PROJECT_CREATE_FAILED");
        }
        projectOwnershipService.recordOwnership(
                PlatformAuthService.SYSTEM_USER_ID,
                request.ref(),
                PlatformMcpProjectRepository.EXTERNAL_PLATFORM,
                principal.actor());

        CreateResult result = new CreateResult(
                true,
                request.ref(),
                request.name().trim(),
                "PENDING",
                notReady());
        complete(claim.operation(), result);
        return result;
    }

    @Transactional("metadataTransactionManager")
    public ProvisionResult provision(AutomationPrincipal principal, ProvisionRequest request) {
        validateProvisionRequest(request);
        requireScope(principal, PlatformMcpScopes.PROJECT_PROVISION);
        lockAndValidateGrant(
                principal, request.ref(), request.approvalId(), PlatformMcpScopes.PROJECT_PROVISION);
        AutomationProjectOperation createOperation = operationRepository
                .findFirstByActorAndGrantIdAndActionAndProjectRefAndStatusOrderByCreatedAtDesc(
                        principal.actor(),
                        principal.grantId(),
                        CREATE_ACTION,
                        request.ref(),
                        "SUCCEEDED")
                .orElseThrow(() -> new PlatformMcpOperationException("TRACE_CONTEXT_MISMATCH"));
        requireMatchingTrace(
                createOperation,
                request.taskId(),
                request.runId(),
                request.specDigest(),
                request.approvalId());
        String requestHash = requestHasher.hash(request);
        OperationClaim<ProvisionResult> claim = claim(
                principal,
                PROVISION_ACTION,
                request.idempotencyKey(),
                requestHash,
                request.ref(),
                request.taskId(),
                request.runId(),
                request.specDigest(),
                request.approvalId(),
                ProvisionResult.class);
        if (claim.replayed() != null) {
            return claim.replayed();
        }

        OwnedProjectState project = requireOwnedProject(principal.actor(), request.ref());
        boolean gatewayAvailable = gatewayAvailable();
        Readiness readiness = readinessChecker.check(project, gatewayAvailable);
        if (DatabaseInitStatus.INITIALIZED.name().equalsIgnoreCase(project.initStatus())) {
            ProvisionResult result = new ProvisionResult(
                    project.ref(),
                    safeState(project, false, readiness),
                    ProjectProvisioningService.SubmissionState.ALREADY_INITIALIZED.name(),
                    STATIC_CONTROL_PLANE,
                    readiness);
            complete(claim.operation(), result);
            return result;
        }
        ProvisionResult result = new ProvisionResult(
                project.ref(),
                safeState(project, isRunningWithLocalWorker(project), readiness),
                "PENDING_RECOVERY",
                STATIC_CONTROL_PLANE,
                readiness);
        persistPendingResult(claim.operation(), result);
        return result;
    }

    @Transactional(value = "metadataTransactionManager", readOnly = true)
    public StatusResult status(AutomationPrincipal principal, StatusRequest request) {
        validateStatusRequest(request);
        requireScope(principal, PlatformMcpScopes.PROJECT_READ);
        validatePrincipalRefAndApproval(principal, request.ref(), request.approvalId());
        long matchingProvision = operationRepository.countMatchingTrace(
                principal.actor(),
                principal.grantId(),
                PROVISION_ACTION,
                request.ref(),
                request.taskId(),
                request.runId(),
                request.specDigest(),
                normalize(request.approvalId()));
        long matchingCreate = matchingProvision > 0
                ? 0
                : operationRepository.countMatchingTrace(
                        principal.actor(),
                        principal.grantId(),
                        CREATE_ACTION,
                        request.ref(),
                        request.taskId(),
                        request.runId(),
                        request.specDigest(),
                        normalize(request.approvalId()));
        if (matchingProvision < 1 && matchingCreate < 1) {
            throw new PlatformMcpOperationException("TRACE_CONTEXT_MISMATCH");
        }
        OwnedProjectState project = requireOwnedProject(principal.actor(), request.ref());
        boolean running = isRunningWithLocalWorker(project);
        boolean gatewayAvailable = gatewayAvailable();
        Readiness readiness = readinessChecker.check(project, gatewayAvailable);
        String state = safeState(project, running, readiness);
        return new StatusResult(
                project.ref(),
                project.name(),
                state,
                project.enabled(),
                running,
                request.taskId(),
                request.runId(),
                request.specDigest(),
                normalize(request.approvalId()),
                STATIC_CONTROL_PLANE,
                readiness,
                advertisedEndpoints(readiness));
    }

    private <T> OperationClaim<T> claim(
            AutomationPrincipal principal,
            String action,
            String idempotencyKey,
            String requestHash,
            String projectRef,
            String taskId,
            String runId,
            String specDigest,
            String approvalId,
            Class<T> resultType) {
        UUID operationId = UUID.randomUUID();
        operationRepository.insertPendingIfAbsent(
                operationId,
                principal.actor(),
                action,
                idempotencyKey,
                requestHash,
                projectRef,
                taskId,
                runId,
                specDigest,
                normalize(approvalId),
                principal.grantId(),
                principal.tokenJti(),
                Instant.now());
        AutomationProjectOperation operation = operationRepository
                .findByActorAndGrantIdAndActionAndIdempotencyKey(
                        principal.actor(), principal.grantId(), action, idempotencyKey)
                .orElseThrow(() -> new PlatformMcpOperationException("OPERATION_CLAIM_FAILED"));
        if (!requestHash.equals(operation.getRequestHash())) {
            throw new PlatformMcpOperationException("IDEMPOTENCY_CONFLICT");
        }
        if ("SUCCEEDED".equals(operation.getStatus())) {
            try {
                return new OperationClaim<>(operation, objectMapper.readValue(
                        operation.getResponseJson(), resultType));
            } catch (Exception e) {
                throw new PlatformMcpOperationException("OPERATION_RESULT_INVALID");
            }
        }
        if ("FAILED".equals(operation.getStatus())
                || "REJECTED".equals(operation.getStatus())) {
            String errorCode = operation.getErrorCode();
            throw new PlatformMcpOperationException(
                    errorCode == null || errorCode.isBlank()
                            ? "OPERATION_NOT_REPLAYABLE"
                            : errorCode);
        }
        if ("PENDING".equals(operation.getStatus())
                && operation.getResponseJson() != null
                && !operation.getResponseJson().isBlank()) {
            try {
                return new OperationClaim<>(operation, objectMapper.readValue(
                        operation.getResponseJson(), resultType));
            } catch (Exception e) {
                throw new PlatformMcpOperationException("OPERATION_RESULT_INVALID");
            }
        }
        if (!operationId.equals(operation.getId()) || !"PENDING".equals(operation.getStatus())) {
            throw new PlatformMcpOperationException("OPERATION_NOT_REPLAYABLE");
        }
        return new OperationClaim<>(operation, null);
    }

    private void complete(AutomationProjectOperation operation, Object safeResult) {
        try {
            operation.setResponseJson(objectMapper.writeValueAsString(safeResult));
            operation.setStatus("SUCCEEDED");
            operation.setUpdatedAt(Instant.now());
            operationRepository.saveAndFlush(operation);
        } catch (Exception e) {
            throw new PlatformMcpOperationException("OPERATION_RESULT_WRITE_FAILED");
        }
    }

    private void persistPendingResult(AutomationProjectOperation operation, Object safeResult) {
        try {
            operation.setResponseJson(objectMapper.writeValueAsString(safeResult));
            operation.setUpdatedAt(Instant.now());
            operationRepository.saveAndFlush(operation);
        } catch (Exception e) {
            throw new PlatformMcpOperationException("OPERATION_RESULT_WRITE_FAILED");
        }
    }

    private AutomationGrant lockAndValidateGrant(
            AutomationPrincipal principal,
            String ref,
            String approvalId,
            String requiredScope) {
        AutomationGrant grant = grantRepository.findLockedById(principal.grantId())
                .orElseThrow(() -> new PlatformMcpOperationException("GRANT_INACTIVE"));
        try {
            Set<String> scopes = objectMapper.readValue(
                    grant.getScopes(), new TypeReference<LinkedHashSet<String>>() { });
            if (!Boolean.TRUE.equals(grant.getActive())
                    || !principal.actor().equals(grant.getActor())
                    || grant.getTokenVersion() == null
                    || grant.getTokenVersion() != principal.tokenVersion()
                    || !scopes.contains(requiredScope)) {
                throw new PlatformMcpOperationException("GRANT_INACTIVE");
            }
        } catch (PlatformMcpOperationException e) {
            throw e;
        } catch (Exception e) {
            throw new PlatformMcpOperationException("GRANT_INVALID");
        }
        validateRefPrefix(ref, grant.getAllowedRefPrefix());
        if (!equalsNormalized(grant.getApprovalBinding(), approvalId)) {
            throw new PlatformMcpOperationException("APPROVAL_BINDING_MISMATCH");
        }
        return grant;
    }

    private static void validatePrincipalRefAndApproval(
            AutomationPrincipal principal, String ref, String approvalId) {
        validateRefPrefix(ref, principal.allowedRefPrefix());
        if (!equalsNormalized(principal.approvalBinding(), approvalId)) {
            throw new PlatformMcpOperationException("APPROVAL_BINDING_MISMATCH");
        }
    }

    private static void requireMatchingTrace(
            AutomationProjectOperation operation,
            String taskId,
            String runId,
            String specDigest,
            String approvalId) {
        if (!java.util.Objects.equals(operation.getTaskId(), taskId)
                || !java.util.Objects.equals(operation.getRunId(), runId)
                || !java.util.Objects.equals(operation.getSpecDigest(), specDigest)
                || !equalsNormalized(operation.getApprovalId(), approvalId)) {
            throw new PlatformMcpOperationException("TRACE_CONTEXT_MISMATCH");
        }
    }

    private OwnedProjectState requireOwnedProject(String actor, String ref) {
        return projectRepository.findOwnedByRef(actor, ref)
                .orElseThrow(() -> new PlatformMcpOperationException("PROJECT_NOT_FOUND"));
    }

    private static void requireScope(AutomationPrincipal principal, String scope) {
        if (!principal.hasScope(scope)) {
            throw new PlatformMcpOperationException("INSUFFICIENT_SCOPE");
        }
    }

    private static boolean isRunning(OwnedProjectState project) {
        return DatabaseInitStatus.INITIALIZING.name().equalsIgnoreCase(project.initStatus());
    }

    private boolean isRunningWithLocalWorker(OwnedProjectState project) {
        return isRunning(project) || projectProvisioningService.isRunning(project.dbKey());
    }

    String safeState(
            OwnedProjectState project, boolean submittedOrRunning, Readiness readiness) {
        boolean running = submittedOrRunning || isRunningWithLocalWorker(project);
        if (running) return "PROVISIONING";
        if (DatabaseInitStatus.INITIALIZED.name().equalsIgnoreCase(project.initStatus())) {
            if (!project.enabled()) return "DISABLED";
            return readinessChecker.allRequiredStaticChecksPass(readiness)
                    ? "PROVISIONED"
                    : "BLOCKED";
        }
        if (DatabaseInitStatus.INIT_FAILED.name().equalsIgnoreCase(project.initStatus())) {
            return "FAILED";
        }
        if (DatabaseInitStatus.PENDING_INIT.name().equalsIgnoreCase(project.initStatus())) {
            return "PENDING";
        }
        return "UNKNOWN";
    }

    private static Readiness notReady() {
        return new Readiness(false, false, false, false, false, false, false, false, false);
    }

    Map<String, String> advertisedEndpoints(Readiness readiness) {
        String configuredBase = properties.getPublicBaseUrl();
        if (configuredBase == null
                || configuredBase.isBlank()
                || configuredBase.indexOf('\\') >= 0) {
            throw new PlatformMcpOperationException("PUBLIC_BASE_URL_INVALID");
        }
        String base = configuredBase.strip().replaceAll("/+$", "");
        URI uri;
        try {
            uri = URI.create(base);
        } catch (Exception e) {
            throw new PlatformMcpOperationException("PUBLIC_BASE_URL_INVALID");
        }
        String scheme = uri.getScheme();
        int port = uri.getPort();
        if (!uri.isAbsolute()
                || uri.isOpaque()
                || scheme == null
                || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || uri.getHost() == null
                || uri.getHost().isBlank()
                || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || port == 0
                || port > 65535
                || uri.getRawAuthority() == null
                || uri.getRawAuthority().endsWith(":")) {
            throw new PlatformMcpOperationException("PUBLIC_BASE_URL_INVALID");
        }
        Map<String, String> endpoints = new LinkedHashMap<>();
        if (readiness.database()) endpoints.put("rest", base + "/rest/v1");
        if (readiness.auth()) endpoints.put("auth", base + "/auth/v1");
        if (readiness.storage()) endpoints.put("storage", base + "/storage/v1");
        if (readiness.memory()) endpoints.put("memory", base + "/mem/v1");
        if (readiness.assets()) endpoints.put("assets", base + "/assets/v1");
        if (readiness.functions()) endpoints.put("functions", base + "/functions/v1");
        if (readiness.mcp()) endpoints.put("mcp", base + "/mcp");
        if (readiness.gateway()) {
            endpoints.put("gateway", base + "/v1");
            endpoints.put("openAi", base + "/v1");
            endpoints.put("anthropicMessages", base + "/v1/messages");
        }
        return Map.copyOf(endpoints);
    }

    private boolean gatewayAvailable() {
        return platformUpstreamRepository.hasUsableActiveCatalogUpstream();
    }

    private static void validateCreateRequest(CreateRequest request) {
        if (request == null
                || !matches(request.idempotencyKey(), IDEMPOTENCY_PATTERN)
                || !matches(request.ref(), REF_PATTERN)
                || request.name() == null
                || request.name().isBlank()
                || request.name().trim().length() > 255
                || request.description() != null && request.description().length() > 2000) {
            throw new PlatformMcpOperationException("INVALID_ARGUMENTS");
        }
        validateTrace(request.taskId(), request.runId(), request.specDigest(), request.approvalId());
    }

    private static void validateProvisionRequest(ProvisionRequest request) {
        if (request == null
                || !matches(request.idempotencyKey(), IDEMPOTENCY_PATTERN)
                || !matches(request.ref(), REF_PATTERN)) {
            throw new PlatformMcpOperationException("INVALID_ARGUMENTS");
        }
        validateTrace(request.taskId(), request.runId(), request.specDigest(), request.approvalId());
    }

    private static void validateStatusRequest(StatusRequest request) {
        if (request == null || !matches(request.ref(), REF_PATTERN)) {
            throw new PlatformMcpOperationException("INVALID_ARGUMENTS");
        }
        validateTrace(request.taskId(), request.runId(), request.specDigest(), request.approvalId());
    }

    private static void validateTrace(
            String taskId, String runId, String specDigest, String approvalId) {
        if (!matches(taskId, TRACE_PATTERN)
                || !matches(runId, TRACE_PATTERN)
                || !matches(specDigest, DIGEST_PATTERN)
                || approvalId != null && !matches(approvalId, TRACE_PATTERN)) {
            throw new PlatformMcpOperationException("INVALID_TRACE_CONTEXT");
        }
    }

    private static void validateRefPrefix(String ref, String prefix) {
        if (prefix == null
                || !matches(prefix, REF_PATTERN)
                || !prefix.endsWith("_")
                || !ref.startsWith(prefix)) {
            throw new PlatformMcpOperationException("PROJECT_REF_OUT_OF_SCOPE");
        }
    }

    private static boolean matches(String value, String pattern) {
        return value != null && value.matches(pattern);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static boolean equalsNormalized(String left, String right) {
        return java.util.Objects.equals(normalize(left), normalize(right));
    }

    private record OperationClaim<T>(AutomationProjectOperation operation, T replayed) {
    }
}
