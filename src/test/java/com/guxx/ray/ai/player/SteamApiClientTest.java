package com.guxx.ray.ai.player;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class SteamApiClientTest {
    private static final String ID = "76561198000000000";
    private SteamApiClient client;
    private MockRestServiceServer server;

    @BeforeEach
    void setup() {
        var template = new RestTemplate();
        server = MockRestServiceServer.bindTo(template).build();
        client = new SteamApiClient(template, "test-key");
    }

    @Test
    void mapsSteamFieldsAndAllowsMissingPrivateCountry() {
        server.expect(requestTo(SteamApiClient.ENDPOINT + "?key=test-key&steamids=" + ID))
                .andRespond(withSuccess("""
                        {"response":{"players":[{"steamid":"76561198000000000","personaname":"Hunter",
                        "avatarfull":"https://avatars.example/full.jpg","extra":"ignored"}]}}
                        """, MediaType.APPLICATION_JSON));
        assertThat(client.getPlayerSummary(ID)).isEqualTo(new PlayerSummary(ID, "Hunter", "https://avatars.example/full.jpg", null));
        server.verify();
    }

    @Test
    void mapsCountry() {
        server.expect(anything()).andRespond(withSuccess("""
                {"response":{"players":[{"steamid":"76561198000000000","personaname":"Hunter","loccountrycode":"CN"}]}}
                """, MediaType.APPLICATION_JSON));
        assertThat(client.getPlayerSummary(ID).locCountryCode()).isEqualTo("CN");
    }

    @Test
    void distinguishesMissingPlayerFromMalformedResponse() {
        server.expect(anything()).andRespond(withSuccess("{\"response\":{\"players\":[]}}", MediaType.APPLICATION_JSON));
        server.expect(anything()).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        assertStatus(HttpStatus.NOT_FOUND);
        assertStatus(HttpStatus.BAD_GATEWAY);
    }

    @Test
    void hidesUpstreamSecretsAndHandlesRateLimit() {
        server.expect(anything()).andRespond(withStatus(HttpStatus.FORBIDDEN).body("secret test-key"));
        server.expect(anything()).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        assertThatThrownBy(() -> client.getPlayerSummary(ID)).isInstanceOfSatisfying(PlayerException.class, ex -> {
            assertThat(ex.status()).isEqualTo(HttpStatus.BAD_GATEWAY);
            assertThat(ex.getMessage()).doesNotContain("test-key");
            assertThat(ex.getCause()).isNull();
        });
        assertStatus(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void validatesIdAndRequiresConfiguredKeyBeforeNetwork() {
        assertThatThrownBy(() -> client.getPlayerSummary("invalid")).isInstanceOfSatisfying(PlayerException.class,
                ex -> assertThat(ex.status()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> new SteamApiClient(new RestTemplate(), "").getPlayerSummary(ID))
                .isInstanceOfSatisfying(PlayerException.class, ex -> assertThat(ex.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        server.verify();
    }

    private void assertStatus(HttpStatus status) {
        assertThatThrownBy(() -> client.getPlayerSummary(ID)).isInstanceOfSatisfying(PlayerException.class,
                ex -> assertThat(ex.status()).isEqualTo(status));
    }
}
