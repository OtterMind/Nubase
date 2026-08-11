package ai.nubase.common.multitenancy;

import ai.nubase.ai.gateway.testsupport.LogCapture;
import ai.nubase.auth.repository.UserRepository;
import ai.nubase.auth.service.JwtSecretService;
import ai.nubase.auth.service.OAuthStateService;
import ai.nubase.common.context.MultiTenancyContext;
import ai.nubase.common.enums.DatabaseInitStatus;
import ai.nubase.common.enums.Role;
import ai.nubase.postgrest.multidb.DatabaseConfig;
import ai.nubase.postgrest.multidb.DatabaseConfigRepository;
import ai.nubase.postgrest.multidb.RoutingDataSource;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpRuntimeLoggingSafetyTest {

    private static final String JWT_SECRET = "test-secret-".repeat(4);
    private static final String REQUEST_HEADER_SENTINEL = "request-header-sensitive-sentinel";
    private static final String DIAGNOSTIC_HEADER_SENTINEL = "diagnostic-header-sensitive-sentinel";
    private static final String REQUEST_BODY_SENTINEL = "request-body-sensitive-sentinel";
    private static final String RESPONSE_HEADER_SENTINEL = "response-header-sensitive-sentinel";
    private static final String RESPONSE_BODY_SENTINEL = "response-body-sensitive-sentinel";
    private static final String AUTH_ERROR_SENTINEL = "auth-error-sensitive-sentinel";
    private static final String FILTER_ERROR_SENTINEL = "filter-error-sensitive-sentinel";
    private static final String REQUEST_PATH_SENTINEL = "request-path-sensitive-sentinel";
    private static final String REQUEST_QUERY_SENTINEL = "request-query-sensitive-sentinel";

    @AfterEach
    void tearDown() {
        MultiTenancyContext.clear();
    }

    @Test
    void logsOnlyAllowlistedHeaderPresenceAndBodyMetadata() throws Exception {
        DatabaseConfigRepository repository = mock(DatabaseConfigRepository.class);
        RoutingDataSource routingDataSource = mock(RoutingDataSource.class);
        JwtSecretService jwtSecretService = mock(JwtSecretService.class);
        when(repository.findByAppCode("appabc")).thenReturn(databaseConfig());
        when(jwtSecretService.validateToken(anyString()))
                .thenThrow(new IllegalArgumentException(AUTH_ERROR_SENTINEL));

        MockHttpServletRequest request = mcpRequest();
        request.addHeader("Authorization", "Bearer " + REQUEST_HEADER_SENTINEL);
        request.addHeader("Cookie", REQUEST_HEADER_SENTINEL);
        request.addHeader("X-Custom-Token", REQUEST_HEADER_SENTINEL);
        request.addHeader("X-Request-Id", DIAGNOSTIC_HEADER_SENTINEL);
        request.setContent(("{\"token\":\"" + REQUEST_BODY_SENTINEL + "\"}")
                .getBytes(StandardCharsets.UTF_8));
        request.setContentType("application/json");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicInteger chainStage = new AtomicInteger();

        try (LogCapture applicationLogs = LogCapture.forClass(UnifiedMultiTenancyFilter.class);
             NamedLogCapture mcpLogs = NamedLogCapture.start("McpLogger")) {
            filter(repository, routingDataSource, jwtSecretService)
                    .doFilter(request, response, (wrappedRequest, wrappedResponse) -> {
                        wrappedRequest.getInputStream().readAllBytes();
                        chainStage.set(1);
                        ((HttpServletResponse) wrappedResponse)
                                .setHeader("Set-Cookie", "session=" + RESPONSE_HEADER_SENTINEL + "; HttpOnly");
                        chainStage.set(2);
                        wrappedResponse.getWriter().write(
                                "{\"authorization\":\"" + RESPONSE_BODY_SENTINEL + "\"}");
                        chainStage.set(3);
                    });

            assertThat(chainStage).hasValue(3);
            assertNoSensitiveValues(applicationLogs.formattedMessages());
            assertNoSensitiveValues(mcpLogs.formattedMessages());
            assertThat(applicationLogs.formattedMessages())
                    .anyMatch(message -> message.contains("Processing request")
                            && message.contains("POST /mcp"));
            assertThat(mcpLogs.formattedMessages())
                    .anyMatch(message -> message.contains("MCP Request:")
                            && message.contains("uri=/mcp"))
                    .anyMatch(message -> message.contains("X-Request-Id=[present]"))
                    .anyMatch(message -> message.contains("MCP Request:") && message.contains("bodyBytes="))
                    .anyMatch(message -> message.contains("MCP Response:") && message.contains("bodyBytes="));
            assertThat(response.getContentAsString())
                    .doesNotContain(REQUEST_PATH_SENTINEL)
                    .doesNotContain(REQUEST_QUERY_SENTINEL);
        }
    }

    @Test
    void mcpFailuresLogOnlyFixedErrorType() throws Exception {
        DatabaseConfigRepository repository = mock(DatabaseConfigRepository.class);
        when(repository.findByAppCode("appabc"))
                .thenThrow(new IllegalStateException(FILTER_ERROR_SENTINEL));
        MockHttpServletResponse response = new MockHttpServletResponse();

        try (LogCapture applicationLogs = LogCapture.forClass(UnifiedMultiTenancyFilter.class);
             NamedLogCapture mcpLogs = NamedLogCapture.start("McpLogger")) {
            filter(repository, mock(RoutingDataSource.class), mock(JwtSecretService.class))
                    .doFilter(mcpRequest(), response, (request, ignoredResponse) -> {});

            assertNoSensitiveValues(applicationLogs.formattedMessages());
            assertNoSensitiveValues(mcpLogs.formattedMessages());
            assertThat(applicationLogs.formattedMessages())
                    .anyMatch(message -> message.contains("errorType=IllegalStateException"));
            assertThat(mcpLogs.formattedMessages())
                    .anyMatch(message -> message.contains("errorType=IllegalStateException")
                            && message.contains("uri=/mcp"));
            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
            assertThat(response.getContentAsString())
                    .contains("Unauthorized")
                    .doesNotContain(FILTER_ERROR_SENTINEL)
                    .doesNotContain(REQUEST_PATH_SENTINEL)
                    .doesNotContain(REQUEST_QUERY_SENTINEL);
        }
    }

    @Test
    void matrixParameterMcpFailuresNeverLogOrReturnTheRequestPath() throws Exception {
        DatabaseConfigRepository repository = mock(DatabaseConfigRepository.class);
        when(repository.findByAppCode("appabc"))
                .thenThrow(new IllegalStateException(FILTER_ERROR_SENTINEL));
        MockHttpServletRequest request = mcpRequest("/mcp;token=" + REQUEST_PATH_SENTINEL);
        MockHttpServletResponse response = new MockHttpServletResponse();

        try (LogCapture applicationLogs = LogCapture.forClass(UnifiedMultiTenancyFilter.class);
             NamedLogCapture mcpLogs = NamedLogCapture.start("McpLogger")) {
            filter(repository, mock(RoutingDataSource.class), mock(JwtSecretService.class))
                    .doFilter(request, response, (ignoredRequest, ignoredResponse) -> {});

            assertNoSensitiveValues(applicationLogs.formattedMessages());
            assertNoSensitiveValues(mcpLogs.formattedMessages());
            assertThat(applicationLogs.formattedMessages())
                    .anyMatch(message -> message.contains("Processing request")
                            && message.contains("POST /mcp"))
                    .anyMatch(message -> message.contains("errorType=IllegalStateException"));
            assertThat(mcpLogs.formattedMessages())
                    .anyMatch(message -> message.contains("errorType=IllegalStateException")
                            && message.contains("uri=/mcp"));
            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
            assertThat(response.getContentAsString())
                    .contains("Unauthorized")
                    .doesNotContain(REQUEST_PATH_SENTINEL)
                    .doesNotContain(FILTER_ERROR_SENTINEL);
        }
    }

    private void assertNoSensitiveValues(List<String> messages) {
        assertThat(messages).noneMatch(message -> message.contains(REQUEST_HEADER_SENTINEL)
                || message.contains(DIAGNOSTIC_HEADER_SENTINEL)
                || message.contains(REQUEST_BODY_SENTINEL)
                || message.contains(RESPONSE_HEADER_SENTINEL)
                || message.contains(RESPONSE_BODY_SENTINEL)
                || message.contains(AUTH_ERROR_SENTINEL)
                || message.contains(FILTER_ERROR_SENTINEL)
                || message.contains(REQUEST_PATH_SENTINEL)
                || message.contains(REQUEST_QUERY_SENTINEL));
    }

    private UnifiedMultiTenancyFilter filter(
            DatabaseConfigRepository repository,
            RoutingDataSource routingDataSource,
            JwtSecretService jwtSecretService) {
        return new UnifiedMultiTenancyFilter(
                repository,
                routingDataSource,
                jwtSecretService,
                mock(OAuthStateService.class),
                mock(UserRepository.class));
    }

    private MockHttpServletRequest mcpRequest() {
        return mcpRequest("/mcp/" + REQUEST_PATH_SENTINEL);
    }

    private MockHttpServletRequest mcpRequest(String requestUri) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", requestUri);
        request.setQueryString("trace=" + REQUEST_QUERY_SENTINEL);
        request.setServerName("appabc.example");
        request.addHeader("Apikey", jwt());
        return request;
    }

    private DatabaseConfig databaseConfig() {
        return DatabaseConfig.builder()
                .appCode("appabc")
                .dbKey("appabc")
                .schemaName("public")
                .jwtSecret(JWT_SECRET)
                .enabled(true)
                .initStatus(DatabaseInitStatus.INITIALIZED.name())
                .dbSchemas(List.of("public"))
                .authenticatedToken(jwt())
                .build();
    }

    private String jwt() {
        return Jwts.builder()
                .claim("ref", "appabc")
                .claim("role", Role.AUTHENTICATED.getValue())
                .signWith(Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    private static final class NamedLogCapture implements AutoCloseable {
        private final Logger logger;
        private final Level originalLevel;
        private final ListAppender<ILoggingEvent> appender;

        private NamedLogCapture(String loggerName) {
            logger = (Logger) LoggerFactory.getLogger(loggerName);
            originalLevel = logger.getLevel();
            logger.setLevel(Level.TRACE);
            appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);
        }

        static NamedLogCapture start(String loggerName) {
            return new NamedLogCapture(loggerName);
        }

        List<String> formattedMessages() {
            return appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
        }

        @Override
        public void close() {
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(originalLevel);
        }
    }
}
