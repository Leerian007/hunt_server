package com.guxx.ray.ai.match;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guxx.ray.ai.entity.MatchRecord;
import com.guxx.ray.ai.entity.UserProfile;
import com.guxx.ray.ai.repository.MatchRecordRepository;
import com.guxx.ray.ai.repository.UserProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MatchServiceTest {
    private static final String ID = "76561198000000000";
    private MatchRecordRepository matches;
    private UserProfileRepository users;
    private MatchService service;
    private UserProfile user;

    static MatchSyncDTO payload() {
        return new MatchSyncDTO("match-1", Instant.parse("2026-10-08T10:00:00Z"), "Stillwater Bayou", "bounty_hunt",
                true, 3, 4, 1, 2, 1, 100, 2500, -10, List.of(Map.of("name", "Enemy", "mmr", 2600, "downed_by_me", 1)));
    }

    @BeforeEach
    void setup() {
        matches = mock(MatchRecordRepository.class);
        users = mock(UserProfileRepository.class);
        service = new MatchService(matches, users, new ObjectMapper());
        user = new UserProfile();
        user.setSteamId(ID);
        user.setCurrentMmr(2400);
        when(users.findBySteamIdForUpdate(ID)).thenReturn(Optional.of(user));
    }

    @Test
    void createsRecordWithAuthenticatedOwnerAndUsesLatestMmr() throws Exception {
        when(matches.insertIfAbsent(any(), any())).thenReturn(1);
        var latest = new MatchRecord();
        latest.setMmr(2800);
        when(matches.findFirstBySteamIdAndMmrIsNotNullOrderByMatchTimeDescIdDesc(ID)).thenReturn(Optional.of(latest));
        service.sync(ID, payload());
        var record = org.mockito.ArgumentCaptor.forClass(MatchRecord.class);
        var json = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(matches).insertIfAbsent(record.capture(), json.capture());
        assertThat(record.getValue().getSteamId()).isEqualTo(ID);
        assertThat(record.getValue().getMatchId()).isEqualTo("match-1");
        assertThat(record.getValue().getMmrChange()).isEqualTo(-10);
        assertThat(record.getValue().getEncounteredPlayers()).isEqualTo(payload().encounteredPlayers());
        assertThat(new ObjectMapper().readTree(json.getValue()).get(0).get("downed_by_me").asInt()).isEqualTo(1);
        assertThat(record.getValue().getTeamKills()).isEqualTo(4);
        assertThat(user.getCurrentMmr()).isEqualTo(2800); // Older submitted MMR must not replace newer history.
        var order = inOrder(users, matches);
        order.verify(matches).findByMatchId("match-1");
        order.verify(users).ensureProfile(ID);
        order.verify(users).findBySteamIdForUpdate(ID);
        order.verify(matches).insertIfAbsent(any(), any());
    }

    @Test
    void alreadyStoredMatchReturnsWithoutUpdatingMmrOrProfile() {
        var existing = new MatchRecord();
        existing.setSteamId(ID);
        when(matches.findByMatchId("match-1")).thenReturn(Optional.of(existing));
        service.sync(ID, payload());
        verify(matches, never()).insertIfAbsent(any(), any());
        verifyNoInteractions(users);
    }

    @Test
    void concurrentDuplicateDoesNotUpdateMmr() {
        var existing = new MatchRecord();
        existing.setSteamId(ID);
        when(matches.findByMatchId("match-1")).thenReturn(Optional.empty(), Optional.of(existing));
        when(matches.insertIfAbsent(any(), any())).thenReturn(0);
        service.sync(ID, payload());
        assertThat(user.getCurrentMmr()).isEqualTo(2400);
        verify(matches, never()).findFirstBySteamIdAndMmrIsNotNullOrderByMatchTimeDescIdDesc(any());
    }

    @Test
    void rejectsOtherOwnersAndInvalidSession() {
        var existing = new MatchRecord();
        existing.setSteamId("76561198000000001");
        when(matches.findByMatchId("match-1")).thenReturn(Optional.of(existing));
        assertThatThrownBy(() -> service.sync(ID, payload())).isInstanceOfSatisfying(MatchException.class,
                ex -> assertThat(ex.status()).isEqualTo(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> service.sync(null, payload())).isInstanceOfSatisfying(MatchException.class,
                ex -> assertThat(ex.status()).isEqualTo(HttpStatus.UNAUTHORIZED));
        verifyNoInteractions(users);
    }

    @Test
    void selectsFiltersAndConvertsOneBasedPages() {
        var request = PageRequest.of(1, 20);
        when(matches.findBySteamIdOrderByMatchTimeDescIdDesc(ID, request)).thenReturn(new PageImpl<>(List.of(), request, 21));
        var page = service.history(ID, 2, 20, "all");
        assertThat(page.total()).isEqualTo(21);
        assertThat(page.pages()).isEqualTo(2);
        when(matches.findBySteamIdAndExtractedOrderByMatchTimeDescIdDesc(ID, true, request))
                .thenReturn(new PageImpl<>(List.of(), request, 0));
        assertThat(service.history(ID, 2, 20, "extracted").list()).isEmpty();
        when(matches.findBySteamIdAndDeathsGreaterThanOrderByMatchTimeDescIdDesc(ID, 0, request))
                .thenReturn(new PageImpl<>(List.of(), request, 0));
        assertThat(service.history(ID, 2, 20, "died").total()).isZero();
        verify(matches).findBySteamIdAndDeathsGreaterThanOrderByMatchTimeDescIdDesc(ID, 0, request);
    }

    @Test
    void rejectsInvalidPagingAndFilterBeforeQuery() {
        assertThatThrownBy(() -> service.history(ID, 0, 20, "all")).isInstanceOf(MatchException.class);
        assertThatThrownBy(() -> service.history(ID, 1, 101, "all")).isInstanceOf(MatchException.class);
        assertThatThrownBy(() -> service.history(ID, Integer.MAX_VALUE, 100, "all")).isInstanceOf(MatchException.class);
        assertThatThrownBy(() -> service.history(ID, 1, 20, "bad")).isInstanceOf(MatchException.class);
        verifyNoInteractions(matches);
    }
}
