package ai.nubase.auth.service;

import ai.nubase.auth.dto.request.platform.PlatformSignInRequest;
import ai.nubase.auth.dto.request.platform.PlatformSignUpRequest;
import ai.nubase.auth.dto.response.platform.PlatformAuthResponse;
import ai.nubase.auth.dto.response.platform.PlatformUserPayload;
import ai.nubase.auth.exception.EmailAlreadyExistsException;
import ai.nubase.metadata.entity.PlatformUser;
import ai.nubase.metadata.repository.PlatformUserRepository;
import ai.nubase.platform.mail.PlatformEmailService.Purpose;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
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

    /**
     * Reserved owner for projects created via the metadata service-role key (root/automation),
     * which carry no human user id. Seeded by Flyway migration V8 in {@code platform_users};
     * guarantees every project has at least one ownership row so listing can go through
     * {@code platform_user_projects} uniformly.
     */
    public static final UUID SYSTEM_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private final PlatformUserRepository platformUserRepository;
    private final PasswordService passwordService;
    private final PlatformOtpService otpService;
    private final RateLimiterService rateLimiter;
    private final Environment environment;

    @Value("${nubase.platform.jwt-secret:}")
    private String configuredJwtSecret;

    @Value("${nubase.platform.jwt-expiration-seconds:86400}")
    private long expirationSeconds;

    @Value("${nubase.platform.signup-enabled:true}")
    private boolean signupEnabled;

    @Value("${nubase.platform.email-verification-enabled:true}")
    private boolean emailVerificationEnabled;

    public boolean isSignupEnabled() {
        return signupEnabled;
    }

    /**
     * Result of a signup/sign-in attempt: either an issued session ({@code token}) or a request for
     * the caller to complete email-code verification first ({@code pendingEmail}). Exactly one is set.
     */
    public record AuthOutcome(PlatformAuthResponse token, String pendingEmail) {
        public boolean pending() {
            return token == null;
        }

        public static AuthOutcome ok(PlatformAuthResponse token) {
            return new AuthOutcome(token, null);
        }

        public static AuthOutcome pending(String email) {
            return new AuthOutcome(null, email);
        }
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
            boolean dev = Arrays.asList(environment.getActiveProfiles()).contains("dev");
            if (!dev) {
                // A per-process random key invalidates sessions on restart and, worse, makes each
                // replica sign with a different key (tokens only valid on the issuing node). Refuse
                // to boot like this outside dev.
                throw new IllegalStateException(
                        "No platform JWT signing key configured. Set nubase.platform.jwt-secret "
                        + "(env NUBASE_PLATFORM_JWT_SECRET) or PGRST_ENCRYPTION_MASTER_KEY before "
                        + "running outside the 'dev' profile.");
            }
            // Dev fallback only: generate a process-local random key so the server still boots.
            log.warn("No platform JWT secret configured (dev). Generating a random per-process key — "
                    + "sessions will not survive a restart.");
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
    public AuthOutcome signUp(PlatformSignUpRequest request) {
        String email = request.getEmail().trim().toLowerCase();

        // An account that exists but never confirmed its email is an *unfinished* signup, not a real
        // conflict — the email owner hasn't been proven yet. Treat a repeat signup as "continue": refresh
        // the chosen credentials and re-send a fresh code. A verified (or OAuth / verification-off)
        // account is a genuine duplicate and is rejected.
        Optional<PlatformUser> existingUser = platformUserRepository.findByEmailIgnoreCase(email);
        if (existingUser.isPresent()) {
            PlatformUser user = existingUser.get();
            if (!emailVerificationEnabled || user.getEmailVerifiedAt() != null) {
                throw new EmailAlreadyExistsException("Platform account already exists for " + email);
            }
            user.setEncryptedPassword(passwordService.hashPassword(request.getPassword()));
            if (request.getFullName() != null && !request.getFullName().isBlank()) {
                user.setFullName(request.getFullName().trim());
            }
            platformUserRepository.save(user);
            otpService.issue(email, Purpose.SIGNUP);
            return AuthOutcome.pending(email);
        }

        // The very first signup is always allowed so a fresh install can bootstrap its super admin,
        // even when sign-ups are disabled for the public.
        long existing = platformUserRepository.count();
        if (!signupEnabled && existing > 0L) {
            throw new IllegalStateException("Public sign-ups are disabled on this workspace.");
        }

        // First account on a fresh install becomes the bootstrap super admin.
        // Every subsequent signup defaults to 'user' and only sees projects they own.
        String role = existing == 0L ? PLATFORM_ROLE_SUPER_ADMIN : PLATFORM_ROLE_USER;

        PlatformUser user = PlatformUser.builder()
                .email(email)
                .encryptedPassword(passwordService.hashPassword(request.getPassword()))
                .fullName(request.getFullName())
                .role(role)
                .isActive(Boolean.TRUE)
                .emailVerifiedAt(emailVerificationEnabled ? null : Instant.now())
                .build();

        PlatformUser saved = platformUserRepository.save(user);
        if (!emailVerificationEnabled) {
            return AuthOutcome.ok(buildResponse(saved));
        }
        // Require email confirmation before issuing a session.
        otpService.issue(email, Purpose.SIGNUP);
        return AuthOutcome.pending(email);
    }

    @Transactional("metadataTransactionManager")
    public AuthOutcome signIn(PlatformSignInRequest request) {
        String email = request.getEmail().trim();
        String rlKey = "platform_login:" + email.toLowerCase();
        // Throttle online password guessing against Studio accounts (mirrors tenant auth).
        rateLimiter.assertNotLockedOut(rlKey);
        Optional<PlatformUser> maybe = platformUserRepository.findByEmailIgnoreCase(email);
        if (maybe.isEmpty()) {
            rateLimiter.recordFailure(rlKey);
            throw new IllegalArgumentException("Invalid email or password");
        }
        PlatformUser user = maybe.get();
        if (!Boolean.TRUE.equals(user.getIsActive())) {
            throw new IllegalArgumentException("Account is disabled");
        }
        if (!passwordService.verifyPassword(request.getPassword(), user.getEncryptedPassword())) {
            rateLimiter.recordFailure(rlKey);
            throw new IllegalArgumentException("Invalid email or password");
        }
        rateLimiter.recordSuccess(rlKey);
        // Email verification is a one-time gate: an unverified account must confirm a code before its
        // first session. Once verified (or when the feature is off), login is password-only.
        if (emailVerificationEnabled && user.getEmailVerifiedAt() == null) {
            otpService.issue(user.getEmail(), Purpose.LOGIN);
            return AuthOutcome.pending(user.getEmail());
        }
        user.setLastSignedInAt(Instant.now());
        platformUserRepository.save(user);
        return AuthOutcome.ok(buildResponse(user));
    }

    /**
     * Confirm a pending signup/login code and issue a session. The password was already proven in the
     * step that triggered the send, so the code is the second factor.
     */
    @Transactional("metadataTransactionManager")
    public PlatformAuthResponse verifyEmail(String rawEmail, String code) {
        String email = rawEmail == null ? "" : rawEmail.trim().toLowerCase();
        PlatformUser user = platformUserRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new IllegalArgumentException("Invalid or expired code"));
        if (!Boolean.TRUE.equals(user.getIsActive())) {
            throw new IllegalArgumentException("Account is disabled");
        }
        // A pending code may have been issued for signup or for the login gate — accept either.
        otpService.verifyAny(email, code, Purpose.SIGNUP, Purpose.LOGIN);
        if (user.getEmailVerifiedAt() == null) {
            user.setEmailVerifiedAt(Instant.now());
        }
        user.setLastSignedInAt(Instant.now());
        PlatformUser saved = platformUserRepository.save(user);
        return buildResponse(saved);
    }

    /** Re-send a verification code for an unverified account. No-op (silent) for unknown/verified emails. */
    @Transactional("metadataTransactionManager")
    public void resendVerification(String rawEmail) {
        String email = rawEmail == null ? "" : rawEmail.trim().toLowerCase();
        platformUserRepository.findByEmailIgnoreCase(email).ifPresent(user -> {
            if (Boolean.TRUE.equals(user.getIsActive()) && user.getEmailVerifiedAt() == null) {
                otpService.issue(email, Purpose.LOGIN);
            }
        });
    }

    /** Step 1 of password change: verify the current password, then email a confirmation code. */
    @Transactional("metadataTransactionManager")
    public void requestPasswordOtp(UUID userId, String currentPassword) {
        PlatformUser user = requireUser(userId);
        if (!passwordService.verifyPassword(currentPassword, user.getEncryptedPassword())) {
            throw new IllegalArgumentException("Current password is incorrect");
        }
        otpService.issue(user.getEmail(), Purpose.PASSWORD_CHANGE);
    }

    /** Step 2 of password change: re-check the current password + the emailed code, then update. */
    @Transactional("metadataTransactionManager")
    public void changePassword(UUID userId, String currentPassword, String newPassword, String code) {
        PlatformUser user = requireUser(userId);
        if (!passwordService.verifyPassword(currentPassword, user.getEncryptedPassword())) {
            throw new IllegalArgumentException("Current password is incorrect");
        }
        otpService.verify(user.getEmail(), Purpose.PASSWORD_CHANGE, code);
        user.setEncryptedPassword(passwordService.hashPassword(newPassword));
        platformUserRepository.save(user);
    }

    private PlatformUser requireUser(UUID userId) {
        return platformUserRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Platform user not found"));
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
            // OAuth proves email ownership — backfill verification for existing password accounts.
            if (user.getEmailVerifiedAt() == null) {
                user.setEmailVerifiedAt(Instant.now());
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
                    // OAuth identities are email-verified by the provider — exempt from OTP.
                    .emailVerifiedAt(Instant.now())
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

    /**
     * Validate a platform JWT and resolve the caller's identity together with their
     * <em>current</em> role. Unlike {@link #validateAndGetSubject}, this loads the platform
     * user fresh from the database, so a role change (e.g. demotion) takes effect immediately
     * rather than lingering until the (up to 24h) token expires. Used by
     * {@code AdminInitAuthFilter} to stash both the user id and the super-admin flag.
     */
    public PlatformPrincipal resolvePrincipal(String token) {
        UUID userId = validateAndGetSubject(token);
        PlatformUser user = platformUserRepository.findById(userId).orElse(null);
        String role = user != null ? user.getRole() : null;
        boolean superAdmin = PLATFORM_ROLE_SUPER_ADMIN.equalsIgnoreCase(role);
        return new PlatformPrincipal(userId, superAdmin, role);
    }

    /** Resolved platform caller identity for a request (id + current role). */
    public record PlatformPrincipal(UUID userId, boolean superAdmin, String role) {
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
