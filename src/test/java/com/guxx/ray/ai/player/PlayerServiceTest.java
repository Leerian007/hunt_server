package com.guxx.ray.ai.player;

import com.guxx.ray.ai.repository.MatchRecordRepository;
import com.guxx.ray.ai.repository.UserProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlayerServiceTest {
    private static final String ID = "76561198000000000";
    private final PlayerSummary profile = new PlayerSummary(ID, "Hunter", "avatar", "CN");
    private SteamApiClient steam;
    private PlayerProfileCache cache;
    private PlayerProfileWriter writer;
    private MatchRecordRepository matches;
    private UserProfileRepository users;
    private PlayerService service;

    @BeforeEach
    void setup() {
        steam = mock(SteamApiClient.class);
        cache = mock(PlayerProfileCache.class);
        writer = mock(PlayerProfileWriter.class);
        matches = mock(MatchRecordRepository.class);
        users = mock(UserProfileRepository.class);
        service = new PlayerService(steam, cache, writer, matches, users);
    }

    @Test
    void cacheHitAvoidsSteamAndDatabase() {
        when(cache.get(ID)).thenReturn(Optional.of(profile));
        assertThat(service.summary(ID)).isEqualTo(profile);
        verifyNoInteractions(steam, writer, matches, users);
    }

    @Test
    void cacheMissCachesBeforeSchedulingPersistence() {
        when(cache.get(ID)).thenReturn(Optional.empty());
        when(steam.getPlayerSummary(ID)).thenReturn(profile);
        assertThat(service.summary(ID)).isEqualTo(profile);
        var ordered = inOrder(cache, writer);
        ordered.verify(cache).get(ID);
        ordered.verify(cache).put(profile);
        ordered.verify(writer).persist(profile);
    }

    @Test
    void queueRejectionReturnsFetchedProfileAndAllowsRetry() {
        when(cache.get(ID)).thenReturn(Optional.empty());
        when(steam.getPlayerSummary(ID)).thenReturn(profile);
        doThrow(new RejectedExecutionException()).when(writer).persist(profile);
        assertThat(service.summary(ID)).isEqualTo(profile);
        verify(cache).evict(ID);
    }

    @Test
    void computesRatiosWithAssistsAndHandlesZeroDeathsAndEmptyHistory() {
        var totals = mock(MatchRecordRepository.CareerTotals.class);
        when(matches.aggregateBySteamId(ID)).thenReturn(totals);
        when(users.findBySteamId(ID)).thenReturn(Optional.empty());
        var empty = service.stats(ID);
        assertThat(empty.totalMatches()).isZero();
        assertThat(empty.extractionRate()).isEqualByComparingTo("0");
        assertThat(empty.kda()).isEqualByComparingTo("0");
        assertThat(empty.currentMmr()).isNull();
        when(totals.getTotalMatches()).thenReturn(3L);
        when(totals.getExtractedMatches()).thenReturn(2L);
        when(totals.getTotalKills()).thenReturn(7L);
        when(totals.getTotalAssists()).thenReturn(2L);
        var zeroDeaths = service.stats(ID);
        assertThat(zeroDeaths.kda()).isEqualByComparingTo("9.00");
        assertThat(zeroDeaths.extractionRate()).isEqualByComparingTo("0.6667");
        assertThat(zeroDeaths.winRate()).isEqualTo(zeroDeaths.extractionRate());
        when(totals.getTotalDeaths()).thenReturn(4L);
        assertThat(service.stats(ID).kda()).isEqualByComparingTo("2.25");
        verifyNoInteractions(steam, cache, writer);
    }
}
