package ai.nubase.auth.service;

import ai.nubase.auth.util.TokenGenerator;
import ai.nubase.metadata.entity.PlatformOneTimeToken;
import ai.nubase.metadata.repository.PlatformOneTimeTokenRepository;
import ai.nubase.platform.mail.PlatformEmailService;
import ai.nubase.platform.mail.PlatformEmailService.Purpose;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformOtpServiceTest {

    private PlatformOneTimeTokenRepository repo;
    private RateLimiterService rateLimiter;
    private PlatformEmailService email;
    private TokenGenerator tokenGenerator;
    private PlatformOtpService service;

    private static final String EMAIL = "dev@example.com";

    @BeforeEach
    void setUp() {
        repo = mock(PlatformOneTimeTokenRepository.class);
        rateLimiter = mock(RateLimiterService.class);
        email = mock(PlatformEmailService.class);
        tokenGenerator = new TokenGenerator(); // pure, no context
        service = new PlatformOtpService(repo, tokenGenerator, rateLimiter, email);
        ReflectionTestUtils.setField(service, "codeLength", 6);
        ReflectionTestUtils.setField(service, "expirationSeconds", 600L);
    }

    /** issue() persists a hashed code and emails the plaintext; a matching code then verifies & consumes. */
    @Test
    void issueThenVerify_consumesToken() {
        service.issue(EMAIL, Purpose.SIGNUP);

        verify(rateLimiter).checkRate(eq("platform_otp:signup"), eq(EMAIL));
        // issue() persists via an atomic upsert; capture the emailed code and the stored hash.
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(email).sendOtp(eq(EMAIL), code.capture(), eq(Purpose.SIGNUP), eq(600L));
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(repo).upsert(eq(EMAIL), eq("signup"), hash.capture(), any(Instant.class));

        assertThat6Digits(code.getValue());
        // The stored hash is the SHA-256 of the emailed code, never the plaintext.
        org.assertj.core.api.Assertions.assertThat(hash.getValue())
                .isEqualTo(tokenGenerator.sha256(code.getValue()))
                .isNotEqualTo(code.getValue());

        PlatformOneTimeToken stored = PlatformOneTimeToken.builder()
                .email(EMAIL).purpose("signup")
                .tokenHash(hash.getValue())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        when(repo.findByEmailIgnoreCaseAndPurpose(EMAIL, "signup")).thenReturn(Optional.of(stored));

        assertThatCode(() -> service.verify(EMAIL, Purpose.SIGNUP, code.getValue())).doesNotThrowAnyException();
        verify(repo).delete(stored);
    }

    @Test
    void verify_wrongCode_throwsAndKeepsToken() {
        PlatformOneTimeToken stored = PlatformOneTimeToken.builder()
                .email(EMAIL).purpose("signup")
                .tokenHash(tokenGenerator.sha256("111111"))
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        when(repo.findByEmailIgnoreCaseAndPurpose(EMAIL, "signup")).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.verify(EMAIL, Purpose.SIGNUP, "999999"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repo, never()).delete(any());
    }

    @Test
    void verify_expiredCode_throwsAndDeletesToken() {
        PlatformOneTimeToken stored = PlatformOneTimeToken.builder()
                .email(EMAIL).purpose("login")
                .tokenHash(tokenGenerator.sha256("123456"))
                .expiresAt(Instant.now().minusSeconds(1))
                .build();
        when(repo.findByEmailIgnoreCaseAndPurpose(EMAIL, "login")).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.verify(EMAIL, Purpose.LOGIN, "123456"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repo, times(1)).delete(stored);
    }

    @Test
    void verify_noPendingCode_throws() {
        when(repo.findByEmailIgnoreCaseAndPurpose(EMAIL, "password_change")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.verify(EMAIL, Purpose.PASSWORD_CHANGE, "123456"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertThat6Digits(String code) {
        org.assertj.core.api.Assertions.assertThat(code).hasSize(6).matches("\\d{6}");
    }
}
