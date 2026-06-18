package ai.nubase.auth.controller;

import ai.nubase.common.config.AuthConfig;
import ai.nubase.auth.dto.oauth.OAuthStateData;
import ai.nubase.auth.dto.response.AuthResponse;
import ai.nubase.auth.entity.User;
import ai.nubase.auth.service.AuthService;
import ai.nubase.auth.service.IdTokenService;
import ai.nubase.auth.service.OAuthService;
import ai.nubase.auth.service.OAuthStateService;
import ai.nubase.auth.service.OtpService;
import ai.nubase.auth.service.PkceService;
import ai.nubase.auth.service.RedirectUrlValidator;
import ai.nubase.auth.dto.request.OtpRequest;
import ai.nubase.auth.dto.request.RecoverRequest;
import ai.nubase.auth.dto.request.RefreshTokenRequest;
import ai.nubase.auth.dto.request.ResendRequest;
import ai.nubase.auth.dto.request.SignInRequest;
import ai.nubase.auth.dto.request.SignUpRequest;
import ai.nubase.auth.dto.request.VerifyRequest;
import ai.nubase.auth.util.TokenGenerator;
import ai.nubase.common.context.MultiTenancyContext;
import ai.nubase.common.util.HtmlEscape;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Authentication Controller
 * Handles all authentication-related endpoints
 * Base path: /auth/v1 (configured in application.yml)
 */
@RestController("")
@RequiredArgsConstructor
@RequestMapping("/auth/v1")

public class AuthController {

    private final AuthService authService;
    private final OtpService otpService;
    private final OAuthService oauthService;
    private final OAuthStateService oauthStateService;
    private final PkceService pkceService;
    private final IdTokenService idTokenService;
    private final RedirectUrlValidator redirectUrlValidator;
    private final ResourceLoader resourceLoader;
    private final TokenGenerator tokenGenerator;
    private final AuthConfig authConfig;

