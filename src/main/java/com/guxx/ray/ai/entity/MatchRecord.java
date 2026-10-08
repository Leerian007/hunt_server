package com.guxx.ray.ai.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "match_record", uniqueConstraints =
        @UniqueConstraint(name = "uk_match_record_match_id", columnNames = "match_id"),
        indexes = @Index(name = "idx_match_record_steam_time", columnList = "steam_id, match_time DESC, id DESC"))
public class MatchRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "match_id", nullable = false, length = 128, updatable = false)
    private String matchId;

    // The foreign key is enforced by db/schema.sql.
    @Column(name = "steam_id", nullable = false, length = 20, updatable = false)
    private String steamId;

    @Column(name = "match_time", nullable = false)
    private Instant matchTime;

    @Column(name = "map_name", nullable = false, length = 100)
    private String mapName;

    @Column(name = "game_mode", nullable = false, length = 50)
    private String gameMode;

    @Column(nullable = false)
    private boolean extracted;

    @Column(nullable = false)
    private int kills;

    @Column(name = "team_kills", nullable = false)
    private int teamKills;

    @Column(nullable = false)
    private int deaths;

    @Column(nullable = false)
    private int assists;

    @Column(name = "team_wipes", nullable = false)
    private int teamWipes;

    @Column(name = "bounty_extracted", nullable = false)
    private int bountyExtracted;

    @Column
    private Integer mmr;

    @Column(name = "mmr_change")
    private Integer mmrChange;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "encountered_players", nullable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> encounteredPlayers = new ArrayList<>();
}
