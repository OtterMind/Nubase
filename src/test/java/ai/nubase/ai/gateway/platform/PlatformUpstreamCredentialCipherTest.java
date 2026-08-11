package ai.nubase.ai.gateway.platform;

import ai.nubase.postgrest.multidb.EncryptionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.AEADBadTagException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PlatformUpstreamCredentialCipherTest {

    @TempDir
    Path tempDirectory;

    @Test
    void noDedicatedKeyDelegatesToTenantEncryptionService() throws Exception {
        EncryptionService fallback = mock(EncryptionService.class);
        when(fallback.encrypt("secret")).thenReturn("fallback-ciphertext");
        when(fallback.decrypt("fallback-ciphertext")).thenReturn("secret");
        when(fallback.isEncrypted("fallback-ciphertext")).thenReturn(true);
        PlatformUpstreamCredentialCipher cipher =
                new PlatformUpstreamCredentialCipher(fallback, "", "");

        assertThat(cipher.encrypt("secret")).isEqualTo("fallback-ciphertext");
        assertThat(cipher.decrypt("fallback-ciphertext")).isEqualTo("secret");
        assertThat(cipher.isEncrypted("fallback-ciphertext")).isTrue();
        verify(fallback).isEncrypted("fallback-ciphertext");
    }

    @Test
    void dedicatedKeyRoundTripsWithoutUsingTenantEncryptionService() throws Exception {
        EncryptionService fallback = mock(EncryptionService.class);
        PlatformUpstreamCredentialCipher cipher =
                new PlatformUpstreamCredentialCipher(fallback, randomKey(), "");

        String encrypted = cipher.encrypt("platform-secret");

        assertThat(encrypted).startsWith("ENCRYPTED:AES256:");
        assertThat(cipher.decrypt(encrypted)).isEqualTo("platform-secret");
        verify(fallback, never()).encrypt("platform-secret");
    }

    @Test
    void dedicatedKeyMatchesTenantCipherNullAndBlankContract() throws Exception {
        EncryptionService fallback = mock(EncryptionService.class);
        PlatformUpstreamCredentialCipher cipher =
                new PlatformUpstreamCredentialCipher(fallback, randomKey(), "");

        assertThat(cipher.encrypt(null)).isNull();
        assertThatThrownBy(() -> cipher.encrypt("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Cannot encrypt blank platform upstream credential");
        verifyNoInteractions(fallback);
    }

    @Test
    void dedicatedKeyFileUsesTheSameFormat() throws Exception {
        EncryptionService fallback = mock(EncryptionService.class);
        Path keyFile = tempDirectory.resolve("platform.key");
        Files.writeString(keyFile, randomKey() + System.lineSeparator());
        PlatformUpstreamCredentialCipher cipher =
                new PlatformUpstreamCredentialCipher(fallback, "", keyFile.toString());

        String encrypted = cipher.encrypt("platform-secret");

        assertThat(cipher.decrypt(encrypted)).isEqualTo("platform-secret");
        assertThatThrownBy(() -> cipher.decrypt("legacy-plaintext"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Platform upstream credential is not encrypted");
    }

    @Test
    void configuredDedicatedKeyNeverFallsBackAfterDecryptionFailure() throws Exception {
        EncryptionService fallback = mock(EncryptionService.class);
        PlatformUpstreamCredentialCipher writer =
                new PlatformUpstreamCredentialCipher(fallback, randomKey(), "");
        PlatformUpstreamCredentialCipher reader =
                new PlatformUpstreamCredentialCipher(fallback, randomKey(), "");

        String encrypted = writer.encrypt("platform-secret");

        assertThatThrownBy(() -> reader.decrypt(encrypted))
                .isInstanceOf(AEADBadTagException.class);
        verifyNoInteractions(fallback);
    }

    @Test
    void ambiguousOrInvalidDedicatedKeyConfigurationFailsClosed() {
        EncryptionService fallback = mock(EncryptionService.class);

        assertThatThrownBy(() -> new PlatformUpstreamCredentialCipher(
                fallback, randomKey(), tempDirectory.resolve("platform.key").toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Configure only one platform upstream encryption key source");
        assertThatThrownBy(() -> new PlatformUpstreamCredentialCipher(
                fallback, "not-base64", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Platform upstream encryption key must be a Base64-encoded 256-bit key");
        assertThatThrownBy(() -> new PlatformUpstreamCredentialCipher(
                fallback, "", tempDirectory.resolve("missing.key").toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Failed to load platform upstream encryption key");
    }

    private static String randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }
}
