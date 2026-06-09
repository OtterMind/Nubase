package ai.nubase.auth.controller;

import ai.nubase.auth.dto.response.platform.PlatformAuthResponse;
import ai.nubase.auth.service.PlatformOAuthService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * OAuth login for platform (Studio) developer accounts.
 *
 * - Google GIS / One Tap: the frontend obtains a GIS credential and POSTs it here.
 * - Google / GitHub authorization code: standard redirect flow with a CSRF state cookie.
 */
@RestController
@RequestMapping("/auth/v1/platform/oauth")
@RequiredArgsConstructor
@Slf4j
public class PlatformOAuthController {

    private static final String GITHUB_STATE_COOKIE = "nb_platform_oauth_state";
    private static final String GOOGLE_STATE_COOKIE = "nb_platform_goauth_state";

    private final PlatformOAuthService oauth;

    @Value("${nubase.platform.oauth.site-url:}")
    private String configuredSiteUrl;

    /** Google Identity Services / One Tap — the body carries the ID token credential. */
    @PostMapping("/google")
    public ResponseEntity<?> google(@RequestBody Map<String, String> body) {
        String credential = body.get("credential");
        try {
            PlatformAuthResponse res = oauth.loginWithGoogleCredential(credential);
            return ResponseEntity.ok(res);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                    .body(Map.of("error", "not_configured", "message", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "invalid_credential", "message", e.getMessage()));
        } catch (Exception e) {
            log.error("Google platform login failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "login_failed", "message", e.getMessage()));
        }
    }

    /** Begin the Google authorization-code redirect flow. */
    @GetMapping("/google/start")
    public void googleStart(HttpServletRequest request, HttpServletResponse response) throws java.io.IOException {
        if (!oauth.googleCodeEnabled()) {
            response.sendError(HttpStatus.NOT_IMPLEMENTED.value(), "Google authorization-code login is not configured");
            return;
        }
        String state = newState();
        writeStateCookie(response, GOOGLE_STATE_COOKIE, state);
        response.sendRedirect(oauth.googleAuthorizeUrl(state, callbackUrl(request, "google")));
    }

    /** Google redirects back here with ?code & ?state. */
    @GetMapping("/google/callback")
    public void googleCallback(@RequestParam(required = false) String code,
                               @RequestParam(required = false) String state,
                               HttpServletRequest request,
                               HttpServletResponse response) throws java.io.IOException {
        String studio = baseUrl(request) + "/studio/login";
        try {
            String cookieState = readCookie(request, GOOGLE_STATE_COOKIE);
            if (StringUtils.isBlank(code) || StringUtils.isBlank(state)
                    || cookieState == null || !cookieState.equals(state)) {
                throw new IllegalArgumentException("Invalid OAuth state");
            }
            clearStateCookie(response, GOOGLE_STATE_COOKIE);
            PlatformAuthResponse res = oauth.loginWithGoogleCode(code, callbackUrl(request, "google"));
            // Token goes in the URL fragment so it is not sent to servers/logs; Studio reads it on load.
            response.sendRedirect(studio + "#access_token=" + enc(res.getAccessToken())
                    + "&token_type=Bearer&expires_in=" + res.getExpiresIn());
        } catch (Exception e) {
            log.warn("Google platform login failed: {}", e.getMessage());
            response.sendRedirect(studio + "?oauth_error=" + enc("google_login_failed"));
        }
    }

    /** Begin the GitHub OAuth redirect flow. */
    @GetMapping("/github/start")
    public void githubStart(HttpServletRequest request, HttpServletResponse response) throws java.io.IOException {
        if (!oauth.githubEnabled()) {
            response.sendError(HttpStatus.NOT_IMPLEMENTED.value(), "GitHub login is not configured");
            return;
        }
        String state = newState();
        writeStateCookie(response, GITHUB_STATE_COOKIE, state);
        response.sendRedirect(oauth.githubAuthorizeUrl(state, callbackUrl(request, "github")));
    }

    /** GitHub redirects back here with ?code & ?state. */
    @GetMapping("/github/callback")
    public void githubCallback(@RequestParam(required = false) String code,
                               @RequestParam(required = false) String state,
                               HttpServletRequest request,
                               HttpServletResponse response) throws java.io.IOException {
        String studio = baseUrl(request) + "/studio/login";
        try {
            String cookieState = readCookie(request, GITHUB_STATE_COOKIE);
            if (StringUtils.isBlank(code) || StringUtils.isBlank(state)
                    || cookieState == null || !cookieState.equals(state)) {
                throw new IllegalArgumentException("Invalid OAuth state");
            }
            clearStateCookie(response, GITHUB_STATE_COOKIE);
            PlatformAuthResponse res = oauth.loginWithGithubCode(code, callbackUrl(request, "github"));
            // Token goes in the URL fragment so it is not sent to servers/logs; Studio reads it on load.
            response.sendRedirect(studio + "#access_token=" + enc(res.getAccessToken())
                    + "&token_type=Bearer&expires_in=" + res.getExpiresIn());
        } catch (Exception e) {
            log.warn("GitHub platform login failed: {}", e.getMessage());
            response.sendRedirect(studio + "?oauth_error=" + enc("github_login_failed"));
        }
    }

    // ---------------------------------------------------------------- helpers
    private String baseUrl(HttpServletRequest request) {
        if (StringUtils.isNotBlank(configuredSiteUrl)) {
            return configuredSiteUrl.replaceAll("/+$", "");
        }
        String proto = headerOr(request, "X-Forwarded-Proto", request.getScheme());
        String host = headerOr(request, "X-Forwarded-Host", request.getHeader("Host"));
        return proto + "://" + host;
    }

    private String callbackUrl(HttpServletRequest request, String provider) {
        return baseUrl(request) + "/auth/v1/platform/oauth/" + provider + "/callback";
    }

    private static String newState() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static void writeStateCookie(HttpServletResponse response, String name, String state) {
        Cookie c = new Cookie(name, state);
        c.setHttpOnly(true);
        c.setSecure(true);
        c.setPath("/auth/v1/platform/oauth");
        c.setMaxAge(600);
        response.addCookie(c);
    }

    private static String headerOr(HttpServletRequest request, String header, String fallback) {
        String v = request.getHeader(header);
        return StringUtils.isNotBlank(v) ? v.split(",")[0].trim() : fallback;
    }

    private static String readCookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) return null;
        for (Cookie c : request.getCookies()) {
            if (name.equals(c.getName())) return c.getValue();
        }
        return null;
    }

    private void clearStateCookie(HttpServletResponse response, String name) {
        Cookie c = new Cookie(name, "");
        c.setPath("/auth/v1/platform/oauth");
        c.setMaxAge(0);
        response.addCookie(c);
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }
}
