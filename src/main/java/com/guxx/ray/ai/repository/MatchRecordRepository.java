package com.guxx.ray.ai.repository;

import com.guxx.ray.ai.entity.MatchRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface MatchRecordRepository extends JpaRepository<MatchRecord, Long> {
    @Modifying
    @Query(value = """
            INSERT INTO match_record (match_id, steam_id, match_time, map_name, game_mode, extracted,
                kills, team_kills, deaths, assists, team_wipes, bounty_extracted, mmr, mmr_change, encountered_players)
            VALUES (:#{#record.matchId}, :#{#record.steamId}, :#{#record.matchTime}, :#{#record.mapName},
                :#{#record.gameMode}, :#{#record.extracted}, :#{#record.kills}, :#{#record.teamKills}, :#{#record.deaths},
                :#{#record.assists}, :#{#record.teamWipes}, :#{#record.bountyExtracted}, :#{#record.mmr},
                :#{#record.mmrChange}, CAST(:players AS jsonb))
            ON CONFLICT (match_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("record") MatchRecord record, @Param("players") String players);

    Optional<MatchRecord> findFirstBySteamIdAndMmrIsNotNullOrderByMatchTimeDescIdDesc(String steamId);

    @Query("""
            select count(m) as totalMatches,
                   coalesce(sum(case when m.extracted = true then 1 else 0 end), 0) as extractedMatches,
                   coalesce(sum(m.kills), 0) as totalKills,
                   coalesce(sum(m.deaths), 0) as totalDeaths,
                   coalesce(sum(m.assists), 0) as totalAssists,
                   coalesce(sum(m.bountyExtracted), 0) as totalBountyExtracted
            from MatchRecord m where m.steamId = :steamId
            """)
    CareerTotals aggregateBySteamId(@Param("steamId") String steamId);

    interface CareerTotals {
        long getTotalMatches();
        long getExtractedMatches();
        long getTotalKills();
        long getTotalDeaths();
        long getTotalAssists();
        long getTotalBountyExtracted();
    }

    Optional<MatchRecord> findByMatchId(String matchId);
    boolean existsByMatchId(String matchId);
    Page<MatchRecord> findBySteamIdOrderByMatchTimeDescIdDesc(String steamId, Pageable pageable);
    Page<MatchRecord> findBySteamIdAndExtractedOrderByMatchTimeDescIdDesc(
            String steamId, boolean extracted, Pageable pageable);
    Page<MatchRecord> findBySteamIdAndDeathsGreaterThanOrderByMatchTimeDescIdDesc(
            String steamId, int deaths, Pageable pageable);
}
