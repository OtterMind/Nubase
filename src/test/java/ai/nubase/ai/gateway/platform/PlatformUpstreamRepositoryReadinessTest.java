package ai.nubase.ai.gateway.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformUpstreamRepositoryReadinessTest {

    private JdbcTemplate jdbcTemplate;
    private PlatformUpstreamCredentialCipher credentialCipher;
    private PlatformUpstreamRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        credentialCipher = mock(PlatformUpstreamCredentialCipher.class);
        repository = new PlatformUpstreamRepository(
                jdbcTemplate, credentialCipher, new ObjectMapper());
    }

    @Test
    void encryptedDecryptableCandidateWithRoutableModelIsReady() throws Exception {
        ResultSet candidate = candidate(
                "OPENAI",
                "https://gateway.example/v1",
                "encrypted-token",
                "[\"model-a\"]");
        when(credentialCipher.isEncrypted("encrypted-token")).thenReturn(true);
        when(credentialCipher.decrypt("encrypted-token")).thenReturn("decrypted-token");
        invokeRows(candidate);

        assertThat(repository.hasUsableActiveCatalogUpstream()).isTrue();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), any(RowCallbackHandler.class));
        assertThat(sql.getValue())
                .contains("SELECT provider, base_url, auth_token_encrypted, supported_models")
                .contains("WHERE is_active = TRUE")
                .contains("ORDER BY priority ASC, id ASC");
    }

    @Test
    void invalidCandidateIsSkippedWhenALaterCandidateIsUsable() throws Exception {
        ResultSet invalid = candidate(
                "UNKNOWN",
                "file:///tmp/upstream",
                "plaintext-token",
                "[\"*\"]");
        ResultSet healthy = candidate(
                "CLAUDE",
                "https://gateway.example",
                "encrypted-token",
                "[\"model-b\"]");
        when(credentialCipher.isEncrypted("encrypted-token")).thenReturn(true);
        when(credentialCipher.decrypt("encrypted-token")).thenReturn("decrypted-token");
        invokeRows(invalid, healthy);

        assertThat(repository.hasUsableActiveCatalogUpstream()).isTrue();
    }

    @Test
    void plaintextWildcardOrUnknownProviderCannotMakeGatewayReady() throws Exception {
        ResultSet plaintext = candidate(
                "OPENAI",
                "https://gateway.example",
                "plaintext-token",
                "[\"model-a\"]");
        ResultSet wildcard = candidate(
                "CLAUDE",
                "https://gateway.example",
                "encrypted-token",
                "[\"*\"]");
        ResultSet unknown = candidate(
                "UNKNOWN",
                "https://gateway.example",
                "encrypted-token",
                "[\"model-c\"]");
        when(credentialCipher.isEncrypted("encrypted-token")).thenReturn(true);
        invokeRows(plaintext, wildcard, unknown);

        assertThat(repository.hasUsableActiveCatalogUpstream()).isFalse();
    }

    @Test
    void decryptionFailureRemainsUnavailableWithoutExposingTheToken() throws Exception {
        ResultSet candidate = candidate(
                "OPENAI",
                "https://gateway.example",
                "encrypted-token",
                "[\"model-a\"]");
        when(credentialCipher.isEncrypted("encrypted-token")).thenReturn(true);
        when(credentialCipher.decrypt("encrypted-token"))
                .thenThrow(new IllegalStateException("token-sentinel"));
        invokeRows(candidate);

        assertThat(repository.hasUsableActiveCatalogUpstream()).isFalse();
    }

    @Test
    void normalReadStrictlyDecryptsStoredCredential() throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getString("auth_token_encrypted")).thenReturn("plaintext-token");
        when(credentialCipher.decrypt("plaintext-token"))
                .thenThrow(new IllegalArgumentException("credential must be encrypted"));
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            RowMapper<PlatformUpstream> mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(row, 0));
        }).when(jdbcTemplate).query(anyString(), any(RowMapper.class));

        assertThatThrownBy(repository::findAllActive)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Failed to decrypt platform upstream auth token");
        verify(credentialCipher).decrypt("plaintext-token");
    }

    private void invokeRows(ResultSet... rows) {
        doAnswer(invocation -> {
            RowCallbackHandler callback = invocation.getArgument(1);
            for (ResultSet row : rows) {
                callback.processRow(row);
            }
            return null;
        }).when(jdbcTemplate).query(anyString(), any(RowCallbackHandler.class));
    }

    private static ResultSet candidate(
            String provider,
            String baseUrl,
            String encryptedToken,
            String models) throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getString("provider")).thenReturn(provider);
        when(row.getString("base_url")).thenReturn(baseUrl);
        when(row.getString("auth_token_encrypted")).thenReturn(encryptedToken);
        when(row.getString("supported_models")).thenReturn(models);
        return row;
    }
}
