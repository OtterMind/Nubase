package ai.nubase.platform.mcp;

import ai.nubase.ai.gateway.platform.PlatformUpstreamRepository;
import ai.nubase.auth.service.ProjectProvisioningService;
import ai.nubase.metadata.entity.AutomationGrant;
import ai.nubase.metadata.entity.AutomationProjectOperation;
import ai.nubase.metadata.repository.AutomationGrantRepository;
import ai.nubase.metadata.repository.AutomationProjectOperationRepository;
import ai.nubase.metadata.repository.AutomationProjectOperationRepository.ProvisionRecoveryCandidate;
import ai.nubase.platform.mcp.PlatformMcpProjectRepository.OwnedProjectState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformProvisionOutboxRecoveryTest {

    private static final Instant NOW = Instant.parse("2026-08-11T08:00:00Z");
    private static final UUID OPERATION_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID GRANT_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");

    private AutomationProjectOperationRepository operationRepository;
    private AutomationGrantRepository grantRepository;
    private PlatformMcpProjectRepository projectRepository;
    private ProjectProvisioningService provisioningService;
    private PlatformUpstreamRepository upstreamRepository;
    private PlatformProvisionOutboxRecovery recovery;
    private AutomationProjectOperation operation;
    private AutomationGrant grant;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        operationRepository = mock(AutomationProjectOperationRepository.class);
        grantRepository = mock(AutomationGrantRepository.class);
        projectRepository = mock(PlatformMcpProjectRepository.class);
        provisioningService = mock(ProjectProvisioningService.class);
        upstreamRepository = mock(PlatformUpstreamRepository.class);
        clock = new MutableClock(NOW);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(transactionStatus);

        PlatformMcpTenantReadinessProbe tenantReadinessProbe =
                mock(PlatformMcpTenantReadinessProbe.class);
        when(tenantReadinessProbe.check(anyString(), anyBoolean(), anyBoolean(), anyBoolean()))
                .thenReturn(new PlatformMcpTenantReadinessProbe.TenantReadiness(
                        true, true, true, true, true, true));
        recovery = new PlatformProvisionOutboxRecovery(
                operationRepository,
                grantRepository,
                projectRepository,
                provisioningService,
                upstreamRepository,
                new PlatformMcpReadinessChecker(new MockEnvironment(), tenantReadinessProbe),
                new ObjectMapper(),
                transactionManager,
                clock);

        operation = pendingOperation(OPERATION_ID, "goai_notes");
        grant = activeGrant();
        when(operationRepository.findClaimableProvisionCandidates(any()))
                .thenReturn(List.of(candidate(operation)));
        when(grantRepository.findRecoveryLockedById(GRANT_ID))
                .thenReturn(Optional.of(grant));
        when(operationRepository.findRecoveryLockedById(OPERATION_ID))
                .thenReturn(Optional.of(operation));
        stubClaiming(operation);
        when(operationRepository.countSuccessfulCreateLineage(
                eq("agentteams.local"),
                eq(GRANT_ID),
                eq(7L),
                eq("goai_notes"),
                eq("task-1"),
                eq("run-1"),
                eq("sha256:" + "a".repeat(64)),
                isNull(),
                eq(NOW.minusSeconds(30))))
                .thenReturn(1L);
        when(projectRepository.findOwnedByRef("agentteams.local", "goai_notes"))
                .thenReturn(Optional.of(pendingProject("goai_notes")));
        when(upstreamRepository.hasUsableActiveCatalogUpstream()).thenReturn(false);
    }

    @Test
    void pendingNotInitializedDispatchesAndSchedulesASeparateStatusPoll() {
        when(provisioningService.submit(anyString(), anyString(), any()))
                .thenReturn(new ProjectProvisioningService.Submission(
                        ProjectProvisioningService.SubmissionState.QUEUED));

        recovery.recoverDue(NOW);

        verify(provisioningService).submit("goai_notes", "PENDING_INIT", OWNER_ID);
        assertThat(operation.getStatus()).isEqualTo("PENDING");
        assertThat(operation.getAttemptCount()).isZero();
        assertThat(operation.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(5));
        assertThat(operation.getErrorCode()).isNull();
        assertThat(operation.getClaimToken()).isNull();
        assertThat(operation.getClaimedUntil()).isNull();
        assertThat(operation.getResponseJson())
                .contains("QUEUED")
                .contains("STATIC_CONTROL_PLANE")
                .doesNotContain("token");
    }

    @Test
    void recoveryUsesTheSameGrantThenOperationLockOrderAsTheFacade() {
        when(provisioningService.submit(anyString(), anyString(), any()))
                .thenReturn(new ProjectProvisioningService.Submission(
                        ProjectProvisioningService.SubmissionState.QUEUED));

        recovery.recoverDue(NOW);

        InOrder lockOrder = inOrder(grantRepository, operationRepository);
        lockOrder.verify(grantRepository).findRecoveryLockedById(GRANT_ID);
        lockOrder.verify(operationRepository).findRecoveryLockedById(OPERATION_ID);
    }

    @Test
    void applicationReadyRecoveryFindsPreviouslyCommittedDueRows() {
        operation.setNextAttemptAt(Instant.EPOCH);
        grant.setValidUntil(Instant.parse("2099-01-01T00:00:00Z"));
        when(provisioningService.submit(anyString(), anyString(), any()))
                .thenReturn(new ProjectProvisioningService.Submission(
                        ProjectProvisioningService.SubmissionState.ALREADY_RUNNING));

        recovery.recoverOnStartup();

        verify(operationRepository).findClaimableProvisionCandidates(any());
        verify(provisioningService).submit(anyString(), anyString(), any());
        assertThat(operation.getStatus()).isEqualTo("PENDING");
    }

    @Test
    void initializedPhysicalProjectFinalizesWithStaticControlPlaneSemantics() {
        when(projectRepository.findOwnedByRef("agentteams.local", "goai_notes"))
                .thenReturn(Optional.of(initializedProject("goai_notes")));
        when(upstreamRepository.hasUsableActiveCatalogUpstream()).thenReturn(true);

        recovery.recoverDue(NOW);

        assertThat(operation.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(operation.getErrorCode()).isNull();
        assertThat(operation.getClaimToken()).isNull();
        assertThat(operation.getResponseJson())
                .contains("ALREADY_INITIALIZED")
                .contains("PROVISIONED")
                .contains("STATIC_CONTROL_PLANE");
    }

    @Test
    void dispatchFailuresUseBoundedExponentialBackoffAndCanRecover() {
        when(provisioningService.submit(anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("executor unavailable"))
                .thenReturn(new ProjectProvisioningService.Submission(
                        ProjectProvisioningService.SubmissionState.QUEUED));

        recovery.recoverDue(NOW);

        assertThat(operation.getAttemptCount()).isEqualTo(1);
        assertThat(operation.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(2));
        assertThat(operation.getErrorCode()).isEqualTo("PROVISION_DISPATCH_FAILED");
        assertThat(operation.getClaimToken()).isNull();

        clock.setInstant(NOW.plusSeconds(2));
        recovery.recoverDue(clock.instant());

        verify(provisioningService, times(2)).submit(anyString(), anyString(), any());
        assertThat(operation.getAttemptCount()).isZero();
        assertThat(operation.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(7));
        assertThat(operation.getErrorCode()).isNull();
    }

    @Test
    void slowRunningProvisioningDoesNotConsumeTheFailureBudget() {
        when(provisioningService.submit(anyString(), anyString(), any()))
                .thenReturn(new ProjectProvisioningService.Submission(
                        ProjectProvisioningService.SubmissionState.ALREADY_RUNNING));

        for (int poll = 0; poll < 20; poll++) {
            clock.setInstant(NOW.plusSeconds(
                    poll * PlatformProvisionOutboxRecovery.PROVISION_POLL_DELAY_SECONDS));
            recovery.recoverDue(clock.instant());
        }

        verify(provisioningService, times(20)).submit(anyString(), anyString(), any());
        assertThat(operation.getStatus()).isEqualTo("PENDING");
        assertThat(operation.getAttemptCount()).isZero();
        assertThat(operation.getErrorCode()).isNull();
    }

    @Test
    void maximumDispatchFailureFinalizesWithAFixedSafeError() {
        operation.setAttemptCount(PlatformProvisionOutboxRecovery.MAX_RECOVERY_FAILURES - 1);
        when(provisioningService.submit(anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("executor unavailable"));

        recovery.recoverDue(NOW);

        assertThat(operation.getAttemptCount())
                .isEqualTo(PlatformProvisionOutboxRecovery.MAX_RECOVERY_FAILURES);
        assertThat(operation.getStatus()).isEqualTo("FAILED");
        assertThat(operation.getErrorCode())
                .isEqualTo(PlatformProvisionOutboxRecovery.PROVISION_RETRY_EXHAUSTED);
        assertThat(operation.getClaimToken()).isNull();
        assertThat(operation.getResponseJson())
                .isEqualTo("{\"error\":\"PROVISION_RETRY_EXHAUSTED\"}");
    }

    @Test
    void revokedGrantTerminatesBeforeLineageLookupOrDispatch() {
        grant.setActive(false);
        grant.setTokenVersion(8L);

        recovery.recoverDue(NOW);

        assertThat(operation.getStatus()).isEqualTo("FAILED");
        assertThat(operation.getErrorCode())
                .isEqualTo(PlatformProvisionOutboxRecovery.GRANT_INACTIVE);
        verify(operationRepository, never()).countSuccessfulCreateLineage(
                anyString(), any(), any(), anyString(), anyString(), anyString(),
                anyString(), any(), any());
        verify(provisioningService, never()).submit(anyString(), anyString(), any());
    }

    @Test
    void activeGrantWithReplacedTokenVersionTerminatesBeforeDispatch() {
        grant.setTokenVersion(8L);

        recovery.recoverDue(NOW);

        assertThat(operation.getStatus()).isEqualTo("FAILED");
        assertThat(operation.getErrorCode())
                .isEqualTo(PlatformProvisionOutboxRecovery.GRANT_INACTIVE);
        verify(operationRepository, never()).countSuccessfulCreateLineage(
                anyString(), any(), any(), anyString(), anyString(), anyString(),
                anyString(), any(), any());
        verify(provisioningService, never()).submit(anyString(), anyString(), any());
    }

    @Test
    void missingSuccessfulCreateLineageTerminatesBeforeDispatch() {
        when(operationRepository.countSuccessfulCreateLineage(
                anyString(), any(), any(), anyString(), anyString(), anyString(),
                anyString(), any(), any()))
                .thenReturn(0L);

        recovery.recoverDue(NOW);

        assertThat(operation.getStatus()).isEqualTo("FAILED");
        assertThat(operation.getErrorCode())
                .isEqualTo(PlatformProvisionOutboxRecovery.TRACE_CONTEXT_MISMATCH);
        verify(projectRepository, never()).findOwnedByRef(anyString(), anyString());
        verify(provisioningService, never()).submit(anyString(), anyString(), any());
    }

    @Test
    void poisonCandidateIsBackedOffWithoutStarvingTheNextCandidate() {
        UUID poisonId = UUID.fromString("44444444-4444-4444-4444-444444444444");
        AutomationProjectOperation poison = pendingOperation(poisonId, "goai_poison");
        when(operationRepository.findClaimableProvisionCandidates(any()))
                .thenReturn(List.of(candidate(poison), candidate(operation)));
        when(operationRepository.findRecoveryLockedById(poisonId))
                .thenReturn(Optional.of(poison));
        stubClaiming(poison, operation);
        when(operationRepository.countSuccessfulCreateLineage(
                anyString(), any(), any(), anyString(), anyString(), anyString(),
                anyString(), any(), any()))
                .thenReturn(1L);
        when(projectRepository.findOwnedByRef("agentteams.local", "goai_poison"))
                .thenThrow(new IllegalStateException("poison row"));
        when(provisioningService.submit(anyString(), anyString(), any()))
                .thenReturn(new ProjectProvisioningService.Submission(
                        ProjectProvisioningService.SubmissionState.QUEUED));

        recovery.recoverDue(NOW);

        assertThat(poison.getStatus()).isEqualTo("PENDING");
        assertThat(poison.getAttemptCount()).isEqualTo(1);
        assertThat(poison.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(2));
        assertThat(poison.getErrorCode())
                .isEqualTo(PlatformProvisionOutboxRecovery.PROVISION_RECOVERY_FAILED);
        assertThat(poison.getClaimToken()).isNull();
        assertThat(operation.getAttemptCount()).isZero();
        verify(provisioningService).submit("goai_notes", "PENDING_INIT", OWNER_ID);
    }

    @Test
    void aClaimTakenOverByAnotherRecoveryInstanceIsNeverDispatchedOrCleared() {
        UUID otherClaimToken =
                UUID.fromString("55555555-5555-5555-5555-555555555555");
        when(operationRepository.findRecoveryLockedById(OPERATION_ID)).thenAnswer(ignored -> {
            operation.setClaimToken(otherClaimToken);
            operation.setClaimedUntil(NOW.plusSeconds(60));
            return Optional.of(operation);
        });

        recovery.recoverDue(NOW);

        assertThat(operation.getClaimToken()).isEqualTo(otherClaimToken);
        assertThat(operation.getClaimedUntil()).isEqualTo(NOW.plusSeconds(60));
        verify(grantRepository).findRecoveryLockedById(GRANT_ID);
        verify(provisioningService, never()).submit(anyString(), anyString(), any());
    }

    @Test
    void anExpiredLeaseCanBeReclaimedAndIsClearedAfterDispatch() {
        operation.setClaimToken(
                UUID.fromString("66666666-6666-6666-6666-666666666666"));
        operation.setClaimedUntil(NOW.minusSeconds(1));
        when(provisioningService.submit(anyString(), anyString(), any()))
                .thenReturn(new ProjectProvisioningService.Submission(
                        ProjectProvisioningService.SubmissionState.QUEUED));

        recovery.recoverDue(NOW);

        verify(provisioningService).submit("goai_notes", "PENDING_INIT", OWNER_ID);
        assertThat(operation.getClaimToken()).isNull();
        assertThat(operation.getClaimedUntil()).isNull();
    }

    @Test
    void grantExpiryUsesFreshClockTimeAfterBothLocksAreAcquired() {
        grant.setValidUntil(NOW.plusSeconds(1));
        when(operationRepository.findRecoveryLockedById(OPERATION_ID)).thenAnswer(ignored -> {
            clock.setInstant(NOW.plusSeconds(2));
            return Optional.of(operation);
        });

        recovery.recoverDue(NOW);

        assertThat(operation.getStatus()).isEqualTo("FAILED");
        assertThat(operation.getErrorCode())
                .isEqualTo(PlatformProvisionOutboxRecovery.GRANT_INACTIVE);
        assertThat(operation.getUpdatedAt()).isEqualTo(NOW.plusSeconds(2));
        verify(provisioningService, never()).submit(anyString(), anyString(), any());
    }

    @Test
    void aMissingGrantStillLocksTheOperationAndTerminatesSafely() {
        when(grantRepository.findRecoveryLockedById(GRANT_ID)).thenReturn(Optional.empty());

        recovery.recoverDue(NOW);

        InOrder lockOrder = inOrder(grantRepository, operationRepository);
        lockOrder.verify(grantRepository).findRecoveryLockedById(GRANT_ID);
        lockOrder.verify(operationRepository).findRecoveryLockedById(OPERATION_ID);
        assertThat(operation.getStatus()).isEqualTo("FAILED");
        assertThat(operation.getErrorCode())
                .isEqualTo(PlatformProvisionOutboxRecovery.GRANT_INACTIVE);
        assertThat(operation.getClaimToken()).isNull();
        verify(provisioningService, never()).submit(anyString(), anyString(), any());
    }

    @Test
    void aBlockedClaimedCandidateLeavesABoundedLeaseAndDoesNotStarveLaterWork() {
        UUID blockedId = UUID.fromString("77777777-7777-7777-7777-777777777777");
        AutomationProjectOperation blocked = pendingOperation(blockedId, "goai_blocked");
        when(operationRepository.findClaimableProvisionCandidates(any()))
                .thenReturn(List.of(candidate(blocked), candidate(operation)));
        when(grantRepository.findRecoveryLockedById(GRANT_ID))
                .thenThrow(new CannotAcquireLockException("row is busy"))
                .thenReturn(Optional.of(grant));
        stubClaiming(blocked, operation);
        when(provisioningService.submit(anyString(), anyString(), any()))
                .thenReturn(new ProjectProvisioningService.Submission(
                        ProjectProvisioningService.SubmissionState.QUEUED));

        recovery.recoverDue(NOW);

        assertThat(blocked.getStatus()).isEqualTo("PENDING");
        assertThat(blocked.getAttemptCount()).isZero();
        assertThat(blocked.getClaimToken()).isNotNull();
        assertThat(blocked.getClaimedUntil())
                .isEqualTo(NOW.plusSeconds(PlatformProvisionOutboxRecovery.CLAIM_LEASE_SECONDS));
        assertThat(operation.getClaimToken()).isNull();
        verify(operationRepository, never()).findRecoveryLockedById(blockedId);
        verify(provisioningService).submit("goai_notes", "PENDING_INIT", OWNER_ID);
    }

    @Test
    void aStalledRecoveryTerminatesAtTheWallClockDeadline() {
        operation.setCreatedAt(NOW.minusSeconds(
                PlatformProvisionOutboxRecovery.PROVISION_RECOVERY_DEADLINE_SECONDS));

        recovery.recoverDue(NOW);

        assertThat(operation.getStatus()).isEqualTo("FAILED");
        assertThat(operation.getErrorCode())
                .isEqualTo(PlatformProvisionOutboxRecovery.PROVISION_RECOVERY_TIMEOUT);
        assertThat(operation.getResponseJson())
                .isEqualTo("{\"error\":\"PROVISION_RECOVERY_TIMEOUT\"}");
        assertThat(operation.getClaimToken()).isNull();
        verify(provisioningService, never()).submit(anyString(), anyString(), any());
    }

    @Test
    void missingProjectFinalizesWithAFixedSafeFailure() {
        when(projectRepository.findOwnedByRef("agentteams.local", "goai_notes"))
                .thenReturn(Optional.empty());

        recovery.recoverDue(NOW);

        ArgumentCaptor<AutomationProjectOperation> saved =
                ArgumentCaptor.forClass(AutomationProjectOperation.class);
        verify(operationRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo("FAILED");
        assertThat(saved.getValue().getErrorCode()).isEqualTo("PROJECT_NOT_FOUND");
        assertThat(saved.getValue().getResponseJson()).isEqualTo(
                "{\"error\":\"PROJECT_NOT_FOUND\"}");
    }

    @Test
    void retryDelayIsExponentialAndCapped() {
        assertThat(PlatformProvisionOutboxRecovery.retryDelaySeconds(1)).isEqualTo(2);
        assertThat(PlatformProvisionOutboxRecovery.retryDelaySeconds(2)).isEqualTo(4);
        assertThat(PlatformProvisionOutboxRecovery.retryDelaySeconds(3)).isEqualTo(8);
        assertThat(PlatformProvisionOutboxRecovery.retryDelaySeconds(4)).isEqualTo(16);
        assertThat(PlatformProvisionOutboxRecovery.retryDelaySeconds(5)).isEqualTo(32);
        assertThat(PlatformProvisionOutboxRecovery.retryDelaySeconds(10)).isEqualTo(60);
    }

    private static ProvisionRecoveryCandidate candidate(AutomationProjectOperation value) {
        return new TestProvisionRecoveryCandidate(value.getId(), value.getGrantId());
    }

    private void stubClaiming(AutomationProjectOperation... operations) {
        when(operationRepository.claimProvisionCandidates(
                anyList(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    List<UUID> operationIds = invocation.getArgument(0);
                    UUID claimToken = invocation.getArgument(1);
                    Instant eligibleAt = invocation.getArgument(2);
                    Instant claimedUntil = invocation.getArgument(4);
                    int claimed = 0;
                    for (AutomationProjectOperation candidate : operations) {
                        if (!operationIds.contains(candidate.getId())) {
                            continue;
                        }
                        if (candidate.getClaimToken() != null
                                && candidate.getClaimedUntil() != null
                                && candidate.getClaimedUntil().isAfter(eligibleAt)) {
                            continue;
                        }
                        candidate.setClaimToken(claimToken);
                        candidate.setClaimedUntil(claimedUntil);
                        claimed++;
                    }
                    return claimed;
                });
    }

    private static AutomationProjectOperation pendingOperation(UUID id, String ref) {
        return AutomationProjectOperation.builder()
                .id(id)
                .actor("agentteams.local")
                .action(PlatformProjectAutomationFacade.PROVISION_ACTION)
                .idempotencyKey("idem-key-1")
                .requestHash("b".repeat(64))
                .status("PENDING")
                .projectRef(ref)
                .taskId("task-1")
                .runId("run-1")
                .specDigest("sha256:" + "a".repeat(64))
                .grantId(GRANT_ID)
                .tokenJti("token-lineage-id")
                .tokenVersion(7L)
                .attemptCount(0)
                .nextAttemptAt(NOW)
                .createdAt(NOW.minusSeconds(30))
                .updatedAt(NOW.minusSeconds(30))
                .build();
    }

    private static AutomationGrant activeGrant() {
        return AutomationGrant.builder()
                .id(GRANT_ID)
                .actor("agentteams.local")
                .actorType(AutomationJwtVerifier.ACTOR_TYPE)
                .scopes("[\"project:provision\"]")
                .tokenVersion(7L)
                .allowedRefPrefix("goai_")
                .maxProjects(5)
                .active(true)
                .validFrom(NOW.minusSeconds(60))
                .validUntil(NOW.plusSeconds(3600))
                .createdAt(NOW.minusSeconds(60))
                .updatedAt(NOW.minusSeconds(60))
                .build();
    }

    private static OwnedProjectState pendingProject(String ref) {
        return new OwnedProjectState(
                ref,
                ref,
                "Notes",
                "PENDING_INIT",
                false,
                null,
                null,
                OWNER_ID,
                true);
    }

    private static OwnedProjectState initializedProject(String ref) {
        return new OwnedProjectState(
                ref,
                ref,
                "Notes",
                "INITIALIZED",
                true,
                NOW.minusSeconds(30),
                NOW,
                OWNER_ID,
                true);
    }

    private record TestProvisionRecoveryCandidate(UUID id, UUID grantId)
            implements ProvisionRecoveryCandidate {

        @Override
        public UUID getId() {
            return id;
        }

        @Override
        public UUID getGrantId() {
            return grantId;
        }
    }

    private static final class MutableClock extends Clock {

        private Instant instant;
        private final ZoneId zone;

        private MutableClock(Instant instant) {
            this(instant, ZoneId.of("UTC"));
        }

        private MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        private void setInstant(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
