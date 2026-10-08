package com.guxx.ray.ai.player;

import com.guxx.ray.ai.repository.MatchRecordRepository;
import com.guxx.ray.ai.repository.UserProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.RejectedExecutionException;

@Service
public class PlayerService {
    private static final Logger log = LoggerFactory.getLogger(PlayerService.class);
    private final SteamApiClient steam;
    private final PlayerProfileCache cache;
    private final PlayerProfileWriter writer;
    private final MatchRecordRepository matches;
    private final UserProfileRepository users;

    public PlayerService(SteamApiClient steam, PlayerProfileCache cache, PlayerProfileWriter writer,
                         MatchRecordRepository matches, UserProfileRepository users) {
        this.steam = steam;
        this.cache = cache;
        this.writer = writer;
        this.matches = matches;
        this.users = users;
    }

    public PlayerSummary summary(String steamId) {
        SteamApiClient.validateSteamId(steamId);
        return cache.get(steamId).orElseGet(() -> {
            PlayerSummary profile = steam.getPlayerSummary(steamId);
            cache.put(profile);
            try {
                writer.persist(profile);
            } catch (RejectedExecutionException ex) {
                log.warn("Player profile persistence queue full for {}", steamId);
                cache.evict(steamId);
            }
            return profile;
        });
    }

    public PlayerStats stats(String steamId) {
        SteamApiClient.validateSteamId(steamId);
        var totals = matches.aggregateBySteamId(steamId);
        var user = users.findBySteamId(steamId).orElse(null);
        BigDecimal rate = ratio(BigDecimal.valueOf(totals.getExtractedMatches()), totals.getTotalMatches(), 4);
        BigDecimal kda = ratio(BigDecimal.valueOf(totals.getTotalKills()).add(BigDecimal.valueOf(totals.getTotalAssists())),
                Math.max(totals.getTotalDeaths(), 1), 2);
        return new PlayerStats(steamId, totals.getTotalMatches(), totals.getExtractedMatches(), rate, rate,
                totals.getTotalKills(), totals.getTotalDeaths(), totals.getTotalAssists(), kda,
                totals.getTotalBountyExtracted(), user == null ? null : user.getCurrentMmr(),
                user == null ? null : user.getMmrStars());
    }

    private BigDecimal ratio(BigDecimal numerator, long denominator, int scale) {
        return denominator == 0 ? BigDecimal.ZERO.setScale(scale)
                : numerator.divide(BigDecimal.valueOf(denominator), scale, RoundingMode.HALF_UP);
    }
}
