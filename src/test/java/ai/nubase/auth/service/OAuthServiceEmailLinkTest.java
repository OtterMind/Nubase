package ai.nubase.auth.service;

import ai.nubase.auth.dto.oauth.OAuthUserInfo;
import ai.nubase.auth.entity.User;
import ai.nubase.auth.oauth.OAuthProvider;
import ai.nubase.auth.repository.IdentityRepository;
import ai.nubase.auth.repository.SessionRepository;
import ai.nubase.auth.repository.UserRepository;
import ai.nubase.auth.util.UserMapper;
import ai.nubase.common.config.AuthConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("OAuthService email auto-link guards")
class OAuthServiceEmailLinkTest {

    private UserRepository userRepository;
    private IdentityRepository identityRepository;
    private EffectiveAuthConfig effectiveAuthConfig;
    private OAuthService svc;

    private final UUID victimId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        identityRepository = mock(IdentityRepository.class);
        effectiveAuthConfig = mock(EffectiveAuthConfig.class);
        OAuthProvider provider = mock(OAuthProvider.class);

        svc = new OAuthService(
                Map.of("github", provider),
                userRepository,
                identityRepository,
                mock(SessionRepository.class),
                mock(JwtSecretService.class),
                mock(TokenService.class),
                new UserMapper(),
                new AuthConfig(),
                effectiveAuthConfig,
                mock(AuthResponseFactory.class));

        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(identityRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(identityRepository.findByProviderAndProviderId(any(), any())).thenReturn(Optional.empty());
        when(effectiveAuthConfig.emailConfirmationRequired()).thenReturn(true);
    }

    @Test
    @DisplayName("rejects auto-link when OAuth email is not provider-verified")
    void rejectsUnverifiedProviderEmail() {
        User victim = confirmedUser("victim@x.com");
        when(userRepository.findByEmail("victim@x.com")).thenReturn(Optional.of(victim));

        OAuthUserInfo info = OAuthUserInfo.builder()
                .provider("github").providerId("gh-1")
                .email("victim@x.com").emailVerified(false).build();

        assertThatThrownBy(() -> svc.signInWithProviderInfo(info, false))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("not verified by the provider");

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("rejects auto-link when existing account email is not confirmed")
    void rejectsUnconfirmedTenantEmail() {
        User victim = User.builder()
                .id(victimId)
                .email("victim@x.com")
                .emailConfirmedAt(null)
                .build();
        when(userRepository.findByEmail("victim@x.com")).thenReturn(Optional.of(victim));

        OAuthUserInfo info = OAuthUserInfo.builder()
                .provider("github").providerId("gh-1")
                .email("victim@x.com").emailVerified(true).build();

        assertThatThrownBy(() -> svc.signInWithProviderInfo(info, false))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("not confirmed");
    }

    @Test
    @DisplayName("allows auto-link when provider email is verified and tenant email is confirmed")
    void allowsVerifiedLink() {
        User victim = confirmedUser("victim@x.com");
        when(userRepository.findByEmail("victim@x.com")).thenReturn(Optional.of(victim));

        OAuthUserInfo info = OAuthUserInfo.builder()
                .provider("github").providerId("gh-1")
                .email("victim@x.com").emailVerified(true).build();

        OAuthService.ProviderSignIn result = svc.signInWithProviderInfo(info, false);

        assertThat(result.user().getId()).isEqualTo(victimId);
        verify(userRepository, org.mockito.Mockito.atLeastOnce()).save(victim);
        verify(identityRepository, org.mockito.Mockito.atLeastOnce()).save(any());
    }

    private User confirmedUser(String email) {
        return User.builder()
                .id(victimId)
                .email(email)
                .emailConfirmedAt(Instant.now())
                .build();
    }
}
