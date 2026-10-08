package com.guxx.ray.ai.match;

import com.guxx.ray.ai.auth.AuthConfiguration;
import com.guxx.ray.ai.auth.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value = MatchController.class, excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class,
        properties = {"jwt.secret=dGVzdC1vbmx5LXNlY3JldC1rZXktMzItYnl0ZXMtbG9uZyE=", "jwt.expiration=7d"})
@Import({AuthConfiguration.class, JwtService.class})
class MatchControllerTest {
    private static final String ID = "76561198000000000";
    private static final String JSON = """
            {"match_id":"match-1","match_time":"2026-10-08T10:00:00Z","map_name":"Stillwater Bayou",
            "game_mode":"Bounty Hunt","extracted":true,"kills":3,"team_kills":4,"deaths":1,"assists":2,
            "team_wipes":1,"bounty_extracted":100,"mmr":2500,"mmr_change":-10,
            "encountered_players":[{"name":"Enemy","mmr":2600,"downed_by_me":1}]}
            """;
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @MockitoBean MatchService service;

    private String bearer() { return "Bearer " + jwt.issue(ID); }

    @Test
    void acceptsFixtureProducedByActualPythonParserIncludingMicrosecondTime() throws Exception {
        byte[] exported;
        try (var stream = getClass().getResourceAsStream("/match/client-export.json")) {
            assertThat(stream).isNotNull();
            exported = stream.readAllBytes();
        }
        mvc.perform(post("/api/v1/matches/sync").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(exported))
                .andExpect(status().isOk());
        var dto = org.mockito.ArgumentCaptor.forClass(MatchSyncDTO.class);
        verify(service).sync(eq(ID), dto.capture());
        assertThat(dto.getValue().matchTime()).isEqualTo(java.time.Instant.parse("2026-10-08T10:00:00.123456Z"));
        assertThat(dto.getValue().gameMode()).isEqualTo("Bounty Hunt");
        assertThat(dto.getValue().teamKills()).isEqualTo(4);
        assertThat(dto.getValue().encounteredPlayers().get(0)).containsEntry("name", "Enemy")
                .containsEntry("mmr", 2600).containsEntry("downed_by_me", 1);
    }

    @Test
    void acceptsClientFieldNamesAndInjectsAuthenticatedSteamId() throws Exception {
        mvc.perform(post("/api/v1/matches/sync").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(JSON))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        var dto = org.mockito.ArgumentCaptor.forClass(MatchSyncDTO.class);
        verify(service).sync(eq(ID), dto.capture());
        assertThat(dto.getValue().matchId()).isEqualTo("match-1");
        assertThat(dto.getValue().mmrChange()).isEqualTo(-10);
        assertThat(dto.getValue().encounteredPlayers().get(0).get("downed_by_me")).isEqualTo(1);
        assertThat(dto.getValue().teamKills()).isEqualTo(4);
    }

    @Test
    void rejectsInvalidMissingAndForgedFieldsAsUniform400() throws Exception {
        for (String json : List.of(JSON.replace("\"kills\":3", "\"kills\":-1"),
                JSON.replace("\"extracted\":true,", ""),
                JSON.replace("\"match_time\":\"2026-10-08T10:00:00Z\"", "\"match_time\":\"invalid\""),
                JSON.replace("\"match_id\":", "\"steam_id\":\"76561198000000001\",\"match_id\":"),
                "{broken")) {
            mvc.perform(post("/api/v1/matches/sync").header("Authorization", bearer())
                            .contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").exists());
        }
        verifyNoInteractions(service);
    }

    @Test
    void protectsBothEndpointsAndDefaultsHistoryPaging() throws Exception {
        mvc.perform(post("/api/v1/matches/sync").contentType(MediaType.APPLICATION_JSON).content(JSON))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
        mvc.perform(get("/api/v1/players/" + ID + "/matches")).andExpect(status().isUnauthorized());
        when(service.history(ID, 1, 20, "all")).thenReturn(new MatchPage(0, 0, List.of()));
        mvc.perform(get("/api/v1/players/" + ID + "/matches").header("Authorization", bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.pages").value(0)).andExpect(jsonPath("$.data.list").isArray());
        mvc.perform(get("/api/v1/players/" + ID + "/matches").header("Authorization", bearer()).param("page", "abc"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void unexpectedExceptionsBecomeSanitized500() throws Exception {
        when(service.history(ID, 1, 20, "all")).thenThrow(new IllegalStateException("internal sensitive detail"));
        mvc.perform(get("/api/v1/players/" + ID + "/matches").header("Authorization", bearer()))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("Internal server error"));
    }
}
