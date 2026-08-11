package ai.nubase.platform.mcp;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PlatformMcpAutomationAuthFilterTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void matrixParametersAndTokensNeverEnterLogsAndContextIsCleared() throws Exception {
        String sentinel = "platform-mcp-secret-sentinel";
        AutomationJwtVerifier verifier = mock(AutomationJwtVerifier.class);
        when(verifier.verify(sentinel)).thenReturn(principal());
        PlatformMcpAutomationAuthFilter filter = filter(verifier, List.of());
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/platform/mcp;token=" + sentinel);
        request.addHeader("Authorization", "Bearer " + sentinel);
        MockHttpServletResponse response = new MockHttpServletResponse();

        Logger logger = (Logger) LoggerFactory.getLogger(PlatformMcpAutomationAuthFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            filter.doFilter(request, response, new MockFilterChain());
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        String logs = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (left, right) -> left + "\n" + right);
        assertThat(logs).contains("/platform/mcp").doesNotContain(sentinel);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void exactNormalizedOriginIsAllowed() throws Exception {
        AutomationJwtVerifier verifier = mock(AutomationJwtVerifier.class);
        when(verifier.verify("token")).thenReturn(principal());
        PlatformMcpAutomationAuthFilter filter = filter(
                verifier, List.of("https://MCP.EXAMPLE:443/"));
        MockHttpServletRequest request = request("token");
        request.addHeader("Origin", "https://mcp.example");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
        verify(verifier).verify("token");
    }

    @Test
    void absentOriginIsAllowedForNonBrowserClients() throws Exception {
        AutomationJwtVerifier verifier = mock(AutomationJwtVerifier.class);
        when(verifier.verify("token")).thenReturn(principal());
        PlatformMcpAutomationAuthFilter filter = filter(verifier, List.of());
        MockHttpServletRequest request = request("token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
        verify(verifier).verify("token");
    }

    @Test
    void hostileOriginIsRejectedBeforeAuthenticationWithoutEchoingIt() throws Exception {
        String sentinel = "https://mcp.example.evil/origin-secret-sentinel";
        AutomationJwtVerifier verifier = mock(AutomationJwtVerifier.class);
        PlatformMcpAutomationAuthFilter filter = filter(
                verifier, List.of("https://mcp.example"));
        MockHttpServletRequest request = request("token");
        request.addHeader("Origin", sentinel);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        Logger logger = (Logger) LoggerFactory.getLogger(PlatformMcpAutomationAuthFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            filter.doFilter(request, response, chain);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        String logs = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (left, right) -> left + "\n" + right);
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString())
                .contains("Forbidden")
                .doesNotContain(sentinel);
        assertThat(logs).doesNotContain(sentinel);
        assertThat(chain.getRequest()).isNull();
        verifyNoInteractions(verifier);
    }

    @Test
    void defaultEmptyAllowlistRejectsAnyPresentOrigin() throws Exception {
        AutomationJwtVerifier verifier = mock(AutomationJwtVerifier.class);
        PlatformMcpAutomationAuthFilter filter = filter(verifier, List.of());
        MockHttpServletRequest request = request("token");
        request.addHeader("Origin", "https://mcp.example");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
        verifyNoInteractions(verifier);
    }

    @Test
    void differentSchemeHostPortOrMultipleOriginsAreRejected() throws Exception {
        for (String origin : List.of(
                "http://mcp.example",
                "https://other.example",
                "https://mcp.example:444",
                "null")) {
            AutomationJwtVerifier verifier = mock(AutomationJwtVerifier.class);
            PlatformMcpAutomationAuthFilter filter = filter(
                    verifier, List.of("https://mcp.example"));
            MockHttpServletRequest request = request("token");
            request.addHeader("Origin", origin);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, new MockFilterChain());

            assertThat(response.getStatus()).isEqualTo(403);
            verifyNoInteractions(verifier);
        }

        AutomationJwtVerifier verifier = mock(AutomationJwtVerifier.class);
        PlatformMcpAutomationAuthFilter filter = filter(
                verifier, List.of("https://mcp.example"));
        MockHttpServletRequest request = request("token");
        request.addHeader("Origin", "https://mcp.example");
        request.addHeader("Origin", "https://other.example");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
        verifyNoInteractions(verifier);
    }

    private static PlatformMcpAutomationAuthFilter filter(
            AutomationJwtVerifier verifier, List<String> allowedOrigins) {
        PlatformMcpProperties properties = new PlatformMcpProperties();
        properties.setAllowedOrigins(allowedOrigins);
        return new PlatformMcpAutomationAuthFilter(verifier, properties);
    }

    private static MockHttpServletRequest request(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/platform/mcp");
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }

    private static AutomationPrincipal principal() {
        return new AutomationPrincipal(
                "agentteams.local",
                UUID.randomUUID(),
                "jti",
                1,
                Set.of(PlatformMcpScopes.PROJECT_READ),
                "goai_",
                2,
                null);
    }
}
