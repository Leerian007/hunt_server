package com.guxx.ray.ai.player;

import com.guxx.ray.ai.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/players/{steam_id}")
public class PlayerController {
    private final PlayerService players;

    public PlayerController(PlayerService players) {
        this.players = players;
    }

    @GetMapping("/summary")
    public Result<PlayerSummary> summary(@PathVariable("steam_id") String steamId) {
        return Result.success(players.summary(steamId));
    }

    @GetMapping("/stats")
    public Result<PlayerStats> stats(@PathVariable("steam_id") String steamId) {
        return Result.success(players.stats(steamId));
    }
}
