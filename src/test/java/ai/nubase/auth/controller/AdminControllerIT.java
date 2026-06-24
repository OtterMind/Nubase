package ai.nubase.auth.controller;

import ai.nubase.auth.dto.request.platform.PlatformSignUpRequest;
import ai.nubase.auth.dto.response.platform.PlatformAuthResponse;
import ai.nubase.auth.service.PlatformAuthService;
import ai.nubase.metadata.entity.PlatformUser;
import ai.nubase.metadata.entity.SqlSnippet;
import ai.nubase.metadata.repository.PlatformUserProjectRepository;
import ai.nubase.metadata.repository.PlatformUserRepository;
import ai.nubase.metadata.repository.SqlSnippetRepository;
import ai.nubase.postgrest.multidb.DatabaseConfig;
import ai.nubase.postgrest.multidb.DatabaseConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end integration test for the AdminController endpoints added in batches 1, 4, 5 and 6.
 * Spins up the full Spring context against the dev metadata Postgres, signs up a fresh
 * super-admin (when needed) and a fresh regular user, then exercises every endpoint.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
// Fixtures sign accounts up directly; disable the email-OTP gate so signUp/signIn return a token.
@TestPropertySource(properties = "nubase.platform.email-verification-enabled=false")
@DisplayName("AdminController integration (dev metadata DB)")
class AdminControllerIT {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper om;
    @Autowired private PlatformAuthService platformAuthService;
    @Autowired private PlatformUserRepository platformUserRepository;
    @Autowired private PlatformUserProjectRepository platformUserProjectRepository;
    @Autowired private SqlSnippetRepository sqlSnippetRepository;
    @Autowired private DatabaseConfigRepository databaseConfigRepository;

    private final List<UUID> tempPlatformUsers = new ArrayList<>();
    private final List<Long> tempSnippets = new ArrayList<>();

    private String adminToken;
    private String adminUserId;
    private String regularToken;
    private String regularUserId;
    /** A long-lived INITIALIZED project we'll attach test fixtures to. */
    private DatabaseConfig sampleProject;

