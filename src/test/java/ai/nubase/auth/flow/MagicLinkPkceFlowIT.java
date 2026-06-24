package ai.nubase.auth.flow;

import ai.nubase.auth.dto.request.admin.InitDatabaseRequest;
import ai.nubase.auth.dto.response.admin.InitDatabaseResponse;
import ai.nubase.auth.entity.User;
import ai.nubase.auth.service.DatabaseInitService;
import ai.nubase.auth.service.EmailService;
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
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end verification of the passwordless magic-link + PKCE flow.
 *
 * <ol>
 *   <li>{@code POST /otp} with a {@code code_challenge} issues a magic link (token captured via a
 *       mocked {@link EmailService}).</li>
 *   <li>{@code GET /verify?type=magiclink&...&redirect_to=...} 303-redirects to
 *       {@code redirect_to?code=<authCode>} instead of returning tokens.</li>
 *   <li>{@code POST /token?grant_type=pkce} exchanges that code (+ verifier) for a session.</li>
 * </ol>
 */
// DB-dependent integration test: requires a local Postgres. Skipped unless RUN_DB_IT=true
// (so CI without a database does not fail). Run locally with: RUN_DB_IT=true mvn test -Dtest=MagicLinkPkceFlowIT
@EnabledIfEnvironmentVariable(named = "RUN_DB_IT", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Magic-link + PKCE: /otp → /verify?code= → grant_type=pkce")
class MagicLinkPkceFlowIT {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper om;
    @Autowired private DatabaseInitService databaseInitService;

    /** Mocked so we can capture the (otherwise emailed-only) magic-link token. */
    @MockBean private EmailService emailService;

    private String dbKey;
    private String apikey;
    private String jwtSecret;
    private String email;

    @BeforeEach
    void setUp() throws Exception {
        dbKey = "mldemo" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        InitDatabaseRequest req = new InitDatabaseRequest();
        req.setDbKey(dbKey);
        req.setDbName(dbKey);
        req.setAppCode(dbKey);
        req.setAppName("MagicLink Demo");
        InitDatabaseResponse resp = databaseInitService.initDatabase(req);
        assertThat(resp.isSuccess()).as("tenant provisioning (msg: %s)", resp.getMessage()).isTrue();

        jwtSecret = resp.getJwtSecret();
        apikey = resp.getAuthenticatedToken();
        email = "ml_" + UUID.randomUUID().toString().substring(0, 8) + "@nubase-test.local";

        mvc.perform(get("/auth/v1/settings").header("apikey", apikey)).andExpect(status().isOk());
    }

    @AfterEach
    void tearDown() {
        try {
            // databaseConfigRepository not needed here; drop handled out-of-band. Best-effort no-op.
        } catch (Exception ignored) {
            // best-effort
        }
    }

    @Test
    @DisplayName("magic link with code_challenge redirects to ?code= which exchanges for a session")
    void magicLinkPkce() throws Exception {
        String verifier = base64Url(randomBytes(32));
        String challenge = base64Url(sha256(verifier));
        // Must pass the open-redirect allow-list; localhost is permitted by default.
        String redirectTo = "http://localhost:3000/auth/callback";

        // 1) Request a passwordless magic link with a PKCE challenge.
        String otpBody = om.writeValueAsString(Map.of(
                "email", email,
                "code_challenge", challenge,
                "code_challenge_method", "s256"));
        mvc.perform(post("/auth/v1/otp")
                        .header("apikey", apikey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(otpBody))
                .andExpect(status().isOk());

        // Capture the magic-link token handed to the (mocked) email sender. The redirect_to that
        // drives the eventual ?code= bounce is the one supplied to /verify below, not /otp.
        ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendMagicLinkEmail(any(User.class), tokenCaptor.capture(), any(), any());
        String linkToken = tokenCaptor.getValue();
        assertThat(linkToken).isNotBlank();

        // 2) Click the magic link (GET /verify) WITH a redirect_to → expect 303 to redirect_to?code=
        MvcResult verifyRes = mvc.perform(get("/auth/v1/verify")
                        .header("apikey", apikey)
                        .param("type", "magiclink")
                        .param("token", linkToken)
                        .param("email", email)
                        .param("redirect_to", redirectTo))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        String location = verifyRes.getResponse().getHeader("Location");
        assertThat(location).as("redirect location").startsWith(redirectTo);
        Map<String, String> q = UriComponentsBuilder.fromUriString(location).build()
                .getQueryParams().toSingleValueMap();
        String authCode = q.get("code");
        assertThat(authCode).as("auth code in redirect").isNotBlank();

        // 3) Exchange the code (+ verifier) for a session.
        MvcResult tokenRes = mvc.perform(post("/auth/v1/token?grant_type=pkce")
                        .header("apikey", apikey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("auth_code", authCode, "code_verifier", verifier))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").exists())
                .andExpect(jsonPath("$.user.email").value(email))
                .andReturn();

        String accessToken = (String) om.readValue(tokenRes.getResponse().getContentAsString(), Map.class).get("access_token");
        SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        Claims claims = Jwts.parser().setSigningKey(key).build().parseClaimsJws(accessToken).getBody();
        assertThat(claims.get("email")).isEqualTo(email);
        assertThat(claims.get("amr").toString()).contains("otp");

        // 4) Single-use: the code cannot be exchanged again.
        mvc.perform(post("/auth/v1/token?grant_type=pkce")
                        .header("apikey", apikey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("auth_code", authCode, "code_verifier", verifier))))
                .andExpect(status().is5xxServerError());

        System.out.println("[MagicLinkPkceFlowIT] OK — magic link redirected to ?code, exchanged for session of "
                + email + "; amr=" + claims.get("amr"));
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        new SecureRandom().nextBytes(b);
        return b;
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String base64Url(String value) {
        return base64Url(value.getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] sha256(String value) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
    }
}
