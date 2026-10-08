package com.guxx.ray.ai.match;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record MatchSyncDTO(
        @NotBlank @Size(max = 128) String matchId,
        @NotNull Instant matchTime,
        @NotBlank @Size(max = 100) String mapName,
        @NotBlank @Size(max = 50) String gameMode,
        @NotNull Boolean extracted,
        @NotNull @PositiveOrZero Integer kills,
        @PositiveOrZero Integer teamKills,
        @NotNull @PositiveOrZero Integer deaths,
        @NotNull @PositiveOrZero Integer assists,
        @NotNull @PositiveOrZero Integer teamWipes,
        @NotNull @PositiveOrZero Integer bountyExtracted,
        @PositiveOrZero Integer mmr,
        Integer mmrChange,
        @NotNull List<@NotNull Map<String, Object>> encounteredPlayers) {

    @JsonAnySetter
    public void rejectUnknownField(String name, JsonNode value) {
        throw new IllegalArgumentException("Unknown match sync field");
    }
}
