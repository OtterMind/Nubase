package ai.nubase.auth.controller;

import ai.nubase.auth.service.AuthService;
import ai.nubase.auth.service.IdTokenService;
import ai.nubase.auth.service.OAuthService;
import ai.nubase.auth.service.OAuthStateService;
import ai.nubase.auth.service.OtpService;
import ai.nubase.auth.service.PkceService;
import ai.nubase.auth.service.RedirectUrlValidator;
import ai.nubase.auth.util.TokenGenerator;
import ai.nubase.common.config.AuthConfig;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.mock;

class AuthControllerCallbackTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new AuthController(
                mock(AuthService.class),
                mock(OtpService.class),
                mock(OAuthService.class),
                mock(OAuthStateService.class),
                mock(PkceService.class),
                mock(IdTokenService.class),
                mock(RedirectUrlValidator.class),
                mock(org.springframework.core.io.ResourceLoader.class),
                mock(TokenGenerator.class),
                mock(AuthConfig.class)
        ))
                .setMessageConverters(
                        new MappingJackson2HttpMessageConverter(OBJECT_MAPPER),
                        new StringHttpMessageConverter())
                .build();
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
}
