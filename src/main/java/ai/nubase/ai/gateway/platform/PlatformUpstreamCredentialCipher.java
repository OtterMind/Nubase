package ai.nubase.ai.gateway.platform;

import ai.nubase.postgrest.multidb.EncryptionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts platform-upstream credentials in an optional, isolated key domain.
 *
 * <p>When no dedicated key is configured, all operations delegate to the existing tenant
 * credential encryption service. Once a dedicated key is configured, failures never fall back to
 * the tenant key: accepting either key would hide configuration drift and make writes ambiguous.</p>
 */
@Component
public class PlatformUpstreamCredentialCipher {

    private static final String ENCRYPTED_PREFIX = "ENCRYPTED:AES256:";
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int GCM_IV_LENGTH = 12;

    private final EncryptionService fallbackEncryptionService;
    private final SecretKey dedicatedKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public PlatformUpstreamCredentialCipher(
            EncryptionService fallbackEncryptionService,
            @Value("${nubase.ai-gateway.platform-upstream.encryption.master-key:}")
                    String configuredKey,
            @Value("${nubase.ai-gateway.platform-upstream.encryption.master-key-file:}")
                    String configuredKeyFile) {
        this.fallbackEncryptionService = fallbackEncryptionService;
        this.dedicatedKey = loadDedicatedKey(configuredKey, configuredKeyFile);
    }

    boolean isEncrypted(String value) {
        if (dedicatedKey == null) {
            return fallbackEncryptionService.isEncrypted(value);
        }
        return value != null && value.startsWith(ENCRYPTED_PREFIX);
    }

    String encrypt(String plaintext) throws Exception {
        if (dedicatedKey == null) {
            return fallbackEncryptionService.encrypt(plaintext);
        }
        if (plaintext == null) {
            return null;
        }
        if (plaintext.isBlank()) {
            throw new IllegalArgumentException("Cannot encrypt blank platform upstream credential");
        }
        byte[] iv = new byte[GCM_IV_LENGTH];
        secureRandom.nextBytes(iv);
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, dedicatedKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        byte[] combined = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
        return ENCRYPTED_PREFIX + Base64.getEncoder().encodeToString(combined);
    }

    String decrypt(String encryptedText) throws Exception {
        if (dedicatedKey == null) {
            return fallbackEncryptionService.decrypt(encryptedText);
        }
        if (!isEncrypted(encryptedText)) {
            throw new IllegalArgumentException("Platform upstream credential is not encrypted");
        }
        byte[] combined = Base64.getDecoder().decode(
                encryptedText.substring(ENCRYPTED_PREFIX.length()));
        if (combined.length <= GCM_IV_LENGTH) {
            throw new IllegalArgumentException("Platform upstream credential is invalid");
        }
        byte[] iv = new byte[GCM_IV_LENGTH];
        byte[] ciphertext = new byte[combined.length - GCM_IV_LENGTH];
        System.arraycopy(combined, 0, iv, 0, iv.length);
        System.arraycopy(combined, iv.length, ciphertext, 0, ciphertext.length);
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, dedicatedKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
        return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    }

    private static SecretKey loadDedicatedKey(String configuredKey, String configuredKeyFile) {
        boolean hasKey = configuredKey != null && !configuredKey.isBlank();
        boolean hasFile = configuredKeyFile != null && !configuredKeyFile.isBlank();
        if (hasKey && hasFile) {
            throw new IllegalStateException(
                    "Configure only one platform upstream encryption key source");
        }
        if (!hasKey && !hasFile) {
            return null;
        }
        String encoded;
        try {
            encoded = hasKey
                    ? configuredKey.strip()
                    : Files.readString(Path.of(configuredKeyFile.strip())).strip();
        } catch (Exception error) {
            throw new IllegalStateException(
                    "Failed to load platform upstream encryption key", error);
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(encoded);
            if (decoded.length != 32) {
                throw new IllegalArgumentException("Key length must be 32 bytes");
            }
            return new SecretKeySpec(decoded, "AES");
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException(
                    "Platform upstream encryption key must be a Base64-encoded 256-bit key",
                    error);
        }
    }
}
