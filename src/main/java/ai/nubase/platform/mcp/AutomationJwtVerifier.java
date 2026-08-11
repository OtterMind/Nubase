package ai.nubase.platform.mcp;

import ai.nubase.metadata.entity.AutomationGrant;
import ai.nubase.metadata.repository.AutomationGrantRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "nubase.platform-mcp", name = "enabled", havingValue = "true")
public class AutomationJwtVerifier {

    public static final String ISSUER = "nubase-platform";
    public static final String AUDIENCE = "nubase-agentteams-provisioning";
    public static final String ACTOR_TYPE = "automation";
    public static final String ROLE = "platform_automation";
    public static final String KEY_ID = "platform-mcp-v1";

    private static final int MIN_SECRET_BYTES = 32;
    private static final int MAX_ACTOR_LENGTH = 128;
    private static final int MAX_JTI_LENGTH = 128;
    private static final int MAX_TOKEN_LENGTH = 8192;
    private static final long CLOCK_SKEW_SECONDS = 30;
    private static final long MAX_TOKEN_TTL_SECONDS = 10 * 60;
    private static final long MIN_REMAINING_TTL_SECONDS = 60;

    private final PlatformMcpProperties properties;
    private final AutomationGrantRepository grantRepository;
    private final ObjectMapper objectMapper;

    private SecretKey signingKey;

    private static final Set<String> ALLOWED_CLAIMS = Set.of(
            "iss", "aud", "sub", "actor_type", "role", "scope", "grant_id",
            "token_version", "jti", "iat", "nbf", "exp", "approval_binding");
    private static final Set<String> ALLOWED_HEADERS = Set.of("alg", "typ", "kid");

