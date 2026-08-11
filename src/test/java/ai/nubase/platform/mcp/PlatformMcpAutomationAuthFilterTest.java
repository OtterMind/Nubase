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

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
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
        when(verifier.verify(sentinel)).thenReturn(new AutomationPrincipal(
                "agentteams.local",
                UUID.randomUUID(),
                "jti",
                1,
                Set.of(PlatformMcpScopes.PROJECT_READ),
                "goai_",
                2,
                null));
        PlatformMcpAutomationAuthFilter filter = new PlatformMcpAutomationAuthFilter(verifier);
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
}
