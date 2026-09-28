package ai.nubase.common.multitenancy;

import ai.nubase.auth.repository.UserRepository;
import ai.nubase.auth.service.JwtSecretService;
import ai.nubase.auth.service.OAuthStateService;
import ai.nubase.common.context.MultiTenancyContext;
import ai.nubase.common.enums.DatabaseInitStatus;
import ai.nubase.common.enums.Role;
import ai.nubase.postgrest.multidb.DatabaseConfig;
import ai.nubase.postgrest.multidb.DatabaseConfigRepository;
import ai.nubase.postgrest.multidb.RoutingDataSource;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UnifiedMultiTenancyFilterTest {

    private static final String JWT_SECRET = "test-secret-".repeat(4);

    @AfterEach
    void tearDown() {
        MultiTenancyContext.clear();
    }

    @Test
    void acceptsMatchingProjectRefHeaderFromSameOriginProxy() throws Exception {
        var repository = mock(DatabaseConfigRepository.class);
        var routingDataSource = mock(RoutingDataSource.class);
        when(repository.findByAppCode("appabc")).thenReturn(databaseConfig("appabc"));

        var request = requestWithProjectRef("appabc", "appabc");
        var response = new MockHttpServletResponse();

        filter(repository, routingDataSource)
                .doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(MultiTenancyContext.getAppCode()).isNull();
    }

    @Test
    void skipsAppWorkerDeployPathSoPlatformAdminFilterCanAuthenticateIt() throws Exception {
        var request = new MockHttpServletRequest(
                "POST",
                "/deployments/platform/v1/app-workers/deploy");
        request.setServerName("nubase.example");
        var response = new MockHttpServletResponse();

        filter(mock(DatabaseConfigRepository.class), mock(RoutingDataSource.class))
                .doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(MultiTenancyContext.getAppCode()).isNull();
    }

    @Test
    void skipsAppWorkerReadPathSoPlainPlatformKeyIsNotParsedAsTenantJwt() throws Exception {
        var request = new MockHttpServletRequest(
                "GET",
                "/deployments/platform/v1/app-workers/appabc");
        request.addHeader(
                "Authorization",
                "Bearer test-platform-key");
        request.setServerName("nubase.example");
        var response = new MockHttpServletResponse();

        filter(mock(DatabaseConfigRepository.class), mock(RoutingDataSource.class))
                .doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(MultiTenancyContext.getAppCode()).isNull();
    }

    @Test
    void skipsOnlyExactPublicModelCatalogPath() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/models/public");
        var response = new MockHttpServletResponse();

        filter(mock(DatabaseConfigRepository.class), mock(RoutingDataSource.class))
                .doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void doesNotSkipPathsBelowPublicModelCatalog() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/models/public/private");
        request.setServerName("nubase.example");
        var response = new MockHttpServletResponse();

        filter(mock(DatabaseConfigRepository.class), mock(RoutingDataSource.class))
                .doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("Apikey header is missing");
    }

    @Test
    void rejectsProjectRefHeaderWhenItDoesNotMatchApikeyRef() throws Exception {
        var request = requestWithProjectRef("appabc", "otherapp");
        var response = new MockHttpServletResponse();

        filter(mock(DatabaseConfigRepository.class), mock(RoutingDataSource.class))
                .doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString())
                .contains("x-nubase-project-ref does not match apikey ref");
    }

    @Test
    void clearsContextWhenResponseWriteFailsBeforeNextDeploymentOnSameThread() throws Exception {
        var repository = mock(DatabaseConfigRepository.class);
        when(repository.findByAppCode("appabc")).thenReturn(databaseConfig("appabc"));
        var filter = filter(repository, mock(RoutingDataSource.class));
        var response = new MockHttpServletResponse() {
            @Override
            public ServletOutputStream getOutputStream() {
                return new ServletOutputStream() {
                    @Override public boolean isReady() { return true; }
                    @Override public void setWriteListener(WriteListener listener) {}
                    @Override public void write(int value) throws IOException {
                        throw new IOException("simulated client disconnect");
                    }
                };
            }
        };

        assertThatThrownBy(() -> filter.doFilter(requestWithProjectRef("appabc", "appabc"), response,
                (request, cachedResponse) -> {
                    assertThat(MultiTenancyContext.getAppCode()).isEqualTo("appabc");
                    cachedResponse.getOutputStream().write(1);
                }))
                .isInstanceOf(IOException.class)
                .hasMessage("simulated client disconnect");
        assertThat(MultiTenancyContext.getContext()).isNull();

        var deploy = new MockHttpServletRequest("POST", "/deployments/platform/v1/app-workers/deploy");
        filter.doFilter(deploy, new MockHttpServletResponse(), (request, deployResponse) ->
                assertThat(MultiTenancyContext.getContext()).isNull());
    }

    private UnifiedMultiTenancyFilter filter(
            DatabaseConfigRepository repository,
            RoutingDataSource routingDataSource) {
        return new UnifiedMultiTenancyFilter(
                repository,
                routingDataSource,
                mock(JwtSecretService.class),
                mock(OAuthStateService.class),
                mock(UserRepository.class));
    }

    private MockHttpServletRequest requestWithProjectRef(String tokenAppCode, String projectRef) {
        var request = new MockHttpServletRequest("POST", "/auth/v1/signup");
        request.setServerName("appabc.ottermind.app");
        request.addHeader("Apikey", jwt(tokenAppCode));
        request.addHeader("x-nubase-project-ref", projectRef);
        return request;
    }

    private DatabaseConfig databaseConfig(String appCode) {
        return DatabaseConfig.builder()
                .appCode(appCode)
                .dbKey(appCode)
                .schemaName("public")
                .jwtSecret(JWT_SECRET)
                .enabled(true)
                .initStatus(DatabaseInitStatus.INITIALIZED.name())
                .dbSchemas(List.of("public"))
                .authenticatedToken(jwt(appCode))
                .build();
    }

    private String jwt(String appCode) {
        return Jwts.builder()
                .claim("ref", appCode)
                .claim("role", Role.AUTHENTICATED.getValue())
                .signWith(Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }
}
