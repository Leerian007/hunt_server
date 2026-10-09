package com.guxx.ray.ai.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.hamcrest.Matchers.containsString;

class SteamAuthServiceTest {
    private static final String STATE = "a".repeat(43);
    private OpenIdStateStore states;
    private JwtService jwt;
    private SteamAuthService service;
    private MockRestServiceServer server;

    @BeforeEach
    void setup() {
        states = mock(OpenIdStateStore.class);
        jwt = mock(JwtService.class);
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        service = new SteamAuthService(builder.build(), states, jwt,
                Clock.fixed(Instant.parse("2026-10-08T10:00:00Z"), ZoneOffset.UTC),
                "https://api.hunt1896.app", "hunt1896://auth/steam", "https://hunt1896.app/auth/callback");
        when(states.claimNonce(anyString())).thenReturn(true);
        when(jwt.issue("76561198000000000")).thenReturn("signed.jwt.token");
    }

    private MultiValueMap<String, String> params(String platform) {
        var params = new LinkedMultiValueMap<String, String>();
        params.set("platform", platform);
        params.set("state", STATE);
        params.set("openid.ns", "http://specs.openid.net/auth/2.0");
        params.set("openid.mode", "id_res");
        params.set("openid.op_endpoint", SteamAuthService.ENDPOINT);
//        params.set("openid.return_to", "https://api.hunt1896.app/api/v1/auth/steam/callback?platform=" + platform + "&state=" + STATE);
        params.set("openid.return_to", "http://localhost:8080/api/v1/auth/steam/callback?platform=" + platform + "&state=" + STATE);
        params.set("openid.claimed_id", "https://steamcommunity.com/openid/id/76561198000000000");
        params.set("openid.identity", params.getFirst("openid.claimed_id"));
        params.set("openid.response_nonce", "2026-10-08T10:00:00Zunique");
        params.set("openid.assoc_handle", "association");
        params.set("openid.sig", "signature");
        params.set("openid.signed", "op_endpoint,claimed_id,identity,return_to,response_nonce,assoc_handle");
        return params;
    }

    private void expectVerification(String body) {
        server.expect(requestTo(SteamAuthService.ENDPOINT)).andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("openid.mode=check_authentication")))
                .andExpect(content().string(containsString("openid.sig=signature")))
                .andRespond(withSuccess(body, MediaType.TEXT_PLAIN));
    }

    @ParameterizedTest
    @ValueSource(strings = {"web", "mobile"})
    void verifiesWithSteamAndRedirectsToConfiguredPlatform(String platform) {
        when(states.consume(STATE)).thenReturn(platform);
        expectVerification("ns:http://specs.openid.net/auth/2.0\nis_valid:true\n");
        String destination = platform.equals("web") ? "https://hunt1896.app/auth/callback" : "hunt1896://auth/steam";
        assertThat(service.authenticate(params(platform), STATE).toString())
                .isEqualTo(destination + "?token=signed.jwt.token&steam_id=76561198000000000");
        server.verify();
    }

    @Test
    void loginUrlUsesSteamAndStoresUnpredictablePlatformBoundState() {
        var login = service.loginUrl("mobile");
        assertThat(login.state()).matches("[A-Za-z0-9_-]{43}");
        assertThat(login.url()).startsWith(SteamAuthService.ENDPOINT + "?")
                .contains("openid.mode=checkid_setup", "openid.return_to=", "openid.claimed_id=");
        verify(states).save(login.state(), "mobile");
        assertThatThrownBy(() -> service.loginUrl("attacker")).isInstanceOf(AuthException.class);
    }

    @Test
    void rejectsInvalidSteamSignatureWithoutIssuingToken() {
        expectVerification("is_valid:false\n");
        assertThatThrownBy(() -> service.authenticate(params("web"), STATE)).isInstanceOf(AuthException.class);
        verifyNoInteractions(jwt, states);
    }

    @Test
    void rejectsCookieMismatchTamperedReturnUrlUnsignedIdentityAndStaleNonceBeforeNetwork() {
        assertThatThrownBy(() -> service.authenticate(params("web"), "wrong")).isInstanceOf(AuthException.class);
        var tampered = params("web");
        tampered.set("openid.return_to", "https://attacker.example/");
        assertThatThrownBy(() -> service.authenticate(tampered, STATE)).isInstanceOf(AuthException.class);
        var unsigned = params("web");
        unsigned.set("openid.signed", "return_to");
        assertThatThrownBy(() -> service.authenticate(unsigned, STATE)).isInstanceOf(AuthException.class);
        var stale = params("web");
        stale.set("openid.response_nonce", "2026-10-08T09:00:00Zunique");
        assertThatThrownBy(() -> service.authenticate(stale, STATE)).isInstanceOf(AuthException.class);
        server.verify();
        verifyNoInteractions(jwt, states);
    }

    @Test
    void rejectsConsumedStateAndReplayedNonce() {
        expectVerification("is_valid:true\n");
        expectVerification("is_valid:true\n");
        when(states.consume(STATE)).thenReturn(null, "web");
        when(states.claimNonce(anyString())).thenReturn(false);
        assertThatThrownBy(() -> service.authenticate(params("web"), STATE)).isInstanceOf(AuthException.class);
        assertThatThrownBy(() -> service.authenticate(params("web"), STATE)).isInstanceOf(AuthException.class);
        verifyNoInteractions(jwt);
        server.verify();
    }

    @Test
    void rejectsNonSteamIdentityEvenWhenProviderSaysValid() {
        var params = params("web");
        params.set("openid.claimed_id", "https://attacker.example/openid/id/76561198000000000");
        params.set("openid.identity", params.getFirst("openid.claimed_id"));
        expectVerification("is_valid:true\n");
        assertThatThrownBy(() -> service.authenticate(params, STATE)).isInstanceOf(AuthException.class);
        verifyNoInteractions(jwt);
    }

    @Test
    void mapsUpstreamFailureToBadGateway() {
        server.expect(requestTo(SteamAuthService.ENDPOINT)).andRespond(withServerError());
        assertThatThrownBy(() -> service.authenticate(params("web"), STATE))
                .isInstanceOfSatisfying(AuthException.class, ex -> assertThat(ex.status()).isEqualTo(HttpStatus.BAD_GATEWAY));
        verifyNoInteractions(jwt);
    }
}
