package ai.nubase.auth.service;

import ai.nubase.auth.dto.response.AuthResponse;
import ai.nubase.auth.entity.FlowState;
import ai.nubase.auth.entity.User;
import ai.nubase.auth.repository.FlowStateRepository;
import ai.nubase.auth.repository.UserRepository;
import ai.nubase.auth.util.TokenGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for {@link PkceService}: challenge verification + auth-code exchange edge cases.
 * Dependencies are mocked — no Spring / DB.
 */
@DisplayName("PkceService")
class PkceServiceTest {

    private FlowStateRepository flowStateRepository;
    private UserRepository userRepository;
    private AuthResponseFactory authResponseFactory;
    private AuditService auditService;
    private PkceService pkce;

    @BeforeEach
    void setUp() {
        flowStateRepository = mock(FlowStateRepository.class);
        userRepository = mock(UserRepository.class);
        authResponseFactory = mock(AuthResponseFactory.class);
        auditService = mock(AuditService.class);
        pkce = new PkceService(flowStateRepository, userRepository,
                new TokenGenerator(), authResponseFactory, auditService);
    }

    // ---------------------------------------------------------------- verifyChallenge

    @Test
    @DisplayName("verifyChallenge: S256 matches BASE64URL(SHA256(verifier))")
    void s256Match() throws Exception {
        String verifier = "the-quick-brown-fox-code-verifier-1234567890";
        String challenge = s256(verifier);
        assertThat(pkce.verifyChallenge(challenge, "s256", verifier)).isTrue();
        assertThat(pkce.verifyChallenge(challenge, "S256", verifier)).isTrue();
        assertThat(pkce.verifyChallenge(challenge, "s256", "wrong-verifier")).isFalse();
    }

    @Test
    @DisplayName("verifyChallenge: plain compares verbatim")
    void plainMatch() {
        assertThat(pkce.verifyChallenge("abc123", "plain", "abc123")).isTrue();
        assertThat(pkce.verifyChallenge("abc123", "plain", "abc124")).isFalse();
    }

    @Test
    @DisplayName("issueAuthCode: only an exact plain method is stored as plain")
    void plainMethodRequiresExactMatch() {
        pkce.issueAuthCode(user(), "challenge", "plain-text", "oauth", "oauth");

        ArgumentCaptor<FlowState> flow = ArgumentCaptor.forClass(FlowState.class);
        verify(flowStateRepository).save(flow.capture());
        assertThat(flow.getValue().getCodeChallengeMethod()).isEqualTo(FlowState.METHOD_S256);
    }

    // ---------------------------------------------------------------- exchangeCodeForSession

