package ai.nubase.platform.mcp;

import ai.nubase.metadata.entity.AutomationGrant;
import ai.nubase.metadata.repository.AutomationGrantRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AutomationJwtVerifierTest {

    private static final String SECRET = "t".repeat(32);
    private static final UUID GRANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String ACTOR = "agentteams.local";

    private AutomationGrantRepository grantRepository;
    private AutomationJwtVerifier verifier;
    private SecretKey signingKey;

    @BeforeEach
    void setUp() throws Exception {
        grantRepository = mock(AutomationGrantRepository.class);
        PlatformMcpProperties properties = new PlatformMcpProperties();
        properties.setJwtSecret(SECRET);
        ObjectMapper objectMapper = new ObjectMapper();
        verifier = new AutomationJwtVerifier(properties, grantRepository, objectMapper);
        verifier.init();
        signingKey = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        when(grantRepository.findById(GRANT_ID)).thenReturn(Optional.of(grant(
                "[\"project:create\",\"project:provision\",\"project:status\"]",
                true,
                "approval-1")));
    }

    @Test
    void acceptsOnlyTheExactBuildProfileAndCurrentGrant() {
        AutomationPrincipal principal = verifier.verify(token(
                "project:create project:provision project:status", 300, "approval-1", true));

        assertThat(principal.actor()).isEqualTo(ACTOR);
        assertThat(principal.grantId()).isEqualTo(GRANT_ID);
        assertThat(principal.allowedRefPrefix()).isEqualTo("goai_");
        assertThat(principal.maxProjects()).isEqualTo(2);
        assertThat(principal.scopes()).isEqualTo(PlatformMcpScopes.ALL);
    }

    @Test
    void rejectsPartialBuildScopesEvenWhenEveryScopeIsKnown() {
        assertThatThrownBy(() -> verifier.verify(token(
                "project:create project:status", 300, "approval-1", true)))
                .isInstanceOf(PlatformMcpAuthenticationException.class);
    }

    @Test
    void rejectsDuplicateScopeEntriesInsteadOfNormalizingThem() {
        assertThatThrownBy(() -> verifier.verify(token(
                "project:status project:status", 300, "approval-1", true)))
                .isInstanceOf(PlatformMcpAuthenticationException.class);
    }

    @Test
    void rejectsTokensWithLessThanSixtySecondsRemaining() {
        assertThatThrownBy(() -> verifier.verify(token(
                "project:status", 30, "approval-1", true)))
                .isInstanceOf(PlatformMcpAuthenticationException.class);
    }

    @Test
    void rejectsMissingMandatoryIssuedAt() {
        assertThatThrownBy(() -> verifier.verify(token(
                "project:status", 300, "approval-1", false)))
                .isInstanceOf(PlatformMcpAuthenticationException.class);
    }

    @Test
    void rejectsApprovalBindingThatDiffersFromTheActiveGrant() {
        assertThatThrownBy(() -> verifier.verify(token(
                "project:status", 300, "different", true)))
                .isInstanceOf(PlatformMcpAuthenticationException.class);
    }

    @Test
    void rejectsARevokedGrant() throws Exception {
        when(grantRepository.findById(GRANT_ID)).thenReturn(Optional.of(grant(
                "[\"project:status\"]", false, "approval-1")));

        assertThatThrownBy(() -> verifier.verify(token(
                "project:status", 300, "approval-1", true)))
                .isInstanceOf(PlatformMcpAuthenticationException.class);
    }

    private AutomationGrant grant(String scopes, boolean active, String approvalBinding) {
        return AutomationGrant.builder()
                .id(GRANT_ID)
                .actor(ACTOR)
                .actorType(AutomationJwtVerifier.ACTOR_TYPE)
                .scopes(scopes)
                .tokenVersion(3L)
                .allowedRefPrefix("goai_")
                .maxProjects(2)
                .approvalBinding(approvalBinding)
                .active(active)
                .validFrom(Instant.now().minusSeconds(60))
                .validUntil(Instant.now().plusSeconds(3600))
                .build();
    }

    private String token(
            String scope, long ttlSeconds, String approvalBinding, boolean includeIssuedAt) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .header()
                    .type("JWT")
                    .keyId(AutomationJwtVerifier.KEY_ID)
                    .and()
                .issuer(AutomationJwtVerifier.ISSUER)
                .audience()
                    .add(AutomationJwtVerifier.AUDIENCE)
                    .and()
                .subject(ACTOR)
                .id("token-jti")
                .notBefore(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .claim("role", AutomationJwtVerifier.ROLE)
                .claim("actor_type", AutomationJwtVerifier.ACTOR_TYPE)
                .claim("scope", scope)
                .claim("grant_id", GRANT_ID.toString())
                .claim("token_version", 3)
                .claim("approval_binding", approvalBinding);
        if (includeIssuedAt) {
            builder.issuedAt(Date.from(now));
        }
        return builder.signWith(signingKey, Jwts.SIG.HS256).compact();
    }
}
