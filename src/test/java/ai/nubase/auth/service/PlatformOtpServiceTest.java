package ai.nubase.auth.service;

import ai.nubase.auth.util.TokenGenerator;
import ai.nubase.metadata.entity.PlatformOneTimeToken;
import ai.nubase.metadata.repository.PlatformOneTimeTokenRepository;
import ai.nubase.platform.mail.PlatformEmailService;
import ai.nubase.platform.mail.PlatformEmailService.Purpose;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
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

    private static final String EMAIL = "dev@example.com";

    private PlatformOneTimeTokenRepository repository;
    private RateLimiterService rateLimiter;
    private PlatformEmailService emailService;
    private TokenGenerator tokenGenerator;
    private MockEnvironment environment;
    private PlatformOtpService service;

    @BeforeEach
    void setUp() {
        repository = mock(PlatformOneTimeTokenRepository.class);
        rateLimiter = mock(RateLimiterService.class);
        emailService = mock(PlatformEmailService.class);
        tokenGenerator = new TokenGenerator();
        environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        service = new PlatformOtpService(
                repository,
                tokenGenerator,
                rateLimiter,
                emailService,
                environment);
        ReflectionTestUtils.setField(service, "codeLength", 6);
        ReflectionTestUtils.setField(service, "expirationSeconds", 600L);
    }

    @Test
    void issueThenVerifyConsumesToken() {
        service.issue(EMAIL, Purpose.SIGNUP);

        verify(rateLimiter).checkRate(eq("platform_otp:signup"), eq(EMAIL));
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendOtp(eq(EMAIL), code.capture(), eq(Purpose.SIGNUP), eq(600L));
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(repository).upsert(eq(EMAIL), eq("signup"), hash.capture(), any(Instant.class));

        assertThat(code.getValue()).hasSize(6).matches("\\d{6}");
        assertThat(hash.getValue())
                .isEqualTo(tokenGenerator.sha256(code.getValue()))
                .isNotEqualTo(code.getValue());

        PlatformOneTimeToken stored = token(
                "signup",
                hash.getValue(),
                Instant.now().plusSeconds(300));
        when(repository.findByEmailIgnoreCaseAndPurpose(EMAIL, "signup"))
                .thenReturn(Optional.of(stored));

        assertThatCode(() -> service.verify(EMAIL, Purpose.SIGNUP, code.getValue()))
                .doesNotThrowAnyException();
        verify(repository).delete(stored);
    }

    @Test
    void issueInLocalDevelopmentUsesFixedCodeAndSkipsEmail() {
        environment.setActiveProfiles("dev");
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);

        service.issue(EMAIL, Purpose.LOGIN);

        verify(repository).upsert(eq(EMAIL), eq("login"), hash.capture(), any(Instant.class));
        assertThat(hash.getValue())
                .isEqualTo(tokenGenerator.sha256(PlatformOtpService.LOCAL_DEVELOPMENT_CODE));
        verify(emailService, never()).sendOtp(any(), any(), any(), any(Long.class));
    }

    @Test
    void issueNormalizesEmailIndependentlyOfDefaultLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            service.issue("ADMIN@EXAMPLE.COM", Purpose.LOGIN);

            verify(rateLimiter).checkRate("platform_otp:login", "admin@example.com");
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void verifyWrongCodeThrowsAndKeepsToken() {
        PlatformOneTimeToken stored = token(
                "signup",
                tokenGenerator.sha256("111111"),
                Instant.now().plusSeconds(300));
        when(repository.findByEmailIgnoreCaseAndPurpose(EMAIL, "signup"))
                .thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.verify(EMAIL, Purpose.SIGNUP, "999999"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).delete(any());
    }

    @Test
    void verifyExpiredCodeThrowsAndDeletesToken() {
        PlatformOneTimeToken stored = token(
                "login",
                tokenGenerator.sha256("123456"),
                Instant.now().minusSeconds(1));
        when(repository.findByEmailIgnoreCaseAndPurpose(EMAIL, "login"))
                .thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.verify(EMAIL, Purpose.LOGIN, "123456"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, times(1)).delete(stored);
    }

    @Test
    void verifyNoPendingCodeThrows() {
        when(repository.findByEmailIgnoreCaseAndPurpose(EMAIL, "password_change"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verify(EMAIL, Purpose.PASSWORD_CHANGE, "123456"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private PlatformOneTimeToken token(String purpose, String hash, Instant expiresAt) {
        return PlatformOneTimeToken.builder()
                .email(EMAIL)
                .purpose(purpose)
                .tokenHash(hash)
                .expiresAt(expiresAt)
                .build();
    }
}
    
