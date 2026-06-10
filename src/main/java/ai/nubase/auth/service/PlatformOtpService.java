package ai.nubase.auth.service;

import ai.nubase.auth.util.TokenGenerator;
import ai.nubase.metadata.entity.PlatformOneTimeToken;
import ai.nubase.metadata.repository.PlatformOneTimeTokenRepository;
import ai.nubase.platform.mail.PlatformEmailService;
import ai.nubase.platform.mail.PlatformEmailService.Purpose;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Issues and verifies email one-time codes for platform (Studio) developer accounts.
 *
 * <p>Self-contained platform path that reuses the context-free pieces of the tenant auth stack:
 * {@link TokenGenerator} (numeric code + SHA-256) and {@link RateLimiterService} (keyed by action,
 * tenant resolves to {@code "_"} when there is no request context). Codes are stored only as a
 * SHA-256 hash in {@code platform_one_time_tokens}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PlatformOtpService {

    private final PlatformOneTimeTokenRepository tokenRepository;
    private final TokenGenerator tokenGenerator;
    private final RateLimiterService rateLimiter;
    private final PlatformEmailService emailService;

    @Value("${nubase.platform.otp.length:6}")
    private int codeLength;

    @Value("${nubase.platform.otp.expiration-seconds:600}")
    private long expirationSeconds;

    /** Generate a code, persist its hash (replacing any pending code for this email+purpose), email it. */
    @Transactional("metadataTransactionManager")
    public void issue(String rawEmail, Purpose purpose) {
        String email = normalize(rawEmail);
        String storageKey = storageKey(purpose);
        rateLimiter.checkRate("platform_otp:" + storageKey, email);

        String code = tokenGenerator.generateNumericOTP(codeLength);
        // Atomic upsert on the (email, purpose) unique key: replaces any pending code in one statement,
        // so two concurrent issue() calls (e.g. double-clicked "resend") can't race into a constraint
        // violation, and there's no Hibernate insert-before-delete flush-ordering hazard.
        tokenRepository.upsert(email, storageKey,
                tokenGenerator.sha256(code), Instant.now().plusSeconds(expirationSeconds));

        emailService.sendOtp(email, code, purpose, expirationSeconds);
    }

    /**
     * Verify a code for the given email+purpose. Consumes the code on success.
     *
     * @throws IllegalArgumentException if no pending code, the code is wrong, or it has expired
     */
    @Transactional("metadataTransactionManager")
    public void verify(String rawEmail, Purpose purpose, String code) {
        verifyAny(rawEmail, code, purpose);
    }

    /**
     * Verify a code against any of the given purposes, consuming the matching code on success.
     *
     * <p>Checks each purpose in turn and only throws <em>once</em>, at the end, if none match. This
     * matters because a mid-method throw inside this {@code @Transactional} would mark the caller's
     * shared transaction rollback-only — so we must not "try then catch" across purposes.
     *
     * @throws IllegalArgumentException if no purpose has a valid, unexpired, matching code
     */
    @Transactional("metadataTransactionManager")
    public void verifyAny(String rawEmail, String code, Purpose... purposes) {
        String email = normalize(rawEmail);
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Verification code is required");
        }
        String hash = tokenGenerator.sha256(code.trim());
        for (Purpose purpose : purposes) {
            Optional<PlatformOneTimeToken> maybe =
                    tokenRepository.findByEmailIgnoreCaseAndPurpose(email, storageKey(purpose));
            if (maybe.isEmpty()) {
                continue;
            }
            PlatformOneTimeToken token = maybe.get();
            if (token.getExpiresAt().isBefore(Instant.now())) {
                tokenRepository.delete(token); // prune the expired code, keep looking
                continue;
            }
            if (token.getTokenHash().equals(hash)) {
                tokenRepository.delete(token);
                return;
            }
        }
        throw new IllegalArgumentException("Invalid or expired code");
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    private static String storageKey(Purpose purpose) {
        return switch (purpose) {
            case SIGNUP -> PlatformOneTimeToken.PURPOSE_SIGNUP;
            case LOGIN -> PlatformOneTimeToken.PURPOSE_LOGIN;
            case PASSWORD_CHANGE -> PlatformOneTimeToken.PURPOSE_PASSWORD_CHANGE;
        };
    }
}
