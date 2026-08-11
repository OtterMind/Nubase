package ai.nubase.auth.service;

import ai.nubase.common.config.AuthConfig;
import ai.nubase.common.context.MultiTenancyContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.regex.Pattern;

/**
 * Validates {@code redirect_to} targets to prevent open-redirect attacks.
 *
 * <p>A redirect URL is permitted when it is:
 * <ul>
 *   <li>a relative path ({@code /path}, but not protocol-relative {@code //host}),</li>
 *   <li>an absolute http/https URL whose host matches the requesting tenant's own domain
 *       ({@code appCode.serviceName}) — when {@code allowTenantDomain} is on,</li>
 *   <li>a localhost / 127.0.0.1 / [::1] URL — when {@code allowLocalhost} is on,</li>
 *   <li>or a match against one of the configured allow-list glob patterns
 *       ({@code *} matches within a path segment, {@code **} across segments).</li>
 * </ul>
 * Anything else is rejected: {@link #sanitize(String)} returns the configured {@code siteUrl}
 * fallback (or {@code null}), so the caller renders a non-redirect response instead of bouncing
 * the browser to an attacker-controlled URL. Mirrors Supabase GoTrue's URI allow-list behavior.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RedirectUrlValidator {

    private final AuthConfig authConfig;            // app domain (global, not per-tenant)
    private final EffectiveAuthConfig effectiveAuthConfig;  // redirect allow-list (per-tenant)

    /**
     * Return a safe redirect target, or {@code null} (falling back to {@code siteUrl} if set)
     * when the supplied value is blank or not allowed.
     */
    public String sanitize(String redirectTo) {
        if (StringUtils.isBlank(redirectTo)) {
            return defaultSafe(null);
        }
        if (isAllowed(redirectTo)) {
            return redirectTo;
        }
        log.warn("Rejected redirect_to (not in allow-list): {}", redirectTo);
        return defaultSafe(redirectTo);
    }

    /** Whether the URL passes the allow-list (no fallback applied). */
    public boolean isAllowed(String redirectTo) {
        if (StringUtils.isBlank(redirectTo)) {
            return false;
        }
        AuthConfig.RedirectSettings cfg = effectiveAuthConfig.redirect();

        // Relative path (same-origin), but reject protocol-relative "//host" and
        // backslashes, which browsers normalize to slashes for special URL schemes.
        if (redirectTo.startsWith("/")
                && !redirectTo.startsWith("//")
                && redirectTo.indexOf('\\') < 0) {
            return true;
        }

        URI uri;
        try {
            uri = URI.create(redirectTo);
        } catch (Exception e) {
            return false;
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();

        if (StringUtils.isNotBlank(scheme) && StringUtils.isNotBlank(host)
                && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            if (cfg.isAllowLocalhost() && isLocalhost(host)) {
                return true;
            }
            if (cfg.isAllowTenantDomain() && host.equalsIgnoreCase(tenantHost())) {
                return true;
            }
        }

        // Configured allow-list patterns. Exact callback entries are allowed to receive transient
        // query/fragment state (for example ?returnTo=/) without forcing every query variant into
        // the allow-list.
        if (cfg.getAllowList() != null) {
            String urlWithoutQueryOrFragment = withoutQueryOrFragment(uri);
            for (String pattern : cfg.getAllowList()) {
                if (StringUtils.isBlank(pattern)) {
                    continue;
                }
                if (globMatches(pattern, redirectTo)
                        || (urlWithoutQueryOrFragment != null
                            && !urlWithoutQueryOrFragment.equals(redirectTo)
                            && globMatches(pattern, urlWithoutQueryOrFragment))) {
                    return true;
                }
            }
        }
        return false;
    }

    private String defaultSafe(String rejected) {
        String siteUrl = effectiveAuthConfig.redirect().getSiteUrl();
        return StringUtils.isNotBlank(siteUrl) ? siteUrl : null;
    }

    private boolean isLocalhost(String host) {
        return "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "[::1]".equals(host)
                || "::1".equals(host);
    }

    private String tenantHost() {
        String appCode = MultiTenancyContext.getAppCode();
        if (StringUtils.isBlank(appCode)) {
            return null;
        }
        try {
            return URI.create(authConfig.getApp().getDomain(appCode)).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    private String withoutQueryOrFragment(URI uri) {
        if (uri == null || (uri.getQuery() == null && uri.getFragment() == null)) {
            return null;
        }
        try {
            return new URI(
                    uri.getScheme(),
                    uri.getAuthority(),
                    uri.getPath(),
                    null,
                    null
            ).toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** Glob match: {@code *} = within a path segment, {@code **} = across segments. */
    static boolean globMatches(String pattern, String url) {
        StringBuilder re = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*') {
                if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '*') {
                    re.append(".*");
                    i++;
                } else {
                    re.append("[^/]*");
                }
            } else if ("\\.[]{}()+-^$|?".indexOf(c) >= 0) {
                re.append('\\').append(c);
            } else {
                re.append(c);
            }
        }
        return Pattern.compile(re.toString(), Pattern.CASE_INSENSITIVE).matcher(url).matches();
    }
}
