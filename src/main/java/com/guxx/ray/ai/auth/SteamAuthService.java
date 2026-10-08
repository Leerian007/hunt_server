package com.guxx.ray.ai.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class SteamAuthService {
    public static final String ENDPOINT = "https://steamcommunity.com/openid/login";
    private static final String NS = "http://specs.openid.net/auth/2.0";
    private static final String SELECT = NS + "/identifier_select";
    private static final Pattern ID = Pattern.compile("^https://steamcommunity\\.com/openid/id/([0-9]{17})$");
    private final RestClient steamClient;
    private final OpenIdStateStore states;
    private final JwtService jwt;
    private final Clock clock;
    private final String baseDomain;
    private final String mobileLink;
    private final String webReturnUrl;
    private final SecureRandom random = new SecureRandom();

    public SteamAuthService(RestClient steamClient, OpenIdStateStore states, JwtService jwt, Clock clock,
                            @Value("${steam.base-domain}") String baseDomain,
                            @Value("${mobile.deep-link}") String mobileLink,
                            @Value("${web.return-url}") String webReturnUrl) {
        this.steamClient = steamClient;
        this.states = states;
        this.jwt = jwt;
        this.clock = clock;
        this.baseDomain = baseDomain.replaceAll("/+$", "");
        this.mobileLink = mobileLink;
        this.webReturnUrl = webReturnUrl;
    }

    public LoginStart loginUrl(String platform) {
        validatePlatform(platform);
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        states.save(state, platform);
        String url = UriComponentsBuilder.fromUriString(ENDPOINT)
                .queryParam("openid.ns", NS).queryParam("openid.mode", "checkid_setup")
                .queryParam("openid.return_to", callbackUrl(platform, state))
                .queryParam("openid.realm", baseDomain + "/")
                .queryParam("openid.identity", SELECT).queryParam("openid.claimed_id", SELECT)
                .build().encode().toUriString();
        return new LoginStart(url, state);
    }

    public URI authenticate(MultiValueMap<String, String> params, String cookieState) {
        if (params.values().stream().anyMatch(values -> values.size() != 1)) {
            throw unauthorized();
        }
        String platform = required(params, "platform");
        validatePlatform(platform);
        String state = required(params, "state");
        if (!state.matches("[A-Za-z0-9_-]{43}") || cookieState == null
                || !MessageDigest.isEqual(state.getBytes(StandardCharsets.UTF_8),
                    cookieState.getBytes(StandardCharsets.UTF_8))) {
            throw unauthorized();
        }
        if (!NS.equals(required(params, "openid.ns"))
                || !"id_res".equals(required(params, "openid.mode"))
                || !ENDPOINT.equals(required(params, "openid.op_endpoint"))
                || !callbackUrl(platform, state).equals(required(params, "openid.return_to"))) {
            throw unauthorized();
        }
        Set<String> signed = Set.copyOf(Arrays.asList(required(params, "openid.signed").split(",")));
        if (!signed.containsAll(Set.of("op_endpoint", "claimed_id", "identity", "return_to", "response_nonce", "assoc_handle"))) {
            throw unauthorized();
        }
        required(params, "openid.sig");
        required(params, "openid.assoc_handle");
        String claimedId = required(params, "openid.claimed_id");
        if (!claimedId.equals(required(params, "openid.identity"))) {
            throw unauthorized();
        }
        String nonce = required(params, "openid.response_nonce");
        validateNonce(nonce);
        var form = new LinkedMultiValueMap<String, String>();
        params.forEach((key, values) -> {
            if (key.startsWith("openid.")) form.put(key, values);
        });
        form.set("openid.mode", "check_authentication");
        String verification;
        try {
            verification = steamClient.post().uri(ENDPOINT)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
                    .retrieve().body(String.class);
        } catch (RestClientException ex) {
            throw new AuthException(HttpStatus.BAD_GATEWAY, "Steam authentication service unavailable");
        }
        if (verification == null || verification.lines().filter("is_valid:true"::equals).count() != 1
                || verification.lines().filter(line -> line.startsWith("is_valid:")).count() != 1) {
            throw unauthorized();
        }
        var matcher = ID.matcher(claimedId);
        if (!matcher.matches()) throw unauthorized();
        if (!platform.equals(states.consume(state)) || !states.claimNonce(nonce)) throw unauthorized();
        String steamId = matcher.group(1);
        return UriComponentsBuilder.fromUriString("mobile".equals(platform) ? mobileLink : webReturnUrl)
                .queryParam("token", jwt.issue(steamId)).queryParam("steam_id", steamId)
                .build().encode().toUri();
    }

    private String callbackUrl(String platform, String state) {
        return UriComponentsBuilder.fromUriString(baseDomain + "/api/v1/auth/steam/callback")
                .queryParam("platform", platform).queryParam("state", state).build().encode().toUriString();
    }

    private void validateNonce(String nonce) {
        try {
            if (nonce.length() <= 20 || nonce.length() > 255) throw unauthorized();
            Instant time = Instant.parse(nonce.substring(0, 20));
            if (time.isBefore(clock.instant().minus(OpenIdStateStore.TTL))
                    || time.isAfter(clock.instant().plusSeconds(30))) throw unauthorized();
        } catch (DateTimeParseException ex) {
            throw unauthorized();
        }
    }

    private static String required(MultiValueMap<String, String> params, String name) {
        String value = params.getFirst(name);
        if (value == null || value.isBlank()) throw unauthorized();
        return value;
    }

    private static void validatePlatform(String platform) {
        if (!"web".equals(platform) && !"mobile".equals(platform)) {
            throw new AuthException(HttpStatus.BAD_REQUEST, "platform must be web or mobile");
        }
    }

    private static AuthException unauthorized() {
        return new AuthException(HttpStatus.UNAUTHORIZED, "Invalid or expired Steam authentication response");
    }

    public record LoginStart(String url, String state) {}
}