    @Test
    @DisplayName("exchange: blank args are rejected")
    void blankArgs() {
        assertThatThrownBy(() -> pkce.exchangeCodeForSession(null, "v"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pkce.exchangeCodeForSession("code", " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("exchange: unknown auth code is rejected")
    void unknownCode() {
        when(flowStateRepository.findByAuthCode("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> pkce.exchangeCodeForSession("nope", "verifier"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("invalid flow state");
    }

    @Test
    @DisplayName("exchange: expired auth code is deleted and rejected")
    void expiredCode() {
        FlowState flow = flow("code", s256Silently("verifier"), "s256",
                Instant.now().minus(10, ChronoUnit.MINUTES), user());
        when(flowStateRepository.findByAuthCode("code")).thenReturn(Optional.of(flow));

        assertThatThrownBy(() -> pkce.exchangeCodeForSession("code", "verifier"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("expired");
        verify(flowStateRepository).delete(flow);
        verify(authResponseFactory, never()).newSignIn(any(), any());
    }

    @Test
    @DisplayName("exchange: wrong verifier is rejected (code not consumed)")
    void wrongVerifier() {
        FlowState flow = flow("code", s256Silently("right-verifier"), "s256", Instant.now(), user());
        when(flowStateRepository.findByAuthCode("code")).thenReturn(Optional.of(flow));

        assertThatThrownBy(() -> pkce.exchangeCodeForSession("code", "wrong-verifier"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("code challenge");
        verify(authResponseFactory, never()).newSignIn(any(), any());
    }

    @Test
    @DisplayName("exchange: valid code+verifier issues a session and consumes the code")
    void validExchange() {
        User u = user();
        FlowState flow = flow("code", s256Silently("verifier"), "s256", Instant.now(), u);
        flow.setAuthenticationMethod("oauth");
        when(flowStateRepository.findByAuthCode("code")).thenReturn(Optional.of(flow));
        when(userRepository.findById(u.getId())).thenReturn(Optional.of(u));
        AuthResponse sentinel = AuthResponse.success("acc", "ref", 3600, null);
        when(authResponseFactory.newSignIn(eq(u), eq("oauth"))).thenReturn(sentinel);

        AuthResponse result = pkce.exchangeCodeForSession("code", "verifier");

        assertThat(result).isSameAs(sentinel);
        verify(flowStateRepository).delete(flow);          // single use
        verify(authResponseFactory).newSignIn(u, "oauth");
    }

    // ---------------------------------------------------------------- helpers

    private static User user() {
        return User.builder().id(UUID.randomUUID()).email("u@test.local").build();
    }

    private static FlowState flow(String code, String challenge, String method, Instant issuedAt, User user) {
        return FlowState.builder()
                .id(UUID.randomUUID())
                .authCode(code)
                .codeChallenge(challenge)
                .codeChallengeMethod(method)
                .authenticationMethod("oauth")
                .authCodeIssuedAt(issuedAt)
                .user(user)
                .build();
    }

    private static String s256(String v) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }

    private static String s256Silently(String v) {
        try {
            return s256(v);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
package ai.nubase.auth.service;

import ai.nubase.auth.dto.response.AuthResponse;
import ai.nubase.auth.entity.FlowState;
import ai.nubase.auth.entity.User;
import ai.nubase.auth.repository.FlowStateRepository;
import ai.nubase.auth.repository.UserRepository;
import ai.nubase.auth.util.TokenGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for {@link PkceService}: challenge verification + auth-code exchange edge cases.
 * Dependencies are mocked — no Spring / DB.
 */
@DisplayName("PkceService")
class PkceServiceTest {

    private FlowStateRepository flowStateRepository;
    private UserRepository userRepository;
    private AuthResponseFactory authResponseFactory;
    private AuditService auditService;
    private PkceService pkce;

    @BeforeEach
    void setUp() {
        flowStateRepository = mock(FlowStateRepository.class);
        userRepository = mock(UserRepository.class);
        authResponseFactory = mock(AuthResponseFactory.class);
        auditService = mock(AuditService.class);
        pkce = new PkceService(flowStateRepository, userRepository,
                new TokenGenerator(), authResponseFactory, auditService);
    }

    // ---------------------------------------------------------------- verifyChallenge

    @Test
    @DisplayName("verifyChallenge: S256 matches BASE64URL(SHA256(verifier))")
    void s256Match() throws Exception {
        String verifier = "the-quick-brown-fox-code-verifier-1234567890";
        String challenge = s256(verifier);
        assertThat(pkce.verifyChallenge(challenge, "s256", verifier)).isTrue();
        assertThat(pkce.verifyChallenge(challenge, "S256", verifier)).isTrue();
        assertThat(pkce.verifyChallenge(challenge, "s256", "wrong-verifier")).isFalse();
    }

    @Test
    @DisplayName("verifyChallenge: plain compares verbatim")
    void plainMatch() {
        assertThat(pkce.verifyChallenge("abc123", "plain", "abc123")).isTrue();
        assertThat(pkce.verifyChallenge("abc123", "plain", "abc124")).isFalse();
    }

    // ---------------------------------------------------------------- exchangeCodeForSession

    @Test
    @DisplayName("exchange: blank args are rejected")
    void blankArgs() {
        assertThatThrownBy(() -> pkce.exchangeCodeForSession(null, "v"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pkce.exchangeCodeForSession("code", " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("exchange: unknown auth code is rejected")
    void unknownCode() {
        when(flowStateRepository.findByAuthCode("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> pkce.exchangeCodeForSession("nope", "verifier"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("invalid flow state");
    }

    @Test
    @DisplayName("exchange: expired auth code is deleted and rejected")
    void expiredCode() {
        FlowState flow = flow("code", s256Silently("verifier"), "s256",
                Instant.now().minus(10, ChronoUnit.MINUTES), user());
        when(flowStateRepository.findByAuthCode("code")).thenReturn(Optional.of(flow));

        assertThatThrownBy(() -> pkce.exchangeCodeForSession("code", "verifier"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("expired");
        verify(flowStateRepository).delete(flow);
        verify(authResponseFactory, never()).newSignIn(any(), any());
    }

    @Test
    @DisplayName("exchange: wrong verifier is rejected (code not consumed)")
    void wrongVerifier() {
        FlowState flow = flow("code", s256Silently("right-verifier"), "s256", Instant.now(), user());
        when(flowStateRepository.findByAuthCode("code")).thenReturn(Optional.of(flow));

        assertThatThrownBy(() -> pkce.exchangeCodeForSession("code", "wrong-verifier"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("code challenge");
        verify(authResponseFactory, never()).newSignIn(any(), any());
    }

    @Test
    @DisplayName("exchange: valid code+verifier issues a session and consumes the code")
    void validExchange() {
        User u = user();
        FlowState flow = flow("code", s256Silently("verifier"), "s256", Instant.now(), u);
        flow.setAuthenticationMethod("oauth");
        when(flowStateRepository.findByAuthCode("code")).thenReturn(Optional.of(flow));
        when(userRepository.findById(u.getId())).thenReturn(Optional.of(u));
        AuthResponse sentinel = AuthResponse.success("acc", "ref", 3600, null);
        when(authResponseFactory.newSignIn(eq(u), eq("oauth"))).thenReturn(sentinel);

        AuthResponse result = pkce.exchangeCodeForSession("code", "verifier");

        assertThat(result).isSameAs(sentinel);
        verify(flowStateRepository).delete(flow);          // single use
        verify(authResponseFactory).newSignIn(u, "oauth");
    }

    // ---------------------------------------------------------------- helpers

    private static User user() {
        return User.builder().id(UUID.randomUUID()).email("u@test.local").build();
    }

    private static FlowState flow(String code, String challenge, String method, Instant issuedAt, User user) {
        return FlowState.builder()
                .id(UUID.randomUUID())
                .authCode(code)
                .codeChallenge(challenge)
                .codeChallengeMethod(method)
                .authenticationMethod("oauth")
                .authCodeIssuedAt(issuedAt)
                .user(user)
                .build();
    }

    private static String s256(String v) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }

    private static String s256Silently(String v) {
        try {
            return s256(v);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
