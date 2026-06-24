package ai.nubase.platform.controller;

import ai.nubase.platform.service.PlatformSettingsService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Super-admin REST API for platform-wide runtime settings.
 *
 * <p>Auth: piggybacks on {@code AdminInitAuthFilter} which already brackets
 * {@code /auth/v1/admin/platform/**} (accepts the metadata service-role key or a
 * platform-user JWT). We additionally require the caller to be a {@code super_admin}
 * platform user, except when the metadata service-role key is used (no platformUserId
 * attribute is set in that case — treated as trusted bootstrap path).
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/auth/v1/admin/platform/settings")
public class PlatformSettingsController {

    /** Whitelist — only these category names can be read or written via the HTTP API. */
    private static final Set<String> ALLOWED_CATEGORIES = Set.of(
            "smtp",
            "storage_r2",
            "storage_s3vectors",
            "oauth_google",
            "oauth_github",
            "llm_openai",
            "llm_anthropic",
            "llm_generic",
            "auth_policy",
            "general"
    );

    private final PlatformSettingsService settingsService;

    @GetMapping("/{category}")
    public ResponseEntity<?> get(@PathVariable("category") String category,
                                 HttpServletRequest request) {
        ResponseEntity<?> denied = ensureSuperAdmin(request);
        if (denied != null) return denied;
        if (!ALLOWED_CATEGORIES.contains(category)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "unknown_category"));
        }
        Map<String, Object> body = new HashMap<>();
        body.put("category", category);
        body.put("values", settingsService.getCategoryMasked(category));
        return ResponseEntity.ok(body);
    }

    @PutMapping("/{category}")
    public ResponseEntity<?> put(@PathVariable("category") String category,
                                 @RequestBody Map<String, String> values,
                                 HttpServletRequest request) {
        ResponseEntity<?> denied = ensureSuperAdmin(request);
        if (denied != null) return denied;
        if (!ALLOWED_CATEGORIES.contains(category)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "unknown_category"));
        }
        UUID actor = (UUID) request.getAttribute("platformUserId");
        try {
            settingsService.replaceCategory(category, values, actor);
        } catch (RuntimeException ex) {
            log.error("Failed to update settings category={}", category, ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", ex.getMessage()));
        }
        Map<String, Object> body = new HashMap<>();
        body.put("category", category);
        body.put("values", settingsService.getCategoryMasked(category));
        return ResponseEntity.ok(body);
    }

    private ResponseEntity<?> ensureSuperAdmin(HttpServletRequest request) {
        // Super-admin decision is made once (with a fresh role lookup) in AdminInitAuthFilter,
        // covering both super_admin platform users and the metadata service-role key.
        if (!Boolean.TRUE.equals(request.getAttribute("platformIsSuperAdmin"))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "super_admin_required"));
        }
        return null;
    }
}
