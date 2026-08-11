package ai.nubase.common.multitenancy;

import ai.nubase.auth.service.PlatformAuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminInitAuthFilterTest {

    private static final String METADATA_KEY = "metadata-service-role-key";

    private AdminInitAuthFilter filter;
    private PlatformAuthService platformAuthService;

    @BeforeEach
    void setUp() {
        filter = new AdminInitAuthFilter();
        platformAuthService = mock(PlatformAuthService.class);
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(new String[]{"dev"});
        ReflectionTestUtils.setField(filter, "metadataServiceRoleKey", METADATA_KEY);
        ReflectionTestUtils.setField(filter, "environment", environment);
        ReflectionTestUtils.setField(filter, "platformAuthService", platformAuthService);
    }

    @Test
    void acceptsMetadataServiceRoleKeyForPlatformDeployPath() throws Exception {
        var request = platformRequest("POST", "/deployments/platform/v1/app-workers/deploy", METADATA_KEY);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(request.getAttribute("platformUserId")).isEqualTo(PlatformAuthService.SYSTEM_USER_ID);
        assertThat(request.getAttribute(AdminInitAuthFilter.METADATA_ROOT_AUTHENTICATED_ATTRIBUTE))
                .isEqualTo(Boolean.TRUE);
        assertThat(request.getAttribute("platformIsSuperAdmin")).isEqualTo(true);
    }

    @Test
    void acceptsMetadataServiceRoleKeyForPlatformAppWorkerReadPath() throws Exception {
        var request = platformRequest("GET", "/deployments/platform/v1/app-workers/appabc", METADATA_KEY);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(request.getAttribute("platformIsSuperAdmin")).isEqualTo(true);
    }

    @Test
    void rejectsNonSuperAdminPlatformJwtForAppWorkerControlPlane() throws Exception {
        var request = platformRequest("POST", "/deployments/platform/v1/app-workers/deploy", "platform-jwt");
        when(platformAuthService.resolvePrincipal("platform-jwt"))
                .thenReturn(new PlatformAuthService.PlatformPrincipal(
                        UUID.fromString("11111111-1111-1111-1111-111111111111"),
                        false,
                        PlatformAuthService.PLATFORM_ROLE_USER
                ));
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("Super admin privileges are required");
    }

    @Test
    void acceptsPlatformJwtFromApikeyHeaderForLegacyStudioRequests() throws Exception {
        var request = new MockHttpServletRequest("GET", "/auth/v1/admin/projects");
        request.addHeader("apikey", "platform-jwt");
        when(platformAuthService.resolvePrincipal("platform-jwt"))
                .thenReturn(new PlatformAuthService.PlatformPrincipal(
                        UUID.fromString("11111111-1111-1111-1111-111111111111"),
                        false,
                        PlatformAuthService.PLATFORM_ROLE_USER
                ));
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(request.getAttribute("platformUserId"))
                .isEqualTo(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        assertThat(request.getAttribute("platformIsSuperAdmin")).isEqualTo(false);
    }

    @Test
    void platformJwtWithSystemSubjectDoesNotGainMetadataRootCredentialKind() throws Exception {
        var request = platformRequest(
                "POST", "/auth/v1/admin/platform/automation-grants", "platform-jwt");
        when(platformAuthService.resolvePrincipal("platform-jwt"))
                .thenReturn(new PlatformAuthService.PlatformPrincipal(
                        PlatformAuthService.SYSTEM_USER_ID,
                        true,
                        PlatformAuthService.PLATFORM_ROLE_SUPER_ADMIN));
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(request.getAttribute("platformUserId"))
                .isEqualTo(PlatformAuthService.SYSTEM_USER_ID);
        assertThat(request.getAttribute(AdminInitAuthFilter.METADATA_ROOT_AUTHENTICATED_ATTRIBUTE))
                .isNull();
    }

    @Test
    void rejectsMetadataServiceRoleKeyFromApikeyHeaderForPlatformControlPlane() throws Exception {
        var request = new MockHttpServletRequest("POST", "/deployments/platform/v1/app-workers/deploy");
        request.addHeader("apikey", METADATA_KEY);
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("Invalid service role key");
    }

    @Test
    void rejectsMetadataServiceRoleKeyFromApikeyQueryForPlatformControlPlane() throws Exception {
        var request = new MockHttpServletRequest("POST", "/deployments/platform/v1/app-workers/deploy");
        request.setParameter("apikey", METADATA_KEY);
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("Missing authentication token");
    }

    private MockHttpServletRequest platformRequest(String method, String path, String token) {
        var request = new MockHttpServletRequest(method, path);
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }
}
