package ai.nubase.platform.mcp;

import ai.nubase.ai.gateway.platform.PlatformUpstreamRepository;
import ai.nubase.auth.service.ProjectProvisioningService;
import ai.nubase.common.enums.DatabaseInitStatus;
import ai.nubase.metadata.entity.AutomationGrant;
import ai.nubase.metadata.entity.AutomationProjectOperation;
import ai.nubase.metadata.repository.AutomationGrantRepository;
import ai.nubase.metadata.repository.AutomationProjectOperationRepository;
import ai.nubase.metadata.repository.AutomationProjectOperationRepository.ProvisionRecoveryCandidate;
import ai.nubase.platform.mcp.PlatformMcpProjectRepository.OwnedProjectState;
import ai.nubase.platform.mcp.PlatformProjectDtos.ProvisionResult;
import ai.nubase.platform.mcp.PlatformProjectDtos.Readiness;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "nubase.platform-mcp", name = "enabled", havingValue = "true")
public class PlatformProvisionOutboxRecovery {

    static final int MAX_RECOVERY_FAILURES = 5;
    static final long BASE_RETRY_DELAY_SECONDS = 2;
    static final long MAX_RETRY_DELAY_SECONDS = 60;
    static final long PROVISION_POLL_DELAY_SECONDS = 5;
    static final long PROVISION_RECOVERY_DEADLINE_SECONDS = 5 * 60;
    static final long CLAIM_LEASE_SECONDS = 30;
    static final int CLAIM_TRANSACTION_TIMEOUT_SECONDS = 2;
    static final int RECOVERY_TRANSACTION_TIMEOUT_SECONDS = 10;

    static final String GRANT_INACTIVE = "GRANT_INACTIVE";
    static final String TRACE_CONTEXT_MISMATCH = "TRACE_CONTEXT_MISMATCH";
    static final String PROVISION_RETRY_EXHAUSTED = "PROVISION_RETRY_EXHAUSTED";
    static final String PROVISION_RECOVERY_FAILED = "PROVISION_RECOVERY_FAILED";
    static final String PROVISION_OPERATION_INVALID = "PROVISION_OPERATION_INVALID";
    static final String PROVISION_RECOVERY_TIMEOUT = "PROVISION_RECOVERY_TIMEOUT";

    private final AutomationProjectOperationRepository operationRepository;
    private final AutomationGrantRepository grantRepository;
    private final PlatformMcpProjectRepository projectRepository;
    private final ProjectProvisioningService provisioningService;
    private final PlatformUpstreamRepository upstreamRepository;
    private final PlatformMcpReadinessChecker readinessChecker;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate claimTransaction;
    private final TransactionTemplate recoveryTransaction;

    public PlatformProvisionOutboxRecovery(
            AutomationProjectOperationRepository operationRepository,
            AutomationGrantRepository grantRepository,
            PlatformMcpProjectRepository projectRepository,
            ProjectProvisioningService provisioningService,
            PlatformUpstreamRepository upstreamRepository,
            PlatformMcpReadinessChecker readinessChecker,
            ObjectMapper objectMapper,
            @Qualifier("metadataTransactionManager")
            PlatformTransactionManager transactionManager) {
        this(
                operationRepository,
                grantRepository,
                projectRepository,
                provisioningService,
                upstreamRepository,
                readinessChecker,
                objectMapper,
                transactionManager,
                Clock.systemUTC());
    }

    PlatformProvisionOutboxRecovery(
            AutomationProjectOperationRepository operationRepository,
            AutomationGrantRepository grantRepository,
            PlatformMcpProjectRepository projectRepository,
            ProjectProvisioningService provisioningService,
            PlatformUpstreamRepository upstreamRepository,
            PlatformMcpReadinessChecker readinessChecker,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.operationRepository = operationRepository;
        this.grantRepository = grantRepository;
        this.projectRepository = projectRepository;
        this.provisioningService = provisioningService;
        this.upstreamRepository = upstreamRepository;
        this.readinessChecker = readinessChecker;
        this.objectMapper = objectMapper;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.claimTransaction = new TransactionTemplate(transactionManager);
        this.claimTransaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.claimTransaction.setTimeout(CLAIM_TRANSACTION_TIMEOUT_SECONDS);
        this.recoveryTransaction = new TransactionTemplate(transactionManager);
        this.recoveryTransaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.recoveryTransaction.setTimeout(RECOVERY_TRANSACTION_TIMEOUT_SECONDS);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        recoverDue(clock.instant());
    }

