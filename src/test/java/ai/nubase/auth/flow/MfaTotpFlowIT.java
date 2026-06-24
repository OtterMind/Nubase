package ai.nubase.auth.flow;

import ai.nubase.auth.dto.request.admin.InitDatabaseRequest;
import ai.nubase.auth.dto.response.admin.InitDatabaseResponse;
import ai.nubase.auth.entity.User;
import ai.nubase.auth.repository.UserRepository;
import ai.nubase.auth.service.DatabaseInitService;
import ai.nubase.auth.service.PasswordService;
import ai.nubase.common.context.MultiTenancyContext;
import ai.nubase.common.enums.Role;
import ai.nubase.postgrest.multidb.DatabaseConfig;
import ai.nubase.postgrest.multidb.DatabaseConfigRepository;
import cn.hutool.core.codec.Base32;
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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
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
 * End-to-end verification of the TOTP MFA lifecycle: enroll → challenge → verify → AAL2 upgrade.
 *
 * <p>Provisions a throwaway tenant, signs a user in with password (AAL1), enrolls a TOTP factor,
 * recomputes the current TOTP code from the returned secret, verifies it, and asserts the freshly
 * issued access token is at {@code aal2} with {@code amr=[password, totp]}.
 */
// DB-dependent integration test: requires a local Postgres. Skipped unless RUN_DB_IT=true
// (so CI without a database does not fail). Run locally with: RUN_DB_IT=true mvn test -Dtest=MfaTotpFlowIT
@EnabledIfEnvironmentVariable(named = "RUN_DB_IT", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("TOTP MFA flow: enroll → challenge → verify → aal2")
class MfaTotpFlowIT {

    private static final String PASSWORD = "Password123!";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper om;
    @Autowired private DatabaseInitService databaseInitService;
    @Autowired private DatabaseConfigRepository databaseConfigRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordService passwordService;

    private String dbKey;
    private DatabaseConfig tenant;
    private String apikey;
    private String jwtSecret;
    private String email;
    private UUID userId;

    @BeforeEach
    void setUp() throws Exception {
        dbKey = "mfademo" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);

        InitDatabaseRequest req = new InitDatabaseRequest();
        req.setDbKey(dbKey);
        req.setDbName(dbKey);
        req.setAppCode(dbKey);
        req.setAppName("MFA Demo");
        InitDatabaseResponse resp = databaseInitService.initDatabase(req);
        assertThat(resp.isSuccess()).as("tenant provisioning (msg: %s)", resp.getMessage()).isTrue();

        jwtSecret = resp.getJwtSecret();
        apikey = resp.getAuthenticatedToken();

        mvc.perform(get("/auth/v1/settings").header("apikey", apikey));

        // Seed a confirmed user with a known password so the password grant works.
        tenant = databaseConfigRepository.findByDbKey(dbKey);
        email = "mfa_" + UUID.randomUUID().toString().substring(0, 8) + "@nubase-test.local";
        setTenantContext();
        try {
            Map<String, Object> appMeta = new HashMap<>();
            appMeta.put("provider", "email");
            appMeta.put("providers", List.of("email"));
            User user = userRepository.save(User.builder()
                    .email(email)
                    .encryptedPassword(passwordService.hashPassword(PASSWORD))
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
        try {
            databaseConfigRepository.delete(dbKey);
        } catch (Exception ignored) {
            // best-effort
        }
    }

    @Test
    @DisplayName("enroll TOTP, challenge, verify a computed code, and get an aal2 session")
    void totpLifecycle() throws Exception {
        // 1) Password sign-in → AAL1 access token (carries session_id + amr=password).
        String aal1Token = passwordSignIn();
        Claims aal1Claims = decode(aal1Token);
        assertThat(aal1Claims.get("aal")).isEqualTo("aal1");

        // 2) Enroll a TOTP factor.
        MvcResult enrollRes = mvc.perform(post("/auth/v1/factors")
                        .header("apikey", apikey).header("Authorization", "Bearer " + aal1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"factor_type\":\"totp\",\"friendly_name\":\"Authenticator\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.type").value("totp"))
                .andExpect(jsonPath("$.totp.secret").exists())
                .andExpect(jsonPath("$.totp.uri").exists())
                .andReturn();
        Map<?, ?> enroll = om.readValue(enrollRes.getResponse().getContentAsString(), Map.class);
        String factorId = (String) enroll.get("id");
        String secret = (String) ((Map<?, ?>) enroll.get("totp")).get("secret");

        // 3) Create a challenge.
        MvcResult chalRes = mvc.perform(post("/auth/v1/factors/" + factorId + "/challenge")
                        .header("apikey", apikey).header("Authorization", "Bearer " + aal1Token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andReturn();
        String challengeId = (String) om.readValue(chalRes.getResponse().getContentAsString(), Map.class).get("id");

        // 4) Verify the computed TOTP code → new AAL2 session.
        String code = generateTotp(secret);
        MvcResult verifyRes = mvc.perform(post("/auth/v1/factors/" + factorId + "/verify")
                        .header("apikey", apikey).header("Authorization", "Bearer " + aal1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("challenge_id", challengeId, "code", code))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").exists())
                .andExpect(jsonPath("$.user.id").value(userId.toString()))
                .andReturn();

        String aal2Token = (String) om.readValue(verifyRes.getResponse().getContentAsString(), Map.class).get("access_token");
        Claims aal2 = decode(aal2Token);
        assertThat(aal2.get("aal")).as("upgraded assurance level").isEqualTo("aal2");
        String amr = aal2.get("amr").toString();
        assertThat(amr).contains("password").contains("totp");

        // 5) The factor is now verified.
        mvc.perform(get("/auth/v1/factors")
                        .header("apikey", apikey).header("Authorization", "Bearer " + aal1Token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("verified"));

        // 6) A wrong code is rejected.
        MvcResult chal2 = mvc.perform(post("/auth/v1/factors/" + factorId + "/challenge")
                        .header("apikey", apikey).header("Authorization", "Bearer " + aal1Token))
                .andReturn();
        String challenge2 = (String) om.readValue(chal2.getResponse().getContentAsString(), Map.class).get("id");
        mvc.perform(post("/auth/v1/factors/" + factorId + "/verify")
                        .header("apikey", apikey).header("Authorization", "Bearer " + aal1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("challenge_id", challenge2, "code", "000000"))))
                .andExpect(status().is5xxServerError());

        System.out.println("[MfaTotpFlowIT] OK — factor " + factorId + " verified; aal1->aal2; amr=" + amr);
    }

    // ---------------------------------------------------------------- helpers

    private String passwordSignIn() throws Exception {
        MvcResult res = mvc.perform(post("/auth/v1/token?grant_type=password")
                        .header("apikey", apikey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").exists())
                .andReturn();
        return (String) om.readValue(res.getResponse().getContentAsString(), Map.class).get("access_token");
    }

    private Claims decode(String token) {
        SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        return Jwts.parser().setSigningKey(key).build().parseClaimsJws(token).getBody();
    }

    /** Recompute the current TOTP code exactly as {@code TotpService} does (SHA1, 6 digits, 30s). */
    private static String generateTotp(String base32Secret) throws Exception {
        byte[] key = Base32.decode(base32Secret);
        long counter = System.currentTimeMillis() / 1000L / 30L;
        byte[] data = new byte[8];
        long v = counter;
        for (int i = 7; i >= 0; i--) {
            data[i] = (byte) (v & 0xff);
            v >>= 8;
        }
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(key, "HmacSHA1"));
        byte[] hash = mac.doFinal(data);
        int offset = hash[hash.length - 1] & 0x0f;
        int binary = ((hash[offset] & 0x7f) << 24)
                | ((hash[offset + 1] & 0xff) << 16)
                | ((hash[offset + 2] & 0xff) << 8)
                | (hash[offset + 3] & 0xff);
        return String.format("%06d", binary % 1_000_000);
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
}
