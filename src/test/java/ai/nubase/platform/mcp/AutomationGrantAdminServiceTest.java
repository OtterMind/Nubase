package ai.nubase.platform.mcp;

import ai.nubase.metadata.entity.AutomationGrant;
import ai.nubase.metadata.repository.AutomationGrantRepository;
import ai.nubase.platform.mcp.AutomationGrantAdminService.CreateGrantRequest;
import ai.nubase.platform.mcp.AutomationGrantAdminService.MintTokenRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AutomationGrantAdminServiceTest {

    private static final String SECRET = "t".repeat(32);

    private AutomationGrantRepository grantRepository;
    private AutomationGrantAdminService adminService;
    private AutomationJwtVerifier verifier;
    private AtomicReference<AutomationGrant> savedGrant;

    @BeforeEach
    void setUp() {
        grantRepository = mock(AutomationGrantRepository.class);
        PlatformMcpProperties properties = new PlatformMcpProperties();
        properties.setJwtSecret(SECRET);
        ObjectMapper objectMapper = new ObjectMapper();
        adminService = new AutomationGrantAdminService(properties, grantRepository, objectMapper);
        adminService.init();
        verifier = new AutomationJwtVerifier(properties, grantRepository, objectMapper);
        verifier.init();
        savedGrant = new AtomicReference<>();
        when(grantRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            AutomationGrant grant = invocation.getArgument(0);
            savedGrant.set(grant);
            return grant;
        });
        when(grantRepository.findById(any())).thenAnswer(invocation ->
                Optional.ofNullable(savedGrant.get()));
        when(grantRepository.findLockedById(any())).thenAnswer(invocation ->
                Optional.ofNullable(savedGrant.get()));
    }

    @Test
    void rootHelperCanMintBuildThenReadTokensFromTheSameGrant() {
        var created = adminService.createGrant(new CreateGrantRequest(
                "agentteams.local",
                "build",
                "goai_",
                2,
                "approval-1",
                3600,
                300));

        AutomationPrincipal build = verifier.verify(created.token());
        assertThat(build.scopes()).isEqualTo(PlatformMcpScopes.ALL);

        var readToken = adminService.mintToken(
                created.grantId(), new MintTokenRequest("read", 300));
        AutomationPrincipal read = verifier.verify(readToken.token());
        assertThat(read.grantId()).isEqualTo(build.grantId());
        assertThat(read.actor()).isEqualTo(build.actor());
        assertThat(read.scopes()).containsExactly(PlatformMcpScopes.PROJECT_READ);
    }

    @Test
    void activeActorUniquenessFailureUsesAFixedSafeCode() {
        when(grantRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("database detail sentinel"));

        assertThatThrownBy(() -> adminService.createGrant(new CreateGrantRequest(
                "agentteams.local",
                "build",
                "goai_",
                2,
                null,
                3600,
                300)))
                .isInstanceOfSatisfying(PlatformMcpOperationException.class,
                        error -> {
                            assertThat(error.code()).isEqualTo("ACTIVE_GRANT_EXISTS");
                            assertThat(error.getMessage()).doesNotContain("sentinel");
                        });
    }
}
