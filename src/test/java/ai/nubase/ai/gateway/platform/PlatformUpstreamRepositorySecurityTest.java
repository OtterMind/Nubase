package ai.nubase.ai.gateway.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PlatformUpstreamRepositorySecurityTest {

    @Test
    void blankTokenCannotBeReusedWhenHostOrEffectivePortChanges() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PlatformUpstreamCredentialCipher cipher = mock(PlatformUpstreamCredentialCipher.class);
        PlatformUpstreamRepository repository = repository(
                jdbcTemplate, cipher, true, false, publicResolver());
        doReturn(Optional.of(existing("https://api.example/v1")))
                .when(repository).findById(7L);

        assertThatThrownBy(() -> repository.save(update("https://other.example/v1", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("authToken is required when changing the upstream origin");
        assertThatThrownBy(() -> repository.save(update("https://api.example:444/v1", "   ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("authToken is required when changing the upstream origin");
        assertThatThrownBy(() -> repository.save(update("http://api.example/v1", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("authToken is required when changing the upstream origin");

        verifyNoInteractions(jdbcTemplate);
        verify(cipher, never()).encrypt(anyString());
    }

    @Test
    void blankTokenIsReencryptedWhenOriginIsEquivalent() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PlatformUpstreamCredentialCipher cipher = mock(PlatformUpstreamCredentialCipher.class);
        PlatformUpstreamRepository repository = repository(
                jdbcTemplate, cipher, false, false, publicResolver());
        doReturn(Optional.of(existing("https://api.example/v1")))
                .when(repository).findById(7L);
        when(cipher.encrypt("stored-credential")).thenReturn("versioned-ciphertext");
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        repository.save(update("https://API.EXAMPLE.:443/v2", null));

        verify(cipher).encrypt("stored-credential");
        verify(jdbcTemplate).update(
                org.mockito.ArgumentMatchers.contains("AND base_url = ?"),
                any(Object[].class));
    }

    @Test
    void explicitReplacementDoesNotDecryptTheStoredCredentialBeforeWrite() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PlatformUpstreamCredentialCipher cipher = mock(PlatformUpstreamCredentialCipher.class);
        PlatformUpstreamRepository repository = repository(
                jdbcTemplate, cipher, false, false, publicResolver());
        PlatformUpstream saved = update("https://other.example/v1", "replacement-credential");
        doReturn(Optional.of(saved)).when(repository).findById(7L);
        when(cipher.encrypt("replacement-credential")).thenReturn("versioned-ciphertext");
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        repository.save(saved);

        verify(repository, times(1)).findById(7L);
        verify(cipher).encrypt("replacement-credential");
        verify(jdbcTemplate).update(
                argThat(sql -> !sql.contains("AND base_url = ?")),
                any(Object[].class));
    }

    @Test
    void saveRejectsHttpAndRestrictedAddressesByDefault() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PlatformUpstreamCredentialCipher cipher = mock(PlatformUpstreamCredentialCipher.class);
        PlatformUpstreamRepository repository = repository(
                jdbcTemplate, cipher, false, false, InetAddress::getAllByName);

        assertThatThrownBy(() -> repository.save(create("http://8.8.8.8/v1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must use HTTPS");
        assertThatThrownBy(() -> repository.save(create("https://127.0.0.1/v1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("baseUrl points to a restricted network address");
        assertThatThrownBy(() -> repository.save(create("https://10.0.0.1/v1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("baseUrl points to a restricted network address");
        assertThatThrownBy(() -> repository.save(create("https://169.254.1.1/v1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("baseUrl points to a restricted network address");
        assertThatThrownBy(() -> repository.save(create("https://127.1/v1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("baseUrl must be an absolute URI");
        assertThatThrownBy(() -> repository.save(create("https://0177.0.0.1/v1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("baseUrl must include a valid host");
        assertThatThrownBy(() -> repository.save(create("https://user@8.8.8.8/v1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("baseUrl must be an absolute URI");
        assertThatThrownBy(() -> repository.save(create("https://8.8.8.8/v1?debug=true")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("baseUrl must be an absolute URI");

        verifyNoInteractions(jdbcTemplate, cipher);
    }

    @Test
    void unresolvedHostIsRejectedWithoutEchoingTheHost() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PlatformUpstreamCredentialCipher cipher = mock(PlatformUpstreamCredentialCipher.class);
        String hostSentinel = "unresolved-host-sentinel.invalid";
        PlatformUpstreamRepository repository = repository(
                jdbcTemplate,
                cipher,
                false,
                false,
                host -> {
                    throw new UnknownHostException(hostSentinel);
                });

        assertThatThrownBy(() -> repository.save(create("https://" + hostSentinel + "/v1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("baseUrl host cannot be resolved")
                .hasMessageNotContaining(hostSentinel)
                .hasNoCause();
        verifyNoInteractions(jdbcTemplate, cipher);
    }

    @Test
    void localHttpRequiresBothControlledExceptions() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PlatformUpstreamCredentialCipher cipher = mock(PlatformUpstreamCredentialCipher.class);
        PlatformUpstreamRepository repository = repository(
                jdbcTemplate,
                cipher,
                true,
                true,
                host -> new InetAddress[] {InetAddress.getLoopbackAddress()});
        when(cipher.encrypt("new-credential")).thenReturn("versioned-ciphertext");
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(9L);
        doReturn(Optional.empty()).when(repository).findById(9L);

        repository.save(create("http://127.0.0.1:9999/v1"));

        verify(cipher).encrypt("new-credential");
        verify(jdbcTemplate).queryForObject(anyString(), eq(Long.class), any(Object[].class));
    }

    private static PlatformUpstreamRepository repository(
            JdbcTemplate jdbcTemplate,
            PlatformUpstreamCredentialCipher cipher,
            boolean allowHttp,
            boolean allowPrivateNetwork,
            PlatformUpstreamRepository.HostResolver resolver) {
        return spy(new PlatformUpstreamRepository(
                jdbcTemplate,
                cipher,
                new ObjectMapper(),
                allowHttp,
                allowPrivateNetwork,
                resolver));
    }

    private static PlatformUpstreamRepository.HostResolver publicResolver() {
        return host -> new InetAddress[] {
                InetAddress.getByAddress(new byte[] {8, 8, 8, 8})
        };
    }

    private static PlatformUpstream existing(String baseUrl) {
        return PlatformUpstream.builder()
                .id(7L)
                .name("existing")
                .baseUrl(baseUrl)
                .authToken("stored-credential")
                .build();
    }

    private static PlatformUpstream update(String baseUrl, String authToken) {
        return PlatformUpstream.builder()
                .id(7L)
                .name("updated")
                .baseUrl(baseUrl)
                .authToken(authToken)
                .build();
    }

    private static PlatformUpstream create(String baseUrl) {
        return PlatformUpstream.builder()
                .name("created")
                .baseUrl(baseUrl)
                .authToken("new-credential")
                .build();
    }
}
