package ai.nubase.auth.flow;

import ai.nubase.auth.dto.request.admin.InitDatabaseRequest;
import ai.nubase.auth.dto.response.admin.InitDatabaseResponse;
import ai.nubase.auth.entity.User;
import ai.nubase.auth.repository.UserRepository;
import ai.nubase.auth.service.DatabaseInitService;
import ai.nubase.auth.service.PkceService;
import ai.nubase.common.context.MultiTenancyContext;
import ai.nubase.common.enums.Role;
import ai.nubase.postgrest.multidb.DatabaseConfig;
import ai.nubase.postgrest.multidb.DatabaseConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end verification of the PKCE auth-code exchange ({@code POST /token?grant_type=pkce}).
 *
 * <p>Provisions a throwaway tenant in-process via {@link DatabaseInitService} — which runs the
 * updated {@code init_auth_schema.sql} (so the new {@code auth.flow_state} / {@code mfa_amr_claims}
 * tables and {@code is_anonymous} column are created) and encrypts the tenant credentials with the
 * running instance's key. It then seeds a user, issues a real PKCE auth code via {@link PkceService},
 * and exchanges it over MockMvc, asserting a valid session JWT comes back (single use, verifier-bound).
 */
// DB-dependent integration test: requires a local Postgres. Skipped unless RUN_DB_IT=true
// (so CI without a database does not fail). Run locally with: RUN_DB_IT=true mvn test -Dtest=PkceFlowIT
@EnabledIfEnvironmentVariable(named = "RUN_DB_IT", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("PKCE exchange flow (provisioned throwaway tenant)")
class PkceFlowIT {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper om;
    @Autowired private DatabaseInitService databaseInitService;
    @Autowired private DatabaseConfigRepository databaseConfigRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PkceService pkceService;

    private String dbKey;
    private DatabaseConfig tenant;
    private String apikey;
    private String jwtSecret;
    private UUID userId;

    @BeforeEach
    void setUp() throws Exception {
        dbKey = "pkcedemo" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);

        InitDatabaseRequest req = new InitDatabaseRequest();
        req.setDbKey(dbKey);
        req.setDbName(dbKey);          // valid identifier: starts lowercase, [a-z0-9_]
        req.setAppCode(dbKey);
        req.setAppName("PKCE Demo");

        InitDatabaseResponse resp = databaseInitService.initDatabase(req);
        assertThat(resp.isSuccess())
                .as("tenant provisioning (message: %s)", resp.getMessage())
                .isTrue();

        jwtSecret = resp.getJwtSecret();
        apikey = resp.getAuthenticatedToken();
        assertThat(apikey).as("authenticated apikey").isNotBlank();

        // Warm up so the tenant routing datasource pool is registered for this request thread.
        mvc.perform(get("/auth/v1/settings").header("apikey", apikey)).andExpect(status().isOk());

        // Seed a user + issue a PKCE auth code under the tenant context.
        tenant = databaseConfigRepository.findByDbKey(dbKey);
        setTenantContext();
        try {
            Map<String, Object> appMeta = new HashMap<>();
            appMeta.put("provider", "email");
            appMeta.put("providers", List.of("email"));
            User user = userRepository.save(User.builder()
                    .email("pkce_" + UUID.randomUUID().toString().substring(0, 8) + "@nubase-test.local")
                    .role(Role.AUTHENTICATED.getValue())
                    .aud(Role.AUTHENTICATED.getValue())
                    .emailConfirmedAt(java.time.Instant.now())
                    .rawAppMetaData(appMeta)
                    .rawUserMetaData(new HashMap<>())
                    .isSuperAdmin(false).isSsoUser(false).isAnonymous(false)
                    .build());
            userId = user.getId();
        } finally {
            MultiTenancyContext.clear();
        }
    }

    @AfterEach
    void tearDown() {
        // Best-effort: remove the throwaway tenant config (physical DB is left for dev inspection).
        try {
            databaseConfigRepository.delete(dbKey);
        } catch (Exception ignored) {
            // best-effort
        }
    }

    @Test
    @DisplayName("exchanges a PKCE auth code for a valid session; single-use; verifier-bound")
    void exchangePkceCode() throws Exception {
        String verifier = base64Url(randomBytes(32));
        String challenge = base64Url(sha256(verifier));

        String authCode = issueAuthCode(challenge);

        // 1) Wrong verifier is rejected.
        mvc.perform(pkceExchange(authCode, base64Url(randomBytes(32))))
                .andExpect(status().is5xxServerError());

        // 2) Correct verifier yields a session.
        MvcResult result = mvc.perform(pkceExchange(authCode, verifier))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").exists())
                .andExpect(jsonPath("$.refresh_token").exists())
                .andExpect(jsonPath("$.token_type").value("bearer"))
                .andExpect(jsonPath("$.user.id").value(userId.toString()))
                .andReturn();

        Map<?, ?> json = om.readValue(result.getResponse().getContentAsString(), Map.class);
        String accessToken = (String) json.get("access_token");
        SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        Claims claims = Jwts.parser().setSigningKey(key).build().parseClaimsJws(accessToken).getBody();
        assertThat(claims.getSubject()).isEqualTo(userId.toString());
        assertThat(claims.get("role")).isEqualTo(Role.AUTHENTICATED.getValue());
        assertThat(claims.get("session_id")).isNotNull();
        assertThat(claims.get("amr").toString()).contains("oauth");

        // 3) Single use: the same code cannot be exchanged again.
        mvc.perform(pkceExchange(authCode, verifier))
                .andExpect(status().is5xxServerError());

        System.out.println("[PkceFlowIT] OK — exchanged PKCE code for session of user " + userId
                + "; access_token sub=" + claims.getSubject() + " amr=" + claims.get("amr"));
    }

    // ---------------------------------------------------------------- helpers

    private String issueAuthCode(String challenge) {
        setTenantContext();
        try {
            User user = userRepository.findById(userId).orElseThrow();
            return pkceService.issueAuthCode(user, challenge, "s256", "oauth", "oauth");
        } finally {
            MultiTenancyContext.clear();
        }
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder pkceExchange(
            String authCode, String verifier) throws Exception {
        Map<String, Object> body = Map.of("auth_code", authCode, "code_verifier", verifier);
        return post("/auth/v1/token?grant_type=pkce")
                .header("apikey", apikey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(body));
    }

    private void setTenantContext() {
        SecretKey key = Keys.hmacShaKeyFor(tenant.getJwtSecret().getBytes(StandardCharsets.UTF_8));
        MultiTenancyContext.setContext(MultiTenancyContext.ContextData.builder()
                .appCode(tenant.getAppCode())
                .schemaName(tenant.getSchemaName())
                .jwtSecret(tenant.getJwtSecret())
                .jwtSecretKey(key)
                .databaseKey(tenant.getDbKey())
                .databaseConfig(tenant)
                .apikey(apikey)
                .serviceRole(false)
                .build());
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        new SecureRandom().nextBytes(b);
        return b;
    }

    private static String base64Url(byte[] bytes) {
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String base64Url(String value) {
        return base64Url(value.getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] sha256(String value) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
    }
}
