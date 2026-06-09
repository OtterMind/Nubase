package ai.nubase.auth.service;

import ai.nubase.auth.dto.response.platform.PlatformAuthResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * OAuth login for platform (Studio) developer accounts.
 * Providers are configured via env and auto-hidden when unset:
 *   NUBASE_PLATFORM_OAUTH_GOOGLE_CLIENT_ID      (GIS button + One Tap; no secret needed)
 *   NUBASE_PLATFORM_OAUTH_GOOGLE_CLIENT_SECRET  (optional; adds the authorization-code redirect)
 *   NUBASE_PLATFORM_OAUTH_GITHUB_CLIENT_ID / NUBASE_PLATFORM_OAUTH_GITHUB_CLIENT_SECRET
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PlatformOAuthService {

    private final PlatformAuthService platformAuthService;
    private final RestTemplate http = new RestTemplate();

    @Value("${nubase.platform.oauth.google.client-id:}")
    private String googleClientId;

    @Value("${nubase.platform.oauth.google.client-secret:}")
    private String googleClientSecret;

    @Value("${nubase.platform.oauth.github.client-id:}")
    private String githubClientId;

    @Value("${nubase.platform.oauth.github.client-secret:}")
    private String githubClientSecret;

    public boolean googleEnabled() {
        return googleClientId != null && !googleClientId.isBlank();
    }

    /** Authorization-code redirect login needs the secret too (GIS / One Tap do not). */
    public boolean googleCodeEnabled() {
        return googleEnabled()
                && googleClientSecret != null && !googleClientSecret.isBlank();
    }

    public boolean githubEnabled() {
        return githubClientId != null && !githubClientId.isBlank()
                && githubClientSecret != null && !githubClientSecret.isBlank();
    }

    public String googleClientId() {
        return googleClientId == null ? "" : googleClientId.trim();
    }

    // ---------------------------------------------------------------- Google
    /** Verify a Google Identity Services credential (ID token from the GIS button or One Tap). */
    public PlatformAuthResponse loginWithGoogleCredential(String credential) {
        if (!googleEnabled()) {
            throw new IllegalStateException("Google login is not configured");
        }
        if (credential == null || credential.isBlank()) {
            throw new IllegalArgumentException("Missing Google credential");
        }
        return verifyGoogleIdToken(credential);
    }

    /** Authorization-code redirect (GitHub-style): exchange the code, then verify the ID token. */
    public PlatformAuthResponse loginWithGoogleCode(String code, String redirectUri) {
        if (!googleCodeEnabled()) {
            throw new IllegalStateException("Google authorization-code login is not configured");
        }
        HttpHeaders th = new HttpHeaders();
        th.setAccept(List.of(MediaType.APPLICATION_JSON));
        th.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", googleClientId.trim());
        form.add("client_secret", googleClientSecret.trim());
        form.add("code", code);
        form.add("redirect_uri", redirectUri);
        form.add("grant_type", "authorization_code");
        Map<?, ?> tok = http.postForObject(
                "https://oauth2.googleapis.com/token", new HttpEntity<>(form, th), Map.class);
        String idToken = tok == null ? null : str(tok.get("id_token"));
        if (idToken == null || idToken.isBlank()) {
            throw new IllegalArgumentException("Google token exchange failed");
        }
        return verifyGoogleIdToken(idToken);
    }

    public String googleAuthorizeUrl(String state, String redirectUri) {
        return "https://accounts.google.com/o/oauth2/v2/auth"
                + "?client_id=" + enc(googleClientId.trim())
                + "&redirect_uri=" + enc(redirectUri)
                + "&response_type=code"
                + "&scope=" + enc("openid email profile")
                + "&state=" + enc(state)
                + "&access_type=online"
                + "&prompt=select_account";
    }

    /** Validate a Google-issued ID token via the public tokeninfo endpoint and sign the user in. */
    private PlatformAuthResponse verifyGoogleIdToken(String idToken) {
        Map<?, ?> info;
        try {
            info = http.getForObject(
                    "https://oauth2.googleapis.com/tokeninfo?id_token=" + enc(idToken), Map.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid Google credential");
        }
        if (info == null) {
            throw new IllegalArgumentException("Invalid Google credential");
        }
        if (!googleClientId.trim().equals(str(info.get("aud")))) {
            throw new IllegalArgumentException("Google credential audience mismatch");
        }
        String iss = str(info.get("iss"));
        if (iss == null || !iss.contains("accounts.google.com")) {
            throw new IllegalArgumentException("Unexpected Google token issuer");
        }
        if (!"true".equalsIgnoreCase(str(info.get("email_verified")))) {
            throw new IllegalArgumentException("Google email is not verified");
        }
        return platformAuthService.oauthSignIn(str(info.get("email")), str(info.get("name")));
    }

    // ---------------------------------------------------------------- GitHub
    public String githubAuthorizeUrl(String state, String redirectUri) {
        return "https://github.com/login/oauth/authorize"
                + "?client_id=" + enc(githubClientId.trim())
                + "&redirect_uri=" + enc(redirectUri)
                + "&scope=" + enc("read:user user:email")
                + "&state=" + enc(state);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public PlatformAuthResponse loginWithGithubCode(String code, String redirectUri) {
        if (!githubEnabled()) {
            throw new IllegalStateException("GitHub login is not configured");
        }
        // 1. Exchange the authorization code for an access token.
        HttpHeaders th = new HttpHeaders();
        th.setAccept(List.of(MediaType.APPLICATION_JSON));
        th.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", githubClientId.trim());
        form.add("client_secret", githubClientSecret.trim());
        form.add("code", code);
        form.add("redirect_uri", redirectUri);
        Map tok = http.postForObject(
                "https://github.com/login/oauth/access_token", new HttpEntity<>(form, th), Map.class);
        String accessToken = tok == null ? null : str(tok.get("access_token"));
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException("GitHub token exchange failed");
        }
        // 2. Fetch the user profile and email.
        HttpHeaders uh = new HttpHeaders();
        uh.setBearerAuth(accessToken);
        uh.set("User-Agent", "nubase");
        uh.setAccept(List.of(MediaType.APPLICATION_JSON));
        Map user = http.exchange("https://api.github.com/user", HttpMethod.GET,
                new HttpEntity<>(uh), Map.class).getBody();
        String name = user == null ? null : str(user.get("name"));
        if (name == null || name.isBlank()) {
            name = user == null ? null : str(user.get("login"));
        }
        String email = user == null ? null : str(user.get("email"));
        if (email == null || email.isBlank()) {
            List emails = http.exchange("https://api.github.com/user/emails", HttpMethod.GET,
                    new HttpEntity<>(uh), List.class).getBody();
            if (emails != null) {
                for (Object o : emails) {
                    Map m = (Map) o;
                    if (Boolean.TRUE.equals(m.get("primary")) && Boolean.TRUE.equals(m.get("verified"))) {
                        email = str(m.get("email"));
                        break;
                    }
                }
            }
        }
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("GitHub account has no verified email");
        }
        return platformAuthService.oauthSignIn(email, name);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }
}
