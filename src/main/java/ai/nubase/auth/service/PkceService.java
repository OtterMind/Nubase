package ai.nubase.auth.service;

import ai.nubase.auth.dto.response.AuthResponse;
import ai.nubase.auth.entity.FlowState;
import ai.nubase.auth.entity.MfaAmrClaim;
import ai.nubase.auth.entity.User;
import ai.nubase.auth.repository.FlowStateRepository;
import ai.nubase.auth.repository.UserRepository;
import ai.nubase.auth.util.TokenGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

/**
 * PKCE (Proof Key for Code Exchange) support. After an OAuth / magic-link / SAML flow the
 * server issues a one-time {@code authCode} bound to the client's {@code codeChallenge};
 * the client later exchanges it (with the matching {@code codeVerifier}) for a session via
 * {@code POST /token?grant_type=pkce}. Mirrors Supabase GoTrue's PKCE flow.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PkceService {

    /** Auth codes are valid for this many seconds before they must be exchanged. */
    private static final long AUTH_CODE_TTL_SECONDS = 300;

    private final FlowStateRepository flowStateRepository;
    private final UserRepository userRepository;
    private final TokenGenerator tokenGenerator;
    private final AuthResponseFactory authResponseFactory;
    private final AuditService auditService;

    /**
     * Issue a one-time auth code for a completed authentication.
     *
     * @return the auth code to hand back to the client (e.g. {@code ?code=...})
     */
    @Transactional
    public String issueAuthCode(User user, String codeChallenge, String codeChallengeMethod,
                                String providerType, String authenticationMethod) {
        String method = normalizeMethod(codeChallengeMethod);
        String authCode = tokenGenerator.generateSecureToken();
        flowStateRepository.save(FlowState.builder()
                .user(user)
                .authCode(authCode)
                .codeChallenge(codeChallenge)
                .codeChallengeMethod(method)
                .providerType(providerType)
                .authenticationMethod(authenticationMethod)
                .build());
        return authCode;
    }

    /**
     * Exchange an auth code + verifier for a session.
     */
    @Transactional
    public AuthResponse exchangeCodeForSession(String authCode, String codeVerifier) {
        if (StringUtils.isBlank(authCode) || StringUtils.isBlank(codeVerifier)) {
            throw new IllegalArgumentException("auth_code and code_verifier are required");
        }
        FlowState flow = flowStateRepository.findByAuthCode(authCode)
                .orElseThrow(() -> new RuntimeException("invalid flow state, no valid flow state found"));

        // Expiry
        Instant expiry = flow.getAuthCodeIssuedAt().plus(AUTH_CODE_TTL_SECONDS, ChronoUnit.SECONDS);
        if (Instant.now().isAfter(expiry)) {
            flowStateRepository.delete(flow);
            throw new RuntimeException("invalid flow state, auth code expired");
        }

        // Verify the PKCE challenge.
        if (!verifyChallenge(flow.getCodeChallenge(), flow.getCodeChallengeMethod(), codeVerifier)) {
            throw new RuntimeException("code challenge does not match previously saved code verifier");
        }

        User user = flow.getUser();
        if (user == null) {
            throw new RuntimeException("invalid flow state, no user associated");
        }
        // Reload to get a managed, current entity.
        user = userRepository.findById(user.getId())
                .orElseThrow(() -> new RuntimeException("user not found"));

        // Single use.
        flowStateRepository.delete(flow);

        String amr = flow.getAuthenticationMethod() != null
                ? flow.getAuthenticationMethod() : MfaAmrClaim.METHOD_OAUTH;
        auditService.record(AuditService.LOGIN, user, java.util.Map.of("flow", "pkce"));
        return authResponseFactory.newSignIn(user, amr);
    }

    /** Validate a code challenge against the supplied verifier. */
    public boolean verifyChallenge(String codeChallenge, String method, String codeVerifier) {
        if (FlowState.METHOD_PLAIN.equalsIgnoreCase(method)) {
            return constantTimeEquals(codeChallenge, codeVerifier);
        }
        // s256: BASE64URL(SHA256(verifier))
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            String computed = Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
            return constantTimeEquals(codeChallenge, computed);
        } catch (Exception e) {
            log.warn("PKCE challenge verification error: {}", e.getMessage());
            return false;
        }
    }

    private String normalizeMethod(String method) {
        if (StringUtils.isBlank(method)) {
            return FlowState.METHOD_S256;
        }
        return FlowState.METHOD_PLAIN.equalsIgnoreCase(method)
                ? FlowState.METHOD_PLAIN : FlowState.METHOD_S256;
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
