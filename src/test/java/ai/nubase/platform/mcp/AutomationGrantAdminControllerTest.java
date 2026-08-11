package ai.nubase.platform.mcp;

import ai.nubase.auth.service.PlatformAuthService;
import ai.nubase.common.multitenancy.AdminInitAuthFilter;
import ai.nubase.platform.mcp.AutomationGrantAdminService.CreateGrantRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutomationGrantAdminControllerTest {

    private AutomationGrantAdminService grantAdminService;
    private AutomationGrantAdminController controller;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        grantAdminService = mock(AutomationGrantAdminService.class);
        controller = new AutomationGrantAdminController(grantAdminService);
        request = mock(HttpServletRequest.class);
    }

    @Test
    void rejectsPlatformSuperAdminInsteadOfTreatingItAsMetadataRoot() {
        when(request.getHeader("Authorization")).thenReturn("Bearer platform-jwt");
        when(request.getAttribute("platformUserId")).thenReturn(UUID.randomUUID());

        var response = controller.create(createRequest(), request);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).isEqualTo(
                java.util.Map.of("error", "METADATA_ROOT_REQUIRED"));
        verify(grantAdminService, never()).createGrant(any());
    }

    @Test
    void rejectsPlatformJwtEvenWhenItsSubjectIsTheReservedSystemUuid() {
        when(request.getHeader("Authorization")).thenReturn("Bearer platform-jwt");
        when(request.getAttribute("platformUserId"))
                .thenReturn(PlatformAuthService.SYSTEM_USER_ID);

        var response = controller.create(createRequest(), request);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verify(grantAdminService, never()).createGrant(any());
    }

    @Test
    void rejectsLegacyRawAuthorizationEvenForTheMetadataRootIdentity() {
        when(request.getHeader("Authorization")).thenReturn("metadata-root-key");
        when(request.getAttribute("platformUserId"))
                .thenReturn(PlatformAuthService.SYSTEM_USER_ID);

        var response = controller.create(createRequest(), request);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verify(grantAdminService, never()).createGrant(any());
    }

    @Test
    void acceptsBearerMetadataRootIdentity() {
        CreateGrantRequest body = createRequest();
        when(request.getHeader("Authorization")).thenReturn("Bearer metadata-root-key");
        when(request.getAttribute("platformUserId"))
                .thenReturn(PlatformAuthService.SYSTEM_USER_ID);
        when(request.getAttribute(AdminInitAuthFilter.METADATA_ROOT_AUTHENTICATED_ATTRIBUTE))
                .thenReturn(Boolean.TRUE);

        var response = controller.create(body, request);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        verify(grantAdminService).createGrant(body);
    }

    private static CreateGrantRequest createRequest() {
        return new CreateGrantRequest(
                "agentteams.local",
                "build",
                "goai_",
                2,
                "approval-1",
                3600,
                300);
    }
}
