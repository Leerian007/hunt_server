package com.guxx.ray.ai.match;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guxx.ray.ai.entity.MatchRecord;
import com.guxx.ray.ai.repository.MatchRecordRepository;
import com.guxx.ray.ai.repository.UserProfileRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MatchService {
    private final MatchRecordRepository matches;
    private final UserProfileRepository users;
    private final ObjectMapper mapper;

    public MatchService(MatchRecordRepository matches, UserProfileRepository users, ObjectMapper mapper) {
        this.matches = matches;
        this.users = users;
        this.mapper = mapper;
    }

    @Transactional
    public void sync(String steamId, MatchSyncDTO dto) {
        if (steamId == null || !steamId.matches("[0-9]{17}")) {
            throw new MatchException(HttpStatus.UNAUTHORIZED, "Valid Steam session required");
        }
        var existing = matches.findByMatchId(dto.matchId());
        if (existing.isPresent()) {
            checkOwner(existing.get(), steamId);
            return;
        }
        // Bootstrap authenticated users even if the asynchronous Steam profile refresh has not run.
        users.ensureProfile(steamId);
        var user = users.findBySteamIdForUpdate(steamId).orElseThrow();
        var record = new MatchRecord();
        record.setMatchId(dto.matchId());
        record.setSteamId(steamId);
        record.setMatchTime(dto.matchTime());
        record.setMapName(dto.mapName());
        record.setGameMode(dto.gameMode());
        record.setExtracted(dto.extracted());
        record.setKills(dto.kills());
        record.setTeamKills(dto.teamKills() == null ? 0 : dto.teamKills());
        record.setDeaths(dto.deaths());
        record.setAssists(dto.assists());
        record.setTeamWipes(dto.teamWipes());
        record.setBountyExtracted(dto.bountyExtracted());
        record.setMmr(dto.mmr());
        record.setMmrChange(dto.mmrChange());
        record.setEncounteredPlayers(dto.encounteredPlayers());
        String players;
        try {
            players = mapper.writeValueAsString(dto.encounteredPlayers());
        } catch (JsonProcessingException ex) {
            throw new MatchException(HttpStatus.BAD_REQUEST, "Invalid encountered_players JSON");
        }
        if (matches.insertIfAbsent(record, players) == 0) {
            checkOwner(matches.findByMatchId(dto.matchId()).orElseThrow(), steamId);
            return;
        }
        // The user row lock serializes concurrent MMR updates. Old history must not roll MMR back.
        matches.findFirstBySteamIdAndMmrIsNotNullOrderByMatchTimeDescIdDesc(steamId)
                .ifPresent(latest -> user.setCurrentMmr(latest.getMmr()));
    }

    @Transactional(readOnly = true)
    public MatchPage history(String steamId, int page, int size, String filter) {
        if (steamId == null || !steamId.matches("[0-9]{17}")) {
            throw new MatchException(HttpStatus.BAD_REQUEST, "steam_id must contain 17 digits");
        }
        if (page < 1 || size < 1 || size > 100 || (long) (page - 1) * size > Integer.MAX_VALUE) {
            throw new MatchException(HttpStatus.BAD_REQUEST, "page must be positive and size must be between 1 and 100; offset is too large");
        }
        var pageable = PageRequest.of(page - 1, size);
        Page<MatchRecord> result = switch (filter == null ? "" : filter) {
            case "all" -> matches.findBySteamIdOrderByMatchTimeDescIdDesc(steamId, pageable);
            case "extracted" -> matches.findBySteamIdAndExtractedOrderByMatchTimeDescIdDesc(steamId, true, pageable);
            case "died" -> matches.findBySteamIdAndDeathsGreaterThanOrderByMatchTimeDescIdDesc(steamId, 0, pageable);
            default -> throw new MatchException(HttpStatus.BAD_REQUEST, "filter must be all, extracted or died");
        };
        return new MatchPage(result.getTotalElements(), result.getTotalPages(), result.getContent().stream().map(MatchView::from).toList());
    }

    private void checkOwner(MatchRecord existing, String steamId) {
        if (!steamId.equals(existing.getSteamId())) {
            throw new MatchException(HttpStatus.CONFLICT, "match_id is already associated with another player");
        }
    }
}
