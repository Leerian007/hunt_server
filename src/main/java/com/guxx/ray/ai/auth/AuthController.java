package com.guxx.ray.ai.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.guxx.ray.ai.common.Result;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import java.time.Duration;
import java.time.Instant;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    public static final String STATE_COOKIE = "hunt_steam_state";
    private final SteamAuthService steam;

    public AuthController(SteamAuthService steam) {
        this.steam = steam;
    }

    @GetMapping("/steam/login-url")
    public ResponseEntity<Result<LoginUrl>> loginUrl(@RequestParam(defaultValue = "web") String platform) {
        var login = steam.loginUrl(platform);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, stateCookie(login.state(), OpenIdStateStore.TTL).toString())
                .body(Result.success(new LoginUrl(login.url())));
    }

    @GetMapping("/steam/callback")
    public ResponseEntity<Void> callback(@RequestParam MultiValueMap<String, String> params,
                                        @CookieValue(name = STATE_COOKIE, required = false) String state) {
        var redirect = steam.authenticate(params, state);
        return ResponseEntity.status(HttpStatus.FOUND).location(redirect).cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer")
                .header(HttpHeaders.SET_COOKIE, stateCookie("", Duration.ZERO).toString()).build();
    }

    @GetMapping("/session")
    public ResponseEntity<Result<Session>> session(@AuthenticationPrincipal SessionPrincipal principal) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Result.success(
                new Session(true, principal.steamId(), principal.expiresAt())));
    }

    private ResponseCookie stateCookie(String state, Duration maxAge) {
        return ResponseCookie.from(STATE_COOKIE, state).httpOnly(true).secure(true).sameSite("Lax")
                .path("/api/v1/auth/steam").maxAge(maxAge).build();
    }

    public record LoginUrl(String url) {}
    public record Session(boolean authenticated, @JsonProperty("steam_id") String steamId,
                          @JsonProperty("expires_at") Instant expiresAt) {}
}
