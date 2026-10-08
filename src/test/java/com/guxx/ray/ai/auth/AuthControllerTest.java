package com.guxx.ray.ai.auth;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.net.URI;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value = AuthController.class,
        excludeAutoConfiguration = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class,
        properties = {
        "jwt.secret=dGVzdC1vbmx5LXNlY3JldC1rZXktMzItYnl0ZXMtbG9uZyE=", "jwt.expiration=7d"})
@Import({AuthConfiguration.class, JwtService.class})
class AuthControllerTest {
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @MockitoBean SteamAuthService steam;

    @Test
    void requiresBearerTokenAndRejectsMalformedTokens() throws Exception {
        mvc.perform(get("/api/v1/auth/session")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
        mvc.perform(get("/api/v1/auth/session").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "Bearer"));
    }

    @Test
    void injectsPrincipalAndDoesNotLeakItToNextRequest() throws Exception {
        mvc.perform(get("/api/v1/auth/session").header("Authorization", "Bearer " + jwt.issue("76561198000000000")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.authenticated").value(true))
                .andExpect(jsonPath("$.data.steam_id").value("76561198000000000"))
                .andExpect(jsonPath("$.data.expires_at").exists());
        mvc.perform(get("/api/v1/auth/session")).andExpect(status().isUnauthorized());
    }

    @Test
    void loginEndpointIsPublicAndSetsSecureStateCookie() throws Exception {
        when(steam.loginUrl("mobile")).thenReturn(new SteamAuthService.LoginStart("https://steamcommunity.com/openid/login", "state"));
        mvc.perform(get("/api/v1/auth/steam/login-url").param("platform", "mobile"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.url").exists())
                .andExpect(cookie().httpOnly(AuthController.STATE_COOKIE, true))
                .andExpect(cookie().secure(AuthController.STATE_COOKIE, true));
    }

    @Test
    void callbackRedirectsWithoutCachingAndClearsCookie() throws Exception {
        String target = "hunt1896://auth/steam?token=jwt&steam_id=76561198000000000";
        when(steam.authenticate(any(), eq("state"))).thenReturn(URI.create(target));
        mvc.perform(get("/api/v1/auth/steam/callback").cookie(new Cookie(AuthController.STATE_COOKIE, "state")))
                .andExpect(status().isFound()).andExpect(redirectedUrl(target))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(cookie().maxAge(AuthController.STATE_COOKIE, 0));
    }

    @Test
    void supportsCredentialedWebPreflight() throws Exception {
        mvc.perform(options("/api/v1/auth/session").header("Origin", "https://hunt1896.app")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "authorization"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", "https://hunt1896.app"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }
}
