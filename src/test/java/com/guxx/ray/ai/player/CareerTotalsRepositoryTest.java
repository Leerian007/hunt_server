package com.guxx.ray.ai.player;

import com.guxx.ray.ai.entity.MatchRecord;
import com.guxx.ray.ai.repository.MatchRecordRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:career;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.jpa.hibernate.ddl-auto=create-drop"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CareerTotalsRepositoryTest {
    @Autowired MatchRecordRepository matches;

    @Test
    void aggregatesOnlyRequestedPlayerAndReturnsZerosForEmptyHistory() {
        matches.saveAllAndFlush(List.of(match("one", "76561198000000000", true, 3, 1, 2, 100),
                match("two", "76561198000000000", false, 4, 2, 1, 0),
                match("other", "76561198000000001", true, 99, 99, 99, 999)));
        var totals = matches.aggregateBySteamId("76561198000000000");
        assertThat(totals.getTotalMatches()).isEqualTo(2);
        assertThat(totals.getExtractedMatches()).isEqualTo(1);
        assertThat(totals.getTotalKills()).isEqualTo(7);
        assertThat(totals.getTotalDeaths()).isEqualTo(3);
        assertThat(totals.getTotalAssists()).isEqualTo(3);
        assertThat(totals.getTotalBountyExtracted()).isEqualTo(100);
        var empty = matches.aggregateBySteamId("76561198000000002");
        assertThat(empty.getTotalMatches()).isZero();
        assertThat(empty.getExtractedMatches()).isZero();
        assertThat(empty.getTotalKills()).isZero();
        assertThat(empty.getTotalDeaths()).isZero();
        assertThat(empty.getTotalAssists()).isZero();
        assertThat(empty.getTotalBountyExtracted()).isZero();
    }

    private MatchRecord match(String id, String steamId, boolean extracted, int kills, int deaths, int assists, int bounty) {
        var match = new MatchRecord();
        match.setMatchId(id);
        match.setSteamId(steamId);
        match.setMatchTime(Instant.parse("2026-10-08T10:00:00Z"));
        match.setMapName("Stillwater Bayou");
        match.setGameMode("bounty_hunt");
        match.setExtracted(extracted);
        match.setKills(kills);
        match.setDeaths(deaths);
        match.setAssists(assists);
        match.setBountyExtracted(bounty);
        return match;
    }
}
