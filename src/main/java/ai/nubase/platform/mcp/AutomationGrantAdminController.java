package ai.nubase.platform.mcp;

import ai.nubase.auth.service.PlatformAuthService;
import ai.nubase.common.multitenancy.AdminInitAuthFilter;
import ai.nubase.platform.mcp.AutomationGrantAdminService.CreateGrantRequest;
import ai.nubase.platform.mcp.AutomationGrantAdminService.MintTokenRequest;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/auth/v1/admin/platform/automation-grants")
@ConditionalOnProperty(prefix = "nubase.platform-mcp", name = "enabled", havingValue = "true")
public class AutomationGrantAdminController {

    private final AutomationGrantAdminService grantAdminService;

    @PostMapping
    public ResponseEntity<?> create(
            @RequestBody CreateGrantRequest body, HttpServletRequest request) {
        ResponseEntity<?> denied = requireMetadataRoot(request);
        if (denied != null) return denied;
        try {
            return sensitiveResponse(HttpStatus.CREATED)
                    .body(grantAdminService.createGrant(body));
        } catch (PlatformMcpOperationException e) {
            return fixedError(HttpStatus.BAD_REQUEST, e.code());
        }
    }

    @PostMapping("/{grantId}/tokens")
    public ResponseEntity<?> mint(
            @PathVariable UUID grantId,
            @RequestBody MintTokenRequest body,
            HttpServletRequest request) {
        ResponseEntity<?> denied = requireMetadataRoot(request);
        if (denied != null) return denied;
        try {
            return sensitiveResponse(HttpStatus.OK)
                    .body(grantAdminService.mintToken(grantId, body));
        } catch (PlatformMcpOperationException e) {
            return fixedError(HttpStatus.BAD_REQUEST, e.code());
        }
    }

    @DeleteMapping("/{grantId}")
    public ResponseEntity<?> revoke(
            @PathVariable UUID grantId, HttpServletRequest request) {
        ResponseEntity<?> denied = requireMetadataRoot(request);
        if (denied != null) return denied;
        try {
            grantAdminService.revoke(grantId);
            return ResponseEntity.noContent().build();
        } catch (PlatformMcpOperationException e) {
            return fixedError(HttpStatus.BAD_REQUEST, e.code());
        }
    }

    private static ResponseEntity<?> requireMetadataRoot(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null
                || !authorization.startsWith("Bearer ")
                || authorization.substring(7).isBlank()
                || !PlatformAuthService.SYSTEM_USER_ID.equals(
                        request.getAttribute("platformUserId"))
                || !Boolean.TRUE.equals(request.getAttribute(
                        AdminInitAuthFilter.METADATA_ROOT_AUTHENTICATED_ATTRIBUTE))) {
            return fixedError(HttpStatus.FORBIDDEN, "METADATA_ROOT_REQUIRED");
        }
        return null;
    }

    private static ResponseEntity<Map<String, String>> fixedError(
            HttpStatus status, String code) {
        return ResponseEntity.status(status).body(Map.of("error", code));
    }

    private static ResponseEntity.BodyBuilder sensitiveResponse(HttpStatus status) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.PRAGMA, "no-cache");
    }
}
