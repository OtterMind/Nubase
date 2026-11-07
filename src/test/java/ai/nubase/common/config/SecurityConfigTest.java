package ai.nubase.common.config;

import ai.nubase.agent.controller.AgentMetadataController;
import ai.nubase.ai.gateway.billing.BillingProperties;
import ai.nubase.ai.gateway.billing.BillingService;
import ai.nubase.ai.gateway.repository.ApiKeyRepository;
import ai.nubase.ai.gateway.service.TokenCounterService;
import ai.nubase.auth.repository.UserRepository;
import ai.nubase.auth.service.JwtSecretService;
import ai.nubase.auth.service.OAuthStateService;
import ai.nubase.common.enums.DatabaseInitStatus;
import ai.nubase.common.enums.Role;
import ai.nubase.common.multitenancy.AdminInitAuthFilter;
import ai.nubase.postgrest.multidb.DatabaseConfig;
import ai.nubase.postgrest.multidb.DatabaseConfigRepository;
import ai.nubase.postgrest.multidb.RoutingDataSource;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;

@WebMvcTest(
        controllers = AgentMetadataController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = AdminInitAuthFilter.class))
@Import(SecurityConfig.class)
class SecurityConfigTest {

    private static final String JWT_SECRET = "test-secret-".repeat(4);

    @Autowired
    private MockMvc mvc;

    @MockBean
    private DatabaseConfigRepository databaseConfigRepository;

    @MockBean
    private RoutingDataSource routingDataSource;

    @MockBean
    private ApiKeyRepository apiKeyRepository;

    @MockBean
    private JwtSecretService jwtSecretService;

    @MockBean
    private OAuthStateService oauthStateService;

    @MockBean
    private UserRepository userRepository;

    @MockBean
    private BillingService billingService;

    @MockBean
    private BillingProperties billingProperties;

    @MockBean
    private TokenCounterService tokenCounterService;

    @BeforeEach
    void setUpTenant() {
        when(databaseConfigRepository.findByAppCode("appabc"))
                .thenReturn(databaseConfig());
    }

    @Test
    void allowsAgentMetadataAfterTenantAuthenticationPasses() throws Exception {
        mvc.perform(get("/agent/v1/connect-config")
                        .param("client", "codex")
                        .header("Apikey", jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.client").value("codex"));
    }

    @Test
    void stillRequiresTenantApiKeyForAgentMetadata() throws Exception {
        mvc.perform(get("/agent/v1/connect-config").param("client", "codex"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(containsString("Apikey header is missing")));
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
                .claim("role", Role.SERVICE_ROLE.getValue())
                .signWith(Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

}
