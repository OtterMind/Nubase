package ai.nubase.auth.controller;

import ai.nubase.auth.dto.request.platform.PlatformSignInRequest;
import ai.nubase.auth.dto.request.platform.PlatformSignUpRequest;
import ai.nubase.auth.dto.response.platform.PlatformAuthResponse;
import ai.nubase.auth.dto.response.platform.PlatformUserPayload;
import ai.nubase.auth.exception.EmailAlreadyExistsException;
import ai.nubase.auth.service.PlatformAuthService;
import ai.nubase.auth.service.PlatformOAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Endpoints for platform-level developer accounts (Studio login).
 * Does not depend on tenant context — uses the metadata database.
 */
@RestController
@RequestMapping("/auth/v1/platform")
@RequiredArgsConstructor
@Slf4j
public class PlatformAuthController {

    private final PlatformAuthService platformAuthService;
    private final PlatformOAuthService platformOAuthService;

    /**
     * GET /auth/v1/platform/config — public config the Studio frontend needs to render the
     * login/signup page correctly (incl. which OAuth providers are enabled). No auth required.
     */
    @GetMapping("/config")
    public ResponseEntity<?> publicConfig() {
        return ResponseEntity.ok(Map.of(
                "signup_enabled", platformAuthService.isSignupEnabled(),
                "google_enabled", platformOAuthService.googleEnabled(),
                "google_code_enabled", platformOAuthService.googleCodeEnabled(),
                "github_enabled", platformOAuthService.githubEnabled(),
                "google_client_id", platformOAuthService.googleClientId()
        ));
    }

    @PostMapping("/signup")
    public ResponseEntity<?> signUp(@Valid @RequestBody PlatformSignUpRequest request) {
        try {
            PlatformAuthResponse response = platformAuthService.signUp(request);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (EmailAlreadyExistsException e) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(Map.of("error", "user_exists", "message", e.getMessage()));
        } catch (Exception e) {
            log.error("Platform signup failed", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "signup_failed", "message", e.getMessage()));
        }
    }

    @PostMapping("/token")
    public ResponseEntity<?> token(@Valid @RequestBody PlatformSignInRequest request) {
        try {
            PlatformAuthResponse response = platformAuthService.signIn(request);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "invalid_credentials", "message", e.getMessage()));
        } catch (Exception e) {
            log.error("Platform sign-in failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "signin_failed", "message", e.getMessage()));
        }
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (StringUtils.isBlank(authHeader) || !authHeader.startsWith("Bearer ")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "missing_token"));
        }
        try {
            UUID userId = platformAuthService.validateAndGetSubject(authHeader.substring(7));
            PlatformUserPayload payload = platformAuthService.describe(userId);
            return ResponseEntity.ok(payload);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "invalid_token", "message", e.getMessage()));
        }
    }
}
