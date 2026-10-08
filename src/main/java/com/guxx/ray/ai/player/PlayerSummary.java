package com.guxx.ray.ai.player;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record PlayerSummary(String steamId, String personaName, String avatarFull, String locCountryCode) {
}
