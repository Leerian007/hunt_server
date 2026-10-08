package com.guxx.ray.ai.player;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class SteamApiClient {
    public static final String ENDPOINT = "https://api.steampowered.com/ISteamUser/GetPlayerSummaries/v2/";
    private final RestTemplate http;
    private final String apiKey;

    public SteamApiClient(@Qualifier("steamApiRestTemplate") RestTemplate http,
                          @Value("${steam.web-api-key:}") String apiKey) {
        this.http = http;
        this.apiKey = apiKey;
    }

    public PlayerSummary getPlayerSummary(String steamId) {
        validateSteamId(steamId);
        if (apiKey == null || apiKey.isBlank()) {
            throw new PlayerException(HttpStatus.SERVICE_UNAVAILABLE, "Steam Web API key is not configured");
        }
        var uri = UriComponentsBuilder.fromUriString(ENDPOINT).queryParam("key", apiKey)
                .queryParam("steamids", steamId).build().encode().toUri();
        JsonNode body;
        try {
            body = http.getForObject(uri, JsonNode.class);
        } catch (HttpStatusCodeException ex) {
            if (ex.getStatusCode().value() == 429) {
                throw new PlayerException(HttpStatus.SERVICE_UNAVAILABLE, "Steam API rate limit reached; retry later");
            }
            // Do not propagate the upstream URL or exception text: it includes the API key.
            throw upstreamFailure();
        } catch (RestClientException ex) {
            throw upstreamFailure();
        }
        if (body == null || !body.path("response").path("players").isArray()) throw upstreamFailure();
        JsonNode players = body.path("response").path("players");
        if (players.isEmpty()) throw new PlayerException(HttpStatus.NOT_FOUND, "Steam player not found");
        for (JsonNode player : players) {
            if (steamId.equals(player.path("steamid").asText())) {
                String name = optionalText(player, "personaname");
                String country = optionalText(player, "loccountrycode");
                if (name == null || name.isBlank() || name.length() > 255
                        || (country != null && !country.matches("[A-Z]{2}"))) throw upstreamFailure();
                return new PlayerSummary(steamId, name, optionalText(player, "avatarfull"), country);
            }
        }
        throw upstreamFailure();
    }

    public static void validateSteamId(String steamId) {
        if (steamId == null || !steamId.matches("[0-9]{17}")) {
            throw new PlayerException(HttpStatus.BAD_REQUEST, "steam_id must contain 17 digits");
        }
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw upstreamFailure();
        return value.asText();
    }

    private static PlayerException upstreamFailure() {
        return new PlayerException(HttpStatus.BAD_GATEWAY, "Steam player API unavailable or returned an invalid response");
    }
}