    @Scheduled(
            initialDelayString = "${nubase.platform-mcp.provision-recovery-initial-delay-ms:2000}",
            fixedDelayString = "${nubase.platform-mcp.provision-recovery-delay-ms:5000}")
    public void recoverScheduled() {
        recoverDue(clock.instant());
    }

    void recoverDue(Instant dueAt) {
        Objects.requireNonNull(dueAt, "dueAt");
        ClaimedBatch batch = claimDueCandidates(dueAt);
        if (batch == null) {
            return;
        }
        for (ProvisionRecoveryCandidate candidate : batch.candidates()) {
            try {
                recoveryTransaction.executeWithoutResult(
                        ignored -> recoverClaimedCandidate(candidate, batch.claimToken()));
            } catch (RuntimeException failure) {
                log.warn(
                        "Platform provision outbox recovery left a bounded claim after a lock or database failure: operationId={}, errorType={}",
                        candidate.getId(),
                        failure.getClass().getSimpleName());
            }
        }
    }

    private ClaimedBatch claimDueCandidates(Instant dueAt) {
        UUID claimToken = UUID.randomUUID();
        try {
            return claimTransaction.execute(ignored -> {
                Instant claimedAt = clock.instant();
                Instant eligibleAt = dueAt.isBefore(claimedAt) ? dueAt : claimedAt;
                List<ProvisionRecoveryCandidate> candidates =
                        operationRepository.findClaimableProvisionCandidates(eligibleAt);
                List<ProvisionRecoveryCandidate> validCandidates = new ArrayList<>();
                List<UUID> operationIds = new ArrayList<>();
                for (ProvisionRecoveryCandidate candidate : candidates) {
                    if (candidate == null || candidate.getId() == null) {
                        log.warn("Platform provision outbox skipped an invalid recovery candidate");
                        continue;
                    }
                    validCandidates.add(candidate);
                    operationIds.add(candidate.getId());
                }
                if (operationIds.isEmpty()) {
                    return new ClaimedBatch(claimToken, List.of());
                }
                int claimed = operationRepository.claimProvisionCandidates(
                        operationIds,
                        claimToken,
                        eligibleAt,
                        claimedAt,
                        claimedAt.plusSeconds(CLAIM_LEASE_SECONDS));
                if (claimed != operationIds.size()) {
                    throw new IllegalStateException("Provision recovery claim count mismatch");
                }
                return new ClaimedBatch(claimToken, List.copyOf(validCandidates));
            });
        } catch (RuntimeException failure) {
            log.warn(
                    "Platform provision outbox claim transaction failed safely: errorType={}",
                    failure.getClass().getSimpleName());
            return null;
        }
    }

    private void recoverClaimedCandidate(
            ProvisionRecoveryCandidate candidate, UUID claimToken) {
        AutomationGrant grant = candidate.getGrantId() == null
                ? null
                : grantRepository.findRecoveryLockedById(candidate.getGrantId()).orElse(null);
        AutomationProjectOperation operation = operationRepository
                .findRecoveryLockedById(candidate.getId())
                .orElse(null);
        if (operation == null || !Objects.equals(claimToken, operation.getClaimToken())) {
            return;
        }
        if (!isPendingProvision(operation)) {
            releaseClaim(operation, clock.instant());
            return;
        }
        Instant now = clock.instant();
        if (!claimIsLive(operation, claimToken, now)) {
            releaseClaim(operation, now);
            return;
        }
        try {
            if (operation.getNextAttemptAt() == null
                    || operation.getCreatedAt() == null
                    || !isValidAttemptCount(operation.getAttemptCount())) {
                fail(operation, PROVISION_OPERATION_INVALID, now);
                return;
            }
            if (operation.getNextAttemptAt().isAfter(now)) {
                releaseClaim(operation, now);
                return;
            }
            if (recoveryDeadlineReached(operation, now)) {
                fail(operation, PROVISION_RECOVERY_TIMEOUT, now);
                return;
            }
            if (!Objects.equals(candidate.getGrantId(), operation.getGrantId())
                    || !grantAuthorizes(grant, operation, now)) {
                fail(operation, GRANT_INACTIVE, now);
                return;
            }
            if (!hasSuccessfulCreateLineage(operation)) {
                fail(operation, TRACE_CONTEXT_MISMATCH, now);
                return;
            }
            recoverAuthorized(operation);
        } catch (RuntimeException failure) {
            if (!isPendingProvision(operation)) {
                throw failure;
            }
            recordRecoveryFailure(operation, clock.instant(), failure);
        }
    }