    @PostConstruct
    void init() {
        byte[] secret = properties.getJwtSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "nubase.platform-mcp.jwt-secret must contain at least 32 UTF-8 bytes");
        }
        signingKey = Keys.hmacShaKeyFor(secret);
    }

    public AutomationPrincipal verify(String token) {
        try {
            if (token == null || token.length() > MAX_TOKEN_LENGTH) {
                throw new PlatformMcpAuthenticationException();
            }
            rejectDuplicateJsonKeys(token);
            Jws<Claims> parsed = Jwts.parser()
                    .verifyWith(signingKey)
                    .clockSkewSeconds(CLOCK_SKEW_SECONDS)
                    .build()
                    .parseSignedClaims(token);
            if (!"HS256".equals(parsed.getHeader().getAlgorithm())) {
                throw new PlatformMcpAuthenticationException();
            }
            requireExact("JWT", parsed.getHeader().getType());
            requireExact(KEY_ID, parsed.getHeader().getKeyId());
            if (!parsed.getHeader().keySet().equals(ALLOWED_HEADERS)) {
                throw new PlatformMcpAuthenticationException();
            }

            Claims claims = parsed.getPayload();
            if (!ALLOWED_CLAIMS.containsAll(claims.keySet())) {
                throw new PlatformMcpAuthenticationException();
            }
            requireExact(ISSUER, claims.getIssuer());
            Set<String> audiences = claims.getAudience();
            if (audiences == null || !audiences.equals(Set.of(AUDIENCE))) {
                throw new PlatformMcpAuthenticationException();
            }
            requireExact(ACTOR_TYPE, claims.get("actor_type", String.class));
            requireExact(ROLE, claims.get("role", String.class));

            String actor = requireActor(claims.getSubject());
            String jti = requireBounded(claims.getId(), MAX_JTI_LENGTH);
            UUID grantId = UUID.fromString(requireBounded(
                    claims.get("grant_id", String.class), 36));
            Number tokenVersionClaim = claims.get("token_version", Number.class);
            if (tokenVersionClaim == null || tokenVersionClaim.longValue() < 0) {
                throw new PlatformMcpAuthenticationException();
            }
            long tokenVersion = tokenVersionClaim.longValue();
            requireTemporalClaims(claims, Instant.now());

            Set<String> tokenScopes = parseTokenScopes(claims.get("scope", String.class));
            String approvalBinding = claims.get("approval_binding", String.class);
            AutomationGrant grant = grantRepository.findById(grantId)
                    .orElseThrow(PlatformMcpAuthenticationException::new);
            validateGrant(
                    grant, actor, tokenVersion, tokenScopes, approvalBinding, Instant.now());

            return new AutomationPrincipal(
                    actor,
                    grantId,
                    jti,
                    tokenVersion,
                    tokenScopes,
                    grant.getAllowedRefPrefix(),
                    grant.getMaxProjects(),
                    grant.getApprovalBinding());
        } catch (PlatformMcpAuthenticationException e) {
            throw e;
        } catch (Exception e) {
            throw new PlatformMcpAuthenticationException(e);
        }
    }

    private void validateGrant(
            AutomationGrant grant,
            String actor,
            long tokenVersion,
            Set<String> tokenScopes,
            String approvalBinding,
            Instant now) throws Exception {
        if (!Boolean.TRUE.equals(grant.getActive())
                || !ACTOR_TYPE.equals(grant.getActorType())
                || !actor.equals(grant.getActor())
                || grant.getTokenVersion() == null
                || grant.getTokenVersion() != tokenVersion
                || grant.getAllowedRefPrefix() == null
                || grant.getAllowedRefPrefix().isBlank()
                || !grant.getAllowedRefPrefix().endsWith("_")
                || grant.getMaxProjects() == null
                || grant.getMaxProjects() <= 0
                || grant.getValidFrom() != null && now.isBefore(grant.getValidFrom())
                || grant.getValidUntil() != null && !now.isBefore(grant.getValidUntil())) {
            throw new PlatformMcpAuthenticationException();
        }

        Set<String> grantScopes = objectMapper.readValue(
                grant.getScopes(), new TypeReference<LinkedHashSet<String>>() { });
        if (grantScopes.isEmpty()
                || !PlatformMcpScopes.ALL.containsAll(grantScopes)
                || !grantScopes.containsAll(tokenScopes)
                || !isExactScopeProfile(tokenScopes)) {
            throw new PlatformMcpAuthenticationException();
        }
        if (!Objects.equals(normalize(grant.getApprovalBinding()), normalize(approvalBinding))) {
            throw new PlatformMcpAuthenticationException();
        }
    }

    private static void requireTemporalClaims(Claims claims, Instant now) {
        Date expiration = claims.getExpiration();
        Date notBefore = claims.getNotBefore();
        Date issuedAt = claims.getIssuedAt();
        if (expiration == null || notBefore == null || issuedAt == null
                || !expiration.after(notBefore)
                || expiration.toInstant().getEpochSecond() - issuedAt.toInstant().getEpochSecond()
                        > MAX_TOKEN_TTL_SECONDS
                || !expiration.after(issuedAt)
                || expiration.toInstant().isBefore(now.plusSeconds(MIN_REMAINING_TTL_SECONDS))
                || issuedAt.toInstant().isAfter(now.plusSeconds(CLOCK_SKEW_SECONDS))
                || notBefore.toInstant().isBefore(
                        issuedAt.toInstant().minusSeconds(CLOCK_SKEW_SECONDS))) {
            throw new PlatformMcpAuthenticationException();
        }
    }

    private static Set<String> parseTokenScopes(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new PlatformMcpAuthenticationException();
        }
        String[] entries = raw.trim().split("\\s+");
        Set<String> scopes = new LinkedHashSet<>(Arrays.asList(entries));
        if (scopes.size() != entries.length
                || scopes.isEmpty()
                || !PlatformMcpScopes.ALL.containsAll(scopes)) {
            throw new PlatformMcpAuthenticationException();
        }
        return Set.copyOf(scopes);
    }

    private static boolean isExactScopeProfile(Set<String> scopes) {
        return scopes.equals(Set.of(PlatformMcpScopes.PROJECT_READ))
                || scopes.equals(PlatformMcpScopes.ALL);
    }

    private static void requireExact(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new PlatformMcpAuthenticationException();
        }
    }

    private static String requireBounded(String value, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new PlatformMcpAuthenticationException();
        }
        return value;
    }

    private static String requireActor(String value) {
        String actor = requireBounded(value, MAX_ACTOR_LENGTH);
        if (!actor.matches("^[A-Za-z0-9][A-Za-z0-9._:@/-]{0,127}$")) {
            throw new PlatformMcpAuthenticationException();
        }
        return actor;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private void rejectDuplicateJsonKeys(String token) throws Exception {
        String[] segments = token.split("\\.", -1);
        if (segments.length != 3) {
            throw new PlatformMcpAuthenticationException();
        }
        ObjectMapper strictMapper = objectMapper.copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        for (int i = 0; i < 2; i++) {
            byte[] decoded = Base64.getUrlDecoder().decode(segments[i]);
            Map<?, ?> parsed = strictMapper.readValue(decoded, Map.class);
            if (parsed == null) {
                throw new PlatformMcpAuthenticationException();
            }
        }
    }
}
