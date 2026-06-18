package ai.nubase.auth.service;

import ai.nubase.common.config.AuthConfig;
import ai.nubase.auth.dto.oauth.OAuthUserInfo;
import ai.nubase.auth.dto.response.AuthResponse;
import ai.nubase.auth.entity.Identity;
import ai.nubase.auth.entity.RefreshToken;
import ai.nubase.auth.entity.Session;
import ai.nubase.auth.entity.User;
import ai.nubase.auth.oauth.OAuthProvider;
import ai.nubase.auth.repository.IdentityRepository;
import ai.nubase.auth.repository.SessionRepository;
import ai.nubase.auth.repository.UserRepository;
import ai.nubase.auth.util.UserMapper;
import ai.nubase.common.enums.Role;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * OAuth Service
 * Manages OAuth authentication flow and user creation/login
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OAuthService {

    private final Map<String, OAuthProvider> providers;
    private final UserRepository userRepository;
    private final IdentityRepository identityRepository;
    private final SessionRepository sessionRepository;
    private final JwtSecretService jwtSecretService;
    private final TokenService tokenService;
    private final UserMapper userMapper;
    private final AuthConfig authConfig;
    private final EffectiveAuthConfig effectiveAuthConfig;
    private final AuthResponseFactory authResponseFactory;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Get authorization URL for OAuth provider
     */
    public String getAuthorizationUrl(String providerName, String redirectUri, String state) {
        OAuthProvider provider = getProvider(providerName);
        return provider.getAuthorizationUrl(redirectUri, state);
    }

    /**
     * Handle OAuth callback and sign in/up user
     */
    @Transactional
    public AuthResponse handleCallback(String providerName, String code, String redirectUri) {
        log.info("Handling OAuth callback for provider: {}", providerName);

        // Get user info from OAuth provider
        OAuthProvider provider = getProvider(providerName);
        OAuthUserInfo oauthUserInfo = provider.getUserInfo(code, redirectUri);

        log.debug("OAuth user info received: provider={}, email={}", providerName, oauthUserInfo.getEmail());

        // Find or create user
        User user = findOrCreateUser(oauthUserInfo);

        // Find or create identity
        Identity identity = findOrCreateIdentity(user, oauthUserInfo);

        // Update last sign in
        identity.setLastSignInAt(Instant.now());
        identityRepository.save(identity);

        user.setLastSignInAt(Instant.now());
        userRepository.save(user);

        // Create session and generate tokens
        Session session = createSession(user);
        String accessToken = jwtSecretService.generateToken(user, session);
        RefreshToken refreshToken = tokenService.generateRefreshToken(user, session);

        log.info("OAuth login successful for user: {} (provider: {})", user.getEmail(), providerName);

        // Build response
        List<Identity> identities = identityRepository.findByUserId(user.getId());
        return AuthResponse.builder()
                .accessToken(accessToken)
                .tokenType("bearer")
                .expiresIn(authConfig.getJwt().getExpiration())
                .refreshToken(refreshToken.getToken())
                .user(userMapper.toUserResponse(user, identities))
                .build();
    }

    /**
     * Exchange an OAuth {@code code} for provider user info and find-or-create the user,
     * optionally issuing tokens. With {@code issueTokens=false} the caller gets the resolved
     * user but no session — used by the PKCE flow, which instead issues a one-time auth code.
     */
    @Transactional
    public ProviderSignIn resolveCallback(String providerName, String code, String redirectUri, boolean issueTokens) {
        OAuthProvider provider = getProvider(providerName);
        OAuthUserInfo info = provider.getUserInfo(code, redirectUri);
        return signInWithProviderInfo(info, issueTokens);
    }

    /**
     * Find-or-create a user + identity from already-resolved provider info and issue a
     * session. Used by the {@code id_token} grant (native social sign-in) where the client
     * supplies a verified ID token instead of going through the redirect callback.
     *
     * @return both the {@link User} and the populated {@link AuthResponse}
     */
    @Transactional
    public AuthResponse signInWithProviderInfo(OAuthUserInfo oauthUserInfo) {
        return signInWithProviderInfo(oauthUserInfo, true).response();
    }

    /** Result holder so callers (e.g. PKCE) can also reach the resolved user. */
    public record ProviderSignIn(User user, AuthResponse response) {}

    @Transactional
    public ProviderSignIn signInWithProviderInfo(OAuthUserInfo oauthUserInfo, boolean issueTokens) {
        User user = findOrCreateUser(oauthUserInfo);
        Identity identity = findOrCreateIdentity(user, oauthUserInfo);
        identity.setLastSignInAt(Instant.now());
        identityRepository.save(identity);
        user.setLastSignInAt(Instant.now());
        user = userRepository.save(user);

        AuthResponse response = issueTokens
                ? authResponseFactory.newSignIn(user, ai.nubase.auth.entity.MfaAmrClaim.METHOD_OAUTH)
                : null;
        return new ProviderSignIn(user, response);
    }

    /**
     * Link a freshly-authenticated provider identity to an EXISTING user (manual identity
     * linking). Refuses if the identity already belongs to a different user. Issues a session
     * for the (now multi-identity) user.
     */
    @Transactional
    public AuthResponse linkIdentity(String providerName, String code, String redirectUri, java.util.UUID linkUserId) {
        OAuthProvider provider = getProvider(providerName);
        OAuthUserInfo info = provider.getUserInfo(code, redirectUri);

        Optional<Identity> existing = identityRepository
                .findByProviderAndProviderId(info.getProvider(), info.getProviderId());
        if (existing.isPresent() && !existing.get().getUser().getId().equals(linkUserId)) {
            throw new RuntimeException("This " + providerName + " account is already linked to another user");
        }

        User user = userRepository.findById(linkUserId)
                .orElseThrow(() -> new RuntimeException("User to link not found"));

        Identity identity = findOrCreateIdentity(user, info);
        identity.setLastSignInAt(Instant.now());
        identityRepository.save(identity);

        mergeProviderMetadata(user, info.getProvider());
        user.setLastSignInAt(Instant.now());
        user = userRepository.save(user);

        return authResponseFactory.newSignIn(user, ai.nubase.auth.entity.MfaAmrClaim.METHOD_OAUTH);
    }

    /**
     * Find existing user or create new one
     */
    private User findOrCreateUser(OAuthUserInfo oauthUserInfo) {
        Optional<Identity> existingIdentity = identityRepository
                .findByProviderAndProviderId(oauthUserInfo.getProvider(), oauthUserInfo.getProviderId());
        if (existingIdentity.isPresent()) {
            log.debug("Found existing user by identity: provider={}, providerId={}",
                    oauthUserInfo.getProvider(), oauthUserInfo.getProviderId());
            return existingIdentity.get().getUser();
        }

        Optional<User> existingUser = Optional.empty();
        if (StringUtils.isNotBlank(oauthUserInfo.getEmail())) {
            existingUser = userRepository.findByEmail(oauthUserInfo.getEmail());
        }

        if (existingUser.isPresent()) {
            // 与新建用户路径一致：仅 IdP 已验证邮箱才允许按 email 自动合并，避免未验证邮箱绑到他人账号。
            // 租户开启邮箱确认时，还要求目标账号已完成确认，防止 OAuth 绕过密码注册的确认流程。
            if (!oauthUserInfo.isEmailVerified()) {
                throw new RuntimeException(
                        "OAuth email is not verified by the provider; cannot link to an existing account");
            }
            User user = existingUser.get();
            if (effectiveAuthConfig.emailConfirmationRequired() && user.getEmailConfirmedAt() == null) {
                throw new RuntimeException("Existing account email is not confirmed");
            }
            log.debug("Linking OAuth identity to confirmed user by verified email: {}", oauthUserInfo.getEmail());
            mergeProviderMetadata(user, oauthUserInfo.getProvider());
            return userRepository.save(user);
        }

        log.info("Creating new user from OAuth: provider={}, providerId={}",
                oauthUserInfo.getProvider(), oauthUserInfo.getProviderId());

        User newUser = User.builder()
                .email(oauthUserInfo.getEmail())
                .role(Role.AUTHENTICATED.getValue())
                .aud(Role.AUTHENTICATED.getValue())
                .emailConfirmedAt(StringUtils.isNotBlank(oauthUserInfo.getEmail()) && oauthUserInfo.isEmailVerified()
                        ? Instant.now()
                        : null)
                .rawUserMetaData(createUserMetadata(oauthUserInfo))
                .rawAppMetaData(createAppMetadata(oauthUserInfo.getProvider()))
                .isSuperAdmin(false)
                .isSsoUser(true)
                .build();

        return userRepository.save(newUser);
    }

    /**
     * Find existing identity or create new one
     */
    private Identity findOrCreateIdentity(User user, OAuthUserInfo oauthUserInfo) {
        Optional<Identity> existingIdentity = identityRepository
                .findByProviderAndProviderId(oauthUserInfo.getProvider(), oauthUserInfo.getProviderId());

        if (existingIdentity.isPresent()) {
            log.debug("Found existing identity for provider: {}", oauthUserInfo.getProvider());
            return existingIdentity.get();
        }

        // Create new identity
        log.info("Creating new identity for user {} with provider {}", user.getEmail(), oauthUserInfo.getProvider());

        Map<String, Object> identityData = new HashMap<>();
        identityData.put("sub", oauthUserInfo.getProviderId());
        identityData.put("email", oauthUserInfo.getEmail());
        identityData.put("email_verified", oauthUserInfo.isEmailVerified());
        identityData.put("name", oauthUserInfo.getName());
        identityData.put("avatar_url", oauthUserInfo.getAvatarUrl());

        // Add raw provider data
        try {
            Map<String, Object> rawData = objectMapper.readValue(oauthUserInfo.getRawData(), Map.class);
            identityData.putAll(rawData);
        } catch (Exception e) {
            log.warn("Failed to parse raw OAuth data: {}", e.getMessage());
        }

        Identity identity = Identity.builder()
                .user(user)
                .provider(oauthUserInfo.getProvider())
                .providerId(oauthUserInfo.getProviderId())
                .identityData(identityData)
                .lastSignInAt(Instant.now())
                .build();

        return identityRepository.save(identity);
    }

    private void mergeProviderMetadata(User user, String provider) {
        Map<String, Object> appMetadata = user.getRawAppMetaData() != null
                ? new HashMap<>(user.getRawAppMetaData())
                : new HashMap<>();

        Object providersValue = appMetadata.get("providers");
        List<String> providers = new java.util.ArrayList<>();
        if (providersValue instanceof List<?> providerList) {
            for (Object item : providerList) {
                if (item != null) {
                    providers.add(String.valueOf(item));
                }
            }
        }
        if (!providers.contains(provider)) {
            providers.add(provider);
        }

        appMetadata.put("provider", provider);
        appMetadata.put("providers", providers);
        user.setRawAppMetaData(appMetadata);
    }

    /**
     * Create user metadata from OAuth info
     */
    private Map<String, Object> createUserMetadata(OAuthUserInfo oauthUserInfo) {
        Map<String, Object> metadata = new HashMap<>();
        if (oauthUserInfo.getName() != null) {
            metadata.put("name", oauthUserInfo.getName());
        }
        if (oauthUserInfo.getAvatarUrl() != null) {
            metadata.put("avatar_url", oauthUserInfo.getAvatarUrl());
        }
        metadata.put("provider", oauthUserInfo.getProvider());
        return metadata;
    }

    /**
     * Create app metadata
     */
    private Map<String, Object> createAppMetadata(String provider) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("provider", provider);
        metadata.put("providers", List.of(provider));
        return metadata;
    }

    /**
     * Get OAuth provider by name
     */
    private OAuthProvider getProvider(String providerName) {
        for (OAuthProvider provider : providers.values()) {
            if (provider.getProviderName().equalsIgnoreCase(providerName)) {
                return provider;
            }
        }
        throw new IllegalArgumentException("Unsupported OAuth provider: " + providerName);
    }

    /**
     * Create a new session
     */
    private Session createSession(User user) {
        HttpServletRequest request = getCurrentRequest();

        Instant now = Instant.now();
        Instant notAfter = now.plus(authConfig.getJwt().getExpiration(), ChronoUnit.SECONDS);

        Session session = Session.builder()
                .user(user)
                .aal("aal1")
                .notAfter(notAfter)
                .userAgent(request != null ? request.getHeader("User-Agent") : null)
                .ip(request != null ? getClientIp(request) : null)
                .build();

        return sessionRepository.save(session);
    }

    /**
     * Get current HTTP request
     */
    private HttpServletRequest getCurrentRequest() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attributes != null ? attributes.getRequest() : null;
    }

    /**
     * Get client IP address from request
     */
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty()) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty()) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }
}
