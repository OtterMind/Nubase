package ai.nubase.common.multitenancy;

import ai.nubase.common.context.MultiTenancyContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantContextLifecycleFilterTest {
    private final TenantContextLifecycleFilter boundary = new TenantContextLifecycleFilter();
    private final UnifiedMultiTenancyFilter tenantFilter = new UnifiedMultiTenancyFilter(null, null, null, null, null);

    @AfterEach
    void clearContext() {
        MultiTenancyContext.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/health", "/api/v1/models/public", "/deployments/platform/v1/app-workers/deploy"})
    void clearsStaleContextOnEntryAndExitForSkippedPaths(String path) throws Exception {
        setTenant("previous-request");
        boundary.doFilter(new MockHttpServletRequest("GET", path), new MockHttpServletResponse(),
                (request, response) -> tenantFilter.doFilter(request, response, (innerRequest, innerResponse) -> {
                    assertThat(MultiTenancyContext.getContext()).isNull();
                    setTenant("downstream-context");
                }));
        assertThat(MultiTenancyContext.getContext()).isNull();
    }

    @Test
    void preservesGatewayContextSetDuringCurrentRequest() throws Exception {
        setTenant("previous-request");
        boundary.doFilter(new MockHttpServletRequest("POST", "/ai/v1/chat/completions"),
                new MockHttpServletResponse(), (request, response) -> {
                    assertThat(MultiTenancyContext.getContext()).isNull();
                    // GatewayApiKeyAuthFilter runs before UnifiedMultiTenancyFilter.
                    setTenant("authenticated-gateway-tenant");
                    tenantFilter.doFilter(request, response, (innerRequest, innerResponse) ->
                            assertThat(MultiTenancyContext.getAppCode()).isEqualTo("authenticated-gateway-tenant"));
                });
        assertThat(MultiTenancyContext.getContext()).isNull();
    }

    @Test
    void clearsContextWhenSkippedRequestThrows() {
        assertThatThrownBy(() -> boundary.doFilter(
                new MockHttpServletRequest("POST", "/deployments/platform/v1/app-workers/deploy"),
                new MockHttpServletResponse(), (request, response) ->
                        tenantFilter.doFilter(request, response, (innerRequest, innerResponse) -> {
                            setTenant("downstream-context");
                            throw new IOException("response failed");
                        })))
                .isInstanceOf(IOException.class).hasMessage("response failed");
        assertThat(MultiTenancyContext.getContext()).isNull();
    }

    private void setTenant(String appCode) {
        MultiTenancyContext.setContext(MultiTenancyContext.ContextData.builder().appCode(appCode).build());
    }
}
