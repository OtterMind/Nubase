package ai.nubase.auth.service;

import ai.nubase.common.config.AuthConfig;
import ai.nubase.common.config.TenantAuthConfig;
import ai.nubase.common.context.MultiTenancyContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for {@link RedirectUrlValidator} (open-redirect guard).
 */
@DisplayName("RedirectUrlValidator")
class RedirectUrlValidatorTest {

    private RedirectUrlValidator validator(AuthConfig cfg) {
        return new RedirectUrlValidator(cfg, new EffectiveAuthConfig(cfg));
    }

    @AfterEach
    void clearContext() {
        MultiTenancyContext.clear();
    }

    @Test
    @DisplayName("relative paths are allowed; cross-origin path forms are rejected")
    void relativePaths() {
        RedirectUrlValidator v = validator(new AuthConfig());
        assertThat(v.isAllowed("/dashboard")).isTrue();
        assertThat(v.isAllowed("/auth/callback?x=1")).isTrue();
        assertThat(v.isAllowed("//evil.com/phish")).isFalse();   // protocol-relative
        assertThat(v.isAllowed("/\\evil.com/phish")).isFalse(); // browser-normalized to //evil.com
        assertThat(v.isAllowed("/safe\\path")).isFalse();       // reject ambiguous separators
    }

    @Test
    @DisplayName("localhost is allowed by default; arbitrary external hosts are rejected")
    void localhostAndExternal() {
        RedirectUrlValidator v = validator(new AuthConfig());
        assertThat(v.isAllowed("http://localhost:3000/cb")).isTrue();
        assertThat(v.isAllowed("http://127.0.0.1:3000/cb")).isTrue();
        assertThat(v.isAllowed("https://evil.com/phish")).isFalse();
        assertThat(v.isAllowed("javascript:alert(1)")).isFalse();
    }

    @Test
    @DisplayName("the requesting tenant's own domain is allowed")
    void tenantDomain() {
        AuthConfig cfg = new AuthConfig();   // serviceName defaults to localhost:9999
        MultiTenancyContext.setContext(MultiTenancyContext.ContextData.builder()
                .appCode("demo").schemaName("public").jwtSecret("s").build());
        RedirectUrlValidator v = validator(cfg);
        assertThat(v.isAllowed("http://demo.localhost:9999/welcome")).isTrue();   // demo.localhost
        assertThat(v.isAllowed("http://other.localhost:9999/welcome")).isFalse(); // different subdomain
    }

    @Test
    @DisplayName("configured allow-list glob patterns are honoured")
    void allowListGlobs() {
        AuthConfig cfg = new AuthConfig();
        cfg.getRedirect().setAllowLocalhost(false);
        cfg.getRedirect().setAllowList(List.of("https://app.example.com/**"));
        RedirectUrlValidator v = validator(cfg);
        assertThat(v.isAllowed("https://app.example.com/auth/cb")).isTrue();
        assertThat(v.isAllowed("https://app.example.com/a/b/c")).isTrue();
        assertThat(v.isAllowed("https://app.example.com.evil.com/x")).isFalse(); // suffix spoof
        assertThat(v.isAllowed("https://evil.com/app.example.com")).isFalse();
    }

    @Test
    @DisplayName("managed redirect allow-lists are honoured")
    void managedRedirectAllowLists() {
        AuthConfig cfg = new AuthConfig();
        cfg.getRedirect().setAllowLocalhost(false);
        TenantAuthConfig tenant = new TenantAuthConfig();
        tenant.setManagedRedirectAllowLists(Map.of(
                "ottermind", List.of("https://appabc.ottermind.app/auth/callback")
        ));
        MultiTenancyContext.setContext(MultiTenancyContext.ContextData.builder()
                .appCode("demo").schemaName("public").jwtSecret("s")
                .tenantAuthConfig(tenant).build());

        RedirectUrlValidator v = validator(cfg);

        assertThat(v.isAllowed("https://appabc.ottermind.app/auth/callback")).isTrue();
        assertThat(v.isAllowed("https://appabc.ottermind.app/auth/callback?returnTo=%2F")).isTrue();
        assertThat(v.isAllowed("https://appabc.ottermind.app/auth/callback#access_token=token")).isTrue();
        assertThat(v.isAllowed("https://other.ottermind.app/auth/callback")).isFalse();
    }

    @Test
    @DisplayName("sanitize falls back to siteUrl when set, else null")
    void sanitizeFallback() {
        RedirectUrlValidator v1 = validator(new AuthConfig());
        assertThat(v1.sanitize("https://evil.com")).isNull();
        assertThat(v1.sanitize("/ok")).isEqualTo("/ok");

        AuthConfig cfg = new AuthConfig();
        cfg.getRedirect().setSiteUrl("https://app.mysite.com");
        RedirectUrlValidator v2 = validator(cfg);
        assertThat(v2.sanitize("https://evil.com")).isEqualTo("https://app.mysite.com");
        assertThat(v2.sanitize(null)).isEqualTo("https://app.mysite.com");
    }

    @Test
    @DisplayName("glob: * stays within a segment, ** crosses segments")
    void globSemantics() {
        assertThat(RedirectUrlValidator.globMatches("https://x.com/*", "https://x.com/a")).isTrue();
        assertThat(RedirectUrlValidator.globMatches("https://x.com/*", "https://x.com/a/b")).isFalse();
        assertThat(RedirectUrlValidator.globMatches("https://x.com/**", "https://x.com/a/b")).isTrue();
    }
}
