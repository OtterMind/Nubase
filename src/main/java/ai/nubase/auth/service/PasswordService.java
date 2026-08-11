package ai.nubase.auth.service;

import ai.nubase.common.config.AuthConfig;
import lombok.RequiredArgsConstructor;
import org.mindrot.jbcrypt.BCrypt;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PasswordService {

    private final EffectiveAuthConfig effectiveAuthConfig;

    public String hashPassword(String plainPassword) {
        return BCrypt.hashpw(plainPassword, BCrypt.gensalt(12));
    }

    public boolean verifyPassword(String plainPassword, String hashedPassword) {
        try {
            return BCrypt.checkpw(plainPassword, hashedPassword);
        } catch (Exception e) {
            return false;
        }
    }

    public void validatePasswordStrength(String password) {
        AuthConfig.PasswordSettings settings = effectiveAuthConfig.password();

        if (password == null || password.length() < settings.getMinLength()) {
            throw new IllegalArgumentException(
                    "Password must be at least " + settings.getMinLength() + " characters long");
        }

        if (settings.isRequireUppercase() && !password.matches(".*[A-Z].*")) {
            throw new IllegalArgumentException("Password must contain at least one uppercase letter");
        }

        if (settings.isRequireLowercase() && !password.matches(".*[a-z].*")) {
            throw new IllegalArgumentException("Password must contain at least one lowercase letter");
        }

        if (settings.isRequireNumber() && !password.matches(".*\\d.*")) {
            throw new IllegalArgumentException("Password must contain at least one number");
        }

        if (settings.isRequireSpecial() && !password.matches(".*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>\\/?].*")) {
            throw new IllegalArgumentException("Password must contain at least one special character");
        }
    }

}
