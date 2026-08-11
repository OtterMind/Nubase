package ai.nubase.platform.mcp;

import ai.nubase.metadata.entity.AutomationGrant;
import ai.nubase.metadata.repository.AutomationGrantRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "nubase.platform-mcp", name = "enabled", havingValue = "true")
public class AutomationGrantAdminService {

    private static final long MIN_TOKEN_TTL_SECONDS = 120;
    private static final long MAX_TOKEN_TTL_SECONDS = 600;
    private static final long MIN_GRANT_TTL_SECONDS = 600;
    private static final long MAX_GRANT_TTL_SECONDS = 7 * 24 * 60 * 60;
    private static final String ACTOR_PATTERN = "^[A-Za-z0-9][A-Za-z0-9._:@/-]{0,127}$";
    private static final String REF_PREFIX_PATTERN = "^[a-z][a-z0-9_]{0,39}$";

    private final PlatformMcpProperties properties;
    private final AutomationGrantRepository grantRepository;
    private final ObjectMapper objectMapper;

    private SecretKey signingKey;

    @PostConstruct
    void init() {
        byte[] secret = properties.getJwtSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException(
                    "nubase.platform-mcp.jwt-secret must contain at least 32 UTF-8 bytes");
        }
        signingKey = Keys.hmacShaKeyFor(secret);
    }

    @Transactional("metadataTransactionManager")
    public TokenResult createGrant(CreateGrantRequest request) {
        validateCreate(request);
        Instant now = Instant.now();
        Set<String> scopes = scopeProfile(request.scope());
        AutomationGrant grant;
        try {
            grant = grantRepository.saveAndFlush(AutomationGrant.builder()
                    .id(UUID.randomUUID())
                    .actor(request.actor())
                    .actorType(AutomationJwtVerifier.ACTOR_TYPE)
                    .scopes(writeScopes(scopes))
                    .tokenVersion(1L)
                    .allowedRefPrefix(request.allowedRefPrefix())
                    .maxProjects(request.maxProjects())
                    .approvalBinding(normalize(request.approvalBinding()))
                    .active(true)
                    .validFrom(now)
                    .validUntil(now.plusSeconds(request.grantTtlSeconds()))
                    .build());
        } catch (DataIntegrityViolationException e) {
            throw new PlatformMcpOperationException("ACTIVE_GRANT_EXISTS");
        }
        return mint(grant, scopes, request.tokenTtlSeconds(), now);
    }

    @Transactional("metadataTransactionManager")
    public TokenResult mintToken(UUID grantId, MintTokenRequest request) {
        if (request == null) {
            throw new PlatformMcpOperationException("INVALID_ARGUMENTS");
        }
        Set<String> requestedScopes = scopeProfile(request.scope());
        validateTokenTtl(request.tokenTtlSeconds());
        AutomationGrant grant = grantRepository.findLockedById(grantId)
                .orElseThrow(() -> new PlatformMcpOperationException("GRANT_NOT_FOUND"));
        Instant now = Instant.now();
        Set<String> grantScopes = readScopes(grant.getScopes());
        if (!Boolean.TRUE.equals(grant.getActive())
                || grant.getValidFrom() != null && now.isBefore(grant.getValidFrom())
                || grant.getValidUntil() != null
                        && now.plusSeconds(request.tokenTtlSeconds()).isAfter(grant.getValidUntil())
                || !grantScopes.containsAll(requestedScopes)) {
            throw new PlatformMcpOperationException("GRANT_INACTIVE");
        }
        return mint(grant, requestedScopes, request.tokenTtlSeconds(), now);
    }

    @Transactional("metadataTransactionManager")
    public void revoke(UUID grantId) {
        AutomationGrant grant = grantRepository.findLockedById(grantId)
                .orElseThrow(() -> new PlatformMcpOperationException("GRANT_NOT_FOUND"));
        grant.setActive(false);
        grant.setTokenVersion(grant.getTokenVersion() + 1);
        grantRepository.saveAndFlush(grant);
    }

    private TokenResult mint(
            AutomationGrant grant, Set<String> scopes, long ttlSeconds, Instant now) {
        Instant expiresAt = now.plusSeconds(ttlSeconds);
        var builder = Jwts.builder()
                .header()
                    .type("JWT")
                    .keyId(AutomationJwtVerifier.KEY_ID)
                    .and()
                .issuer(AutomationJwtVerifier.ISSUER)
                .audience()
                    .add(AutomationJwtVerifier.AUDIENCE)
                    .and()
                .subject(grant.getActor())
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .notBefore(Date.from(now))
                .expiration(Date.from(expiresAt))
                .claim("role", AutomationJwtVerifier.ROLE)
                .claim("actor_type", AutomationJwtVerifier.ACTOR_TYPE)
                .claim("scope", String.join(" ", scopes))
                .claim("grant_id", grant.getId().toString())
                .claim("token_version", grant.getTokenVersion());
        if (grant.getApprovalBinding() != null) {
            builder.claim("approval_binding", grant.getApprovalBinding());
        }
        String token = builder.signWith(signingKey, Jwts.SIG.HS256).compact();
        return new TokenResult(
                grant.getId(),
                grant.getActor(),
                grant.getAllowedRefPrefix(),
                grant.getMaxProjects(),
                Set.copyOf(scopes),
                expiresAt,
                token);
    }

    private void validateCreate(CreateGrantRequest request) {
        if (request == null
                || request.actor() == null
                || !request.actor().matches(ACTOR_PATTERN)
                || request.allowedRefPrefix() == null
                || !request.allowedRefPrefix().matches(REF_PREFIX_PATTERN)
                || !request.allowedRefPrefix().endsWith("_")
                || request.maxProjects() < 1
                || request.maxProjects() > 100
                || request.approvalBinding() != null
                        && !request.approvalBinding().matches("^[A-Za-z0-9._:-]{1,128}$")
                || request.grantTtlSeconds() < MIN_GRANT_TTL_SECONDS
                || request.grantTtlSeconds() > MAX_GRANT_TTL_SECONDS) {
            throw new PlatformMcpOperationException("INVALID_ARGUMENTS");
        }
        scopeProfile(request.scope());
        validateTokenTtl(request.tokenTtlSeconds());
    }

    private static void validateTokenTtl(long ttlSeconds) {
        if (ttlSeconds < MIN_TOKEN_TTL_SECONDS || ttlSeconds > MAX_TOKEN_TTL_SECONDS) {
            throw new PlatformMcpOperationException("INVALID_TOKEN_TTL");
        }
    }

    private static Set<String> scopeProfile(String scope) {
        if ("build".equals(scope)) {
            return PlatformMcpScopes.ALL;
        }
        if ("read".equals(scope)) {
            return Set.of(PlatformMcpScopes.PROJECT_READ);
        }
        throw new PlatformMcpOperationException("INVALID_SCOPE_PROFILE");
    }

    private String writeScopes(Set<String> scopes) {
        try {
            return objectMapper.writeValueAsString(new LinkedHashSet<>(scopes));
        } catch (Exception e) {
            throw new PlatformMcpOperationException("GRANT_WRITE_FAILED");
        }
    }

    private Set<String> readScopes(String value) {
        try {
            return objectMapper.readValue(
                    value,
                    objectMapper.getTypeFactory().constructCollectionType(
                            LinkedHashSet.class, String.class));
        } catch (Exception e) {
            throw new PlatformMcpOperationException("GRANT_INVALID");
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record CreateGrantRequest(
            String actor,
            String scope,
            String allowedRefPrefix,
            int maxProjects,
            String approvalBinding,
            long grantTtlSeconds,
            long tokenTtlSeconds) {
    }

    public record MintTokenRequest(String scope, long tokenTtlSeconds) {
    }

    public record TokenResult(
            UUID grantId,
            String actor,
            String allowedRefPrefix,
            int maxProjects,
            Set<String> scopes,
            Instant expiresAt,
            String token) {
    }
}
