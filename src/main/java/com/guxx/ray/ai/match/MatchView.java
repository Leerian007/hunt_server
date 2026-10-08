package com.guxx.ray.ai.match;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.guxx.ray.ai.entity.MatchRecord;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record MatchView(Long id, String matchId, String steamId, Instant matchTime,
                        String mapName, String gameMode, boolean extracted,
                        int kills, int teamKills, int deaths, int assists, int teamWipes, int bountyExtracted,
                        Integer mmr, Integer mmrChange, List<Map<String, Object>> encounteredPlayers) {
    public static MatchView from(MatchRecord record) {
        return new MatchView(record.getId(), record.getMatchId(), record.getSteamId(), record.getMatchTime(),
                record.getMapName(), record.getGameMode(), record.isExtracted(), record.getKills(),
                record.getTeamKills(), record.getDeaths(), record.getAssists(), record.getTeamWipes(), record.getBountyExtracted(),
                record.getMmr(), record.getMmrChange(), record.getEncounteredPlayers());
    }
}
