package ai.nubase.platform.mcp;

import ai.nubase.ai.gateway.platform.PlatformUpstreamRepository;
import ai.nubase.auth.dto.response.admin.InitDatabaseResponse;
import ai.nubase.auth.service.DatabaseInitService;
import ai.nubase.auth.service.ProjectOwnershipService;
import ai.nubase.auth.service.ProjectProvisioningService;
import ai.nubase.metadata.entity.AutomationGrant;
import ai.nubase.metadata.entity.AutomationProjectOperation;
import ai.nubase.metadata.repository.AutomationGrantRepository;
import ai.nubase.metadata.repository.AutomationProjectOperationRepository;
import ai.nubase.platform.mcp.PlatformMcpProjectRepository.OwnedProjectState;
import ai.nubase.platform.mcp.PlatformProjectDtos.CreateRequest;
import ai.nubase.platform.mcp.PlatformProjectDtos.ProvisionRequest;
import ai.nubase.platform.mcp.PlatformProjectDtos.ProvisionResult;
import ai.nubase.platform.mcp.PlatformProjectDtos.Readiness;
import ai.nubase.platform.mcp.PlatformProjectDtos.StatusRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformProjectAutomationFacadeTest {

    private static final UUID GRANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID REPLACEMENT_GRANT_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String ACTOR = "agentteams.local";
    private static final String DIGEST =
            "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private DatabaseInitService databaseInitService;
    private ProjectOwnershipService ownershipService;
    private ProjectProvisioningService provisioningService;
    private PlatformUpstreamRepository upstreamRepository;
    private PlatformMcpProjectRepository projectRepository;
    private AutomationGrantRepository grantRepository;
    private AutomationProjectOperationRepository operationRepository;
    private PlatformMcpTenantReadinessProbe tenantReadinessProbe;
    private MockEnvironment environment;
    private PlatformMcpProperties properties;
    private PlatformProjectAutomationFacade facade;

    @BeforeEach
    void setUp() {
        databaseInitService = mock(DatabaseInitService.class);
        ownershipService = mock(ProjectOwnershipService.class);
        provisioningService = mock(ProjectProvisioningService.class);
        upstreamRepository = mock(PlatformUpstreamRepository.class);
        projectRepository = mock(PlatformMcpProjectRepository.class);
        grantRepository = mock(AutomationGrantRepository.class);
        operationRepository = mock(AutomationProjectOperationRepository.class);
        tenantReadinessProbe = mock(PlatformMcpTenantReadinessProbe.class);
        when(tenantReadinessProbe.check(anyString(), anyBoolean(), anyBoolean(), anyBoolean()))
                .thenReturn(new PlatformMcpTenantReadinessProbe.TenantReadiness(
                        true, true, true, true, true, true));
        when(operationRepository.countMatchingTrace(
                anyString(), any(), anyString(), anyString(), anyString(), anyString(),
                anyString(), any()))
                .thenReturn(1L);
        environment = new MockEnvironment();
        CanonicalRequestHasher hasher = mock(CanonicalRequestHasher.class);
        when(hasher.hash(any())).thenReturn("a".repeat(64));
        properties = new PlatformMcpProperties();
        properties.setPublicBaseUrl("https://nubase.example/");
        facade = new PlatformProjectAutomationFacade(
                databaseInitService,
                ownershipService,
                provisioningService,
                upstreamRepository,
                projectRepository,
                grantRepository,
                operationRepository,
                hasher,
                properties,
                new PlatformMcpReadinessChecker(environment, tenantReadinessProbe),
                new ObjectMapper());
    }

    @Test
    void createCommitsOnlyASafeLedgerResponse() {
        AtomicReference<UUID> claimedId = new AtomicReference<>();
        when(operationRepository.insertPendingIfAbsent(
                any(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), any(), any(), anyString(), any()))
                .thenAnswer(invocation -> {
                    claimedId.set(invocation.getArgument(0));
                    return 1;
                });
        when(operationRepository.findByActorAndGrantIdAndActionAndIdempotencyKey(
                ACTOR, GRANT_ID, PlatformProjectAutomationFacade.CREATE_ACTION, "idem-key-1"))
                .thenAnswer(invocation -> Optional.of(AutomationProjectOperation.builder()
                        .id(claimedId.get())
                        .actor(ACTOR)
                        .action(PlatformProjectAutomationFacade.CREATE_ACTION)
                        .idempotencyKey("idem-key-1")
                        .requestHash("a".repeat(64))
                        .status("PENDING")
                        .build()));
        when(grantRepository.findLockedById(GRANT_ID)).thenReturn(Optional.of(grant()));
        when(projectRepository.countOwnedProjects(ACTOR)).thenReturn(0);
        when(databaseInitService.createDatabaseConfigIfAbsent(any())).thenReturn(
                InitDatabaseResponse.success(
                        "jwt-secret-sentinel",
                        "service-role-sentinel",
                        "authenticated-sentinel",
                        "PENDING_INIT",
                        List.of("generated credentials"),
                        1));

        var result = facade.create(buildPrincipal(), new CreateRequest(
                "idem-key-1",
                "goai_notes",
                "Notes",
                "Demo",
                "task-1",
                "run-1",
                DIGEST,
                "approval-1"));

        assertThat(result.state()).isEqualTo("PENDING");
        ArgumentCaptor<AutomationProjectOperation> saved =
                ArgumentCaptor.forClass(AutomationProjectOperation.class);
        verify(operationRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getResponseJson())
                .doesNotContain("jwt-secret-sentinel")
                .doesNotContain("service-role-sentinel")
                .doesNotContain("authenticated-sentinel")
                .doesNotContain("jdbc:");
        verify(ownershipService).recordOwnership(
                any(),
                anyString(),
                anyString(),
                anyString());
    }

    @Test
    void createMapsAtomicReferenceConflictToStableErrorCode() {
        AtomicReference<UUID> claimedId = new AtomicReference<>();
        when(operationRepository.insertPendingIfAbsent(
                any(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), any(), any(), anyString(), any()))
                .thenAnswer(invocation -> {
                    claimedId.set(invocation.getArgument(0));
                    return 1;
                });
        when(operationRepository.findByActorAndGrantIdAndActionAndIdempotencyKey(
                ACTOR, GRANT_ID, PlatformProjectAutomationFacade.CREATE_ACTION, "idem-key-1"))
                .thenAnswer(invocation -> Optional.of(AutomationProjectOperation.builder()
                        .id(claimedId.get())
                        .actor(ACTOR)
                        .action(PlatformProjectAutomationFacade.CREATE_ACTION)
                        .idempotencyKey("idem-key-1")
                        .requestHash("a".repeat(64))
                        .status("PENDING")
                        .build()));
        when(grantRepository.findLockedById(GRANT_ID)).thenReturn(Optional.of(grant()));
        when(projectRepository.countOwnedProjects(ACTOR)).thenReturn(0);
        when(databaseInitService.createDatabaseConfigIfAbsent(any())).thenReturn(
                InitDatabaseResponse.error(
                        "Project reference already exists",
                        DatabaseInitService.PROJECT_REF_EXISTS,
                        1));

        assertThatThrownBy(() -> facade.create(buildPrincipal(), new CreateRequest(
                "idem-key-1",
                "goai_notes",
                "Notes",
                null,
                "task-1",
                "run-1",
                DIGEST,
                "approval-1")))
                .isInstanceOfSatisfying(PlatformMcpOperationException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DatabaseInitService.PROJECT_REF_EXISTS));
        verify(ownershipService, never()).recordOwnership(
                any(), anyString(), anyString(), anyString());
        verify(operationRepository, never()).saveAndFlush(any());
    }

    @Test
    void readTokenForTheSameActorCanReadTheBuilderCreatedProject() {
        when(projectRepository.findOwnedByRef(ACTOR, "goai_notes"))
                .thenReturn(Optional.of(readyProject()));
        when(upstreamRepository.hasUsableActiveCatalogUpstream()).thenReturn(true);

        var status = facade.status(readPrincipal(ACTOR), statusRequest("goai_notes"));

        assertThat(status.state()).isEqualTo("PROVISIONED");
        assertThat(status.verificationLevel()).isEqualTo("STATIC_CONTROL_PLANE");
        assertThat(status.taskId()).isEqualTo("task-1");
        assertThat(status.runId()).isEqualTo("run-1");
        assertThat(status.specDigest()).isEqualTo(DIGEST);
        assertThat(status.approvalId()).isEqualTo("approval-1");
        assertThat(status.readiness().gateway()).isTrue();
        assertThat(status.advertisedEndpoints())
                .containsEntry("gateway", "https://nubase.example/v1")
                .containsEntry("openAi", "https://nubase.example/v1")
                .doesNotContainKeys("apikey", "serviceRoleToken", "jdbcUrl");
    }

    @Test
    void aDifferentActorCannotReadTheProject() {
        when(projectRepository.findOwnedByRef("another.actor", "goai_notes"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> facade.status(
                readPrincipal("another.actor"), statusRequest("goai_notes")))
                .isInstanceOfSatisfying(PlatformMcpOperationException.class,
                        error -> assertThat(error.code()).isEqualTo("PROJECT_NOT_FOUND"));
        verify(upstreamRepository, never()).hasUsableActiveCatalogUpstream();
    }

    @Test
    void initializedProjectIsBlockedWhenNoPlatformGatewayUpstreamIsActive() {
        when(projectRepository.findOwnedByRef(ACTOR, "goai_notes"))
                .thenReturn(Optional.of(readyProject()));
        when(upstreamRepository.hasUsableActiveCatalogUpstream()).thenReturn(false);

        var status = facade.status(readPrincipal(ACTOR), statusRequest("goai_notes"));

        assertThat(status.state()).isEqualTo("BLOCKED");
        assertThat(status.readiness().database()).isTrue();
        assertThat(status.readiness().gateway()).isFalse();
        assertThat(status.advertisedEndpoints())
                .doesNotContainKeys("gateway", "openAi", "anthropicMessages");
    }

    @Test
    void disabledProjectDoesNotAdvertiseServiceEndpoints() {
        OwnedProjectState disabled = new OwnedProjectState(
                "goai_notes",
                "goai_notes",
                "Notes",
                "INITIALIZED",
                false,
                Instant.now().minusSeconds(30),
                Instant.now(),
                UUID.randomUUID(),
                true);
        when(projectRepository.findOwnedByRef(ACTOR, "goai_notes"))
                .thenReturn(Optional.of(disabled));
        when(upstreamRepository.hasUsableActiveCatalogUpstream()).thenReturn(true);

        var status = facade.status(readPrincipal(ACTOR), statusRequest("goai_notes"));

        assertThat(status.state()).isEqualTo("DISABLED");
        assertThat(status.advertisedEndpoints()).isEmpty();
        assertThat(status.readiness().database()).isFalse();
        assertThat(status.readiness().auth()).isFalse();
    }

    @Test
    void missingCredentialsBlocksAllServiceEndpointsWithoutSelectingSecrets() {
        OwnedProjectState missingCredentials = new OwnedProjectState(
                "goai_notes",
                "goai_notes",
                "Notes",
                "INITIALIZED",
                true,
                Instant.now().minusSeconds(30),
                Instant.now(),
                UUID.randomUUID(),
                false);
        when(projectRepository.findOwnedByRef(ACTOR, "goai_notes"))
                .thenReturn(Optional.of(missingCredentials));
        when(upstreamRepository.hasUsableActiveCatalogUpstream()).thenReturn(true);

        var status = facade.status(readPrincipal(ACTOR), statusRequest("goai_notes"));

        assertThat(status.state()).isEqualTo("BLOCKED");
        assertThat(status.readiness().credentials()).isFalse();
        assertThat(status.advertisedEndpoints()).isEmpty();
    }

    @Test
    void publicBaseUrlCannotEmbedCredentialsOrQueryValues() {
        properties.setPublicBaseUrl(
                "https://user:credential-sentinel@nubase.example?token=credential-sentinel");
        when(projectRepository.findOwnedByRef(ACTOR, "goai_notes"))
                .thenReturn(Optional.of(readyProject()));
        when(upstreamRepository.hasUsableActiveCatalogUpstream()).thenReturn(true);

        assertThatThrownBy(() -> facade.status(
                readPrincipal(ACTOR), statusRequest("goai_notes")))
                .isInstanceOfSatisfying(
                        PlatformMcpOperationException.class,
                        error -> assertThat(error.code())
                                .isEqualTo("PUBLIC_BASE_URL_INVALID")
                                .doesNotContain("credential-sentinel"));
    }

    @Test
    void disabledModuleFlagBlocksReadyAndOmitsOnlyThatEndpoint() {
        environment.setProperty("nubase.mem.enabled", "false");
        when(projectRepository.findOwnedByRef(ACTOR, "goai_notes"))
                .thenReturn(Optional.of(readyProject()));
        when(upstreamRepository.hasUsableActiveCatalogUpstream()).thenReturn(true);

        var status = facade.status(readPrincipal(ACTOR), statusRequest("goai_notes"));

        assertThat(status.state()).isEqualTo("BLOCKED");
        assertThat(status.readiness().memory()).isFalse();
        assertThat(status.advertisedEndpoints())
                .doesNotContainKey("memory")
                .containsKeys("auth", "storage", "assets", "functions", "mcp", "gateway");
    }

    @Test
    void revokedOrReplacedGrantCannotReplayAnOldOperationResult() {
        when(grantRepository.findLockedById(GRANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> facade.create(buildPrincipal(), new CreateRequest(
                "idem-key-1",
                "goai_notes",
                "Notes",
                null,
                "task-1",
                "run-1",
                DIGEST,
                "approval-1")))
                .isInstanceOfSatisfying(PlatformMcpOperationException.class,
                        error -> assertThat(error.code()).isEqualTo("GRANT_INACTIVE"));
        verify(operationRepository, never()).findByActorAndGrantIdAndActionAndIdempotencyKey(
                anyString(), any(), anyString(), anyString());
    }

    @Test
    void replacementGrantUsesANewIdempotencyLineage() {
        AtomicReference<UUID> claimedId = new AtomicReference<>();
        when(grantRepository.findLockedById(REPLACEMENT_GRANT_ID))
                .thenReturn(Optional.of(grant(REPLACEMENT_GRANT_ID)));
        when(projectRepository.countOwnedProjects(ACTOR)).thenReturn(0);
        when(operationRepository.insertPendingIfAbsent(
                any(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), any(), any(), anyString(), any()))
                .thenAnswer(invocation -> {
                    claimedId.set(invocation.getArgument(0));
                    return 1;
                });
        when(operationRepository.findByActorAndGrantIdAndActionAndIdempotencyKey(
                ACTOR,
                REPLACEMENT_GRANT_ID,
                PlatformProjectAutomationFacade.CREATE_ACTION,
                "idem-key-1"))
                .thenAnswer(invocation -> Optional.of(AutomationProjectOperation.builder()
                        .id(claimedId.get())
                        .actor(ACTOR)
                        .grantId(REPLACEMENT_GRANT_ID)
                        .action(PlatformProjectAutomationFacade.CREATE_ACTION)
                        .idempotencyKey("idem-key-1")
                        .requestHash("a".repeat(64))
                        .status("PENDING")
                        .build()));
        when(databaseInitService.createDatabaseConfigIfAbsent(any())).thenReturn(
                InitDatabaseResponse.error(
                        "Project reference already exists",
                        DatabaseInitService.PROJECT_REF_EXISTS,
                        1));

        assertThatThrownBy(() -> facade.create(
                buildPrincipal(REPLACEMENT_GRANT_ID),
                new CreateRequest(
                        "idem-key-1",
                        "goai_notes",
                        "Notes",
                        null,
                        "task-1",
                        "run-1",
                        DIGEST,
                        "approval-1")))
                .isInstanceOfSatisfying(PlatformMcpOperationException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DatabaseInitService.PROJECT_REF_EXISTS));
        verify(operationRepository).findByActorAndGrantIdAndActionAndIdempotencyKey(
                ACTOR,
                REPLACEMENT_GRANT_ID,
                PlatformProjectAutomationFacade.CREATE_ACTION,
                "idem-key-1");
        verify(operationRepository, never()).findByActorAndGrantIdAndActionAndIdempotencyKey(
                ACTOR,
                GRANT_ID,
                PlatformProjectAutomationFacade.CREATE_ACTION,
                "idem-key-1");
    }

    @Test
    void reusedIdempotencyKeyWithDifferentCanonicalRequestFailsClosed() {
        when(grantRepository.findLockedById(GRANT_ID)).thenReturn(Optional.of(grant()));
        when(operationRepository.findByActorAndGrantIdAndActionAndIdempotencyKey(
                ACTOR, GRANT_ID, PlatformProjectAutomationFacade.CREATE_ACTION, "idem-key-1"))
                .thenReturn(Optional.of(AutomationProjectOperation.builder()
                        .id(UUID.randomUUID())
                        .actor(ACTOR)
                        .action(PlatformProjectAutomationFacade.CREATE_ACTION)
                        .idempotencyKey("idem-key-1")
                        .requestHash("b".repeat(64))
                        .status("SUCCEEDED")
                        .responseJson("{\"created\":true}")
                        .build()));

        assertThatThrownBy(() -> facade.create(buildPrincipal(), new CreateRequest(
                "idem-key-1",
                "goai_notes",
                "Different request",
                null,
                "task-1",
                "run-1",
                DIGEST,
                "approval-1")))
                .isInstanceOfSatisfying(PlatformMcpOperationException.class,
                        error -> assertThat(error.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
        verify(databaseInitService, never()).createDatabaseConfigIfAbsent(any());
    }

    @Test
    void provisionPersistsAnOutboxResultWithoutQueuingBeforeCommit() {
        AtomicReference<UUID> claimedId = new AtomicReference<>();
        when(operationRepository.insertPendingIfAbsent(
                any(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), any(), any(), anyString(), any()))
                .thenAnswer(invocation -> {
                    claimedId.set(invocation.getArgument(0));
                    return 1;
                });
        when(operationRepository.findByActorAndGrantIdAndActionAndIdempotencyKey(
                ACTOR, GRANT_ID, PlatformProjectAutomationFacade.PROVISION_ACTION, "idem-key-2"))
                .thenAnswer(invocation -> Optional.of(AutomationProjectOperation.builder()
                        .id(claimedId.get())
                        .actor(ACTOR)
                        .action(PlatformProjectAutomationFacade.PROVISION_ACTION)
                        .idempotencyKey("idem-key-2")
                        .requestHash("a".repeat(64))
                        .status("PENDING")
                        .build()));
        when(grantRepository.findLockedById(GRANT_ID)).thenReturn(Optional.of(grant()));
        when(operationRepository
                .findFirstByActorAndGrantIdAndActionAndProjectRefAndStatusOrderByCreatedAtDesc(
                        ACTOR, GRANT_ID, PlatformProjectAutomationFacade.CREATE_ACTION,
                        "goai_notes", "SUCCEEDED"))
                .thenReturn(Optional.of(createOperation("task-1", "run-1", DIGEST, GRANT_ID)));
        when(projectRepository.findOwnedByRef(ACTOR, "goai_notes"))
                .thenReturn(Optional.of(pendingProject()));
        when(upstreamRepository.hasUsableActiveCatalogUpstream()).thenReturn(false);

        ProvisionResult result = facade.provision(buildPrincipal(), provisionRequest());

        assertThat(result.submission()).isEqualTo("PENDING_RECOVERY");
        ArgumentCaptor<AutomationProjectOperation> saved =
                ArgumentCaptor.forClass(AutomationProjectOperation.class);
        verify(operationRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo("PENDING");
        assertThat(saved.getValue().getResponseJson()).contains("PENDING_RECOVERY");
        verify(provisioningService, never()).submit(anyString(), anyString(), any());
    }

    @Test
    void sameHashPendingReplayReturnsTheDurableResultWithoutDirectDispatch() throws Exception {
        ProvisionResult durable = new ProvisionResult(
                "goai_notes",
                "PENDING",
                "PENDING_RECOVERY",
                "STATIC_CONTROL_PLANE",
                new Readiness(false, true, false, false, false, false, false, false, false));
        when(grantRepository.findLockedById(GRANT_ID)).thenReturn(Optional.of(grant()));
        when(operationRepository
                .findFirstByActorAndGrantIdAndActionAndProjectRefAndStatusOrderByCreatedAtDesc(
                        ACTOR, GRANT_ID, PlatformProjectAutomationFacade.CREATE_ACTION,
                        "goai_notes", "SUCCEEDED"))
                .thenReturn(Optional.of(createOperation("task-1", "run-1", DIGEST, GRANT_ID)));
        when(operationRepository.findByActorAndGrantIdAndActionAndIdempotencyKey(
                ACTOR, GRANT_ID, PlatformProjectAutomationFacade.PROVISION_ACTION, "idem-key-2"))
                .thenReturn(Optional.of(AutomationProjectOperation.builder()
                        .id(UUID.randomUUID())
                        .actor(ACTOR)
                        .action(PlatformProjectAutomationFacade.PROVISION_ACTION)
                        .idempotencyKey("idem-key-2")
                        .requestHash("a".repeat(64))
                        .status("PENDING")
                        .responseJson(new ObjectMapper().writeValueAsString(durable))
                        .build()));

        ProvisionResult replay = facade.provision(buildPrincipal(), provisionRequest());

        assertThat(replay).isEqualTo(durable);
        verify(projectRepository, never()).findOwnedByRef(anyString(), anyString());
        verify(provisioningService, never()).submit(anyString(), anyString(), any());
    }

    @Test
    void provisionRejectsCrossRunCreateLineage() {
        when(grantRepository.findLockedById(GRANT_ID)).thenReturn(Optional.of(grant()));
        when(operationRepository
                .findFirstByActorAndGrantIdAndActionAndProjectRefAndStatusOrderByCreatedAtDesc(
                        ACTOR, GRANT_ID, PlatformProjectAutomationFacade.CREATE_ACTION,
                        "goai_notes", "SUCCEEDED"))
                .thenReturn(Optional.of(createOperation(
                        "task-1", "different-run", DIGEST, GRANT_ID)));

        assertThatThrownBy(() -> facade.provision(buildPrincipal(), provisionRequest()))
                .isInstanceOfSatisfying(PlatformMcpOperationException.class,
                        error -> assertThat(error.code()).isEqualTo("TRACE_CONTEXT_MISMATCH"));
        verify(operationRepository, never()).insertPendingIfAbsent(
                any(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), any(), any(), anyString(), any());
    }

    @Test
    void provisionRejectsCrossSpecCreateLineage() {
        when(grantRepository.findLockedById(GRANT_ID)).thenReturn(Optional.of(grant()));
        when(operationRepository
                .findFirstByActorAndGrantIdAndActionAndProjectRefAndStatusOrderByCreatedAtDesc(
                        ACTOR, GRANT_ID, PlatformProjectAutomationFacade.CREATE_ACTION,
                        "goai_notes", "SUCCEEDED"))
                .thenReturn(Optional.of(createOperation(
                        "task-1", "run-1", "sha256:" + "b".repeat(64), GRANT_ID)));

        assertThatThrownBy(() -> facade.provision(buildPrincipal(), provisionRequest()))
                .isInstanceOfSatisfying(PlatformMcpOperationException.class,
                        error -> assertThat(error.code()).isEqualTo("TRACE_CONTEXT_MISMATCH"));
    }

    @Test
    void provisionRejectsCreateLineageFromAnotherGrant() {
        when(grantRepository.findLockedById(GRANT_ID)).thenReturn(Optional.of(grant()));
        when(operationRepository
                .findFirstByActorAndGrantIdAndActionAndProjectRefAndStatusOrderByCreatedAtDesc(
                        ACTOR, GRANT_ID, PlatformProjectAutomationFacade.CREATE_ACTION,
                        "goai_notes", "SUCCEEDED"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> facade.provision(buildPrincipal(), provisionRequest()))
                .isInstanceOfSatisfying(PlatformMcpOperationException.class,
                        error -> assertThat(error.code()).isEqualTo("TRACE_CONTEXT_MISMATCH"));
    }

    @Test
    void statusRejectsCrossRunProvisionTraceBeforeProjectLookup() {
        when(operationRepository.countMatchingTrace(
                anyString(), any(), anyString(), anyString(), anyString(), anyString(),
                anyString(), any()))
                .thenReturn(0L);

        assertThatThrownBy(() -> facade.status(
                readPrincipal(ACTOR), statusRequest("goai_notes")))
                .isInstanceOfSatisfying(PlatformMcpOperationException.class,
                        error -> assertThat(error.code()).isEqualTo("TRACE_CONTEXT_MISMATCH"));
        verify(projectRepository, never()).findOwnedByRef(anyString(), anyString());
    }

    @Test
    void statusReconcilesACommittedCreateBeforeProvisionIsRecorded() {
        when(operationRepository.countMatchingTrace(
                ACTOR,
                GRANT_ID,
                PlatformProjectAutomationFacade.PROVISION_ACTION,
                "goai_notes",
                "task-1",
                "run-1",
                DIGEST,
                "approval-1"))
                .thenReturn(0L);
        when(operationRepository.countMatchingTrace(
                ACTOR,
                GRANT_ID,
                PlatformProjectAutomationFacade.CREATE_ACTION,
                "goai_notes",
                "task-1",
                "run-1",
                DIGEST,
                "approval-1"))
                .thenReturn(1L);
        when(projectRepository.findOwnedByRef(ACTOR, "goai_notes"))
                .thenReturn(Optional.of(pendingProject()));
        when(upstreamRepository.hasUsableActiveCatalogUpstream()).thenReturn(true);

        var status = facade.status(readPrincipal(ACTOR), statusRequest("goai_notes"));

        assertThat(status.state()).isEqualTo("PENDING");
        assertThat(status.taskId()).isEqualTo("task-1");
        assertThat(status.runId()).isEqualTo("run-1");
    }

    @Test
    void failedProvisionLedgerStillAllowsSafeFailedStatus() {
        OwnedProjectState failed = new OwnedProjectState(
                "goai_notes",
                "goai_notes",
                "Notes",
                "INIT_FAILED",
                false,
                Instant.now().minusSeconds(30),
                null,
                UUID.randomUUID(),
                true);
        when(projectRepository.findOwnedByRef(ACTOR, "goai_notes"))
                .thenReturn(Optional.of(failed));
        when(upstreamRepository.hasUsableActiveCatalogUpstream()).thenReturn(false);

        var status = facade.status(readPrincipal(ACTOR), statusRequest("goai_notes"));

        assertThat(status.state()).isEqualTo("FAILED");
        assertThat(status.taskId()).isEqualTo("task-1");
        assertThat(status.runId()).isEqualTo("run-1");
    }

    private AutomationPrincipal buildPrincipal() {
        return buildPrincipal(GRANT_ID);
    }

    private AutomationPrincipal buildPrincipal(UUID grantId) {
        return new AutomationPrincipal(
                ACTOR,
                grantId,
                "jti-1",
                1,
                PlatformMcpScopes.ALL,
                "goai_",
                2,
                "approval-1");
    }

    private AutomationPrincipal readPrincipal(String actor) {
        return new AutomationPrincipal(
                actor,
                GRANT_ID,
                "jti-read",
                1,
                Set.of(PlatformMcpScopes.PROJECT_READ),
                "goai_",
                2,
                "approval-1");
    }

    private AutomationGrant grant() {
        return grant(GRANT_ID);
    }

    private AutomationGrant grant(UUID grantId) {
        return AutomationGrant.builder()
                .id(grantId)
                .actor(ACTOR)
                .actorType(AutomationJwtVerifier.ACTOR_TYPE)
                .scopes("[\"project:create\",\"project:provision\",\"project:status\"]")
                .tokenVersion(1L)
                .allowedRefPrefix("goai_")
                .maxProjects(2)
                .approvalBinding("approval-1")
                .active(true)
                .build();
    }

    private OwnedProjectState readyProject() {
        return new OwnedProjectState(
                "goai_notes",
                "goai_notes",
                "Notes",
                "INITIALIZED",
                true,
                Instant.now().minusSeconds(30),
                Instant.now(),
                UUID.randomUUID(),
                true);
    }

    private OwnedProjectState pendingProject() {
        return new OwnedProjectState(
                "goai_notes",
                "goai_notes",
                "Notes",
                "PENDING_INIT",
                false,
                null,
                null,
                UUID.randomUUID(),
                true);
    }

    private AutomationProjectOperation createOperation(
            String taskId, String runId, String specDigest, UUID grantId) {
        return AutomationProjectOperation.builder()
                .id(UUID.randomUUID())
                .actor(ACTOR)
                .action(PlatformProjectAutomationFacade.CREATE_ACTION)
                .projectRef("goai_notes")
                .status("SUCCEEDED")
                .taskId(taskId)
                .runId(runId)
                .specDigest(specDigest)
                .approvalId("approval-1")
                .grantId(grantId)
                .build();
    }

    private ProvisionRequest provisionRequest() {
        return new ProvisionRequest(
                "idem-key-2", "goai_notes", "task-1", "run-1", DIGEST, "approval-1");
    }

    private StatusRequest statusRequest(String ref) {
        return new StatusRequest(
                ref, "task-1", "run-1", DIGEST, "approval-1");
    }
}
