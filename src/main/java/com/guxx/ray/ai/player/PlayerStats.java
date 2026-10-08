package com.guxx.ray.ai.player;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.math.BigDecimal;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record PlayerStats(String steamId, long totalMatches, long extractedMatches,
                          BigDecimal winRate, BigDecimal extractionRate,
                          long totalKills, long totalDeaths, long totalAssists,
                          BigDecimal kda, long totalBountyExtracted,
                          Integer currentMmr, Integer mmrStars) {
}
