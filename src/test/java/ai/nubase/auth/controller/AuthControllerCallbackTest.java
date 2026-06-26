package ai.nubase.auth.controller;

import ai.nubase.auth.service.AuthService;
import ai.nubase.auth.service.IdTokenService;
import ai.nubase.auth.service.OAuthService;
import ai.nubase.auth.service.OAuthStateService;
import ai.nubase.auth.service.OtpService;
import ai.nubase.auth.service.PkceService;
import ai.nubase.auth.service.RedirectUrlValidator;
import ai.nubase.auth.util.TokenGenerator;
import ai.nubase.auth.dto.oauth.OAuthStateData;
import ai.nubase.common.config.AuthConfig;
import ai.nubase.common.config.oauth.OAuthProperties;
import ai.nubase.common.context.MultiTenancyContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static ai.nubase.test.ControllerTestSupport.OBJECT_MAPPER;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

class AuthControllerCallbackTest {

    private MockMvc mvc;
    private OAuthService oauthService;
    private OAuthStateService oauthStateService;
    private RedirectUrlValidator redirectUrlValidator;
    private TokenGenerator tokenGenerator;

    @BeforeEach
    void setUp() {
        MultiTenancyContext.clear();
        oauthService = mock(OAuthService.class);
        oauthStateService = mock(OAuthStateService.class);
        redirectUrlValidator = mock(RedirectUrlValidator.class);
        tokenGenerator = mock(TokenGenerator.class);
        when(tokenGenerator.generateSecureToken()).thenReturn("state-1");
        when(oauthService.getAuthorizationUrl(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()
        )).thenAnswer(invocation -> {
            String redirectUri = invocation.getArgument(1, String.class);
            return "https://accounts.google.com/o/oauth2/v2/auth?redirect_uri="
                    + URLEncoder.encode(redirectUri, StandardCharsets.UTF_8);
        });
        mvc = MockMvcBuilders.standaloneSetup(new AuthController(
                mock(AuthService.class),
                mock(OtpService.class),
                oauthService,
                oauthStateService,
                mock(PkceService.class),
                mock(IdTokenService.class),
                redirectUrlValidator,
                mock(org.springframework.core.io.ResourceLoader.class),
                tokenGenerator,
                mock(AuthConfig.class)
        ))
                .setMessageConverters(
                        new MappingJackson2HttpMessageConverter(OBJECT_MAPPER),
                        new StringHttpMessageConverter())
                .build();
    }

    @AfterEach
    void tearDown() {
        MultiTenancyContext.clear();
    }

    @Test
    void oauthCallbackErrorParamsAreHtmlEscaped() throws Exception {
        mvc.perform(get("/auth/v1/callback")
                        .param("code", "dummy")
                        .param("error", "<img src=x onerror=alert(1)>")
                        .param("error_description", "<script>alert(1)</script>"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("&lt;img src=x onerror=alert(1)&gt;")))
                .andExpect(content().string(containsString("&lt;script&gt;alert(1)&lt;/script&gt;")))
                .andExpect(content().string(not(containsString("<script>alert(1)</script>"))));
    }

    @Test
    void authorizeUsesRequestHostForCallbackUrl() throws Exception {
        mvc.perform(get("/auth/v1/authorize")
                        .param("provider", "google")
                        .header("Host", "appfequkju9th9w.nubase.ai")
                        .header("X-Forwarded-Proto", "https")
                        .header("X-Forwarded-Port", "443"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", containsString(
                        "redirect_uri=https%3A%2F%2Fappfequkju9th9w.nubase.ai%2Fauth%2Fv1%2Fcallback"
                )));
    }

    @Test
    void authorizeDoesNotAppendInternalPortWhenOnlyForwardedProtoIsPresent() throws Exception {
        mvc.perform(get("/auth/v1/authorize")
                        .param("provider", "google")
                        .header("Host", "found-libraries-admitted-transport.trycloudflare.com")
                        .header("X-Forwarded-Proto", "https"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", containsString(
                        "redirect_uri=https%3A%2F%2Ffound-libraries-admitted-transport.trycloudflare.com%2Fauth%2Fv1%2Fcallback"
                )))
                .andExpect(header().string("Location", not(containsString(
                        "found-libraries-admitted-transport.trycloudflare.com%3A80"
                ))));
    }

    @Test
    void authorizeDoesNotUseForwardedHostForCallbackUrl() throws Exception {
        mvc.perform(get("/auth/v1/authorize")
                        .param("provider", "google")
                        .header("Host", "appfequkju9th9w.nubase.ai")
                        .header("X-Forwarded-Proto", "https")
                        .header("X-Forwarded-Host", "appfequkju9th9w.ottermind.app")
                        .header("X-Forwarded-Port", "443"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", containsString(
                        "redirect_uri=https%3A%2F%2Fappfequkju9th9w.nubase.ai%2Fauth%2Fv1%2Fcallback"
                )))
                .andExpect(header().string("Location", not(containsString(
                        "appfequkju9th9w.ottermind.app"
                ))));
    }

    @Test
    void authorizeDoesNotUseConfiguredProviderRedirectUriBeforeRequestHost() throws Exception {
        OAuthProperties oauthProperties = new OAuthProperties();
        OAuthProperties.ProviderConfig google = new OAuthProperties.ProviderConfig();
        google.setRedirectUri("https://appfequkju9th9w.ottermind.app/auth/v1/callback");
        oauthProperties.getProviders().put("google", google);
        MultiTenancyContext.setContext(MultiTenancyContext.ContextData.builder()
                .oauthProperties(oauthProperties)
                .apikey("test-apikey")
                .build());

        mvc.perform(get("/auth/v1/authorize")
                        .param("provider", "google")
                        .header("Host", "appfequkju9th9w.nubase.ai")
                        .header("X-Forwarded-Proto", "https")
                        .header("X-Forwarded-Port", "443"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", containsString(
                        "redirect_uri=https%3A%2F%2Fappfequkju9th9w.nubase.ai%2Fauth%2Fv1%2Fcallback"
                )))
                .andExpect(header().string("Location", not(containsString(
                        "appfequkju9th9w.ottermind.app"
                ))));
    }

    @Test
    void authorizePersistsCallbackUrlInStateForTokenExchange() throws Exception {
        AtomicReference<OAuthStateData> savedState = new AtomicReference<>();
        doAnswer(invocation -> {
            savedState.set(invocation.getArgument(1, OAuthStateData.class));
            return null;
        }).when(oauthStateService).saveState(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(OAuthStateData.class)
        );

        mvc.perform(get("/auth/v1/authorize")
                        .param("provider", "google")
                        .header("Host", "found-libraries-admitted-transport.trycloudflare.com")
                        .header("X-Forwarded-Proto", "https")
                        .header("X-Forwarded-Port", "443"))
                .andExpect(status().isFound());

        org.assertj.core.api.Assertions.assertThat(savedState.get()).isNotNull();
        org.assertj.core.api.Assertions.assertThat(savedState.get().getCallbackUrl())
                .isEqualTo("https://found-libraries-admitted-transport.trycloudflare.com/auth/v1/callback");
    }
}