    /**
     * Sign up a new user
     * POST /auth/v1/signup
     */
    @PostMapping("/signup")
    public ResponseEntity<AuthResponse> signUp(@Valid @RequestBody SignUpRequest request,@RequestParam(value = "redirect_to", required = false) String redirectTo) {
        if(StringUtils.isNotBlank(redirectTo)){
            request.setRedirectTo(redirectTo);
        }
        AuthResponse response = authService.signUp(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Token endpoint - handles both password and refresh_token grant types
     * POST /auth/v1/token?grant_type=password
     * POST /auth/v1/token?grant_type=refresh_token
     */
    @PostMapping("/token")
    public ResponseEntity<AuthResponse> token(HttpServletRequest request,
            @RequestParam("grant_type") String grantType,
            @RequestBody Map<String, Object> body) {
        return switch (grantType) {
            case "password" -> {
                SignInRequest signInRequest = new SignInRequest(
                        (String) body.get("email"),
                        (String)body.get("password")
                );
                AuthResponse response = authService.signIn(signInRequest);
                yield ResponseEntity.ok(response);
            }
            case "refresh_token" -> {
                RefreshTokenRequest refreshRequest = new RefreshTokenRequest(
                        (String) body.get("refresh_token")
                );
                AuthResponse response = authService.refreshToken(refreshRequest);
                yield ResponseEntity.ok(response);
            }
            case "pkce" -> {
                // Exchange a PKCE auth code (from OAuth / magic-link / SAML) for a session.
                AuthResponse response = pkceService.exchangeCodeForSession(
                        (String) body.get("auth_code"),
                        (String) body.get("code_verifier"));
                yield ResponseEntity.ok(response);
            }
            case "id_token" -> {
                // Native social sign-in with a verified third-party OIDC ID token.
                String provider = (String) body.get("provider");
                String idToken = body.get("id_token") != null
                        ? (String) body.get("id_token") : (String) body.get("token");
                String nonce = (String) body.get("nonce");
                var userInfo = idTokenService.verify(provider, idToken, nonce);
                AuthResponse response = oauthService.signInWithProviderInfo(userInfo);
                yield ResponseEntity.ok(response);
            }
            default -> throw new IllegalArgumentException("Unsupported grant_type: " + grantType);
        };
    }

    /**
     * Request password recovery
     * POST /auth/v1/recover
     */
    @PostMapping("/recover")
    public ResponseEntity<Map<String, Object>> recover(@Valid @RequestBody RecoverRequest request) {
        authService.recoverPassword(request.getEmail(), request.getCaptchaToken());
        // Return empty object (Supabase behavior)
        return ResponseEntity.ok(Map.of());
    }

    /**
     * Passwordless sign-in: magic link / email OTP / phone (SMS) OTP.
     * POST /auth/v1/otp
     */
    @PostMapping("/otp")
    public ResponseEntity<Map<String, Object>> otp(@RequestBody OtpRequest request) {
        boolean shouldCreate = request.getCreateUser() == null || request.getCreateUser();
        if (StringUtils.isNotBlank(request.getEmail())) {
            otpService.signInWithEmailOtp(request.getEmail(), shouldCreate,
                    request.getCaptchaToken(), request.getRedirectTo(),
                    request.getCodeChallenge(), request.getCodeChallengeMethod());
        } else if (StringUtils.isNotBlank(request.getPhone())) {
            otpService.signInWithPhoneOtp(request.getPhone(), shouldCreate, request.getCaptchaToken());
        } else {
            throw new IllegalArgumentException("Either email or phone is required");
        }
        // Supabase returns an empty object; tokens are obtained via /verify.
        return ResponseEntity.ok(Map.of());
    }

    /**
     * Resend a signup confirmation or OTP.
     * POST /auth/v1/resend
     */
    @PostMapping("/resend")
    public ResponseEntity<Map<String, Object>> resend(@Valid @RequestBody ResendRequest request) {
        otpService.resend(request.getType(), request.getEmail(), request.getPhone());
        return ResponseEntity.ok(Map.of());
    }

    /**
     * Begin a reauthentication flow (issues a nonce for sensitive operations).
     * GET /auth/v1/reauthenticate  (requires a valid Bearer token)
     */
    @GetMapping("/reauthenticate")
    public ResponseEntity<Map<String, Object>> reauthenticate(@AuthenticationPrincipal User user) {
        authService.reauthenticate(user);
        return ResponseEntity.ok(Map.of());
    }

    /**
     * Verify a token via JSON (POST), returning the session. Handles password-flow
     * tokens (signup/recovery) and passwordless tokens (magiclink/email/sms/phone).
     * POST /auth/v1/verify
     */
    @PostMapping("/verify")
    public ResponseEntity<AuthResponse> verifyPost(@RequestBody VerifyRequest request) {
        AuthResponse response = resolveVerify(
                request.getType(), request.getToken(), request.getEmail(), request.getPhone());
        return ResponseEntity.ok(response);
    }

    /**
     * Verify email or phone with token
     * GET /auth/v1/verify
     */
    @GetMapping("/verify")
    public ResponseEntity<String> verify(@Valid VerifyRequest request) {
        try {
            // Open-redirect guard: only honour an allow-listed redirect_to (else fall back to HTML).
            request.setRedirect_to(redirectUrlValidator.sanitize(request.getRedirect_to()));
            // PKCE magic-link / OTP: redirect back with a one-time ?code= to be exchanged.
            if (isPasswordlessType(request.getType())) {
                OtpService.VerifyResult result = otpService.verifyFlow(
                        request.getType(), request.getToken(), request.getEmail(), request.getPhone());
                if (result.isPkce()) {
                    if (StringUtils.isNotBlank(request.getRedirect_to())) {
                        String redirectUrl = UriComponentsBuilder.fromUriString(request.getRedirect_to())
                                .queryParam("code", result.pkceAuthCode())
                                .build().toUriString();
                        HttpHeaders headers = new HttpHeaders();
                        headers.setLocation(URI.create(redirectUrl));
                        return ResponseEntity.status(HttpStatus.SEE_OTHER).headers(headers).build();
                    }
                    return ResponseEntity.ok().contentType(MediaType.TEXT_HTML)
                            .body("<html><body><p>code=" + result.pkceAuthCode() + "</p></body></html>");
                }
                return verifyHtmlOrRedirect(result.session(), request);
            }

            // Verify the token and get auth response
            AuthResponse response = resolveVerify(
                    request.getType(), request.getToken(), request.getEmail(), request.getPhone());
            return verifyHtmlOrRedirect(response, request);
        } catch (Exception e) {
            // Return error HTML page instead of JSON
            try {
                Resource resource = resourceLoader.getResource("classpath:templates/verify-error.html");
                String htmlContent = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                htmlContent = htmlContent.replace("{{ERROR_MESSAGE}}", HtmlEscape.escape(e.getMessage()));
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.TEXT_HTML)
                        .body(htmlContent);
            } catch (IOException ioException) {
                throw new RuntimeException("Failed to load verification error page", ioException);
            }
        }
    }

    /**
     * Logout user
     * POST /auth/v1/logout
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal User user) {
        authService.logout(user);
        return ResponseEntity.noContent().build();
    }

    /**
     * Health check endpoint
     * GET /health
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "service", "nubase",
                "version", "1.0.0"
        ));
    }

    /**
     * OAuth Authorization Endpoint
     * GET /auth/v1/authorize?provider=google&redirect_to=http://localhost:3000/callback
     *
     * Initiates OAuth flow by redirecting to the provider's authorization page
     * Stores state data (provider, apikey, redirectTo) in Redis for callback validation
     */
    @GetMapping("/authorize")
    public ResponseEntity<?> authorize(
            HttpServletRequest request,
            @RequestParam String provider,
            @RequestParam(name = "redirect_to", required = false) String redirectTo,
            @RequestParam(name = "code_challenge", required = false) String codeChallenge,
            @RequestParam(name = "code_challenge_method", required = false) String codeChallengeMethod) {
        return startOAuthFlow(request, provider, redirectTo, codeChallenge, codeChallengeMethod, null);
    }

    /**
     * Manual identity linking: start an OAuth flow that LINKS the resulting provider identity to
     * the currently-authenticated user (rather than finding/creating a user).
     * GET /auth/v1/user/identities/authorize?provider=...  (requires a Bearer token)
     */
    @GetMapping("/user/identities/authorize")
    public ResponseEntity<?> authorizeLinkIdentity(
            HttpServletRequest request,
            @AuthenticationPrincipal User user,
            @RequestParam String provider,
            @RequestParam(name = "redirect_to", required = false) String redirectTo) {
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return startOAuthFlow(request, provider, redirectTo, null, null, user.getId().toString());
    }

    private ResponseEntity<?> startOAuthFlow(HttpServletRequest request, String provider, String redirectTo,
                                             String codeChallenge, String codeChallengeMethod, String linkUserId) {
        try {
            String state = tokenGenerator.generateSecureToken();
            String apikey = MultiTenancyContext.getApiKey();

            OAuthStateData stateData = OAuthStateData.builder()
                    .provider(provider)
                    .apikey(apikey)
                    .redirectTo(redirectTo)
                    .codeChallenge(codeChallenge)
                    .codeChallengeMethod(codeChallengeMethod)
                    .linkUserId(linkUserId)
                    .createdAt(System.currentTimeMillis())
                    .build();

            oauthStateService.saveState(state, stateData);

            String callbackUrl = buildCallbackUrl(request);
            String authUrl = oauthService.getAuthorizationUrl(provider, callbackUrl, state);

            HttpHeaders headers = new HttpHeaders();
            headers.setLocation(URI.create(authUrl));
            return ResponseEntity.status(HttpStatus.FOUND).headers(headers).build();

        } catch (IllegalStateException e) {
            // Return error HTML when OAuth is not configured
            try {
                Resource resource = resourceLoader.getResource("classpath:templates/oauth-config-error.html");
                String htmlContent = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                htmlContent = htmlContent.replace("{{ERROR_MESSAGE}}", HtmlEscape.escape(e.getMessage()));
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.TEXT_HTML)
                        .body(htmlContent);
            } catch (IOException ioException) {
                throw new RuntimeException("Failed to load OAuth configuration error page", ioException);
            }
        }
    }

