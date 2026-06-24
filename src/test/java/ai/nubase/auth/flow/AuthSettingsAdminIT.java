package ai.nubase.auth.flow;

import ai.nubase.auth.dto.request.admin.InitDatabaseRequest;
import ai.nubase.auth.dto.response.admin.InitDatabaseResponse;
import ai.nubase.postgrest.multidb.DatabaseConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end test for the per-tenant auth-settings admin API and its effect.
 *
 * <p>Provisions a tenant, then as service_role: PUTs an override (disable signup + custom OTP
 * length), verifies it persists and GETs back, that signup is then blocked, and that a
 * non-service_role caller is forbidden from writing settings.
 */
@EnabledIfEnvironmentVariable(named = "RUN_DB_IT", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Per-tenant auth settings admin API")
class AuthSettingsAdminIT {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper om;
    @Autowired private ai.nubase.auth.service.DatabaseInitService databaseInitService;
    @Autowired private DatabaseConfigRepository databaseConfigRepository;

    private String dbKey;
    private String serviceKey;
    private String anonAuthedKey;

    @BeforeEach
    void setUp() {
        dbKey = "asdemo" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        InitDatabaseRequest req = new InitDatabaseRequest();
        req.setDbKey(dbKey);
        req.setDbName(dbKey);
        req.setAppCode(dbKey);
        req.setAppName("AuthSettings Demo");
        InitDatabaseResponse resp = databaseInitService.initDatabase(req);
        assertThat(resp.isSuccess()).as("provision (msg: %s)", resp.getMessage()).isTrue();
        serviceKey = resp.getServiceRoleToken();
        anonAuthedKey = resp.getAuthenticatedToken();
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
    @DisplayName("PUT override persists, GET reflects it, signup is blocked, non-admin is forbidden")
    void perTenantOverride() throws Exception {
        // Non-service_role caller cannot write settings.
        mvc.perform(put("/auth/v1/admin/settings/auth")
                        .header("apikey", anonAuthedKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"disableSignup\":true}"))
                .andExpect(status().isForbidden());

        // service_role: store an override.
        String body = om.writeValueAsString(Map.of(
                "disableSignup", true,
                "otp", Map.of("length", 8, "expiration", 120)));
        mvc.perform(put("/auth/v1/admin/settings/auth")
                        .header("apikey", serviceKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // Persisted to database_configs.auth_config.
        String stored = databaseConfigRepository.findByDbKey(dbKey).getAuthConfigJson();
        assertThat(stored).contains("\"disableSignup\":true").contains("\"length\":8");

        // GET returns the effective config including the override.
        mvc.perform(get("/auth/v1/admin/settings/auth").header("apikey", serviceKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disableSignup").value(true))
                .andExpect(jsonPath("$.otp.length").value(8));

        // Effect: public signup is now blocked for this tenant.
        mvc.perform(post("/auth/v1/signup")
                        .header("apikey", anonAuthedKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"x@nubase-test.local\",\"password\":\"Password123!\"}"))
                .andExpect(status().is5xxServerError());

        // Clearing the override re-enables signup behaviour (override removed).
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/auth/v1/admin/settings/auth").header("apikey", serviceKey))
                .andExpect(status().isOk());
        assertThat(databaseConfigRepository.findByDbKey(dbKey).getAuthConfigJson()).isNull();

        System.out.println("[AuthSettingsAdminIT] OK — per-tenant override persisted, enforced, and cleared for " + dbKey);
    }
}
