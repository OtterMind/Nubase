package ai.nubase.auth.service;

import ai.nubase.auth.dto.oauth.OAuthUserInfo;
import ai.nubase.auth.dto.response.AuthResponse;
import ai.nubase.auth.entity.Identity;
import ai.nubase.auth.entity.User;
import ai.nubase.auth.oauth.OAuthProvider;
import ai.nubase.auth.repository.IdentityRepository;
import ai.nubase.auth.repository.UserRepository;
import ai.nubase.common.config.AuthConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link OAuthService#linkIdentity}'s guard: an identity already owned by a
 * different user must not be re-linked; a fresh identity links to the target user.
 */
@DisplayName("OAuthService.linkIdentity")
class OAuthServiceTest {

    private UserRepository userRepository;
    private IdentityRepository identityRepository;
    private AuthResponseFactory authResponseFactory;
    private EffectiveAuthConfig effectiveAuthConfig;
    private OAuthProvider provider;
    private OAuthService svc;

    private final AuthResponse sentinel = AuthResponse.success("acc", "ref", 3600, null);
    private final UUID targetUserId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        identityRepository = mock(IdentityRepository.class);
        authResponseFactory = mock(AuthResponseFactory.class);
        effectiveAuthConfig = mock(EffectiveAuthConfig.class);
        provider = mock(OAuthProvider.class);
        when(provider.getProviderName()).thenReturn("google");
        when(provider.getUserInfo("code", "uri")).thenReturn(OAuthUserInfo.builder()
                .provider("google").providerId("sub1").email("g@x.com").emailVerified(true).build());

        svc = new OAuthService(
                Map.of("google", provider), userRepository, identityRepository,
                mock(ai.nubase.auth.repository.SessionRepository.class), mock(JwtSecretService.class),
                mock(TokenService.class), new ai.nubase.auth.util.UserMapper(), new AuthConfig(),
                effectiveAuthConfig, authResponseFactory);

        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(identityRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(identityRepository.findByUserId(any())).thenReturn(java.util.List.of());
        when(authResponseFactory.newSignIn(any(), anyString())).thenReturn(sentinel);
    }

    @Test
    @DisplayName("rejects linking an identity already owned by another user")
    void alreadyLinkedElsewhere() {
        User other = User.builder().id(UUID.randomUUID()).build();
        Identity existing = Identity.builder().user(other).provider("google").providerId("sub1").build();
        when(identityRepository.findByProviderAndProviderId("google", "sub1"))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> svc.linkIdentity("google", "code", "uri", targetUserId))
                .isInstanceOf(RuntimeException.class).hasMessageContaining("already linked to another user");
        verify(identityRepository, never()).save(any());
    }

    @Test
    @DisplayName("links a fresh identity to the target user and issues a session")
    void linksFreshIdentity() {
        when(identityRepository.findByProviderAndProviderId("google", "sub1")).thenReturn(Optional.empty());
        when(userRepository.findById(targetUserId))
                .thenReturn(Optional.of(User.builder().id(targetUserId).email("me@x.com").build()));

        AuthResponse res = svc.linkIdentity("google", "code", "uri", targetUserId);

        assertThat(res).isSameAs(sentinel);
        verify(identityRepository, atLeastOnce()).save(any(Identity.class));
        verify(authResponseFactory).newSignIn(any(), eq("oauth"));
    }

    @Test
    @DisplayName("fails when the target user does not exist")
    void targetMissing() {
        when(identityRepository.findByProviderAndProviderId("google", "sub1")).thenReturn(Optional.empty());
        when(userRepository.findById(targetUserId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> svc.linkIdentity("google", "code", "uri", targetUserId))
                .isInstanceOf(RuntimeException.class).hasMessageContaining("User to link not found");
    }
}
