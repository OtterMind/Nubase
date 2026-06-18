package ai.nubase.auth.oauth;

import ai.nubase.common.config.oauth.OAuthProperties;
import ai.nubase.auth.dto.oauth.OAuthUserInfo;
import ai.nubase.common.context.MultiTenancyContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * GitHub OAuth 2.0 Provider Implementation
 * https://docs.github.com/en/developers/apps/building-oauth-apps/authorizing-oauth-apps
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GitHubOAuthProvider implements OAuthProvider {

    private static final String AUTHORIZATION_URL = "https://github.com/login/oauth/authorize";
    private static final String TOKEN_URL = "https://github.com/login/oauth/access_token";
    private static final String USER_INFO_URL = "https://api.github.com/user";
    private static final String USER_EMAIL_URL = "https://api.github.com/user/emails";
    private static final String SCOPE = "read:user user:email";

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getProviderName() {
        return "github";
    }

    @Override
    public String getAuthorizationUrl(String redirectUri, String state) {
        OAuthProperties.ProviderConfig config = getProviderConfig();

        return UriComponentsBuilder.fromHttpUrl(AUTHORIZATION_URL)
                .queryParam("client_id", config.getClientId())
                .queryParam("redirect_uri", redirectUri)
                .queryParam("scope", SCOPE)
                .queryParam("state", state)
                .build()
                .encode()  // Encode URL parameters, converting spaces to %20
                .toUriString();
    }

    @Override
    public OAuthUserInfo getUserInfo(String code, String redirectUri) {
        try {
            // Step 1: Exchange code for access token
            String accessToken = exchangeCodeForToken(code, redirectUri);

            // Step 2: Get user info using access token
            return fetchUserInfo(accessToken);

        } catch (Exception e) {
            log.error("Failed to get GitHub user info: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to authenticate with GitHub: " + e.getMessage(), e);
        }
    }

    private String exchangeCodeForToken(String code, String redirectUri) throws Exception {
        OAuthProperties.ProviderConfig config = getProviderConfig();

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("code", code);
        params.add("client_id", config.getClientId());
        params.add("client_secret", config.getClientSecret());
        params.add("redirect_uri", redirectUri);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(params, headers);

        ResponseEntity<String> response = restTemplate.exchange(
                TOKEN_URL,
                HttpMethod.POST,
                request,
                String.class
        );

        JsonNode jsonNode = objectMapper.readTree(response.getBody());
        return jsonNode.get("access_token").asText();
    }

    private OAuthUserInfo fetchUserInfo(String accessToken) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.set("User-Agent", "Supabase-Auth");

        HttpEntity<String> request = new HttpEntity<>(headers);

        // Get basic user info
        ResponseEntity<String> userResponse = restTemplate.exchange(
                USER_INFO_URL,
                HttpMethod.GET,
                request,
                String.class
        );

        JsonNode userInfo = objectMapper.readTree(userResponse.getBody());

        // 与 PlatformOAuthService 一致：只采用 GitHub /user/emails 中 verified 的地址，不信任公开 profile email。
        String email = null;
        boolean emailVerified = false;
        try {
            ResponseEntity<String> emailResponse = restTemplate.exchange(
                    USER_EMAIL_URL,
                    HttpMethod.GET,
                    request,
                    String.class
            );

            JsonNode emails = objectMapper.readTree(emailResponse.getBody());
            if (emails.isArray()) {
                String firstVerified = null;
                for (JsonNode emailNode : emails) {
                    if (!emailNode.has("verified") || !emailNode.get("verified").asBoolean()) {
                        continue;
                    }
                    String candidate = emailNode.get("email").asText();
                    if (emailNode.has("primary") && emailNode.get("primary").asBoolean()) {
                        email = candidate;
                        emailVerified = true;
                        break;
                    }
                    if (firstVerified == null) {
                        firstVerified = candidate;
                    }
                }
                if (email == null && firstVerified != null) {
                    email = firstVerified;
                    emailVerified = true;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to fetch GitHub user emails: {}", e.getMessage());
        }

        return OAuthUserInfo.builder()
                .providerId(String.valueOf(userInfo.get("id").asLong()))
                .provider("github")
                .email(email)
                .emailVerified(emailVerified)
                .name(userInfo.has("name") && !userInfo.get("name").isNull()
                        ? userInfo.get("name").asText()
                        : userInfo.get("login").asText())
                .avatarUrl(userInfo.has("avatar_url") ? userInfo.get("avatar_url").asText() : null)
                .rawData(userResponse.getBody())
                .build();
    }

    private OAuthProperties.ProviderConfig getProviderConfig() {
        OAuthProperties properties = MultiTenancyContext.getOAuthProperties();
        if (properties == null || properties.getProviders() == null) {
            throw new IllegalStateException("OAuth properties not configured, please check your configuration.");
        }
        OAuthProperties.ProviderConfig config = properties.getProviders().get("github");
        if (config == null || !config.isEnabled()) {
            throw new IllegalStateException("GitHub OAuth provider not configured, please check your configuration.");
        }
        return config;
    }
}