    /**
     * OAuth Callback Endpoint
     * GET /auth/v1/callback?code=xxx&state=xxx
     *
     * Handles OAuth provider callback and completes authentication
     * Validates state from Redis and uses stored context information
     */
    @GetMapping("/callback")
    public ResponseEntity<String> callback(
            HttpServletRequest request,
            @RequestParam String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error,
            @RequestParam(name = "error_description", required = false) String errorDescription) {

        // Handle OAuth error
        if (error != null) {
            String errorHtml = String.format(
                    "<html><body><h1>Authentication Failed</h1><p>%s: %s</p></body></html>",
                    HtmlEscape.escape(error), HtmlEscape.escape(errorDescription)
            );
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.TEXT_HTML)
                    .body(errorHtml);
        }

        // Validate state parameter
        if (state == null || state.isEmpty()) {
            String errorHtml = "<html><body><h1>Authentication Failed</h1><p>Invalid state parameter</p></body></html>";
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.TEXT_HTML)
                    .body(errorHtml);
        }

        try {
            // Retrieve and validate state data from Redis
            OAuthStateData stateData = oauthStateService.getAndRemoveState(state);
            if (stateData == null) {
                String errorHtml = "<html><body><h1>Authentication Failed</h1><p>State expired or invalid. Please try again.</p></body></html>";
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.TEXT_HTML)
                        .body(errorHtml);
            }

            // Set tenant context from state data
            MultiTenancyContext.getContext().setApikey(stateData.getApikey());

            // Get provider from state data
            String provider = stateData.getProvider();
            // Open-redirect guard before bouncing the browser to the supplied target.
            String redirectTo = redirectUrlValidator.sanitize(stateData.getRedirectTo());

            // Build callback URL dynamically from current request (must match the one used in authorize)
            String callbackUrl = buildCallbackUrl(request);

            // PKCE: when the client started the flow with a code_challenge, complete the login
            // but hand back a one-time auth code (?code=) instead of tokens-in-fragment.
            if (StringUtils.isNotBlank(stateData.getCodeChallenge())) {
                var signIn = oauthService.resolveCallback(provider, code, callbackUrl, false);
                String authCode = pkceService.issueAuthCode(
                        signIn.user(), stateData.getCodeChallenge(), stateData.getCodeChallengeMethod(),
                        "oauth", ai.nubase.auth.entity.MfaAmrClaim.METHOD_OAUTH);
                if (StringUtils.isNotBlank(redirectTo)) {
                    String redirectUrl = UriComponentsBuilder.fromUriString(redirectTo)
                            .queryParam("code", authCode)
                            .build().toUriString();
                    HttpHeaders headers = new HttpHeaders();
                    headers.setLocation(URI.create(redirectUrl));
                    return ResponseEntity.status(HttpStatus.SEE_OTHER).headers(headers).build();
                }
                return ResponseEntity.ok()
                        .contentType(MediaType.TEXT_HTML)
                        .body("<html><body><p>code=" + HtmlEscape.escape(authCode) + "</p></body></html>");
            }

            // Manual identity linking: attach the resolved identity to the existing user.
            AuthResponse authResponse;
            if (StringUtils.isNotBlank(stateData.getLinkUserId())) {
                authResponse = oauthService.linkIdentity(
                        provider, code, callbackUrl, java.util.UUID.fromString(stateData.getLinkUserId()));
            } else {
                // Exchange code for user info and create/login user
                authResponse = oauthService.handleCallback(provider, code, callbackUrl);
            }

            // If redirectTo is provided, redirect with tokens in fragment
            if (StringUtils.isNotBlank(redirectTo)) {
                String redirectUrl = UriComponentsBuilder.fromUriString(redirectTo)
                        .fragment("access_token=" + authResponse.getAccessToken() +
                                "&refresh_token=" + authResponse.getRefreshToken() +
                                "&expires_in=" + authResponse.getExpiresIn() +
                                "&token_type=" + authResponse.getTokenType() +
                                "&type=signup")
                        .build()
                        .toUriString();

                HttpHeaders headers = new HttpHeaders();
                headers.setLocation(URI.create(redirectUrl));
                return ResponseEntity.status(HttpStatus.SEE_OTHER).headers(headers).build();
            }

            // Otherwise, return success HTML page with tokens
            String successHtml = String.format(
                    "<html><body>" +
                    "<h1>Authentication Successful!</h1>" +
                    "<p>Access Token: %s</p>" +
                    "<p>Refresh Token: %s</p>" +
                    "<script>window.opener.postMessage({type:'oauth',access_token:'%s',refresh_token:'%s'},'*');window.close();</script>" +
                    "</body></html>",
                    authResponse.getAccessToken(),
                    authResponse.getRefreshToken(),
                    authResponse.getAccessToken(),
                    authResponse.getRefreshToken()
            );

            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_HTML)
                    .body(successHtml);

        } catch (Exception e) {
            String errorHtml = String.format(
                    "<html><body><h1>Authentication Failed</h1><p>%s</p></body></html>",
                    HtmlEscape.escape(e.getMessage())
            );
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.TEXT_HTML)
                    .body(errorHtml);
        }
    }

    /**
     * Route a verification request to the correct flow based on its type.
     * Password-flow tokens (signup/recovery/email_change) are stored on the user row;
     * passwordless tokens (magiclink/email/sms/phone) live in auth.one_time_tokens.
     */
    private AuthResponse resolveVerify(String type, String token, String email, String phone) {
        return switch (type) {
            case "signup", "recovery", "email_change", "invite" ->
                    authService.verify(type, token, email);
            case "magiclink", "email", "otp", "sms", "phone", "phone_change" ->
                    otpService.verify(type, token, email, phone);
            default -> throw new IllegalArgumentException("Unsupported verification type: " + type);
        };
    }

    private boolean isPasswordlessType(String type) {
        return switch (type) {
            case "magiclink", "email", "otp", "sms", "phone", "phone_change" -> true;
            default -> false;
        };
    }

    /** Render the verify outcome as a token-fragment redirect or a success HTML page. */
    private ResponseEntity<String> verifyHtmlOrRedirect(AuthResponse response, VerifyRequest request) {
        if (StringUtils.isNotBlank(request.getRedirect_to())) {
            String redirectUrl = UriComponentsBuilder.fromUriString(request.getRedirect_to())
                    .fragment("access_token=" + response.getAccessToken() +
                            "&refresh_token=" + response.getRefreshToken() +
                            "&expires_in=" + response.getExpiresIn() +
                            "&token_type=" + response.getTokenType() +
                            "&type=" + request.getType())
                    .build()
                    .toUriString();

            HttpHeaders headers = new HttpHeaders();
            headers.setLocation(URI.create(redirectUrl));
            return ResponseEntity.status(HttpStatus.SEE_OTHER).headers(headers).build();
        }

        try {
            Resource resource = resourceLoader.getResource("classpath:templates/verify-success.html");
            String htmlContent = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_HTML)
                    .body(htmlContent);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load verification success page", e);
        }
    }

    /**
     * Build callback URL dynamically from the current request
     * This supports multi-tenancy with subdomains (e.g., app123.nubase.co)
     *
     * @param request Current HTTP request
     * @return Full callback URL (e.g., https://app123.nubase.co/auth/v1/callback)
     */
    private String buildCallbackUrl(HttpServletRequest request) {
        String scheme = request.getHeader("X-Forwarded-Proto");
        if (StringUtils.isBlank(scheme)) {
            scheme = request.getScheme();
        }
        String serverName = request.getServerName();
        String forwardedPort = request.getHeader("X-Forwarded-Port");
        int serverPort = StringUtils.isNotBlank(forwardedPort)
                ? Integer.parseInt(forwardedPort)
                : request.getServerPort();
        StringBuilder callbackUrl = new StringBuilder();
        callbackUrl.append(scheme).append("://").append(serverName);
        if (shouldAppendPort(scheme, serverPort)) {
            callbackUrl.append(":").append(serverPort);
        }
        callbackUrl.append("/auth/v1/callback");

        return callbackUrl.toString();
    }

    private boolean shouldAppendPort(String scheme, int port) {
        if (port <= 0) {
            return false;
        }
        if ("http".equalsIgnoreCase(scheme) && port == 80) {
            return false;
        }
        if ("https".equalsIgnoreCase(scheme) && port == 443) {
            return false;
        }
        return true;
    }


}
