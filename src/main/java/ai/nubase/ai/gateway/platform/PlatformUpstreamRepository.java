package ai.nubase.ai.gateway.platform;

import ai.nubase.common.enums.ApiProvider;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Metadata-DB access for platform-level unified upstreams
 * ({@code public.ai_gateway_platform_upstreams}).
 *
 * <p>The {@code auth_token} is encrypted at rest via {@link PlatformUpstreamCredentialCipher};
 * rows returned from here carry the decrypted token in {@link PlatformUpstream#getAuthToken()} for
 * server-side forwarding only.</p>
 */
@Slf4j
@Repository
public class PlatformUpstreamRepository {

    private static final String COLUMNS =
            "id, name, provider, base_url, auth_token_encrypted, channel_code, supported_models, "
            + "chat_completions_path, is_default, is_active, timeout_ms, max_retries, priority, "
            + "max_input_tokens, description, created_at, updated_at";

    private final JdbcTemplate metadataJdbcTemplate;
    private final PlatformUpstreamCredentialCipher credentialCipher;
    private final ObjectMapper objectMapper;
    private final boolean allowHttp;
    private final boolean allowPrivateNetwork;
    private final HostResolver hostResolver;

    @Autowired
    public PlatformUpstreamRepository(
            @Qualifier("metadataJdbcTemplate") JdbcTemplate metadataJdbcTemplate,
            PlatformUpstreamCredentialCipher credentialCipher,
            ObjectMapper objectMapper,
            @Value("${nubase.ai-gateway.platform-upstream.endpoint-policy.allow-http:false}")
                    boolean allowHttp,
            @Value("${nubase.ai-gateway.platform-upstream.endpoint-policy.allow-private-network:false}")
                    boolean allowPrivateNetwork) {
        this(
                metadataJdbcTemplate,
                credentialCipher,
                objectMapper,
                allowHttp,
                allowPrivateNetwork,
                InetAddress::getAllByName);
    }

    PlatformUpstreamRepository(
            JdbcTemplate metadataJdbcTemplate,
            PlatformUpstreamCredentialCipher credentialCipher,
            ObjectMapper objectMapper) {
        this(
                metadataJdbcTemplate,
                credentialCipher,
                objectMapper,
                false,
                false,
                InetAddress::getAllByName);
    }

    PlatformUpstreamRepository(
            JdbcTemplate metadataJdbcTemplate,
            PlatformUpstreamCredentialCipher credentialCipher,
            ObjectMapper objectMapper,
            boolean allowHttp,
            boolean allowPrivateNetwork,
            HostResolver hostResolver) {
        this.metadataJdbcTemplate = metadataJdbcTemplate;
        this.credentialCipher = credentialCipher;
        this.objectMapper = objectMapper;
        this.allowHttp = allowHttp;
        this.allowPrivateNetwork = allowPrivateNetwork;
        this.hostResolver = hostResolver;
    }

    public List<PlatformUpstream> findAllActive() {
        return metadataJdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM public.ai_gateway_platform_upstreams "
                        + "WHERE is_active = TRUE ORDER BY priority ASC, id ASC",
                mapper);
    }

    /**
     * Reads only non-secret fields required by the public model catalog.
     *
     * <p>This intentionally avoids selecting or decrypting upstream credentials.</p>
     */
    public List<CatalogModelSource> findAllActiveCatalogModels() {
        return metadataJdbcTemplate.query(
                "SELECT provider, supported_models FROM public.ai_gateway_platform_upstreams "
                        + "WHERE is_active = TRUE ORDER BY priority ASC, id ASC",
                (rs, rowNum) -> new CatalogModelSource(
                        ApiProvider.fromString(rs.getString("provider")),
                        fromJson(rs.getString("supported_models"))));
    }

    /**
     * Returns only a readiness decision; no base URL, encrypted token, or model identifier leaves
     * the metadata query. An active row is usable only when every routing prerequisite exists.
     */
    public boolean hasUsableActiveCatalogUpstream() {
        AtomicBoolean available = new AtomicBoolean();
        metadataJdbcTemplate.query("""
                SELECT provider, base_url, auth_token_encrypted, supported_models
                FROM public.ai_gateway_platform_upstreams
                WHERE is_active = TRUE
                ORDER BY priority ASC, id ASC
                """, (RowCallbackHandler) row -> {
                    if (!available.get() && isReadyCandidate(row)) {
                        available.set(true);
                    }
                });
        return available.get();
    }

    private boolean isReadyCandidate(ResultSet row) {
        try {
            String provider = row.getString("provider");
            String baseUrl = row.getString("base_url");
            String encryptedToken = row.getString("auth_token_encrypted");
            List<String> models = fromJson(row.getString("supported_models"));
            if (!knownProvider(provider)
                    || !validBaseUrl(baseUrl)
                    || !credentialCipher.isEncrypted(encryptedToken)
                    || models.stream().noneMatch(PlatformUpstreamRepository::routableModel)) {
                return false;
            }
            String token = credentialCipher.decrypt(encryptedToken);
            return token != null && !token.isBlank();
        } catch (Exception error) {
            log.warn("Platform upstream readiness candidate rejected: errorType={}",
                    error.getClass().getSimpleName());
            return false;
        }
    }

    private static boolean knownProvider(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (ApiProvider provider : ApiProvider.values()) {
            if (provider.name().equalsIgnoreCase(value.trim())) {
                return true;
            }
        }
        return false;
    }

    private boolean validBaseUrl(String value) {
        try {
            validateEndpointPolicy(value);
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static boolean routableModel(String value) {
        return value != null && !value.isBlank() && !"*".equals(value.trim());
    }

    public List<PlatformUpstream> findAll() {
        return metadataJdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM public.ai_gateway_platform_upstreams "
                        + "ORDER BY priority ASC, id ASC",
                mapper);
    }

    public Optional<PlatformUpstream> findById(Long id) {
        List<PlatformUpstream> rows = metadataJdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM public.ai_gateway_platform_upstreams WHERE id = ?",
                mapper, id);
        return rows.stream().findFirst();
    }

    /** Checks row existence without selecting or decrypting the stored credential. */
    public boolean existsById(Long id) {
        Integer count = metadataJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM public.ai_gateway_platform_upstreams WHERE id = ?",
                Integer.class,
                id);
        return count != null && count > 0;
    }

    /**
     * Inserts or updates a platform upstream through the repository's endpoint and credential
     * safety boundary. A blank update token preserves the credential value only when the origin is
     * unchanged; the value is re-encrypted so legacy dedicated ciphertext moves to the versioned
     * dedicated format on the next successful save.
     */
    @Transactional("metadataTransactionManager")
    public PlatformUpstream save(PlatformUpstream u) {
        String normalizedBaseUrl = validateBaseUrl(u.getBaseUrl());
        u.setBaseUrl(normalizedBaseUrl);
        String modelsJson = toJson(u.getSupportedModels());
        String providerName = (u.getProvider() == null ? ApiProvider.CLAUDE : u.getProvider()).name();

        if (u.getId() == null) {
            String encrypted = encrypt(u.getAuthToken());
            if (encrypted == null) {
                throw new IllegalArgumentException(
                        "authToken is required when creating an upstream");
            }
            Long id = metadataJdbcTemplate.queryForObject(
                    "INSERT INTO public.ai_gateway_platform_upstreams "
                            + "(name, provider, base_url, auth_token_encrypted, channel_code, supported_models, "
                            + " chat_completions_path, is_default, is_active, timeout_ms, max_retries, priority, "
                            + " max_input_tokens, description) "
                            + "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id",
                    Long.class,
                    u.getName(), providerName, u.getBaseUrl(), encrypted, u.getChannelCode(), modelsJson,
                    u.getChatCompletionsPath(), bool(u.getIsDefault(), false), bool(u.getIsActive(), true),
                    intOr(u.getTimeoutMs(), 60000), intOr(u.getMaxRetries(), 2), intOr(u.getPriority(), 100),
                    u.getMaxInputTokens(), u.getDescription());
            u.setId(id);
            return findById(id).orElse(u);
        }

        boolean tokenProvided = u.getAuthToken() != null && !u.getAuthToken().isBlank();
        String existingBaseUrl = null;
        String tokenToStore = u.getAuthToken();
        if (!tokenProvided) {
            PlatformUpstream existing = findById(u.getId())
                    .orElseThrow(() -> new IllegalArgumentException("platform upstream not found"));
            existingBaseUrl = existing.getBaseUrl();
            if (!sameOrigin(existingBaseUrl, normalizedBaseUrl)) {
                throw new IllegalArgumentException(
                        "authToken is required when changing the upstream origin");
            }
            tokenToStore = existing.getAuthToken();
        }
        if (tokenToStore == null || tokenToStore.isBlank()) {
            throw new IllegalArgumentException(
                    "authToken is required because the stored credential is unavailable");
        }

        String updateSql = "UPDATE public.ai_gateway_platform_upstreams SET "
                + "name = ?, provider = ?, base_url = ?, auth_token_encrypted = ?, channel_code = ?, "
                + "supported_models = ?::jsonb, chat_completions_path = ?, is_default = ?, is_active = ?, "
                + "timeout_ms = ?, max_retries = ?, priority = ?, max_input_tokens = ?, description = ?, "
                + "updated_at = NOW() WHERE id = ?";
        List<Object> parameters = new ArrayList<>();
        Collections.addAll(parameters,
                u.getName(), providerName, normalizedBaseUrl, encrypt(tokenToStore), u.getChannelCode(),
                modelsJson, u.getChatCompletionsPath(), bool(u.getIsDefault(), false),
                bool(u.getIsActive(), true), intOr(u.getTimeoutMs(), 60000),
                intOr(u.getMaxRetries(), 2), intOr(u.getPriority(), 100));
        parameters.add(u.getMaxInputTokens());
        parameters.add(u.getDescription());
        parameters.add(u.getId());

        if (!tokenProvided) {
            updateSql += " AND base_url = ?";
            parameters.add(existingBaseUrl);
        }
        int updated = metadataJdbcTemplate.update(updateSql, parameters.toArray());
        if (updated == 0) {
            throw new ConcurrentModificationException(
                    "Platform upstream changed concurrently; retry with an explicit authToken");
        }
        return findById(u.getId()).orElse(u);
    }

    private String validateBaseUrl(String value) {
        URI uri = validateEndpointPolicy(value);
        String host = canonicalHost(uri.getHost());
        InetAddress[] addresses;
        try {
            addresses = hostResolver.resolve(host);
        } catch (Exception error) {
            throw new IllegalArgumentException("baseUrl host cannot be resolved");
        }
        if (addresses == null || addresses.length == 0) {
            throw new IllegalArgumentException("baseUrl host cannot be resolved");
        }
        if (!allowPrivateNetwork) {
            for (InetAddress address : addresses) {
                if (isRestrictedAddress(address)) {
                    throw new IllegalArgumentException(
                            "baseUrl points to a restricted network address");
                }
            }
        }
        return value.strip();
    }

    private URI validateEndpointPolicy(String value) {
        URI uri = parseBaseUrl(value);
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme) && !(allowHttp && "http".equals(scheme))) {
            throw new IllegalArgumentException(
                    "baseUrl must use HTTPS unless the controlled HTTP exception is enabled");
        }

        String host = canonicalHost(uri.getHost());
        if (host.isBlank()) {
            throw new IllegalArgumentException("baseUrl must include a valid host");
        }
        if (!allowPrivateNetwork && isLocalHostname(host)) {
            throw new IllegalArgumentException(
                    "baseUrl points to a restricted network address");
        }
        if (isIpLiteral(host)) {
            if (host.indexOf(':') < 0 && !isCanonicalIpv4Literal(host)) {
                throw new IllegalArgumentException("baseUrl must include a valid host");
            }
            if (!allowPrivateNetwork) {
                try {
                    if (isRestrictedAddress(InetAddress.getByName(host))) {
                        throw new IllegalArgumentException(
                                "baseUrl points to a restricted network address");
                    }
                } catch (UnknownHostException invalidAddress) {
                    throw new IllegalArgumentException("baseUrl must include a valid host");
                }
            }
        }
        return uri;
    }

    private static URI parseBaseUrl(String value) {
        if (value == null || value.isBlank() || value.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("baseUrl must be an absolute URI");
        }
        try {
            URI uri = URI.create(value.strip());
            int port = uri.getPort();
            if (!uri.isAbsolute()
                    || uri.isOpaque()
                    || uri.getScheme() == null
                    || uri.getHost() == null
                    || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || port == 0
                    || port > 65535
                    || (uri.getRawAuthority() != null && uri.getRawAuthority().endsWith(":"))) {
                throw new IllegalArgumentException("baseUrl must be an absolute URI");
            }
            return uri;
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("baseUrl must be an absolute URI");
        }
    }

    private static boolean sameOrigin(String existingValue, String requestedValue) {
        try {
            return Origin.from(parseBaseUrl(existingValue))
                    .equals(Origin.from(parseBaseUrl(requestedValue)));
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static String canonicalHost(String host) {
        String normalized = host == null ? "" : host.strip().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static boolean isLocalHostname(String host) {
        return "localhost".equals(host) || host.endsWith(".localhost");
    }

    private static boolean isIpLiteral(String host) {
        if (host.indexOf(':') >= 0 || host.startsWith("0x")) {
            return true;
        }
        for (int i = 0; i < host.length(); i++) {
            char current = host.charAt(i);
            if (current != '.' && !Character.isDigit(current)) {
                return false;
            }
        }
        return !host.isEmpty();
    }

    private static boolean isCanonicalIpv4Literal(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || (part.length() > 1 && part.startsWith("0"))) {
                return false;
            }
            try {
                if (Integer.parseInt(part) > 255) {
                    return false;
                }
            } catch (NumberFormatException invalid) {
                return false;
            }
        }
        return true;
    }

    private static boolean isRestrictedAddress(InetAddress address) {
        if (address == null
                || address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }

        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            int third = Byte.toUnsignedInt(bytes[2]);
            return first == 0
                    || (first == 100 && second >= 64 && second <= 127)
                    || (first == 192 && second == 0 && third == 0)
                    || (first == 192 && second == 0 && third == 2)
                    || (first == 198 && (second == 18 || second == 19))
                    || (first == 198 && second == 51 && third == 100)
                    || (first == 203 && second == 0 && third == 113)
                    || first >= 240;
        }
        if (bytes.length == 16) {
            int first = Byte.toUnsignedInt(bytes[0]);
            return (first & 0xfe) == 0xfc || isIpv6DocumentationAddress(bytes);
        }
        return true;
    }

    private static boolean isIpv6DocumentationAddress(byte[] bytes) {
        return Byte.toUnsignedInt(bytes[0]) == 0x20
                && Byte.toUnsignedInt(bytes[1]) == 0x01
                && Byte.toUnsignedInt(bytes[2]) == 0x0d
                && Byte.toUnsignedInt(bytes[3]) == 0xb8;
    }

    public void deleteById(Long id) {
        metadataJdbcTemplate.update("DELETE FROM public.ai_gateway_platform_upstreams WHERE id = ?", id);
    }

    private final RowMapper<PlatformUpstream> mapper = (ResultSet rs, int rowNum) -> {
        Integer maxInput = (Integer) rs.getObject("max_input_tokens");
        Timestamp created = rs.getTimestamp("created_at");
        Timestamp updated = rs.getTimestamp("updated_at");
        return PlatformUpstream.builder()
                .id(rs.getLong("id"))
                .name(rs.getString("name"))
                .provider(ApiProvider.fromString(rs.getString("provider")))
                .baseUrl(rs.getString("base_url"))
                .authToken(decrypt(rs.getString("auth_token_encrypted")))
                .channelCode(rs.getString("channel_code"))
                .supportedModels(fromJson(rs.getString("supported_models")))
                .chatCompletionsPath(rs.getString("chat_completions_path"))
                .isDefault(rs.getBoolean("is_default"))
                .isActive(rs.getBoolean("is_active"))
                .timeoutMs(rs.getInt("timeout_ms"))
                .maxRetries(rs.getInt("max_retries"))
                .priority(rs.getInt("priority"))
                .maxInputTokens(maxInput)
                .description(rs.getString("description"))
                .createdAt(created == null ? null : created.toInstant())
                .updatedAt(updated == null ? null : updated.toInstant())
                .build();
    };

    private String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return null;
        }
        try {
            return credentialCipher.encrypt(plaintext);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to encrypt platform upstream auth token");
        }
    }

    private String decrypt(String encrypted) {
        if (encrypted == null || encrypted.isBlank()) {
            return null;
        }
        try {
            return credentialCipher.decrypt(encrypted);
        } catch (Exception error) {
            throw new IllegalStateException(
                    "Failed to decrypt platform upstream auth token");
        }
    }

    private String toJson(List<String> models) {
        try {
            return objectMapper.writeValueAsString(models == null ? List.of() : models);
        } catch (Exception e) {
            return "[]";
        }
    }

    private List<String> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            log.warn("Failed to parse platform upstream supported_models: errorType={}",
                    e.getClass().getSimpleName());
            return new ArrayList<>();
        }
    }

    private static boolean bool(Boolean v, boolean dflt) {
        return v == null ? dflt : v;
    }

    private static int intOr(Integer v, int dflt) {
        return v == null ? dflt : v;
    }

    public record CatalogModelSource(
            ApiProvider provider,
            List<String> supportedModels
    ) {
        public CatalogModelSource {
            supportedModels = supportedModels == null ? List.of() : List.copyOf(supportedModels);
        }
    }

    @FunctionalInterface
    interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private record Origin(String scheme, String host, int port) {
        private static Origin from(URI uri) {
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            int port = uri.getPort();
            if (port == -1) {
                port = "https".equals(scheme) ? 443 : 80;
            }
            return new Origin(scheme, canonicalHost(uri.getHost()), port);
        }
    }
}