    private boolean grantAuthorizes(
            AutomationGrant grant, AutomationProjectOperation operation, Instant now) {
        if (grant == null
                || !Boolean.TRUE.equals(grant.getActive())
                || !AutomationJwtVerifier.ACTOR_TYPE.equals(grant.getActorType())
                || !Objects.equals(grant.getId(), operation.getGrantId())
                || !Objects.equals(grant.getActor(), operation.getActor())
                || grant.getTokenVersion() == null
                || !Objects.equals(grant.getTokenVersion(), operation.getTokenVersion())
                || grant.getValidFrom() != null && now.isBefore(grant.getValidFrom())
                || grant.getValidUntil() != null && !now.isBefore(grant.getValidUntil())
                || operation.getTokenJti() == null
                || operation.getTokenJti().isBlank()
                || operation.getCreatedAt() == null
                || operation.getTaskId() == null
                || operation.getTaskId().isBlank()
                || operation.getRunId() == null
                || operation.getRunId().isBlank()
                || operation.getSpecDigest() == null
                || operation.getSpecDigest().isBlank()
                || !isValidAttemptCount(operation.getAttemptCount())
                || !refMatchesGrant(operation.getProjectRef(), grant.getAllowedRefPrefix())
                || !Objects.equals(
                        normalize(operation.getApprovalId()),
                        normalize(grant.getApprovalBinding()))) {
            return false;
        }
        try {
            Set<String> scopes = objectMapper.readValue(
                    grant.getScopes(), new TypeReference<LinkedHashSet<String>>() { });
            return scopes.contains(PlatformMcpScopes.PROJECT_PROVISION);
        } catch (Exception invalidScopes) {
            return false;
        }
    }

    private boolean hasSuccessfulCreateLineage(AutomationProjectOperation operation) {
        return operationRepository.countSuccessfulCreateLineage(
                operation.getActor(),
                operation.getGrantId(),
                operation.getTokenVersion(),
                operation.getProjectRef(),
                operation.getTaskId(),
                operation.getRunId(),
                operation.getSpecDigest(),
                normalize(operation.getApprovalId()),
                operation.getCreatedAt()) > 0;
    }

