package ai.nubase.postgrest.multidb;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encryption/Decryption service for sensitive data
 * Uses AES-256-GCM for authenticated encryption
 *
 * The master encryption key can be provided via:
 * 1. Environment variable PGRST_ENCRYPTION_MASTER_KEY
 * 2. File path via pgrst.multidb.encryption.master-key-file
 * 3. Direct configuration (NOT recommended for production)
 */
@Slf4j
@Service
public class EncryptionService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int GCM_IV_LENGTH = 12;
    private static final String ENCRYPTED_PREFIX = "ENCRYPTED:AES256:";

    @Value("${pgrst.multidb.encryption.master-key:}")
    private String masterKeyConfig;

    @Value("${pgrst.multidb.encryption.master-key-file:}")
    private String masterKeyFile;

    private SecretKey masterKey;
    private final SecureRandom secureRandom = new SecureRandom();

    @PostConstruct
    public void initialize() {
        try {
            String keyBase64 = loadMasterKey();

            if (keyBase64 == null || keyBase64.isBlank()) {
                log.warn("No master encryption key configured. Using a default key for development only!");
                log.warn("For production, set PGRST_ENCRYPTION_MASTER_KEY environment variable");
                // Generate a random key for development (NOT for production!)
                byte[] keyBytes = new byte[32]; // 256 bits
                secureRandom.nextBytes(keyBytes);
                this.masterKey = new SecretKeySpec(keyBytes, "AES");
            } else {
                byte[] keyBytes = Base64.getDecoder().decode(keyBase64);
                if (keyBytes.length != 32) {
                    throw new IllegalArgumentException(
                        "Master key must be 32 bytes (256 bits), got " + keyBytes.length + " bytes"
                    );
                }
                this.masterKey = new SecretKeySpec(keyBytes, "AES");
                log.info("Encryption service initialized successfully");
            }
        } catch (Exception e) {
            log.error("Failed to initialize encryption service", e);
            throw new IllegalStateException("Failed to initialize encryption service", e);
        }
    }

    /**
     * Load master key from configuration or environment
     *
     * @return Base64-encoded master key
     */
    private String loadMasterKey() throws Exception {
        // Priority 1: Environment variable
        String envKey = System.getenv("PGRST_ENCRYPTION_MASTER_KEY");
        if (envKey != null && !envKey.isBlank()) {
            log.info("Loading master key from environment variable");
            return envKey;
        }

        // Priority 2: File
        if (masterKeyFile != null && !masterKeyFile.isBlank()) {
            log.info("Loading master key from file: {}", masterKeyFile);
            return Files.readString(Paths.get(masterKeyFile)).strip();
        }

        // Priority 3: Configuration (NOT recommended)
        if (masterKeyConfig != null && !masterKeyConfig.isBlank()) {
            log.warn("Loading master key from configuration file - NOT recommended for production!");
            return masterKeyConfig;
        }

        return null;
    }

    /**
     * Encrypt plaintext using AES-256-GCM
     *
     * @param plaintext the text to encrypt
     * @return encrypted text with format ENCRYPTED:AES256:base64data
     * @throws Exception if encryption fails
     */
    public String encrypt(String plaintext) throws Exception {
        if (plaintext == null) {
            return null;
        }

        if (plaintext.isBlank()) {
            throw new IllegalArgumentException("Cannot encrypt blank string");
        }

        // Generate random IV
        byte[] iv = new byte[GCM_IV_LENGTH];
        secureRandom.nextBytes(iv);

        // Initialize cipher
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.ENCRYPT_MODE, masterKey, parameterSpec);

        // Encrypt
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

        // Combine IV + ciphertext
        byte[] combined = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);

        // Encode and add prefix
        String encoded = Base64.getEncoder().encodeToString(combined);
        return ENCRYPTED_PREFIX + encoded;
    }

    /**
     * Decrypt encrypted text
     *
     * @param encryptedText the encrypted text with format ENCRYPTED:AES256:base64data
     * @return decrypted plaintext
     * @throws Exception if decryption fails
     */
    public String decrypt(String encryptedText) throws Exception {
        if (encryptedText == null) {
            return null;
        }

        if (!encryptedText.startsWith(ENCRYPTED_PREFIX)) {
            throw new IllegalArgumentException(
                "Encrypted text must start with " + ENCRYPTED_PREFIX + ", got: " +
                encryptedText.substring(0, Math.min(20, encryptedText.length()))
            );
        }

        // Remove prefix and decode
        String base64Data = encryptedText.substring(ENCRYPTED_PREFIX.length());
        byte[] combined = Base64.getDecoder().decode(base64Data);

        if (combined.length < GCM_IV_LENGTH) {
            throw new IllegalArgumentException("Encrypted data is too short");
        }

        // Extract IV and ciphertext
        byte[] iv = new byte[GCM_IV_LENGTH];
        byte[] ciphertext = new byte[combined.length - GCM_IV_LENGTH];
        System.arraycopy(combined, 0, iv, 0, iv.length);
        System.arraycopy(combined, iv.length, ciphertext, 0, ciphertext.length);

        // Initialize cipher
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.DECRYPT_MODE, masterKey, parameterSpec);

        // Decrypt
        byte[] plaintext = cipher.doFinal(ciphertext);
        return new String(plaintext, StandardCharsets.UTF_8);
    }

    /**
     * Check if a string is encrypted
     *
     * @param text the text to check
     * @return true if the text appears to be encrypted
     */
    public boolean isEncrypted(String text) {
        return text != null && text.startsWith(ENCRYPTED_PREFIX);
    }

    /**
     * Decrypt a value only if it's encrypted, otherwise return as-is
     * Useful for backward compatibility
     *
     * @param value the value (may or may not be encrypted)
     * @return decrypted value or original value
     */
    public String decryptIfEncrypted(String value) {
        try {
            if (isEncrypted(value)) {
                return decrypt(value);
            }
            return value;
        } catch (Exception e) {
            log.error("Failed to decrypt value", e);
            throw new IllegalStateException("Decryption failed", e);
        }
    }

}
