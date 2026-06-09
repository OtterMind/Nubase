package ai.nubase.auth.service;

import ai.nubase.auth.dto.request.platform.PlatformSignInRequest;
import ai.nubase.auth.dto.request.platform.PlatformSignUpRequest;
import ai.nubase.auth.dto.response.platform.PlatformAuthResponse;
import ai.nubase.auth.dto.response.platform.PlatformUserPayload;
import ai.nubase.auth.exception.EmailAlreadyExistsException;
import ai.nubase.metadata.entity.PlatformUser;
import ai.nubase.metadata.repository.PlatformUserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * Authentication service for platform-level developer accounts that log into the Studio.
 * Independent of per-tenant auth — uses the metadata database and a dedicated JWT secret.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PlatformAuthService {

    public static final String TOKEN_TYPE = "Bearer";
    public static final String ISSUER = "nubase-platform";
    public static final String ROLE_CLAIM_VALUE = "platform_user";

    public static final String PLATFORM_ROLE_SUPER_ADMIN = "super_admin";
    public static final String PLATFORM_ROLE_USER = "user";

    private final PlatformUserRepository platformUserRepository;
    private final PasswordService passwordService;

    @Value("${nubase.platform.jwt-secret:}")
    private String configuredJwtSecret;

    @Value("${nubase.platform.jwt-expiration-seconds:86400}")
    private long expirationSeconds;

    @Value("${nubase.platform.signup-enabled:true}")
    private boolean signupEnabled;

    public boolean isSignupEnabled() {
        return signupEnabled;
    }

    /** Fallback used when no secret is configured. Derived from a fixed string + a random run-id. */
    @Value("${pgrst.multidb.encryption.master-key:}")
    private String masterKeyFallback;

    private SecretKey signingKey;

    @PostConstruct
    void init() {
        String raw = configuredJwtSecret;
        if (raw == null || raw.isBlank()) {
            raw = masterKeyFallback;
        }
        byte[] material;
        if (raw == null || raw.isBlank()) {
            // Dev fallback: no secret configured anywhere. Generate a process-local random
            // key so the server still boots. JWTs issued in this run will be invalidated
            // on the next restart — set nubase.platform.jwt-secret or
            // pgrst.multidb.encryption.master-key for stable sessions across restarts.
            log.warn("No platform JWT secret configured. Generating a random one for this "
                    + "process — sign-in sessions will not survive a restart. Set "
                    + "PGRST_ENCRYPTION_MASTER_KEY (or nubase.platform.jwt-secret) for "
                    + "production deployments.");
            material = new byte[32];
            new java.security.SecureRandom().nextBytes(material);
        } else {
            // Pad/extend to be safely usable for HS256 (32 bytes min) by mixing with a fixed namespace prefix.
            material = ("nubase-platform:" + raw).getBytes(StandardCharsets.UTF_8);
            if (material.length < 32) {
                byte[] padded = new byte[32];
                System.arraycopy(material, 0, padded, 0, material.length);
                material = padded;
            }
        }
        this.signingKey = Keys.hmacShaKeyFor(material);
        log.info("PlatformAuthService initialised, token TTL={}s", expirationSeconds);
    }

    @Transactional("metadataTransactionManager")
    public PlatformAuthResponse signUp(PlatformSignUpRequest request) {
        String email = request.getEmail().trim();
        // The very first signup is always allowed so a fresh install can bootstrap its super admin,
        // even when sign-ups are disabled for the public.
        long existing = platformUserRepository.count();
        if (!signupEnabled && existing > 0L) {
            throw new IllegalStateException("Public sign-ups are disabled on this workspace.");
        }
        if (platformUserRepository.existsByEmailIgnoreCase(email)) {
            throw new EmailAlreadyExistsException("Platform account already exists for " + email);
        }

        // First account on a fresh install becomes the bootstrap super admin.
        // Every subsequent signup defaults to 'user' and only sees projects they own.
        String role = existing == 0L ? PLATFORM_ROLE_SUPER_ADMIN : PLATFORM_ROLE_USER;

        PlatformUser user = PlatformUser.builder()
                .email(email.toLowerCase())
                .encryptedPassword(passwordService.hashPassword(request.getPassword()))
                .fullName(request.getFullName())
                .role(role)
                .isActive(Boolean.TRUE)
                .build();

        PlatformUser saved = platformUserRepository.save(user);
        return buildResponse(saved);
    }

    @Transactional("metadataTransactionManager")
    public PlatformAuthResponse signIn(PlatformSignInRequest request) {
        String email = request.getEmail().trim();
        Optional<PlatformUser> maybe = platformUserRepository.findByEmailIgnoreCase(email);
        if (maybe.isEmpty()) {
            throw new IllegalArgumentException("Invalid email or password");
        }
        PlatformUser user = maybe.get();
        if (!Boolean.TRUE.equals(user.getIsActive())) {
            throw new IllegalArgumentException("Account is disabled");
        }
        if (!passwordService.verifyPassword(request.getPassword(), user.getEncryptedPassword())) {
            throw new IllegalArgumentException("Invalid email or password");
        }
        user.setLastSignedInAt(Instant.now());
        platformUserRepository.save(user);
        return buildResponse(user);
    }

    /**
     * Find-or-create a platform user from a verified OAuth/One-Tap identity (email) and issue a
     * platform JWT. OAuth accounts store a random password hash (never used to sign in by password).
     */
    @Transactional("metadataTransactionManager")
    public PlatformAuthResponse oauthSignIn(String rawEmail, String fullName) {
        String email = rawEmail == null ? null : rawEmail.trim().toLowerCase();
        if (email == null || email.isEmpty()) {
            throw new IllegalArgumentException("OAuth identity has no email address");
        }
        Optional<PlatformUser> maybe = platformUserRepository.findByEmailIgnoreCase(email);
        PlatformUser user;
        if (maybe.isPresent()) {
            user = maybe.get();
            if (!Boolean.TRUE.equals(user.getIsActive())) {
                throw new IllegalArgumentException("Account is disabled");
            }
            if ((user.getFullName() == null || user.getFullName().isBlank())
                    && fullName != null && !fullName.isBlank()) {
                user.setFullName(fullName.trim());
            }
        } else {
            long existing = platformUserRepository.count();
            if (!signupEnabled && existing > 0L) {
                throw new IllegalStateException("Public sign-ups are disabled on this workspace.");
            }
            String role = existing == 0L ? PLATFORM_ROLE_SUPER_ADMIN : PLATFORM_ROLE_USER;
            user = PlatformUser.builder()
                    .email(email)
                    .encryptedPassword(passwordService.hashPassword(UUID.randomUUID().toString()))
                    .fullName(fullName == null ? null : fullName.trim())
                    .role(role)
                    .isActive(Boolean.TRUE)
                    .build();
        }
        user.setLastSignedInAt(Instant.now());
        PlatformUser saved = platformUserRepository.save(user);
        return buildResponse(saved);
    }

    public PlatformUserPayload describe(UUID id) {
        PlatformUser user = platformUserRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Platform user not found"));
        return toPayload(user);
    }

    /**
     * Validate a platform JWT and return the subject (platform user id).
     * Throws if the token is malformed, expired, or signed with a different secret.
     */
    public UUID validateAndGetSubject(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        String sub = claims.getSubject();
        if (sub == null) {
            throw new IllegalArgumentException("Platform token missing subject");
        }
        String role = claims.get("role", String.class);
        if (!ROLE_CLAIM_VALUE.equals(role)) {
            throw new IllegalArgumentException("Not a platform token");
        }
        return UUID.fromString(sub);
    }

    private PlatformAuthResponse buildResponse(PlatformUser user) {
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(expirationSeconds);
        String token = Jwts.builder()
                .subject(user.getId().toString())
                .issuer(ISSUER)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .claim("role", ROLE_CLAIM_VALUE)
                .claim("email", user.getEmail())
                .claim("platform_role", user.getRole())
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();

        return PlatformAuthResponse.builder()
                .accessToken(token)
                .tokenType(TOKEN_TYPE)
                .expiresIn(expirationSeconds)
                .user(toPayload(user))
                .build();
    }

    private PlatformUserPayload toPayload(PlatformUser user) {
        return PlatformUserPayload.builder()
                .id(user.getId().toString())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .role(user.getRole())
                .createdAt(user.getCreatedAt())
                .build();
    }
}
