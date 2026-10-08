package com.guxx.ray.ai.match;

import com.guxx.ray.ai.entity.MatchRecord;
import com.guxx.ray.ai.repository.MatchRecordRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest(properties = {"spring.datasource.url=jdbc:h2:mem:history;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.jpa.hibernate.ddl-auto=create-drop"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class MatchHistoryRepositoryTest {
    @Autowired MatchRecordRepository matches;

    @Test
    void pagesInStableDescendingOrderAndFiltersActualDeaths() {
        String id = "76561198000000000";
        var old = match("old", id, "2026-10-07T10:00:00Z", false, 0, 2400);
        var revived = match("revived", id, "2026-10-08T10:00:00Z", true, 1, 2500);
        var died = match("died", id, "2026-10-08T10:00:00Z", false, 2, 2600);
        var other = match("other", "76561198000000001", "2026-10-09T10:00:00Z", true, 1, 2700);
        matches.saveAllAndFlush(List.of(old, revived, died, other));
        var page = matches.findBySteamIdOrderByMatchTimeDescIdDesc(id, PageRequest.of(0, 2));
        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getTotalPages()).isEqualTo(2);
        assertThat(page.getContent()).extracting(MatchRecord::getMatchId).containsExactly("died", "revived");
        assertThat(matches.findBySteamIdOrderByMatchTimeDescIdDesc(id, PageRequest.of(1, 2)).getContent())
                .extracting(MatchRecord::getMatchId).containsExactly("old");
        assertThat(matches.findBySteamIdAndExtractedOrderByMatchTimeDescIdDesc(id, true, PageRequest.of(0, 20)).getContent())
                .extracting(MatchRecord::getMatchId).containsExactly("revived");
        assertThat(matches.findBySteamIdAndDeathsGreaterThanOrderByMatchTimeDescIdDesc(id, 0, PageRequest.of(0, 20)).getContent())
                .extracting(MatchRecord::getMatchId).containsExactly("died", "revived");
        assertThat(matches.findFirstBySteamIdAndMmrIsNotNullOrderByMatchTimeDescIdDesc(id).orElseThrow().getMmr()).isEqualTo(2600);
    }

    private MatchRecord match(String matchId, String steamId, String time, boolean extracted, int deaths, int mmr) {
        var match = new MatchRecord();
        match.setMatchId(matchId);
        match.setSteamId(steamId);
        match.setMatchTime(Instant.parse(time));
        match.setMapName("Stillwater Bayou");
        match.setGameMode("bounty_hunt");
        match.setExtracted(extracted);
        match.setDeaths(deaths);
        match.setMmr(mmr);
        return match;
    }
}