    private void recoverAuthorized(AutomationProjectOperation operation) {
        OwnedProjectState project = projectRepository
                .findOwnedByRef(operation.getActor(), operation.getProjectRef())
                .orElse(null);
        Instant observedAt = clock.instant();
        if (recoveryDeadlineReached(operation, observedAt)) {
            fail(operation, PROVISION_RECOVERY_TIMEOUT, observedAt);
            return;
        }
        if (project == null) {
            fail(operation, "PROJECT_NOT_FOUND", observedAt);
            return;
        }
        if (DatabaseInitStatus.INIT_FAILED.name().equalsIgnoreCase(project.initStatus())) {
            fail(operation, "PROJECT_PROVISION_FAILED", observedAt);
            return;
        }
        if (DatabaseInitStatus.INITIALIZED.name().equalsIgnoreCase(project.initStatus())) {
            finalizeInitialized(operation, project);
            return;
        }
        if (operation.getAttemptCount() >= MAX_RECOVERY_FAILURES) {
            fail(operation, PROVISION_RETRY_EXHAUSTED, observedAt);
            return;
        }

        int failureAttempt = operation.getAttemptCount() + 1;
        try {
            ProjectProvisioningService.Submission submission = provisioningService.submit(
                    project.dbKey(), project.initStatus(), project.ownerId());
            Instant completedAt = clock.instant();
            if (recoveryDeadlineReached(operation, completedAt)) {
                fail(operation, PROVISION_RECOVERY_TIMEOUT, completedAt);
                return;
            }
            Readiness readiness = readinessChecker.check(project, gatewayAvailable());
            ProvisionResult result = new ProvisionResult(
                    project.ref(),
                    "PROVISIONING",
                    submission.state().name(),
                    PlatformProjectAutomationFacade.STATIC_CONTROL_PLANE,
                    readiness);
            operation.setResponseJson(objectMapper.writeValueAsString(result));
            operation.setAttemptCount(0);
            operation.setNextAttemptAt(completedAt.plusSeconds(PROVISION_POLL_DELAY_SECONDS));
            operation.setErrorCode(null);
            operation.setUpdatedAt(completedAt);
            clearClaim(operation);
            operationRepository.saveAndFlush(operation);
        } catch (Exception failure) {
            recordDispatchFailure(operation, failureAttempt, clock.instant(), failure);
        }
    }

    private void recordDispatchFailure(
            AutomationProjectOperation operation,
            int attempt,
            Instant now,
            Exception failure) {
        if (recoveryDeadlineReached(operation, now)) {
            fail(operation, PROVISION_RECOVERY_TIMEOUT, now);
            return;
        }
        operation.setAttemptCount(attempt);
        if (attempt >= MAX_RECOVERY_FAILURES) {
            fail(operation, PROVISION_RETRY_EXHAUSTED, now);
        } else {
            operation.setErrorCode("PROVISION_DISPATCH_FAILED");
            operation.setNextAttemptAt(now.plusSeconds(retryDelaySeconds(attempt)));
            operation.setUpdatedAt(now);
            clearClaim(operation);
            operationRepository.saveAndFlush(operation);
        }
        log.warn(
                "Platform provision outbox dispatch failed safely: operationId={}, attempt={}, errorType={}",
                operation.getId(),
                attempt,
                failure.getClass().getSimpleName());
    }

    private void finalizeInitialized(
            AutomationProjectOperation operation, OwnedProjectState project) {
        try {
            Instant now = clock.instant();
            if (recoveryDeadlineReached(operation, now)) {
                fail(operation, PROVISION_RECOVERY_TIMEOUT, now);
                return;
            }
            Readiness readiness = readinessChecker.check(project, gatewayAvailable());
            String state;
            if (!project.enabled()) {
                state = "DISABLED";
            } else if (readinessChecker.allRequiredStaticChecksPass(readiness)) {
                state = "PROVISIONED";
            } else {
                state = "BLOCKED";
            }
            ProvisionResult result = new ProvisionResult(
                    project.ref(),
                    state,
                    ProjectProvisioningService.SubmissionState.ALREADY_INITIALIZED.name(),
                    PlatformProjectAutomationFacade.STATIC_CONTROL_PLANE,
                    readiness);
            operation.setResponseJson(objectMapper.writeValueAsString(result));
            operation.setErrorCode(null);
            operation.setStatus("SUCCEEDED");
            operation.setUpdatedAt(now);
            clearClaim(operation);
            operationRepository.saveAndFlush(operation);
        } catch (Exception failure) {
            Instant now = clock.instant();
            if (recoveryDeadlineReached(operation, now)) {
                fail(operation, PROVISION_RECOVERY_TIMEOUT, now);
                return;
            }
            int attempt = operation.getAttemptCount() + 1;
            operation.setStatus("PENDING");
            operation.setAttemptCount(attempt);
            if (attempt >= MAX_RECOVERY_FAILURES) {
                fail(operation, PROVISION_RETRY_EXHAUSTED, now);
            } else {
                operation.setErrorCode("OPERATION_RESULT_WRITE_FAILED");
                operation.setNextAttemptAt(now.plusSeconds(retryDelaySeconds(attempt)));
                operation.setUpdatedAt(now);
                clearClaim(operation);
                operationRepository.saveAndFlush(operation);
            }
        }
    }