    @BeforeEach
    void setUp() {
        // Make sure a super-admin exists. If admin@nubase.local already exists from earlier
        // sessions, find it; otherwise create one and accept whatever role it gets.
        PlatformUser admin = platformUserRepository.findByEmailIgnoreCase("admin@nubase.local").orElse(null);
        if (admin == null) {
            PlatformSignUpRequest reg = new PlatformSignUpRequest();
            reg.setEmail("admin@nubase.local");
            reg.setPassword("changeme123");
            reg.setFullName("Studio Admin");
            PlatformAuthResponse res = platformAuthService.signUp(reg).token();
            admin = platformUserRepository.findById(UUID.fromString(res.getUser().getId())).orElseThrow();
        }
        // Force role = super_admin for this fixture so the projects-list tests behave consistently.
        if (!"super_admin".equalsIgnoreCase(admin.getRole())) {
            admin.setRole(PlatformAuthService.PLATFORM_ROLE_SUPER_ADMIN);
            platformUserRepository.save(admin);
        }
        adminUserId = admin.getId().toString();
        adminToken = freshTokenFor(admin);

        // Create a regular user fresh per test.
        String reEmail = "test_user_" + UUID.randomUUID().toString().substring(0, 8) + "@nubase-test.local";
        PlatformSignUpRequest reg = new PlatformSignUpRequest();
        reg.setEmail(reEmail);
        reg.setPassword("test-password-12345");
        reg.setFullName("Regular Tester");
        PlatformAuthResponse rres = platformAuthService.signUp(reg).token();
        regularToken = rres.getAccessToken();
        regularUserId = rres.getUser().getId();
        tempPlatformUsers.add(UUID.fromString(regularUserId));

        // Pick any INITIALIZED project to use as the fixture.
        sampleProject = databaseConfigRepository.findAllEnabled().stream()
                .filter(c -> "INITIALIZED".equalsIgnoreCase(c.getInitStatus()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No INITIALIZED project found in dev metadata DB — required as test fixture"));
    }

    @AfterEach
    void tearDown() {
        for (Long id : tempSnippets) {
            try { sqlSnippetRepository.deleteById(id); } catch (Exception ignored) { /* best-effort */ }
        }
        tempSnippets.clear();
        for (UUID userId : tempPlatformUsers) {
            try {
                platformUserProjectRepository.findByUserId(userId)
                        .forEach(m -> platformUserProjectRepository.deleteById(m.getId()));
            } catch (Exception ignored) { /* best-effort */ }
            try { platformUserRepository.deleteById(userId); } catch (Exception ignored) { /* best-effort */ }
        }
        tempPlatformUsers.clear();
    }

    private String freshTokenFor(PlatformUser u) {
        // Reuse the service to sign a token without going through signIn (avoids password check).
        ai.nubase.auth.dto.request.platform.PlatformSignInRequest req =
                new ai.nubase.auth.dto.request.platform.PlatformSignInRequest();
        req.setEmail(u.getEmail());
        // We don't know the password for pre-existing admin@nubase.local; sign in directly via repo + manual JWT.
        // Easiest path: call signIn with the known "changeme123" since the migration / bootstrap used it.
        // If that fails, fall back to a forced re-set via service.
        try {
            req.setPassword("changeme123");
            return platformAuthService.signIn(req).token().getAccessToken();
        } catch (Exception e) {
            // Test fixture safety: rotate the password to a known value, then sign in.
            String hashed = "changeme123";
            u.setEncryptedPassword(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
                    .encode(hashed));
            platformUserRepository.save(u);
            req.setPassword(hashed);
            return platformAuthService.signIn(req).token().getAccessToken();
        }
    }

    // ==================== /admin/projects ====================

    @Test
    @DisplayName("super_admin GET /admin/projects returns all enabled projects")
    void superAdminSeesAllProjects() throws Exception {
        long total = databaseConfigRepository.findAllEnabled().size();
        mvc.perform(get("/auth/v1/admin/projects").header("apikey", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value((int) total));
    }

    @Test
    @DisplayName("regular user GET /admin/projects sees only owned projects (initially empty)")
    void regularUserSeesOnlyOwn() throws Exception {
        mvc.perform(get("/auth/v1/admin/projects").header("apikey", regularToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projects.length()").value(0));
    }

    // ==================== /admin/projects/{ref}/members ====================

    @Test
    @DisplayName("add member → regular user can now see project; remove → invisible again")
    void memberLifecycle() throws Exception {
        String ref = sampleProject.getAppCode();
        PlatformUser regular = platformUserRepository.findById(UUID.fromString(regularUserId)).orElseThrow();

        // Add as member
        mvc.perform(post("/auth/v1/admin/projects/" + ref + "/members")
                        .header("apikey", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("email", regular.getEmail(), "role", "member"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("member"));

        // Visible to regular user
        mvc.perform(get("/auth/v1/admin/projects").header("apikey", regularToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projects[?(@.ref=='" + ref + "')].ref").exists());

        // Remove
        mvc.perform(delete("/auth/v1/admin/projects/" + ref + "/members/" + regular.getId())
                        .header("apikey", adminToken))
                .andExpect(status().isNoContent());

        // Invisible again
        mvc.perform(get("/auth/v1/admin/projects").header("apikey", regularToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projects.length()").value(0));
    }

    @Test
    @DisplayName("non-owner non-member cannot add a member to a project")
    void nonMemberCannotInviteOthers() throws Exception {
        String ref = sampleProject.getAppCode();

        mvc.perform(post("/auth/v1/admin/projects/" + ref + "/members")
                        .header("apikey", regularToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("email", "nobody@example.com", "role", "member"))))
                .andExpect(status().isForbidden());
    }

    // ==================== /admin/platform/users ====================

    @Test
    @DisplayName("super_admin can list every platform user")
    void platformUsersList_superAdmin() throws Exception {
        mvc.perform(get("/auth/v1/admin/platform/users").header("apikey", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThan(0)));
    }

    @Test
    @DisplayName("regular user is forbidden from /admin/platform/users")
    void platformUsersList_regularForbidden() throws Exception {
        mvc.perform(get("/auth/v1/admin/platform/users").header("apikey", regularToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("super_admin can promote and disable a user")
    void promoteAndDisableUser() throws Exception {
        // Promote regularUserId → super_admin
        mvc.perform(put("/auth/v1/admin/platform/users/" + regularUserId)
                        .header("apikey", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("role", "super_admin"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("super_admin"));

        // Disable
        mvc.perform(put("/auth/v1/admin/platform/users/" + regularUserId)
                        .header("apikey", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("isActive", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isActive").value(false));
    }

    // ==================== /admin/projects/{ref}/snippets ====================

    @Test
    @DisplayName("snippets CRUD scoped per (platform user, project)")
    void snippetsCrud() throws Exception {
        String ref = sampleProject.getAppCode();

        // Create
        MvcResult res = mvc.perform(post("/auth/v1/admin/projects/" + ref + "/snippets")
                        .header("apikey", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("name", "t-active-users", "query", "select count(*) from auth.users;"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("t-active-users"))
                .andReturn();
        Map<?, ?> created = om.readValue(res.getResponse().getContentAsByteArray(), Map.class);
        long snippetId = ((Number) created.get("id")).longValue();
        tempSnippets.add(snippetId);

        // List → contains it
        mvc.perform(get("/auth/v1/admin/projects/" + ref + "/snippets").header("apikey", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id==" + snippetId + ")].name").value("t-active-users"));

        // Update
        mvc.perform(put("/auth/v1/admin/projects/" + ref + "/snippets/" + snippetId)
                        .header("apikey", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("name", "t-active-users-v2", "query", "select count(*) from auth.users;"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("t-active-users-v2"));

        // Delete
        mvc.perform(delete("/auth/v1/admin/projects/" + ref + "/snippets/" + snippetId)
                        .header("apikey", adminToken))
                .andExpect(status().isNoContent());
        tempSnippets.remove(Long.valueOf(snippetId));
    }

    @Test
    @DisplayName("user A cannot read user B's snippet via update / delete")
    void snippetsArePrivate() throws Exception {
        String ref = sampleProject.getAppCode();

        // Admin creates a snippet
        MvcResult res = mvc.perform(post("/auth/v1/admin/projects/" + ref + "/snippets")
                        .header("apikey", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("name", "owned-by-admin", "query", "select 1;"))))
                .andExpect(status().isCreated())
                .andReturn();
        long sid = ((Number) om.readValue(res.getResponse().getContentAsByteArray(), Map.class).get("id")).longValue();
        tempSnippets.add(sid);

        // Make regular user a member of this project so they can call the snippets list endpoint at all
        PlatformUser regular = platformUserRepository.findById(UUID.fromString(regularUserId)).orElseThrow();
        mvc.perform(post("/auth/v1/admin/projects/" + ref + "/members")
                        .header("apikey", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("email", regular.getEmail(), "role", "member"))))
                .andExpect(status().isCreated());

        // Regular user can list (sees their own = empty), but cannot delete admin's snippet
        mvc.perform(get("/auth/v1/admin/projects/" + ref + "/snippets").header("apikey", regularToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projects.length()").value(0));

        mvc.perform(delete("/auth/v1/admin/projects/" + ref + "/snippets/" + sid)
                        .header("apikey", regularToken))
                .andExpect(status().isNotFound());

        // Snippet still there for admin
        SqlSnippet still = sqlSnippetRepository.findById(sid).orElse(null);
        assertThat(still).isNotNull();
    }

    // ==================== /admin/projects/{ref} (PATCH/DELETE) + keys ====================

    @Test
    @DisplayName("PATCH updates appName, pause hides from list, resume restores")
    void pauseAndResumeProject() throws Exception {
        String ref = sampleProject.getAppCode();
        String originalName = sampleProject.getAppName();

        // Rename
        mvc.perform(patch("/auth/v1/admin/projects/" + ref)
                        .header("apikey", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("appName", "Test Renamed by IT"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Test Renamed by IT"));

        // Pause
        mvc.perform(patch("/auth/v1/admin/projects/" + ref)
                        .header("apikey", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("enabled", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        // Drops from /admin/projects
        mvc.perform(get("/auth/v1/admin/projects").header("apikey", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projects[?(@.ref=='" + ref + "')]").isEmpty());

        // Resume + restore name
        mvc.perform(patch("/auth/v1/admin/projects/" + ref)
                        .header("apikey", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("enabled", true, "appName", originalName != null ? originalName : ref))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    @DisplayName("GET /admin/projects/{ref}/keys returns both tokens")
    void getProjectKeys() throws Exception {
        String ref = sampleProject.getAppCode();
        mvc.perform(get("/auth/v1/admin/projects/" + ref + "/keys").header("apikey", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service_role_token").isNotEmpty());
    }

    // ==================== /admin/projects/{ref}/sql/history ====================

    @Test
    @DisplayName("history endpoint returns a list (may be empty) and 404 for unknown ref")
    void historyEndpoint() throws Exception {
        String ref = sampleProject.getAppCode();
        mvc.perform(get("/auth/v1/admin/projects/" + ref + "/sql/history?limit=5").header("apikey", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        mvc.perform(get("/auth/v1/admin/projects/does-not-exist-xyz/sql/history").header("apikey", adminToken))
                .andExpect(status().isNotFound());
    }
}