    private void recordRecoveryFailure(
            AutomationProjectOperation operation, Instant now, RuntimeException failure) {
        if (recoveryDeadlineReached(operation, now)) {
            fail(operation, PROVISION_RECOVERY_TIMEOUT, now);
            return;
        }
        if (!isValidAttemptCount(operation.getAttemptCount())) {
            fail(operation, PROVISION_OPERATION_INVALID, now);
            return;
        }
        int attempt = operation.getAttemptCount() + 1;
        operation.setAttemptCount(attempt);
        if (attempt >= MAX_RECOVERY_FAILURES) {
            fail(operation, PROVISION_RETRY_EXHAUSTED, now);
        } else {
            operation.setErrorCode(PROVISION_RECOVERY_FAILED);
            operation.setNextAttemptAt(now.plusSeconds(retryDelaySeconds(attempt)));
            operation.setUpdatedAt(now);
            clearClaim(operation);
            operationRepository.saveAndFlush(operation);
        }
        log.warn(
                "Platform provision outbox business recovery failed safely: operationId={}, attempt={}, errorType={}",
                operation.getId(),
                attempt,
                failure.getClass().getSimpleName());
    }

    private void fail(AutomationProjectOperation operation, String errorCode, Instant now) {
        try {
            operation.setResponseJson(objectMapper.writeValueAsString(Map.of("error", errorCode)));
        } catch (Exception ignored) {
            operation.setResponseJson("{\"error\":\"OPERATION_RESULT_WRITE_FAILED\"}");
            errorCode = "OPERATION_RESULT_WRITE_FAILED";
        }
        operation.setErrorCode(errorCode);
        operation.setStatus("FAILED");
        operation.setNextAttemptAt(now);
        operation.setUpdatedAt(now);
        clearClaim(operation);
        operationRepository.saveAndFlush(operation);
    }

    private void releaseClaim(AutomationProjectOperation operation, Instant now) {
        clearClaim(operation);
        operation.setUpdatedAt(now);
        operationRepository.saveAndFlush(operation);
    }

    private static void clearClaim(AutomationProjectOperation operation) {
        operation.setClaimToken(null);
        operation.setClaimedUntil(null);
    }

    private static boolean claimIsLive(
            AutomationProjectOperation operation, UUID claimToken, Instant now) {
        return Objects.equals(claimToken, operation.getClaimToken())
                && operation.getClaimedUntil() != null
                && now.isBefore(operation.getClaimedUntil());
    }

    private static boolean recoveryDeadlineReached(
            AutomationProjectOperation operation, Instant now) {
        if (operation.getCreatedAt() == null) {
            return true;
        }
        try {
            return !now.isBefore(operation.getCreatedAt()
                    .plusSeconds(PROVISION_RECOVERY_DEADLINE_SECONDS));
        } catch (RuntimeException invalidTimestamp) {
            return true;
        }
    }

    private static boolean isPendingProvision(AutomationProjectOperation operation) {
        return PlatformProjectAutomationFacade.PROVISION_ACTION.equals(operation.getAction())
                && "PENDING".equals(operation.getStatus());
    }

    private static boolean isValidAttemptCount(Integer attemptCount) {
        return attemptCount != null
                && attemptCount >= 0
                && attemptCount <= MAX_RECOVERY_FAILURES;
    }

    private static boolean refMatchesGrant(String ref, String prefix) {
        return ref != null
                && prefix != null
                && prefix.endsWith("_")
                && ref.startsWith(prefix);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    static long retryDelaySeconds(int attempt) {
        long exponential = BASE_RETRY_DELAY_SECONDS << Math.max(0, attempt - 1);
        return Math.min(exponential, MAX_RETRY_DELAY_SECONDS);
    }

    private boolean gatewayAvailable() {
        try {
            return upstreamRepository.hasUsableActiveCatalogUpstream();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private record ClaimedBatch(
            UUID claimToken, List<ProvisionRecoveryCandidate> candidates) {
    }
}
